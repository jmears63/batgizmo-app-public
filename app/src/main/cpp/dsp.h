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

#ifndef BATGIZMO_DSP_H
#define BATGIZMO_DSP_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Playback mode values must match Settings.AudioPlaybackModeOptions in Kotlin.
 * Dual vs single heterodyne both use the heterodyne process path; dual is selected
 * when playback_mode is DUAL or heterodyne2_kHz != 0 after configure.
 */
typedef enum {
    DSP_PLAYBACK_SINGLE_HETERODYNE = 0,
    DSP_PLAYBACK_DUAL_HETERODYNE = 1,
    DSP_PLAYBACK_DIRECT = 2,
    DSP_PLAYBACK_PITCH_SHIFTING = 3,
} dsp_playback_mode_t;

/* Mutable continuity across dsp_process chunks (per live or viewer stream). */
typedef struct {
    int32_t decimation_counter;
    struct {
        int32_t previous[4];  /* Must match DOWNSAMPLING_AA_STAGES in dsp.cpp. */
    } downsampling_filter;
    int reference1_index;
    int reference2_index;
} dsp_state_t;

int dsp_process(const int16_t *pBuffer, uint32_t sample_count,
                             int16_t *downsampled_buffer, dsp_state_t *state);

/* Returns the AAudio output rate on success, or 0 on failure. */
int dsp_configure(int sample_rate,
                  int heterodyne1_kHz, int heterodyne2_kHz,
                  float audio_boost_factor, int samples_per_frame,
                  dsp_playback_mode_t playback_mode, dsp_state_t *state);

void dsp_set_heterodyne(int heterodyne1_kHz, int heterodyne2_kHz);

void dsp_set_audio_boost(float boost_factor);

void dsp_set_agc_enabled(bool enabled);

int dsp_get_decimation_factor(void);

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_DSP_H */
