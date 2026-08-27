/*
 * Copyright (c) 2025-2026 John Mears
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#include "audio_out.h"

#include "dsp.h"

#include <algorithm>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <malloc.h>
#include <string.h>

/*
 * Scratch buffer for one live write must hold a full USB URB of mono samples
 * (same bound as nativeusb MAX_DATA_POINTS_PER_URB).
 */
#define AUDIO_OUT_URBS_PER_SECOND 40
#define AUDIO_OUT_MAX_INPUT_SAMPLES ((384 + 1) * 2 * (1000 / AUDIO_OUT_URBS_PER_SECOND))

static JavaVM *s_pJVM = nullptr;
static AAudioStream *s_android_stream = nullptr;
static dsp_state_t s_live_dsp_state = {};

typedef struct {
    jobject global_ref_to_buffer;
    int32_t visible_length;
    int32_t visible_start;
    int32_t current_position;
    jobject global_callback;
    dsp_state_t dsp_state;
} PlaybackContext;

static PlaybackContext playback_context = {
        .global_ref_to_buffer = nullptr,
        .visible_length = 0,
        .visible_start = 0,
        .current_position = 0,
        .global_callback = nullptr,
        .dsp_state = {}
};

static bool s_looped_playback = false;

static bool start_audio_output(jint output_device_id, int audio_out_rate, void *context);
static bool configure_and_start_audio(jint audio_device_id,
                                      int sample_rate,
                                      int samples_per_frame,
                                      jint heterodyne1_kHz,
                                      jint heterodyne2_kHz,
                                      float audio_boost_factor,
                                      bool direct_playback,
                                      dsp_state_t *state,
                                      void *aaudio_context);

void audio_out_set_jvm(JavaVM *vm) {
    s_pJVM = vm;
}

bool audio_out_is_active(void) {
    return s_android_stream != nullptr;
}

static void signal_progress(PlaybackContext *ctx, JNIEnv *env, int32_t current_position) {

    if (!ctx->global_callback)
        return;

    jclass funcClass = env->GetObjectClass(ctx->global_callback);
    jmethodID invokeMethod = env->GetMethodID(funcClass, "invoke", "(I)V");
    if (invokeMethod) {
        env->CallVoidMethod(ctx->global_callback, invokeMethod, current_position);
    }
    env->DeleteLocalRef(funcClass);
}

static inline int32_t copy_audio_data(int32_t frames_requested,
                                      int32_t &frames_consumed,
                                      int32_t &frames_written,
                                      PlaybackContext *ctx,
                                      jshort *buffer, jshort *out) {

    int32_t frames_available = ctx->visible_length - (ctx->current_position - ctx->visible_start);
    frames_available = std::max(0, frames_available);
    int32_t frames_to_consume = std::min(frames_requested - frames_consumed, frames_available);

    int decimated_sample_count = dsp_process(buffer + ctx->current_position,
                                             frames_to_consume,
                                             out + frames_written,
                                             &ctx->dsp_state);

    ctx->current_position += frames_to_consume;
    frames_consumed += frames_to_consume;
    frames_written += decimated_sample_count;

    if (ctx->current_position >= ctx->visible_start + ctx->visible_length)
        ctx->current_position = ctx->visible_start;

    return decimated_sample_count;
}

static aaudio_data_callback_result_t audioCallback(
        AAudioStream *stream,
        void *user_data,
        void *target_buffer,
        const int32_t frames_requested
) {
    auto *ctx = (PlaybackContext *) user_data;
    auto *out = (int16_t *) target_buffer;

    JNIEnv *env;
    s_pJVM->AttachCurrentThread(&env, nullptr);

    auto local_array = (jshortArray) env->NewLocalRef(ctx->global_ref_to_buffer);
    jshort *buffer = env->GetShortArrayElements(local_array, nullptr);

    int32_t inflated_frames_requested = frames_requested * dsp_get_decimation_factor();

    memset(out, 0, sizeof(int16_t) * frames_requested);

    int32_t frames_consumed = 0;
    int32_t frames_written = 0;
    copy_audio_data(inflated_frames_requested, frames_consumed, frames_written, ctx, buffer, out);
    if (s_looped_playback && (frames_written < frames_requested)) {
        copy_audio_data(inflated_frames_requested, frames_consumed, frames_written, ctx, buffer, out);
    }

    env->ReleaseShortArrayElements(local_array, buffer, 0);
    env->DeleteLocalRef(local_array);

    if (frames_written < frames_requested) {
        signal_progress(ctx, env, -1);
        return AAUDIO_CALLBACK_RESULT_STOP;
    } else {
        signal_progress(ctx, env, ctx->current_position);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
}

static bool configure_and_start_audio(jint audio_device_id,
                                      int sample_rate,
                                      int samples_per_frame,
                                      jint heterodyne1_kHz,
                                      jint heterodyne2_kHz,
                                      float audio_boost_factor,
                                      bool direct_playback,
                                      dsp_state_t *state,
                                      void *aaudio_context) {
    int audio_out_rate = dsp_configure(sample_rate, heterodyne1_kHz, heterodyne2_kHz,
                                       audio_boost_factor, samples_per_frame,
                                       direct_playback, state);
    if (audio_out_rate <= 0)
        return false;
    return start_audio_output(audio_device_id, audio_out_rate, aaudio_context);
}

bool audio_out_start_live(JNIEnv *env,
                          jint audio_device_id,
                          int sample_rate,
                          int samples_per_frame,
                          jint heterodyne1_kHz,
                          jint heterodyne2_kHz,
                          float audio_boost_factor,
                          bool direct_playback) {
    audio_out_stop(env);
    return configure_and_start_audio(audio_device_id, sample_rate, samples_per_frame,
                                     heterodyne1_kHz, heterodyne2_kHz,
                                     audio_boost_factor, direct_playback,
                                     &s_live_dsp_state, nullptr);
}

bool audio_out_start_buffer(JNIEnv *env,
                            jint audio_device_id,
                            int sample_rate,
                            jint heterodyne1_kHz,
                            jint heterodyne2_kHz,
                            float audio_boost_factor,
                            jshortArray buffer,
                            jint start_index,
                            jint end_exclusive_index,
                            bool looped_playback,
                            bool direct_playback,
                            jobject progress_callback) {
    audio_out_stop(env);    // Must run before new global refs.

    s_looped_playback = looped_playback;

    playback_context.visible_start = start_index;
    playback_context.visible_length = end_exclusive_index - start_index;
    playback_context.current_position = playback_context.visible_start;
    playback_context.global_ref_to_buffer = env->NewGlobalRef(buffer);
    playback_context.global_callback = env->NewGlobalRef(progress_callback);

    return configure_and_start_audio(audio_device_id,
                                     sample_rate, sample_rate / 1000,
                                     heterodyne1_kHz, heterodyne2_kHz,
                                     audio_boost_factor, direct_playback,
                                     &playback_context.dsp_state, &playback_context);
}

static bool start_audio_output(jint output_device_id, int audio_out_rate, void *context) {

    AAudioStreamBuilder *builder;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_INFO, __FILE__,
                            "AAudio_createStreamBuilder returned %d", result);
        return false;
    }

    AAudioStreamBuilder_setDeviceId(builder, output_device_id);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, audio_out_rate);
    const int channel_count = 1;
    AAudioStreamBuilder_setChannelCount(builder, channel_count);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);

    if (context)
        AAudioStreamBuilder_setDataCallback(builder, audioCallback, context);

    result = AAudioStreamBuilder_openStream(builder, &s_android_stream);
    if (result != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        __android_log_print(ANDROID_LOG_INFO, __FILE__,
                            "AAudioStreamBuilder_openStream returned %d", result);
        return false;
    }

    AAudioStreamBuilder_delete(builder);
    builder = nullptr;

    int32_t rate = AAudioStream_getSampleRate(s_android_stream);
    int32_t channels = AAudioStream_getChannelCount(s_android_stream);
    int32_t device_id = AAudioStream_getDeviceId(s_android_stream);
    int32_t buffer_frames = AAudioStream_getBufferCapacityInFrames(s_android_stream);

    const int bufframes = buffer_frames / 2;
    const int bufsize = buffer_frames * channels * sizeof(int16_t);
    int16_t *pBuf = (int16_t *) malloc(bufsize);
    if (pBuf) {
        memset(pBuf, 0, bufsize);
        AAudioStream_write(s_android_stream, pBuf, bufframes, 0);
        free(pBuf);
        pBuf = nullptr;
    }

    result = AAudioStream_requestStart(s_android_stream);

    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Audio stream opened: device %d, rate %d, channels %d, buffer %d frames, result %d",
                        device_id, rate, channels, buffer_frames, result);

    return result == 0;
}

void audio_out_stop(JNIEnv *env) {

    if (s_android_stream) {
        AAudioStream_requestStop(s_android_stream);

        aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
        int64_t timeoutNanos = 500 * 1000;  // 500 ms
        AAudioStream_waitForStateChange(s_android_stream,
                                        AAUDIO_STREAM_STATE_STOPPING,
                                        &nextState, timeoutNanos);

        AAudioStream_close(s_android_stream);
        s_android_stream = nullptr;
    }

    if (playback_context.global_ref_to_buffer) {
        env->DeleteGlobalRef(playback_context.global_ref_to_buffer);
        playback_context.global_ref_to_buffer = nullptr;
    }

    if (playback_context.global_callback) {
        env->DeleteGlobalRef(playback_context.global_callback);
        playback_context.global_callback = nullptr;
    }
}

void audio_out_write(const int16_t *samples, uint32_t sample_count) {
    static int16_t downsampled_buffer[AUDIO_OUT_MAX_INPUT_SAMPLES];

    int decimated_sample_count = dsp_process(samples, sample_count,
                                             downsampled_buffer, &s_live_dsp_state);

    const int timeout_ns = 1000000000 / AUDIO_OUT_URBS_PER_SECOND;
    aaudio_result_t rc = AAudioStream_write(s_android_stream,
                                            downsampled_buffer,
                                            decimated_sample_count,
                                            timeout_ns);
    if (rc < 0) {
        __android_log_print(ANDROID_LOG_INFO, __FILE__,
                            "audio_out_write failed to write data: %s",
                            AAudio_convertResultToText(rc));
    }
}
