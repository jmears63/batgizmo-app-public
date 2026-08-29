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

#include "dsp_internal.h"
#include "dsp_agc.h"
#include "dsp_heterodyne.h"
#include "dsp_tdola.h"
#include "dsp_te.h"

#include <math.h>
#include <string.h>
#include <android/log.h>

typedef struct {
    int32_t previous[DOWNSAMPLING_AA_STAGES];
} DownsamplingFilterState;

static_assert(sizeof(DownsamplingFilterState) ==
              sizeof(((dsp_state_t *) nullptr)->downsampling_filter),
              "DownsamplingFilterState must match dsp_state_t.downsampling_filter");

static volatile int s_decimation_factor = 0;

typedef enum {
    DSP_MODE_HETERODYNE = 0,
    DSP_MODE_DIRECT = 1,
    DSP_MODE_PITCH_SHIFT = 2,
    DSP_MODE_TIME_EXPANSION = 3,
} dsp_mode_t;

static volatile dsp_mode_t s_playback_mode = DSP_MODE_HETERODYNE;
static int s_scaled_audio_boost_factor = 1 << BOOST_FACTOR_SCALING_SHIFT;
static int32_t s_downsampling_iir_coefficient = 0;

/***********************************************************************************/
/* Common helpers                                                                  */
/***********************************************************************************/

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
    /* Must use 64-bit: after heterodyning, |value| can be ~2^30. */
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

/***********************************************************************************/
/* Public API                                                                      */
/***********************************************************************************/

int dsp_configure(int sample_rate,
                  int heterodyne1_kHz,
                  int heterodyne2_kHz,
                  float audio_boost_factor,
                  int samples_per_frame,
                  dsp_playback_mode_t playback_mode,
                  int pitch_ratio,
                  dsp_state_t *state) {

    s_decimation_factor = lround((double) sample_rate / TARGET_AUDIO_OUT_RATE);
    if (s_decimation_factor == 0)
        s_decimation_factor = 1;

    /* Direct/heterodyne: AAudio at sample_rate/R. Pitch: always TARGET (48 kHz). */
    int audio_out_rate = sample_rate / s_decimation_factor;
    s_downsampling_iir_coefficient =
            dsp_calculate_iir_coefficient(DOWNSAMPLING_AA_CUTOFF_HZ, sample_rate);
    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Audio parameters: sample_rate = %d, s_decimation_factor = %d, playback_mode = %d, pitch_ratio = %d",
                        sample_rate, s_decimation_factor, playback_mode, pitch_ratio);

    memset(state, 0, sizeof(*state));

    switch (playback_mode) {
        case DSP_PLAYBACK_DIRECT:
            s_playback_mode = DSP_MODE_DIRECT;
            dsp_heterodyne_clear();
            break;
        case DSP_PLAYBACK_PITCH_SHIFTING: {
            s_playback_mode = DSP_MODE_PITCH_SHIFT;
            dsp_heterodyne_clear();
            audio_out_rate = TARGET_AUDIO_OUT_RATE;
            int pr = pitch_ratio < 1 ? s_decimation_factor : pitch_ratio;
            if (pr < 1)
                pr = 1;
            dsp_tdola_configure(sample_rate, TARGET_AUDIO_OUT_RATE, pr);
            break;
        }
        case DSP_PLAYBACK_TIME_EXPANSION: {
            s_playback_mode = DSP_MODE_TIME_EXPANSION;
            dsp_heterodyne_clear();
            audio_out_rate = TARGET_AUDIO_OUT_RATE;
            int e = pitch_ratio < 1 ? 8 : pitch_ratio;
            dsp_te_configure(sample_rate, TARGET_AUDIO_OUT_RATE, e);
            break;
        }
        case DSP_PLAYBACK_SINGLE_HETERODYNE:
        case DSP_PLAYBACK_DUAL_HETERODYNE:
        default:
            s_playback_mode = DSP_MODE_HETERODYNE;
            if (!dsp_heterodyne_configure(heterodyne1_kHz, heterodyne2_kHz,
                                          samples_per_frame, playback_mode))
                return 0;
            break;
    }

    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "AAudio rate = %d", audio_out_rate);

    dsp_set_scaled_audio_boost_from_factor(audio_boost_factor);
    return audio_out_rate;
}

void dsp_set_audio_boost(float boost_factor) {
    dsp_set_scaled_audio_boost_from_factor(boost_factor);
}

int dsp_get_decimation_factor(void) {
    return s_decimation_factor;
}

int dsp_get_input_samples_for_output(int output_frames) {
    if (output_frames <= 0)
        return 0;
    if (s_playback_mode == DSP_MODE_PITCH_SHIFT)
        return dsp_tdola_input_samples_for_output(output_frames);
    if (s_playback_mode == DSP_MODE_TIME_EXPANSION)
        return dsp_te_input_samples_for_output(output_frames);
    int r = s_decimation_factor < 1 ? 1 : s_decimation_factor;
    return output_frames * r;
}

int dsp_process(const int16_t *pBuffer, uint32_t sample_count,
                int16_t *downsampled_buffer, dsp_state_t *state) {

    int decimation_factor = s_decimation_factor;
    if (decimation_factor < 1)
        decimation_factor = 1;

    const bool agc_enabled = dsp_agc_is_enabled();
    const dsp_mode_t mode = s_playback_mode;

    switch (mode) {
        case DSP_MODE_DIRECT:
            return dsp_direct_process(pBuffer, sample_count, downsampled_buffer, state,
                                      decimation_factor, agc_enabled);
        case DSP_MODE_PITCH_SHIFT:
            return dsp_tdola_process(pBuffer, sample_count, downsampled_buffer, state,
                                     agc_enabled);
        case DSP_MODE_TIME_EXPANSION:
            return dsp_te_process(pBuffer, sample_count, downsampled_buffer, state,
                                  agc_enabled);
        case DSP_MODE_HETERODYNE:
        default:
            return dsp_heterodyne_process(pBuffer, sample_count, downsampled_buffer, state,
                                          decimation_factor, agc_enabled);
    }
}
