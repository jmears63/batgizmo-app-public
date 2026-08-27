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

#include "dsp.h"

#include <math.h>
#include <string.h>
#include <android/log.h>

#define TARGET_AUDIO_OUT_RATE 48000     // Usually this is native for Android devices.

#define MAX_REFERENCE_LEN 512
#define CANARY_COUNT 1
#define CANARY_DATA_VALUE ((int16_t) 0xFACE)

// Adjust this so that there is no heterodyned audio output visible over
// about 10 kHz:
#define DOWNSAMPLING_AA_CUTOFF_HZ 3000      // Conservative (low) value to minimize bleed through/feedback.
#define DOWNSAMPLING_AA_STAGES 4            // Order of the LPF

typedef struct {
    int32_t previous[DOWNSAMPLING_AA_STAGES];
} DownsamplingFilterState;

static_assert(sizeof(DownsamplingFilterState) ==
              sizeof(((dsp_state_t *) nullptr)->downsampling_filter),
              "DownsamplingFilterState must match dsp_state_t.downsampling_filter");

#define BOOST_FACTOR_SCALING_SHIFT 8

static volatile int s_decimation_factor = 0;

static int16_t s_reference_data[MAX_REFERENCE_LEN + CANARY_COUNT];
static int s_reference_len = 0;
static int s_heterodyne1_kHz = 0, s_heterodyne2_kHz = 0;
static volatile bool s_direct_playback = false;

static int s_scaled_audio_boost_factor = 1 << BOOST_FACTOR_SCALING_SHIFT;
static int32_t s_downsampling_iir_coefficient = 0;

static int32_t calculate_iir_coefficient(double cutoff_hz, double sample_rate_hz) {
    double exponent = -2.0 * M_PI * cutoff_hz / sample_rate_hz;
    double a = 1.0 - exp(exponent);
    int32_t coeff = (int32_t) lround(a * (1LL << 31));
    return coeff;
}

static int32_t scale_boost_factor(float boost_factor) {
    const float sanity_max = 2 << 16;
    if (boost_factor > sanity_max)
        boost_factor = sanity_max;
    return (int32_t) (boost_factor * (2 << BOOST_FACTOR_SCALING_SHIFT));
}

static void reset_dsp_state(dsp_state_t *state) {
    memset(state, 0, sizeof(*state));
}

int dsp_configure(int sample_rate,
                  int heterodyne1_kHz,
                  int heterodyne2_kHz,
                  float audio_boost_factor,
                  int samples_per_frame,
                  bool direct_playback,
                  dsp_state_t *state) {

    // Important: often the sample rate will be a multiple of 48kHz, but in rare
    // cases it might not be.
    // Find a downsampling rate the gets us close to 48 kHz audio rate:
    s_decimation_factor = lround((double) sample_rate / TARGET_AUDIO_OUT_RATE);
    if (s_decimation_factor == 0)
        s_decimation_factor = 1;

    // The actual audio out rate may be different from the nominal target value:
    int audio_out_rate = sample_rate / s_decimation_factor;   // What if this is fractional?
    s_downsampling_iir_coefficient = calculate_iir_coefficient(DOWNSAMPLING_AA_CUTOFF_HZ,
                                                               sample_rate);
    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Audio parameters: audio_out_rate = %d, s_decimation_factor = %d",
                        audio_out_rate, s_decimation_factor);

    reset_dsp_state(state);

    s_direct_playback = direct_playback;

    int n = samples_per_frame;
    if (n > MAX_REFERENCE_LEN)      // Paranoia.
        n = MAX_REFERENCE_LEN;

    if (!direct_playback) {
        if (heterodyne1_kHz > n || heterodyne2_kHz > n) {
            __android_log_print(ANDROID_LOG_INFO, __FILE__,
                                "Heterodyne reference outside the valid range for the frame length (%d)",
                                n);
            return 0;
        }

        // Don't recalculate this unnecessarily:
        if (n != s_reference_len) {
            /*
             * Set up the correct number of heterodyne data points in a single
             * cycle of a cosine. Having the same number of points as the sampling rate
             * makes it easy to generated references for multiples of kHz.
             */
            const double pi2 = 3.1415927 * 2;
            int i = 0;
            for (i = 0; i < n; i++) {
                double x = ((double) i) * pi2 / n;
                s_reference_data[i] = (int16_t) (cos(x) * 0x7FFE);
            }
            s_reference_data[i] = CANARY_DATA_VALUE;
            s_reference_len = n;
        }
        s_heterodyne1_kHz = heterodyne1_kHz;
        s_heterodyne2_kHz = heterodyne2_kHz;
    } else {
        s_heterodyne1_kHz = 0;
        s_heterodyne2_kHz = 0;
    }
    s_scaled_audio_boost_factor = scale_boost_factor(audio_boost_factor);

    return audio_out_rate;
}

void dsp_set_heterodyne(int heterodyne1_kHz, int heterodyne2_kHz) {
    // A smooth change to the heterodyne frequency, no step:
    s_heterodyne1_kHz = heterodyne1_kHz;
    s_heterodyne2_kHz = heterodyne2_kHz;
}

void dsp_set_audio_boost(float boost_factor) {
    s_scaled_audio_boost_factor = scale_boost_factor(boost_factor);
}

int dsp_get_decimation_factor(void) {
    return s_decimation_factor;
}

int dsp_process(const int16_t *pBuffer, uint32_t sample_count,
                         int16_t *downsampled_buffer, dsp_state_t *state) {
    int decimated_sample_count = 0;
    int decimation_factor = s_decimation_factor;
    if (decimation_factor < 1)
        decimation_factor = 1;

    // Should this be split into multiple loops that it is more likely to
    // handled entirely in CPU registers? But then we would need more intermediate storage, and
    // more memory accesses.

    for (int i = 0; i < sample_count; i++) {

        int32_t mixed;
        if (s_direct_playback) {
            mixed = pBuffer[i];
        } else {
            // Multiply the raw data by the reference(s).
            mixed = pBuffer[i] * s_reference_data[state->reference1_index];
            if (s_heterodyne2_kHz != 0)
                mixed += pBuffer[i] * s_reference_data[state->reference2_index];
        }

        // Apply a low-pass antialiasing filter before decimation. Not needed for direct
        // playback when the output rate matches the source (decimation factor 1): the fixed-
        // point IIR rounds tiny amplitudes to zero and sounds distorted on very quiet files.
        int64_t filtered = mixed;
        const bool skip_antialiasing = s_direct_playback && decimation_factor == 1;
        if (!skip_antialiasing) {
            for (int order = 0; order < DOWNSAMPLING_AA_STAGES; order++) {
                filtered = (int64_t) s_downsampling_iir_coefficient * filtered +
                           (int64_t) ((1LL << 31) - s_downsampling_iir_coefficient) *
                           state->downsampling_filter.previous[order];
                filtered >>= 31;
                state->downsampling_filter.previous[order] = (int32_t) filtered;
            }
        }

        // Down sample:
        state->decimation_counter++;
        if (state->decimation_counter == decimation_factor) {
            state->decimation_counter = 0;

            // "filtered" is the value in 32 bit signed range, held in a 64 bit integer.
            // We need to apply the boost factor, and scale it to a signed 16 bit range,
            // and saturating rather than wrapping around.

            filtered *= s_scaled_audio_boost_factor;

            // Heterodyne mixing multiplies two int16 values; direct playback does not.
            if (s_direct_playback)
                filtered >>= BOOST_FACTOR_SCALING_SHIFT;
            else
                // Scale down the result of filtering, and also apply the boost factor scaling, in
                // one operation. 15 rather than 16 to gain a factor of 2,
                // because 0.5 * 0.5 is 0.25. Note that it remains a 32 bit signed for the moment so we
                // can handle saturation:
                filtered >>= 15 + BOOST_FACTOR_SCALING_SHIFT;

            // Saturate rather then wrapping around:
            if (filtered > INT16_MAX)
                filtered = INT16_MAX;
            if (filtered < INT16_MIN)
                filtered = INT16_MIN;

            downsampled_buffer[decimated_sample_count++] = static_cast<int16_t>(filtered);
        }

        // Step through the reference waveforms:
        if (!s_direct_playback) {
            state->reference1_index += s_heterodyne1_kHz;
            if (state->reference1_index >= s_reference_len)
                state->reference1_index -= s_reference_len;

            state->reference2_index += s_heterodyne2_kHz;
            if (state->reference2_index >= s_reference_len)
                state->reference2_index -= s_reference_len;
        }
    }

    return decimated_sample_count;
}
