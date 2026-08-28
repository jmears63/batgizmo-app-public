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

#define BOOST_FACTOR_SCALING_SHIFT 8    // The boost factor is scaled by this amount.
#define HETERODYNE_SCALING_SHIFT 15     // Heterodyning scales by this amount.
                                        // Why not 16? Because 0.5 x 0.5 is 0.25
#define Q15_BITS 15                     // Q15 fixed-point: unity = 1 << Q15_BITS.

static volatile int s_decimation_factor = 0;

static int16_t s_reference_data[MAX_REFERENCE_LEN + CANARY_COUNT];
static int s_reference_len = 0;
static int s_heterodyne1_kHz = 0, s_heterodyne2_kHz = 0;
static volatile bool s_do_heterodyne = true;
static volatile bool s_agc_enabled = true;

static int s_scaled_audio_boost_factor = 1 << BOOST_FACTOR_SCALING_SHIFT;
static int32_t s_downsampling_iir_coefficient = 0;

/* AGC: peak envelope → gain in boost-scaled units (256 == unity after >> BOOST_FACTOR_SCALING_SHIFT). */
#define AGC_TARGET_LEVEL 5000          // Target peak output amplitude (not boost-scaled).
#define AGC_LEVEL_FLOOR 16             // Envelope floor in input amplitude (not boost-scaled).
#define AGC_TARGET_LEVEL_SCALED (AGC_TARGET_LEVEL << BOOST_FACTOR_SCALING_SHIFT)
#define AGC_GAIN_MIN_SCALED 26         // ~0.1× (matches UI boost floor)
/* Max gain at envelope floor: TARGET_GAIN_SCALED / AGC_LEVEL_FLOOR. */
#define AGC_GAIN_MAX_SCALED (AGC_TARGET_LEVEL_SCALED / AGC_LEVEL_FLOOR)
#define AGC_ATTACK_Q15 512             // Rapid reaction to loud noise
#define AGC_RELEASE_Q15 1              // Slow recovery from loud noises.

// Start with high envelope to avoid initial loud noises:
static int64_t s_agc_envelope_q15 = (int64_t) (AGC_TARGET_LEVEL << Q15_BITS);
static int32_t s_agc_envelope_frac_q30 = 0;  // leftover from (diff * coeff) >> Q15_BITS

/***********************************************************************************/
/* Static helpers                                                                  */
/***********************************************************************************/

static inline int32_t s_calculate_iir_coefficient(double cutoff_hz, double sample_rate_hz) {
    double exponent = -2.0 * M_PI * cutoff_hz / sample_rate_hz;
    double a = 1.0 - exp(exponent);
    int32_t coeff = (int32_t) lround(a * (1LL << 31));
    return coeff;
}

static inline int32_t s_scale_boost_factor(float boost_factor) {
    const float sanity_max = 2 << 16;
    if (boost_factor > sanity_max)
        boost_factor = sanity_max;
    return (int32_t) (boost_factor * (2 << BOOST_FACTOR_SCALING_SHIFT));
}

static inline void s_reset_dsp_state(dsp_state_t *state) {
    memset(state, 0, sizeof(*state));
}

/* Stage 1: multiply by heterodyne reference(s), then advance LUT phase. */
static inline int32_t s_apply_heterodyne_reference(int16_t value, dsp_state_t *state) {
    // int16×int16 fits in 32 bits; two products (dual hetero) can approach 2^31,
    // so accumulate in 64-bit before narrowing.
    int64_t result = (int64_t) value * s_reference_data[state->reference1_index];
    if (s_heterodyne2_kHz != 0)
        result += (int64_t) value * s_reference_data[state->reference2_index];

    state->reference1_index += s_heterodyne1_kHz;
    if (state->reference1_index >= s_reference_len)
        state->reference1_index -= s_reference_len;

    state->reference2_index += s_heterodyne2_kHz;
    if (state->reference2_index >= s_reference_len)
        state->reference2_index -= s_reference_len;

    if (result > INT32_MAX)
        result = INT32_MAX;
    if (result < INT32_MIN)
        result = INT32_MIN;
    return (int32_t) result;
}

/* Stage 2: cascaded 1-pole LPF (AA before decimation / post-heterodyne). */
static inline int32_t s_apply_lpf(int32_t value, dsp_state_t *state) {
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

/* Stage 3: keep every Nth sample. */
static inline bool s_decimate_keep(int decimation_factor, dsp_state_t *state) {
    state->decimation_counter++;
    if (state->decimation_counter != decimation_factor)
        return false;
    state->decimation_counter = 0;
    return true;
}

/* Stage 4: apply boost, rescale after mix/filter, saturate to int16. */
static inline int16_t s_apply_boost_and_saturate(int64_t value, int scaled_boost_factor) {
    // Must use 64-bit: after heterodyning, |value| can be ~2^30 and the boost
    // multiply would overflow int32 before the shift.
    int64_t scaled = (int64_t) value * scaled_boost_factor;
    scaled >>= BOOST_FACTOR_SCALING_SHIFT;

    if (scaled > INT16_MAX)
        scaled = INT16_MAX;
    if (scaled < INT16_MIN)
        scaled = INT16_MIN;

    return static_cast<int16_t>(scaled);
}

static inline void s_reset_agc(void) {
    // Start with large envelope to avoid initial loud noises:
    s_agc_envelope_q15 = (int64_t) (AGC_TARGET_LEVEL << Q15_BITS);
    s_agc_envelope_frac_q30 = 0;
}

/*
 * Peak envelope follower, then apply AGC gain (256 == unity after >> BOOST_FACTOR_SCALING_SHIFT).
 * Envelope is Q15 (int64) plus a fractional residual so slow release keeps moving.
 */
static inline int64_t s_apply_agc(int32_t value) {
    int32_t abs_value = value < 0 ? -value : value;
    if (value == INT32_MIN)
        abs_value = INT32_MAX;

    // Use q15 fixed point integers to allow for fractional calculations, needed
    // for slow changes in envelope:
    const int64_t abs_level_q15 = (int64_t) abs_value << Q15_BITS;

    // Update the envelope, tracking increases faster than decreases:
    int64_t diff_q15 = abs_level_q15 - s_agc_envelope_q15;
    const int32_t coeff_q15 = diff_q15 > 0 ? AGC_ATTACK_Q15 : AGC_RELEASE_Q15;
    // Try to use up any accumulated fractional part, accumulating any that we couldn't use:
    int64_t env_delta_q30 = diff_q15 * coeff_q15 + s_agc_envelope_frac_q30;
    int64_t env_delta_q15 = env_delta_q30 >> Q15_BITS;
    // Update the envelope:
    s_agc_envelope_q15 += env_delta_q15;
    // Save any left over fractional part for the next time around:
    s_agc_envelope_frac_q30 = (int32_t) (env_delta_q30 - (env_delta_q15 << Q15_BITS));

    // Avoid very small envelope values that would result in very high gain:
    const int64_t floor_q15 = (int64_t) AGC_LEVEL_FLOOR << Q15_BITS;
    if (s_agc_envelope_q15 < floor_q15)
        s_agc_envelope_q15 = floor_q15;

    // Calculate the AGC gain we need to achieve the target gain:
    int64_t agc_gain_scaled =
            ((int64_t) AGC_TARGET_LEVEL_SCALED << Q15_BITS) / s_agc_envelope_q15;
    if (agc_gain_scaled < AGC_GAIN_MIN_SCALED)
        agc_gain_scaled = AGC_GAIN_MIN_SCALED;
    if (agc_gain_scaled > AGC_GAIN_MAX_SCALED)
        agc_gain_scaled = AGC_GAIN_MAX_SCALED;

    int64_t result = (int64_t) value * agc_gain_scaled;
    result >>= BOOST_FACTOR_SCALING_SHIFT;
    return result;
}

/***********************************************************************************/
/* Public API                                                                      */
/***********************************************************************************/

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
    s_downsampling_iir_coefficient = s_calculate_iir_coefficient(DOWNSAMPLING_AA_CUTOFF_HZ,
                                                               sample_rate);
    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Audio parameters: audio_out_rate = %d, s_decimation_factor = %d",
                        audio_out_rate, s_decimation_factor);

    s_reset_dsp_state(state);
    s_reset_agc();

    s_do_heterodyne = !direct_playback;

    int n = samples_per_frame;
    if (n > MAX_REFERENCE_LEN)      // Paranoia.
        n = MAX_REFERENCE_LEN;

    if (s_do_heterodyne) {
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
    s_scaled_audio_boost_factor = s_scale_boost_factor(audio_boost_factor);

    return audio_out_rate;
}

void dsp_set_heterodyne(int heterodyne1_kHz, int heterodyne2_kHz) {
    // A smooth change to the heterodyne reference, no step in phase:
    s_heterodyne1_kHz = heterodyne1_kHz;
    s_heterodyne2_kHz = heterodyne2_kHz;
}

void dsp_set_audio_boost(float boost_factor) {
    s_scaled_audio_boost_factor = s_scale_boost_factor(boost_factor);
}

void dsp_set_agc_enabled(bool enabled) {
    s_agc_enabled = enabled;
    if (enabled)
        s_reset_agc();
}

int dsp_get_decimation_factor(void) {
    return s_decimation_factor;
}

int dsp_process(const int16_t *pBuffer, uint32_t sample_count,
                int16_t *downsampled_buffer, dsp_state_t *state) {

    int resultant_sample_count = 0;
    int decimation_factor = s_decimation_factor;
    if (decimation_factor < 1)
        decimation_factor = 1;

    // Without heterodyning, AA is skipped when factor is 1: the fixed-point IIR
    // rounds tiny amplitudes to zero on very quiet files.
    const bool do_heterodyne = s_do_heterodyne;
    const bool do_lpf = do_heterodyne || decimation_factor != 1;

    int scale_shift = 0;
    if (do_heterodyne) {
        scale_shift += HETERODYNE_SCALING_SHIFT;

        // In dual mode, two components are added so we need to scale down by an extra
        // factor of 2:
        if (s_heterodyne2_kHz != 0)
            scale_shift += 1;
    }

    /*
     * The following loop processes a single raw data sample at a time through each
     * DSP stage. That isn't very efficient. It would be more efficient to process data in chunks because
     * more state could remain in CPU registers. One day I will fix this.
     */

    for (uint32_t i = 0; i < sample_count; i++) {
        int16_t raw = pBuffer[i];           // Range of raw: INT16_MIN to INT16_MAX

        int32_t mixed = do_heterodyne ? s_apply_heterodyne_reference(raw, state)
                : raw;
        // Range of mixed: about ±1073642282 (single heterodyne), INT32_MIN to INT32_MAX
        // (dual heterodyne, clamped sum), else INT16_MIN to INT16_MAX

        int32_t filtered = do_lpf ? s_apply_lpf(mixed, state)
                : mixed;
        // Range of filtered: about ±1073642282 (single heterodyne), INT32_MIN to INT32_MAX
        // (dual heterodyne), else INT16_MIN to INT16_MAX

        if (s_decimate_keep(decimation_factor, state)) {
            // Apply any scaling required by previous processing steps:
            filtered >>= scale_shift;       // Range of filtered: INT16_MIN to INT16_MAX

            int64_t gained = s_agc_enabled ? s_apply_agc(filtered) : (int64_t) filtered;
            // Range of gained: about -10240000 to 10198437 (with AGC), else INT16_MIN to INT16_MAX

            downsampled_buffer[resultant_sample_count++] =
                    s_apply_boost_and_saturate(gained, s_scaled_audio_boost_factor);
        }
    }

    return resultant_sample_count;
}
