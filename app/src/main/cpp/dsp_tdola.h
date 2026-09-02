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

#ifndef BATGIZMO_DSP_TDOLA_H
#define BATGIZMO_DSP_TDOLA_H

#include <stdbool.h>
#include <stdint.h>

#include "dsp_types.h"

/*
 * Hops: Ha/Hs = input_rate/output_rate (duration at output_rate).
 * pitch_ratio: overall pitch division vs original.
 */
void dsp_tdola_configure(int input_rate_hz, int output_rate_hz, int pitch_ratio,
                         bool hpf_enabled);

int dsp_tdola_input_samples_for_output(int output_frames);

int dsp_tdola_process(const int16_t *pBuffer, uint32_t sample_count,
                      int16_t *downsampled_buffer, dsp_state_t *state,
                      bool agc_enabled);

#endif /* BATGIZMO_DSP_TDOLA_H */
