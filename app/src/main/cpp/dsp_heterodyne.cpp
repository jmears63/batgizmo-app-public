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
#include "dsp_heterodyne.h"

#include <math.h>
#include <android/log.h>

#define MAX_REFERENCE_LEN 512
#define CANARY_COUNT 1
#define CANARY_DATA_VALUE ((int16_t) 0xFACE)
/* Why not 16? Because 0.5×0.5 is 0.25. */
#define HETERODYNE_SCALING_SHIFT 15

static int16_t s_reference_data[MAX_REFERENCE_LEN + CANARY_COUNT];
static int s_reference_len = 0;
static int s_heterodyne1_kHz = 0;
static int s_heterodyne2_kHz = 0;

static int32_t s_apply_heterodyne_reference(int16_t value, dsp_state_t *state) {
    dsp_heterodyne_state_t *h = &state->heterodyne;
    /* int16×int16 fits in 32 bits; dual mix can approach 2^31 — use 64-bit. */
    int64_t result = (int64_t) value * s_reference_data[h->reference1_index];
    if (s_heterodyne2_kHz != 0)
        result += (int64_t) value * s_reference_data[h->reference2_index];

    h->reference1_index += s_heterodyne1_kHz;
    if (h->reference1_index >= s_reference_len)
        h->reference1_index -= s_reference_len;

    h->reference2_index += s_heterodyne2_kHz;
    if (h->reference2_index >= s_reference_len)
        h->reference2_index -= s_reference_len;

    if (result > INT32_MAX)
        result = INT32_MAX;
    if (result < INT32_MIN)
        result = INT32_MIN;
    return (int32_t) result;
}

bool dsp_heterodyne_configure(int heterodyne1_kHz, int heterodyne2_kHz,
                              int samples_per_frame,
                              dsp_playback_mode_t playback_mode) {
    int n = samples_per_frame;
    if (n > MAX_REFERENCE_LEN)
        n = MAX_REFERENCE_LEN;

    if (heterodyne1_kHz > n || heterodyne2_kHz > n) {
        __android_log_print(ANDROID_LOG_INFO, __FILE__,
                            "Heterodyne reference outside the valid range for the frame length (%d)",
                            n);
        return false;
    }

    if (n != s_reference_len) {
        /*
         * One cosine cycle with n points (matches kHz steps when n ≈ sample rate
         * in kHz units used by the caller).
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
    s_heterodyne2_kHz = (playback_mode == DSP_PLAYBACK_DUAL_HETERODYNE)
            ? heterodyne2_kHz : 0;
    return true;
}

void dsp_heterodyne_clear(void) {
    s_heterodyne1_kHz = 0;
    s_heterodyne2_kHz = 0;
}

void dsp_set_heterodyne(int heterodyne1_kHz, int heterodyne2_kHz) {
    /* Smooth change: no phase reset. */
    s_heterodyne1_kHz = heterodyne1_kHz;
    s_heterodyne2_kHz = heterodyne2_kHz;
}

int dsp_heterodyne_process(const int16_t *pBuffer, uint32_t sample_count,
                           int16_t *downsampled_buffer, dsp_state_t *state,
                           int decimation_factor, bool agc_enabled) {
    int resultant_sample_count = 0;
    /* Dual mix is ~2× hotter; one extra shift bit restores int16-scale peaks. */
    const int scale_shift = HETERODYNE_SCALING_SHIFT + (s_heterodyne2_kHz != 0 ? 1 : 0);

    for (uint32_t i = 0; i < sample_count; i++) {
        int16_t raw = pBuffer[i];
        int32_t mixed = s_apply_heterodyne_reference(raw, state);
        int32_t filtered = dsp_apply_lpf(mixed, state);

        if (dsp_decimate_keep(decimation_factor, state)) {
            filtered >>= scale_shift;
            downsampled_buffer[resultant_sample_count++] =
                    dsp_apply_output_gain(filtered, agc_enabled);
        }
    }
    return resultant_sample_count;
}
