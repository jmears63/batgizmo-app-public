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

#ifndef BATGIZMO_DSP_TE_STATE_H
#define BATGIZMO_DSP_TE_STATE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DSP_TE_LEFTOVER_MAX 64

typedef struct {
    int16_t leftover[DSP_TE_LEFTOVER_MAX];
    int32_t leftover_n;
    int32_t frac_q16;
    int32_t rate_phase;
} dsp_te_state_t;

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_DSP_TE_STATE_H */

#if defined(BATGIZMO_DSP_STATE_COMPLETE) && !defined(BATGIZMO_DSP_TE_API_H)
#define BATGIZMO_DSP_TE_API_H

#ifdef __cplusplus
extern "C" {
#endif

void dsp_te_configure(int input_rate_hz, int output_rate_hz, int expansion_factor);
int dsp_te_input_samples_for_output(int output_frames);
int dsp_te_process(const int16_t *pBuffer, uint32_t sample_count,
                   int16_t *downsampled_buffer, dsp_state_t *state,
                   bool agc_enabled);

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_DSP_TE_API_H */
