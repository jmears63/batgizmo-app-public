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

#ifndef BATGIZMO_DSP_UTILS_H
#define BATGIZMO_DSP_UTILS_H

#include <stdbool.h>
#include <stdint.h>

#include "dsp_types.h"

#define TARGET_AUDIO_OUT_RATE 48000

/* AA cutoff for heterodyned audio — keep bleed/feedback low (~audible band). */
#define DOWNSAMPLING_AA_CUTOFF_HETERODYNE_HZ 3000
/* AA cutoff for direct playback when decimating (Nyquist at 48 kHz out is 24 kHz). */
#define DOWNSAMPLING_AA_CUTOFF_DIRECT_HZ 15000

/* Pre-TD-OLA high-pass cutoff (cascaded one-pole stages in dsp_tdola). */
#define TDOLA_HPF_CUTOFF_HZ 10000

#define BOOST_FACTOR_SCALING_SHIFT 8
#define Q15_BITS 15

int32_t dsp_calculate_iir_coefficient(double cutoff_hz, double sample_rate_hz);
void dsp_set_scaled_audio_boost_from_factor(float boost_factor);
void dsp_set_downsampling_iir_coefficient(int32_t coefficient);

int32_t dsp_apply_lpf(int32_t value, dsp_state_t *state);
bool dsp_decimate_keep(int decimation_factor, dsp_state_t *state);
int16_t dsp_apply_output_gain(int32_t sample, bool agc_enabled);
int16_t dsp_saturate_i32_to_i16(int32_t value);

int dsp_direct_process(const int16_t *pBuffer, uint32_t sample_count,
                       int16_t *downsampled_buffer, dsp_state_t *state,
                       int decimation_factor, bool agc_enabled);

#endif /* BATGIZMO_DSP_UTILS_H */
