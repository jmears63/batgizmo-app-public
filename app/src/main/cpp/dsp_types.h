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

#ifndef BATGIZMO_DSP_TYPES_H
#define BATGIZMO_DSP_TYPES_H

#include <stdint.h>

/*
 * Playback mode values must match Settings.AudioPlaybackModeOptions in Kotlin.
 */
typedef enum {
    DSP_PLAYBACK_SINGLE_HETERODYNE = 0,
    DSP_PLAYBACK_DUAL_HETERODYNE = 1,
    DSP_PLAYBACK_DIRECT = 2,
    DSP_PLAYBACK_PITCH_SHIFTING = 3,
    DSP_PLAYBACK_TIME_EXPANSION = 4,
    DSP_PLAYBACK_AUTO_TUNED_HETERODYNE = 5,
} dsp_playback_mode_t;

/* Must match DOWNSAMPLING_AA_STAGES in dsp_utils.h. */
#define DOWNSAMPLING_AA_STAGES 4

typedef struct {
    int reference1_index;
    int reference2_index;
} dsp_heterodyne_state_t;

/* Analysis grain length. Must be even. */
#define DSP_TDOLA_WINDOW_LEN 256

/* Synthesis grain / OLA buffer upper bound (expansion when pitch > Fin/Fout). */
#define DSP_TDOLA_OUT_MAX (DSP_TDOLA_WINDOW_LEN * 4)

/* Optional pre-TD-OLA high-pass (cascaded one-pole stages). */
#define DSP_TDOLA_HPF_STAGES 4

typedef struct {
    int16_t in[DSP_TDOLA_WINDOW_LEN];
    int32_t in_len;
    int32_t ola[DSP_TDOLA_OUT_MAX];
    int16_t out_fifo[DSP_TDOLA_OUT_MAX];
    int32_t out_r;
    int32_t out_n;
    int32_t rate_phase;
    struct {
        int32_t x_prev[DSP_TDOLA_HPF_STAGES];
        int32_t y_prev[DSP_TDOLA_HPF_STAGES];
    } hpf;
} dsp_tdola_state_t;

#define DSP_TE_LEFTOVER_MAX 64

typedef struct {
    int16_t leftover[DSP_TE_LEFTOVER_MAX];
    int32_t leftover_n;
    int32_t frac_q16;
    int32_t rate_phase;
} dsp_te_state_t;

typedef struct {
    int32_t decimation_counter;
    struct {
        int32_t previous[DOWNSAMPLING_AA_STAGES];
    } downsampling_filter;
    dsp_heterodyne_state_t heterodyne;
    dsp_tdola_state_t tdola;
    dsp_te_state_t te;
} dsp_state_t;

#endif /* BATGIZMO_DSP_TYPES_H */
