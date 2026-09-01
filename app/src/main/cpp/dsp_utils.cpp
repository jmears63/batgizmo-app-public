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

#include "dsp_utils.h"
#include "dsp_agc.h"

#include <math.h>

typedef struct {
    int32_t previous[DOWNSAMPLING_AA_STAGES];
} DownsamplingFilterState;

static_assert(sizeof(DownsamplingFilterState) ==
              sizeof(((dsp_state_t *) nullptr)->downsampling_filter),
              "DownsamplingFilterState must match dsp_state_t.downsampling_filter");

static int s_scaled_audio_boost_factor = 1 << BOOST_FACTOR_SCALING_SHIFT;
static int32_t s_downsampling_iir_coefficient = 0;

int32_t dsp_calculate_iir_coefficient(double cutoff_hz, double sample_rate_hz) {
    double exponent = -2.0 * M_PI * cutoff_hz / sample_rate_hz;
    double a = 1.0 - exp(exponent);
    return (int32_t) lround(a * (1LL << 31));
}

void dsp_set_scaled_audio_boost_from_factor(float boost_factor) {
    const float sanity_max = 2 << 16;
    if (boost_factor > sanity_max)
        boost_factor = sanity_max;
    s_scaled_audio_boost_factor = (int32_t) (boost_factor * (2 << BOOST_FACTOR_SCALING_SHIFT));
}

void dsp_set_downsampling_iir_coefficient(int32_t coefficient) {
    s_downsampling_iir_coefficient = coefficient;
}

int32_t dsp_apply_lpf(int32_t value, dsp_state_t *state) {
    int64_t filtered = value;
    for (int order = 0; order < DOWNSAMPLING_AA_STAGES; order++) {
        filtered = (int64_t) s_downsampling_iir_coefficient * filtered +
                   (int64_t) ((1LL << 31) - s_downsampling_iir_coefficient) *
                   state->downsampling_filter.previous[order];
        filtered >>= 31;
        state->downsampling_filter.previous[order] = (int32_t) filtered;
    }
    return (int32_t) filtered;
}

bool dsp_decimate_keep(int decimation_factor, dsp_state_t *state) {
    state->decimation_counter++;
    if (state->decimation_counter != decimation_factor)
        return false;
    state->decimation_counter = 0;
    return true;
}

static int16_t s_apply_boost_and_saturate(int64_t value, int scaled_boost_factor) {
    int64_t scaled = (int64_t) value * scaled_boost_factor;
    scaled >>= BOOST_FACTOR_SCALING_SHIFT;

    if (scaled > INT16_MAX)
        scaled = INT16_MAX;
    if (scaled < INT16_MIN)
        scaled = INT16_MIN;

    return static_cast<int16_t>(scaled);
}

int16_t dsp_apply_output_gain(int32_t sample, bool agc_enabled) {
    int64_t gained = agc_enabled ? dsp_agc_apply(sample) : (int64_t) sample;
    return s_apply_boost_and_saturate(gained, s_scaled_audio_boost_factor);
}

int16_t dsp_saturate_i32_to_i16(int32_t value) {
    if (value > INT16_MAX)
        return (int16_t) INT16_MAX;
    if (value < INT16_MIN)
        return (int16_t) INT16_MIN;
    return (int16_t) value;
}

int dsp_direct_process(const int16_t *pBuffer, uint32_t sample_count,
                       int16_t *downsampled_buffer, dsp_state_t *state,
                       int decimation_factor, bool agc_enabled) {
    int resultant_sample_count = 0;
    const bool do_lpf = decimation_factor != 1;

    for (uint32_t i = 0; i < sample_count; i++) {
        int16_t raw = pBuffer[i];
        int32_t filtered = do_lpf ? dsp_apply_lpf(raw, state) : raw;

        if (dsp_decimate_keep(decimation_factor, state)) {
            downsampled_buffer[resultant_sample_count++] =
                    dsp_apply_output_gain(filtered, agc_enabled);
        }
    }
    return resultant_sample_count;
}
