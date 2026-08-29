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

/* OLA window length (Hann table and grain size). Must be even. */
#define DSP_TDOLA_WINDOW_LEN 256

/* Per-stream TD-OLA continuity across dsp_process chunks. */
typedef struct {
    int16_t in[DSP_TDOLA_WINDOW_LEN];
    int32_t in_len;
    int32_t ola[DSP_TDOLA_WINDOW_LEN];
    int16_t out_fifo[DSP_TDOLA_WINDOW_LEN];
    int32_t out_r;
    int32_t out_n;
    int32_t rate_phase;  /* for exact out count ≈ in / R */
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

void dsp_tdola_configure(int decimation_factor);
int dsp_tdola_process(const int16_t *pBuffer, uint32_t sample_count,
                      int16_t *downsampled_buffer, dsp_state_t *state,
                      int decimation_factor, bool agc_enabled);

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_DSP_TDOLA_API_H */
