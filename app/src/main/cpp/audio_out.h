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

#ifndef BATGIZMO_AUDIO_OUT_H
#define BATGIZMO_AUDIO_OUT_H

#include <stdint.h>
#include <stdbool.h>
#include <jni.h>

#include "dsp.h"

#ifdef __cplusplus
extern "C" {
#endif

void audio_out_set_jvm(JavaVM *vm);

bool audio_out_is_active(void);

void audio_out_stop(JNIEnv *env);

/* Push live mono PCM through DSP into AAudio (no-op-safe only when active). */
void audio_out_write(const int16_t *samples, uint32_t sample_count);

/* Stop any current output, configure DSP for live path, open AAudio (push mode). */
bool audio_out_start_live(JNIEnv *env,
                          jint audio_device_id,
                          int sample_rate,
                          int samples_per_frame,
                          jint heterodyne1_kHz,
                          jint heterodyne2_kHz,
                          float audio_boost_factor,
                          dsp_playback_mode_t playback_mode);

/*
 * Stop any current output, set up viewer buffer playback, configure DSP, open AAudio
 * (pull/callback mode). Creates JNI global refs for buffer and progress_callback.
 */
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
                            dsp_playback_mode_t playback_mode,
                            jobject progress_callback);

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_AUDIO_OUT_H */
