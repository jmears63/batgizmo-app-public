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

#include <string.h>
#include <math.h>
#include <stdint.h>
#include <jni.h>
#include <android/log.h>

#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <asm/byteorder.h>
#include <malloc.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <assert.h>
#include <algorithm>
#include <memory.h>
#include <sys/poll.h>
#include <atomic>

#include "dsp.h"
#include "audio_out.h"

#define MAX_CHANNELS 2

// Upper limit that we support based on full speed USB. Allow a little extra as some detectors sometimes
// send a bit more data as a lazy way to keep in sync:
#define MAX_SAMPLES_PER_FRAME (384 + 1)

#define URBS_PER_SECOND 40      // The target update rate that results in a smooth UI, including
                                // reasonably smooth with a 44100 kHz microphone.

// Aim for about 20 URBs per second to give a reasonable UI update rate
// while minimizing overheads:
#define URBS_TO_JUGGLE 10        // At least 2 required. More allows a greater queuing depth without loss.
#define PACKETS_PER_URB (1000 / URBS_PER_SECOND)   // One packet (frame) is 1 ms.
#define MAX_DATA_POINTS_PER_URB (MAX_SAMPLES_PER_FRAME * MAX_CHANNELS * PACKETS_PER_URB)

//#define FAKE_DATA 1

// A type representing the audio data we handle:
typedef int16_t data_t;

// This flag is typically set by the UI thread and read by the worker thread.
static volatile bool s_cancel_pending = false;

static volatile bool s_paused = false;

// This is the file descriptor of the data file we are currently streaming audio data to.
static volatile int s_fd_file = -1;

static volatile int s_num_channels = 0;
static volatile int s_sample_rate = 0;
static volatile int s_nominal_samples_per_frame = 0;

/*
 * This mutex protects static data in this module and serialises audio_out start/stop/write.
 */
static pthread_mutex_t s_mutex = PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP;

#define CANARY_COUNT 1
#define CANARY_VALUE_32 0xFABDECAF
#define CANARY_DATA_VALUE ((data_t) 0xFACE)

/*
 * Workaround for usbdevfs_iso_packet_desc having size 0 in usbdevfs_urb:
 */
struct my_usbdevfs_urb {
    usbdevfs_urb urb;
    struct usbdevfs_iso_packet_desc packet_desc[PACKETS_PER_URB];
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    audio_out_set_jvm(vm);
    return JNI_VERSION_1_6;
}

/***********************************************************************************/
/* Basic data stream from USB.                                                     */
/***********************************************************************************/

/**
 * Data used for streaming audio data arriving via USB and ioctls.
 *
 * This data is accessed exclusively from the Java_org_batgizmo_app_pipeline_NativeUSB_stream
 * function which is called from a worker thread.
 *
 * This data has to be statically allocated as it may be referenced after a stream has
 * been closed, due to asynchronous processing
 */
static int16_t audio_buffer[URBS_TO_JUGGLE][MAX_DATA_POINTS_PER_URB + CANARY_COUNT];
static my_usbdevfs_urb urbRequests[URBS_TO_JUGGLE];

static void initialiseRequests(jint endpointAddress, int requested_bytes_per_frame)
{
    memset(audio_buffer, 0, sizeof(audio_buffer));
    for (int i = 0; i < URBS_TO_JUGGLE; i++) {
        audio_buffer[i][MAX_DATA_POINTS_PER_URB] = CANARY_DATA_VALUE;
    }

    for (int i = 0; i < URBS_TO_JUGGLE; i++) {
        my_usbdevfs_urb* req = &urbRequests[i];
        usbdevfs_urb* urb = &req->urb;
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = endpointAddress | 0x80; // 0x80 because this is an input endpoint.
        urb->status = 0;
        urb->flags = USBDEVFS_URB_ISO_ASAP;     // Request isochronous transfer.
        urb->buffer = audio_buffer[i];
        urb->buffer_length = MAX_DATA_POINTS_PER_URB; // 0;
        urb->actual_length = 0;                 // Not set for isochronous transfers.
        urb->start_frame = 0;
        urb->number_of_packets = PACKETS_PER_URB;
        urb->error_count = 0;
        urb->signr = 0;                         // Optional signal to signal on completion.
        urb->usercontext = req;                 // This is a cookie for client code.

        for (int j = 0; j < PACKETS_PER_URB; j++) {
            usbdevfs_iso_packet_desc *pIsoPacketDesc = &urb->iso_frame_desc[j];
            pIsoPacketDesc->length = requested_bytes_per_frame;   // Requested length.
            pIsoPacketDesc->actual_length = 0;
            pIsoPacketDesc->status = 0;
        }
    }
}

/**
 * Launch all the URBs. Return 0 if this was OK, or errno if not.
 */
static int launch_URBs(int fd_usb) {
    int ret;
    for (auto & urbRequest : urbRequests) {
        do {
            ret = ioctl(fd_usb, USBDEVFS_SUBMITURB, &urbRequest);
        } while((ret < 0) && (errno == EINTR));
        if (ret != 0) {
            __android_log_print(ANDROID_LOG_ERROR, __FILE__, "USBDEVFS_SUBMITURB: %d %d", ret, errno);

            // No point going any further, we will block on USBDEVFS_REAPURB indefinitely.
            pthread_mutex_unlock(&s_mutex);
            return errno;
        }
    }
    return 0;
}

/**
 * Discard all the URBs and clean them up. Return 0 if this was OK, or errno if not.
 */
static void discard_URBs(int fd_usb) {

    // Shoot down all the URBs:
    for (auto & urbRequest : urbRequests) {
        do {
            ioctl(fd_usb, USBDEVFS_DISCARDURB, &urbRequests[0]);
        } while (errno == EINTR);
    }

    // Clean them all up by waiting for completion with ioctl:
    usbdevfs_urb *urbReaped = nullptr;
    for (auto & urbRequest : urbRequests) {
        do {
            // Using the non blocking variant of reap:
            ioctl(fd_usb, USBDEVFS_REAPURBNDELAY, &urbReaped);
        } while (errno == EINTR);
    }
}

/**
 * Do audio streaming via isochronous USB.
 * This function is called from a worker thread.
 */
extern "C" JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_stream(JNIEnv* env, jobject thiz,
                                          jint fd_usb, jint configId, jint ifaceId, jint alternateSetting, jint endpointAddress,
                                          jint num_channels, jint sample_rate, jint max_packet_size) {

    pthread_mutex_lock(&s_mutex);

    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Java_org_batgizmo_app_pipeline_NativeUSB_stream fd_usb = %d, s_paused = %s",
                        fd_usb, s_paused ? "true" : "false");

    int ret;

    if (num_channels > MAX_CHANNELS || num_channels < 1) {
        __android_log_print(ANDROID_LOG_ERROR, __FILE__,
                            "Java_org_batgizmo_app_pipeline_NativeUSB_stream invalid number of channels: %d", num_channels);
    }

    s_nominal_samples_per_frame = sample_rate / 1000;      // Samples per ms.

    if (s_nominal_samples_per_frame > MAX_SAMPLES_PER_FRAME || s_nominal_samples_per_frame < 1) {
        __android_log_print(ANDROID_LOG_ERROR, __FILE__,
                            "Java_org_batgizmo_app_pipeline_NativeUSB_stream invalid number of s_nominal_samples_per_frame: %d", s_nominal_samples_per_frame);
    }


    // Prepare to call a kotlin callback to signal buffers ready:
    jmethodID onDataBufferReadyMethod = nullptr;
    const char *kotlinClassName = "org/batgizmo/app/LiveDataBridge";
    const char *kotlinMethodName = "onDataBufferReady";
    jclass bridgeClass = env->FindClass(kotlinClassName);
    if (bridgeClass != nullptr)
        onDataBufferReadyMethod = env->GetStaticMethodID(bridgeClass, kotlinMethodName, "(JI)V");
    if (onDataBufferReadyMethod == nullptr) {
        env->DeleteLocalRef(bridgeClass);
        __android_log_print(ANDROID_LOG_ERROR, __FILE__,
                            "Java_org_batgizmo_app_pipeline_NativeUSB_stream unable to find LiveDataBridge.onDataBufferReady method");
    }

    s_cancel_pending = false;
    s_num_channels = num_channels;
    s_sample_rate = sample_rate;

    // ****** Stream some data ******

    // Important: if request the exact number of samples we expect based on the
    // sampling rate and number of channels, some microphones will occasionally send
    // one more or less. If more samples than our buffer can hold are sent,
    // USBDEVFS_REAPURB hangs, unhelpfully. We could request way more data to be safe,
    // but this causes hangs. It seems as if we have to request the exact number in
    // the USB descriptor endpoint for it to work out:
    const uint32_t requested_bytes_per_frame = max_packet_size;
    initialiseRequests(endpointAddress, requested_bytes_per_frame);

#ifdef FAKE_DATA
    init_fake_data();
#endif

    __android_log_print(ANDROID_LOG_INFO, __FILE__, "starting streaming");

    int balls_in_the_air = 0;
    int s_counter = 0;  // For debugging.
    /*
     * Prepare to use poll on the USB file descriptor to find if data is ready to be read.
     * For some reason, POLLOUT is set when the USB fd is ready to be read. Me neither.
     */
    struct pollfd pfd = { .fd = fd_usb, .events = POLLIN | POLLOUT };

    /**
     * Kick things off by throwing all the balls in the air. We will catch them below
     * and continue juggling them, hopefully without dropping any.
     */
    if (launch_URBs(fd_usb) != 0) {
        // No point going any further, we will block on USBDEVFS_REAPURB indefinitely.
        pthread_mutex_unlock(&s_mutex);
        return errno;
    }
    balls_in_the_air = URBS_TO_JUGGLE;

    /**
     * Juggle the balls until we get notice to stop - at which point, continue catching them
     * until none remain in the air.
     */
    while ((!s_cancel_pending) || (balls_in_the_air > 0)) {
        usbdevfs_urb *urbReaped = nullptr;
        do {
            /*
             * Important: USBDEVFS_REAPURB may hang for ever if the device sends more data then we
             * requested. Usually that doesn't happen, but some microphones will occasionally pad the
             * data if they don't sync their sampling rate to willSoF. On the other hand if we request
             * more data it can also hang. The solution seems to be to use the
             * endpoint buffer size from the USB descriptor.
             *
             * EMT2 however plays by its own rules, and can send much larger data packets than
             * it should. There is some special handling below for that.
             */

            // Use poll to block until data is ready or we time out - ioctl blocks indefinitely
            // making timeouts hard to handle. The USB stream can hang if a larger frame than the buffer
            // size declared in the USB descriptor is received. Timeout seems to be the only way to
            // detect this. EMT2 is one culprit, there may be others.
            const int pollTimeoutMs = 1000;
            int r = poll(&pfd, 1, pollTimeoutMs);
            // __android_log_print(ANDROID_LOG_DEBUG, __FILE__, "poll: %d errno=%d flags=0x%02x", ret, errno, pfd.revents);

            if (r <= 0) {
                // Timeout occurred or something worse.
                __android_log_print(ANDROID_LOG_ERROR, __FILE__,
                                    "poll returned %d, no data ready, errno = %d", ret, errno);
                __android_log_print(ANDROID_LOG_INFO, __FILE__, "recreating URBs");

                // Discard all the URBs and clean them up:
                discard_URBs(fd_usb);
                if (bridgeClass != nullptr)
                    env->DeleteLocalRef(bridgeClass);   // This also cleans up onDataBufferReadyMethod.
                pthread_mutex_unlock(&s_mutex);
                return ETIMEDOUT;
            }

            // Unlock the mutex, so that USB data can be populated into the buffer but
            // other things happen at the same time:
            pthread_mutex_unlock(&s_mutex);
            ret = ioctl(fd_usb, USBDEVFS_REAPURB, &urbReaped);
            pthread_mutex_lock(&s_mutex);
            if (ret == 0) {
                s_counter++;
                balls_in_the_air--;     // We caught one.
                auto *req = (usbdevfs_urb *) urbReaped->usercontext;

                auto *pData = (data_t *) req->buffer;
                // The actual number of samples read might deviate slightly from the number expected,.
                // if the microphone doesn't sync its sampling rate with the host SoF:
                auto actual_samples_read = urbReaped->actual_length / 2;

                // Check the canary value at the end of the buffer:
                assert(pData[MAX_DATA_POINTS_PER_URB] == CANARY_DATA_VALUE);

                if (!s_paused) {

                    if (bridgeClass && onDataBufferReadyMethod) {
                        // Take account of the fact that we often get back fewer data samples
                        // then we requested - the data buffer contains corresponding padding
                        // entries that we need to remove.

                        unsigned int dst_byte_offset = 0, source_byte_offset = 0;
                        auto frame_desc= urbReaped->iso_frame_desc;
                        for (int frame = 0; frame < PACKETS_PER_URB; frame++, frame_desc++) {
                            // memmove because the source and destination overlap:
                            auto actual_length = frame_desc->actual_length;
                            if (frame > 0 && actual_length > 0)
                                memmove((char*) pData + dst_byte_offset, (char*) pData + source_byte_offset, actual_length);
                            dst_byte_offset += frame_desc->actual_length;   // Bytes
                            source_byte_offset += frame_desc->length;       // Bytes
                        }

                        // For stereo data, combine the two channels into a single channel:
                        if (s_num_channels == 2) {
                            // Sample index, not bytes.
                            for (int i = 0, j = 0; i < actual_samples_read; i += 1, j += 2) {
                                // Average of the stereo channel values:
                                pData[i] = (short) (((int) pData[j] + (int) pData[j + 1]) >> 1);
                            }
                            actual_samples_read >>= 1;   // We've just halved the the number of samples.
                        }

                        // Some microphones send empty packets on buffer under run. Avoid wasting time
                        // on them:
                        if (actual_samples_read > 0) {
                            // Notify kotlin that the URB buffer is ready for processing:
                            env->CallStaticVoidMethod(bridgeClass, onDataBufferReadyMethod,
                                                      (jlong) pData, (jint) actual_samples_read);

                            // Write the URB data to the audio output.
                            // Grab the lock to avoid races with audio start/stop.
                            pthread_mutex_lock(&s_mutex);
                            if (audio_out_is_active()) {
                                // Number of channels is 1 by this point:
                                audio_out_write((data_t *) pData, actual_samples_read);
                            }
                            pthread_mutex_unlock(&s_mutex);
                        }
                    }
                }
            }
        } while((ret < 0) && (errno == EINTR) && (!s_cancel_pending));
        if (ret != 0) {
            __android_log_print(ANDROID_LOG_ERROR, __FILE__, "USBDEVFS_REAPURB: %d %d", ret, errno);
            if (errno == ENODEV) {
                // Probably the device is unplugged, so give up.
                break;
            }
            continue;
        }

        // Recycle the request unless a cancel is pending:
        if (!s_cancel_pending) {
            usbdevfs_urb *req = (usbdevfs_urb *) urbReaped->usercontext;
            do {
                ret = ioctl(fd_usb, USBDEVFS_SUBMITURB, req);
                if (ret == 0)
                    balls_in_the_air++; // Rethrow the ball.
            } while ((ret < 0) && (errno == EINTR));
            if (ret != 0) {
                __android_log_print(ANDROID_LOG_ERROR, __FILE__, "USBDEVFS_SUBMITURB 2: %d %d", ret, errno);
                if (errno == ENODEV) {
                    // Probably the device is unplugged, so give up.
                    break;
                }
            }
        }
    }

    // These do nothing if the activity wasn't in progress:
    pthread_mutex_lock(&s_mutex);
    audio_out_stop(env);
    pthread_mutex_unlock(&s_mutex);

    if (bridgeClass != nullptr)
        env->DeleteLocalRef(bridgeClass);   // This also cleans up onDataBufferReadyMethod.

    // Beware: even though streaming has now completed, there may still be calls to
    // Java_org_batgizmo_app_pipeline_NativeUSB_copyBufferData to access the streamed data.

    __android_log_print(ANDROID_LOG_INFO, __FILE__, "ending streaming: ret = %d, errno = %d", ret, errno);

    pthread_mutex_unlock(&s_mutex);

    // If things went bad, return errno, otherwise 0.
    return ret < 0 ? errno : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_cancelStream(JNIEnv* env, jobject thiz) {
    pthread_mutex_lock(&s_mutex);
    s_cancel_pending = true;
    // Reset the pause mode in readiness for the next time we start streaming:
    s_paused = false;
    pthread_mutex_unlock(&s_mutex);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_pauseStream(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&s_mutex);
    __android_log_print(ANDROID_LOG_DEBUG, __FILE__,
                        "Java_org_batgizmo_app_pipeline_NativeUSB_pauseStream pausing");

    s_paused = true;
    pthread_mutex_unlock(&s_mutex);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_resumeStream(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&s_mutex);
    __android_log_print(ANDROID_LOG_DEBUG, __FILE__,
                        "Java_org_batgizmo_app_pipeline_NativeUSB_pauseStream resuming");
    s_paused = false;
    pthread_mutex_unlock(&s_mutex);
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_copyURBBufferData(JNIEnv *env, jobject thiz,
                                                           jlong source_native_offset,
                                                           jint source_samples,
                                                           jshortArray target_buffer,
                                                           jint target_buffer_offset,
                                                           jint target_buffer_size) {

    // The following results in data glitches if uncommented. That's kind of OK
    // as this function doesn't access any module data other than the values in the
    // buffer.
    // pthread_mutex_lock(&s_mutex);

    /*
     * Beware: this function may be called after the stream has closed, due to
     * asynchronous processing as the application disconnects. So the native
     * data offset has to refer to a valid data location at all times.
     */

    jint rc = -1;

    auto pSource = reinterpret_cast<const data_t *>(source_native_offset);

    // Cap this at the target buffer size so there is overwriting or overflow:
    int samples_to_copy = std::min(source_samples, target_buffer_size);

    jshort *pBuffer = env->GetShortArrayElements(target_buffer, nullptr);
    if (pBuffer) {
        data_t *pTarget = pBuffer + target_buffer_offset;

        // We need to copy to the destination target_buffer with wrap, so there may be two parts to the copy.

        const int part1Space = target_buffer_size - target_buffer_offset;
        const int part1Count = samples_to_copy > part1Space ? part1Space : samples_to_copy;
        for (int i = 0; i < part1Count; i++) {
            *pTarget++ = *pSource++;
        }
        samples_to_copy -= part1Count;

        if (samples_to_copy > 0) {
            pTarget = pBuffer;  // Wrap to start of target_buffer.
            const int part2Required = samples_to_copy;
            const int part2Count = part2Required > target_buffer_size ? target_buffer_size : part2Required;
            for (int i = 0; i < part2Count; i++) {
                *pTarget++ = *pSource++;
            }
            samples_to_copy -= part2Count;
        }

        // samples_to_copy should be 0 now.
        rc = source_samples - samples_to_copy;

        env->ReleaseShortArrayElements(target_buffer, pBuffer, 0);
    }

    // pthread_mutex_unlock(&s_mutex);

    return rc;
}

/***********************************************************************************/
/* JNI façade for AAudio playback (implementation in audio_out.cpp).               */
/***********************************************************************************/

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_startAudioFromStream(JNIEnv *env, jobject thiz,
                                                              jint audio_device_id,
                                                              jint heterodyne1_kHz,
                                                              jint heterodyne2_kHz,
                                                              jfloat audio_boost_factor,
                                                              jboolean direct_playback) {

    pthread_mutex_lock(&s_mutex);

    jboolean rc = audio_out_start_live(env, audio_device_id,
                                       s_sample_rate, s_nominal_samples_per_frame,
                                       heterodyne1_kHz, heterodyne2_kHz,
                                       audio_boost_factor, direct_playback);

    pthread_mutex_unlock(&s_mutex);

    return rc;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_startAudioFromLiveInput(JNIEnv *env, jobject thiz,
                                                                 jint audio_device_id,
                                                                 jint sample_rate,
                                                                 jint heterodyne1_kHz,
                                                                 jint heterodyne2_kHz,
                                                                 jfloat audio_boost_factor,
                                                                 jboolean direct_playback) {

    pthread_mutex_lock(&s_mutex);

    jboolean rc = audio_out_start_live(env, audio_device_id,
                                       sample_rate, sample_rate / 1000,
                                       heterodyne1_kHz, heterodyne2_kHz,
                                       audio_boost_factor, direct_playback);

    pthread_mutex_unlock(&s_mutex);

    return rc;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_feedLiveAudioSamples(JNIEnv *env, jobject thiz,
                                                              jshortArray buffer,
                                                              jint offset,
                                                              jint count) {
    if (count <= 0)
        return;

    jshort *samples = env->GetShortArrayElements(buffer, nullptr);
    pthread_mutex_lock(&s_mutex);
    if (audio_out_is_active())
        audio_out_write((data_t *) (samples + offset), (uint32_t) count);
    pthread_mutex_unlock(&s_mutex);
    env->ReleaseShortArrayElements(buffer, samples, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_stopAudio(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&s_mutex);
    audio_out_stop(env);
    pthread_mutex_unlock(&s_mutex);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_startAudioFromBuffer(JNIEnv *env, jobject thiz,
                                                              jint audio_device_id,
                                                              jint sample_rate,
                                                              jint heterodyne1_kHz,
                                                              jint heterodyne2_kHz,
                                                              jfloat audio_boost_factor,
                                                              jshortArray buffer,
                                                              jint start_index,
                                                              jint end_exclusive_index,
                                                              jboolean looped_playback,
                                                              jboolean direct_playback,
                                                              jobject progress_callback) {

    pthread_mutex_lock(&s_mutex);

    jboolean rc = audio_out_start_buffer(env, audio_device_id, sample_rate,
                                         heterodyne1_kHz, heterodyne2_kHz,
                                         audio_boost_factor, buffer,
                                         start_index, end_exclusive_index,
                                         looped_playback, direct_playback,
                                         progress_callback);

    pthread_mutex_unlock(&s_mutex);

    return rc;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_setHeterodyne(JNIEnv *env, jobject thiz,
                                                       jint heterodyne1_kHz, jint heterodyne2_kHz) {
    dsp_set_heterodyne(heterodyne1_kHz, heterodyne2_kHz);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_setAudioBoostFactor(JNIEnv *env, jobject thiz,
                                                             jfloat boost_factor) {
    dsp_set_audio_boost(boost_factor);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_setAgcEnabled(JNIEnv *env, jobject thiz,
                                                       jboolean agc_enabled) {
    dsp_set_agc_enabled(agc_enabled);
}
