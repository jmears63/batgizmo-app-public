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

#include "dsp_agc.h"
#include "dsp_utils.h"

/* Peak envelope → gain in boost-scaled units (256 == unity after >> BOOST). */
#define AGC_TARGET_LEVEL 5000
#define AGC_LEVEL_FLOOR 16
#define AGC_TARGET_LEVEL_SCALED (AGC_TARGET_LEVEL << BOOST_FACTOR_SCALING_SHIFT)
#define AGC_GAIN_MIN_SCALED 26
#define AGC_GAIN_MAX_SCALED (AGC_TARGET_LEVEL_SCALED / AGC_LEVEL_FLOOR)
#define AGC_ATTACK_Q15 512
#define AGC_RELEASE_Q15 1

static volatile bool s_agc_enabled = true;
static int64_t s_agc_envelope_q15 = (int64_t) (AGC_TARGET_LEVEL << Q15_BITS);
static int32_t s_agc_envelope_frac_q30 = 0;
static int64_t s_agc_gain_scaled = AGC_GAIN_MIN_SCALED;

static void s_update_agc_gain_scaled(void) {
    int64_t agc_gain_scaled =
            ((int64_t) AGC_TARGET_LEVEL_SCALED << Q15_BITS) / s_agc_envelope_q15;
    if (agc_gain_scaled < AGC_GAIN_MIN_SCALED)
        agc_gain_scaled = AGC_GAIN_MIN_SCALED;
    if (agc_gain_scaled > AGC_GAIN_MAX_SCALED)
        agc_gain_scaled = AGC_GAIN_MAX_SCALED;
    s_agc_gain_scaled = agc_gain_scaled;
}

void dsp_agc_reset(void) {
    s_agc_envelope_q15 = (int64_t) (AGC_TARGET_LEVEL << Q15_BITS);
    s_agc_envelope_frac_q30 = 0;
    s_update_agc_gain_scaled();
}

bool dsp_agc_is_enabled(void) {
    return s_agc_enabled;
}

void dsp_agc_set_enabled(bool enabled) {
    /* Reset only on off→on so stop/start does not wipe learned gain. */
    if (enabled && !s_agc_enabled)
        dsp_agc_reset();
    s_agc_enabled = enabled;
}

/*
 * Peak envelope follower, then apply AGC gain (256 == unity after >> BOOST).
 * Envelope is Q15 (int64) plus a fractional residual so slow release keeps moving.
 */
int64_t dsp_agc_apply(int32_t value) {
    int32_t abs_value = value < 0 ? -value : value;
    if (value == INT32_MIN)
        abs_value = INT32_MAX;

    const int64_t abs_level_q15 = (int64_t) abs_value << Q15_BITS;

    int64_t diff_q15 = abs_level_q15 - s_agc_envelope_q15;
    const int32_t coeff_q15 = diff_q15 > 0 ? AGC_ATTACK_Q15 : AGC_RELEASE_Q15;
    int64_t env_delta_q30 = diff_q15 * coeff_q15 + s_agc_envelope_frac_q30;
    int64_t env_delta_q15 = env_delta_q30 >> Q15_BITS;
    s_agc_envelope_q15 += env_delta_q15;
    s_agc_envelope_frac_q30 = (int32_t) (env_delta_q30 - (env_delta_q15 << Q15_BITS));

    const int64_t floor_q15 = (int64_t) AGC_LEVEL_FLOOR << Q15_BITS;
    const int64_t envelope_before_clamp_q15 = s_agc_envelope_q15;
    if (s_agc_envelope_q15 < floor_q15)
        s_agc_envelope_q15 = floor_q15;

    if (env_delta_q15 != 0 || s_agc_envelope_q15 != envelope_before_clamp_q15)
        s_update_agc_gain_scaled();

    int64_t result = (int64_t) value * s_agc_gain_scaled;
    result >>= BOOST_FACTOR_SCALING_SHIFT;
    return result;
}
