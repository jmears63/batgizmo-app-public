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

#ifndef BATGIZMO_DSP_TDOLA_STATE_H
#define BATGIZMO_DSP_TDOLA_STATE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Analysis grain length. Must be even. */
#define DSP_TDOLA_WINDOW_LEN 256

/* Synthesis grain / OLA buffer upper bound (expansion when pitch > Fin/Fout). */
#define DSP_TDOLA_OUT_MAX (DSP_TDOLA_WINDOW_LEN * 4)

/* Optional pre-TD-OLA high-pass (cascaded one-pole stages). */
#define DSP_TDOLA_HPF_STAGES 4

/* Per-stream TD-OLA continuity across dsp_process chunks. */
typedef struct {
    int16_t in[DSP_TDOLA_WINDOW_LEN];
    int32_t in_len;
    int32_t ola[DSP_TDOLA_OUT_MAX];
    int16_t out_fifo[DSP_TDOLA_OUT_MAX];
    int32_t out_r;
    int32_t out_n;
    int32_t rate_phase;  /* accumulator for exact out count via Ha/Hs */
    struct {
        int32_t x_prev[DSP_TDOLA_HPF_STAGES];
        int32_t y_prev[DSP_TDOLA_HPF_STAGES];
    } hpf;
} dsp_tdola_state_t;

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_DSP_TDOLA_STATE_H */

#if defined(BATGIZMO_DSP_STATE_COMPLETE) && !defined(BATGIZMO_DSP_TDOLA_API_H)
#define BATGIZMO_DSP_TDOLA_API_H

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Hops: Ha/Hs = input_rate/output_rate (duration at output_rate).
 * pitch_ratio: overall pitch division vs original.
 *   W_out = Win * pitch_ratio * Fout / Fin
 *   (> Win expands / more pitch drop; < Win compresses / less pitch drop).
 */
void dsp_tdola_configure(int input_rate_hz, int output_rate_hz, int pitch_ratio,
                         bool hpf_enabled);

int dsp_tdola_input_samples_for_output(int output_frames);

int dsp_tdola_process(const int16_t *pBuffer, uint32_t sample_count,
                      int16_t *downsampled_buffer, dsp_state_t *state,
                      bool agc_enabled);

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_DSP_TDOLA_API_H */
