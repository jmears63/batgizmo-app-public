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
#include "dsp_tdola.h"

#include <math.h>
#include <string.h>
#include <android/log.h>

/*
 * TD-OLA pitch: Ha ≈ W/2 (overlapping input grains, no gaps),
 * Hs = Ha / R so wall-clock duration matches at F_out = F_in / R.
 * Grains are windowed and overlap-added 1:1 (no per-grain interpolator);
 * playing the OLA stream at F_out yields pitch ÷ R.
 */
static int16_t s_hann_q15[DSP_TDOLA_WINDOW_LEN];
static int s_ha = 0;
static int s_hs = 0;

static void s_init_hann(int window_len, int hs) {
    /* Periodic Hann, hop H = W/M → COLA sum ≈ M/2 = W/(2H). */
    const double cola = (double) window_len / (2.0 * (double) hs);
    const double pi2 = 2.0 * M_PI;
    for (int i = 0; i < window_len; i++) {
        double w = 0.5 * (1.0 - cos(pi2 * (double) i / (double) window_len)) / cola;
        int32_t q15 = (int32_t) lround(w * (double) (1 << Q15_BITS));
        if (q15 > INT16_MAX)
            q15 = INT16_MAX;
        if (q15 < 0)
            q15 = 0;
        s_hann_q15[i] = (int16_t) q15;
    }
}

void dsp_tdola_configure(int decimation_factor) {
    const int w = DSP_TDOLA_WINDOW_LEN;
    int r = decimation_factor < 1 ? 1 : decimation_factor;
    /* Hs = W/(2R) so Ha = Hs*R ≈ W/2: 50% input overlap, no gaps. */
    int hs = w / (2 * r);
    if (hs < 1)
        hs = 1;
    s_hs = hs;
    s_ha = hs * r;
    s_init_hann(w, hs);
    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Pitch OLA: W=%d Ha=%d Hs=%d R=%d",
                        w, s_ha, s_hs, r);
}

static void s_fifo_push(dsp_tdola_state_t *t, int16_t sample) {
    if (t->out_n >= DSP_TDOLA_WINDOW_LEN)
        return;
    int32_t wr = t->out_r + t->out_n;
    if (wr >= DSP_TDOLA_WINDOW_LEN)
        wr -= DSP_TDOLA_WINDOW_LEN;
    t->out_fifo[wr] = sample;
    t->out_n++;
}

static bool s_fifo_pop(dsp_tdola_state_t *t, int16_t *out) {
    if (t->out_n <= 0)
        return false;
    *out = t->out_fifo[t->out_r];
    t->out_r++;
    if (t->out_r >= DSP_TDOLA_WINDOW_LEN)
        t->out_r = 0;
    t->out_n--;
    return true;
}

static void s_overlap_add_grain(dsp_tdola_state_t *t,
                                int16_t *downsampled_buffer,
                                int *out_count,
                                int out_needed,
                                bool agc_enabled) {
    const int w = DSP_TDOLA_WINDOW_LEN;
    const int ha = s_ha;
    const int hs = s_hs;

    for (int i = 0; i < w; i++) {
        int32_t windowed =
                ((int32_t) t->in[i] * (int32_t) s_hann_q15[i]) >> Q15_BITS;
        t->ola[i] += windowed;
    }

    for (int i = 0; i < hs; i++) {
        int16_t raw = dsp_saturate_i32_to_i16(t->ola[i]);
        if (*out_count < out_needed) {
            downsampled_buffer[(*out_count)++] = dsp_apply_output_gain(raw, agc_enabled);
        } else {
            s_fifo_push(t, raw);
        }
    }

    memmove(t->ola, t->ola + hs, (size_t) (w - hs) * sizeof(t->ola[0]));
    memset(t->ola + (w - hs), 0, (size_t) hs * sizeof(t->ola[0]));

    memmove(t->in, t->in + ha, (size_t) (t->in_len - ha) * sizeof(t->in[0]));
    t->in_len -= ha;
}

int dsp_tdola_process(const int16_t *pBuffer, uint32_t sample_count,
                      int16_t *downsampled_buffer, dsp_state_t *state,
                      int decimation_factor, bool agc_enabled) {
    dsp_tdola_state_t *t = &state->tdola;
    const int r = decimation_factor < 1 ? 1 : decimation_factor;
    const int w = DSP_TDOLA_WINDOW_LEN;

    const int out_needed =
            (int) ((t->rate_phase + (int32_t) sample_count) / r);
    t->rate_phase = (t->rate_phase + (int32_t) sample_count) % r;

    int out_count = 0;
    while (out_count < out_needed) {
        int16_t raw;
        if (!s_fifo_pop(t, &raw))
            break;
        downsampled_buffer[out_count++] = dsp_apply_output_gain(raw, agc_enabled);
    }

    for (uint32_t i = 0; i < sample_count; i++) {
        t->in[t->in_len++] = pBuffer[i];
        while (t->in_len >= w) {
            s_overlap_add_grain(t, downsampled_buffer, &out_count, out_needed,
                                agc_enabled);
        }
    }

    while (out_count < out_needed)
        downsampled_buffer[out_count++] = dsp_apply_output_gain(0, agc_enabled);

    return out_count;
}
