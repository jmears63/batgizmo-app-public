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
 * Ha/Hs = Fin/Fout → duration at F_out.
 * W_out = Win * pitch * Fout / Fin → pitch ÷pitch_ratio
 *   (W_out > Win: expand; W_out < Win: compress).
 */
static int16_t s_hann_q15[DSP_TDOLA_OUT_MAX];
static int s_ha = 0;
static int s_hs = 0;
static int s_win = 0;
static int s_wout = 0;
static bool s_hpf_enabled = false;
/* α = exp(-2πfc/fs) in Q31 for y[n] = α*(y[n-1] + x[n] - x[n-1]). */
static int32_t s_hpf_alpha_q31 = 0;

static int s_gcd(int a, int b) {
    while (b != 0) {
        int t = b;
        b = a % b;
        a = t;
    }
    return a < 0 ? -a : a;
}

static void s_init_hann(int window_len, int hs) {
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

void dsp_tdola_configure(int input_rate_hz, int output_rate_hz, int pitch_ratio,
                         bool hpf_enabled) {
    int fin = input_rate_hz < 1 ? 1 : input_rate_hz;
    int fout = output_rate_hz < 1 ? TARGET_AUDIO_OUT_RATE : output_rate_hz;
    int r_pitch = pitch_ratio < 1 ? 1 : pitch_ratio;

    s_hpf_enabled = hpf_enabled;
    if (hpf_enabled) {
        /* LPF a = 1-exp(...); HPF uses α = exp(...) = 1-a. */
        int32_t a_lpf =
                dsp_calculate_iir_coefficient(TDOLA_HPF_CUTOFF_HZ, (double) fin);
        s_hpf_alpha_q31 = (int32_t) ((1LL << 31) - a_lpf);
    } else {
        s_hpf_alpha_q31 = 0;
    }

    /*
     * Exact rate ratio for hops (authoritative for duration).
     * Ha/Hs = Fin/Fout in lowest terms, scaled for ~50% analysis overlap.
     */
    int g = s_gcd(fin, fout);
    int ratio_in = fin / g;
    int ratio_out = fout / g;

    const int win = DSP_TDOLA_WINDOW_LEN;
    int k = (win / 2) / ratio_in;
    if (k < 1)
        k = 1;
    int ha = k * ratio_in;
    int hs = k * ratio_out;
    while (ha > win && k > 1) {
        k--;
        ha = k * ratio_in;
        hs = k * ratio_out;
    }
    if (ha > win) {
        __android_log_print(ANDROID_LOG_WARN, __FILE__,
                            "TD-OLA: ratio Fin:Fout=%d:%d needs Ha=%d > Win=%d; "
                            "using approximate hops (duration may drift slightly)",
                            ratio_in, ratio_out, ratio_in, win);
        ha = win;
        hs = (int) lround((double) win * (double) fout / (double) fin);
        if (hs < 1)
            hs = 1;
    }

    /*
     * Synthesis grain length: W_out = Win * pitch * Fout / Fin.
     * pitch 8 with Fin/Fout=8 → W_out=Win; pitch 4 → W_out=Win/2; pitch 16 → 2*Win.
     */
    int64_t wout64 = ((int64_t) win * r_pitch * fout) / fin;
    if (wout64 < 2)
        wout64 = 2;
    if (wout64 > DSP_TDOLA_OUT_MAX) {
        __android_log_print(ANDROID_LOG_WARN, __FILE__,
                            "TD-OLA: W_out %lld exceeds max %d; clamping",
                            (long long) wout64, DSP_TDOLA_OUT_MAX);
        wout64 = DSP_TDOLA_OUT_MAX;
    }
    /* Prefer even length for symmetric Hann. */
    if ((wout64 & 1) != 0)
        wout64++;
    if (wout64 > DSP_TDOLA_OUT_MAX)
        wout64 = DSP_TDOLA_OUT_MAX;

    int wout = (int) wout64;

    /* OLA needs Hs <= W_out; shrink hop scale if compression made W_out small. */
    while (hs > wout && k > 1) {
        k--;
        ha = k * ratio_in;
        hs = k * ratio_out;
    }
    if (hs > wout) {
        __android_log_print(ANDROID_LOG_WARN, __FILE__,
                            "TD-OLA: Hs=%d > W_out=%d; raising W_out (pitch slightly softer)",
                            hs, wout);
        wout = hs;
        if (wout > DSP_TDOLA_OUT_MAX)
            wout = DSP_TDOLA_OUT_MAX;
    }

    s_ha = ha;
    s_hs = hs;
    s_win = win;
    s_wout = wout;
    s_init_hann(wout, hs);

    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "TD-OLA: Fin=%d Fout=%d Win=%d Wout=%d Ha=%d Hs=%d "
                        "(Ha/Hs=%d/%d) pitch=%d hpf=%d %s",
                        fin, fout, win, wout, ha, hs, ratio_in, ratio_out,
                        r_pitch, s_hpf_enabled ? 1 : 0,
                        wout > win ? "expand" : (wout < win ? "compress" : "1:1"));
}

int dsp_tdola_input_samples_for_output(int output_frames) {
    if (output_frames <= 0)
        return 0;
    if (s_hs < 1 || s_ha < 1)
        return output_frames;
    return (int) (((int64_t) output_frames * s_ha + s_hs - 1) / s_hs);
}

static void s_fifo_push(dsp_tdola_state_t *t, int16_t sample) {
    if (t->out_n >= DSP_TDOLA_OUT_MAX)
        return;
    int32_t wr = t->out_r + t->out_n;
    if (wr >= DSP_TDOLA_OUT_MAX)
        wr -= DSP_TDOLA_OUT_MAX;
    t->out_fifo[wr] = sample;
    t->out_n++;
}

static bool s_fifo_pop(dsp_tdola_state_t *t, int16_t *out) {
    if (t->out_n <= 0)
        return false;
    *out = t->out_fifo[t->out_r];
    t->out_r++;
    if (t->out_r >= DSP_TDOLA_OUT_MAX)
        t->out_r = 0;
    t->out_n--;
    return true;
}

static void s_overlap_add_grain(dsp_tdola_state_t *t,
                                int16_t *downsampled_buffer,
                                int *out_count,
                                int out_needed,
                                bool agc_enabled) {
    const int win = s_win;
    const int wout = s_wout;
    const int ha = s_ha;
    const int hs = s_hs;

    for (int j = 0; j < wout; j++) {
        int32_t sample;
        if (wout == win) {
            sample = t->in[j];
        } else {
            /* Linear interpolation either way: expand (wout>win) or compress (wout<win). */
            const int32_t src_q16 = (int32_t) ((int64_t) j * win * 65536 / wout);
            int i0 = src_q16 >> 16;
            if (i0 >= win)
                i0 = win - 1;
            int i1 = i0 + 1;
            if (i1 >= win)
                i1 = win - 1;
            const int32_t frac = src_q16 & 0xFFFF;
            sample = ((int32_t) t->in[i0] * (65536 - frac) +
                      (int32_t) t->in[i1] * frac) >> 16;
        }
        int32_t windowed = (sample * (int32_t) s_hann_q15[j]) >> Q15_BITS;
        t->ola[j] += windowed;
    }

    for (int i = 0; i < hs; i++) {
        int16_t raw = dsp_saturate_i32_to_i16(t->ola[i]);
        if (*out_count < out_needed) {
            downsampled_buffer[(*out_count)++] = dsp_apply_output_gain(raw, agc_enabled);
        } else {
            s_fifo_push(t, raw);
        }
    }

    memmove(t->ola, t->ola + hs, (size_t) (wout - hs) * sizeof(t->ola[0]));
    memset(t->ola + (wout - hs), 0, (size_t) hs * sizeof(t->ola[0]));

    memmove(t->in, t->in + ha, (size_t) (t->in_len - ha) * sizeof(t->in[0]));
    t->in_len -= ha;
}

/* Cascaded one-pole HPF: y = α*(y_prev + x - x_prev), α = exp(-2πfc/fs). */
static int16_t s_apply_hpf(int16_t sample, dsp_tdola_state_t *t) {
    int64_t v = sample;
    for (int stage = 0; stage < DSP_TDOLA_HPF_STAGES; stage++) {
        int32_t x_prev = t->hpf.x_prev[stage];
        int32_t y_prev = t->hpf.y_prev[stage];
        int64_t y = ((int64_t) s_hpf_alpha_q31 * (y_prev + v - x_prev)) >> 31;
        t->hpf.x_prev[stage] = (int32_t) v;
        t->hpf.y_prev[stage] = (int32_t) y;
        v = y;
    }
    return dsp_saturate_i32_to_i16((int32_t) v);
}

int dsp_tdola_process(const int16_t *pBuffer, uint32_t sample_count,
                      int16_t *downsampled_buffer, dsp_state_t *state,
                      bool agc_enabled) {
    dsp_tdola_state_t *t = &state->tdola;
    const int win = s_win > 0 ? s_win : DSP_TDOLA_WINDOW_LEN;
    const int ha = s_ha > 0 ? s_ha : 1;
    const int hs = s_hs > 0 ? s_hs : 1;

    const int out_needed =
            (int) ((t->rate_phase + (int64_t) sample_count * hs) / ha);
    t->rate_phase =
            (int32_t) ((t->rate_phase + (int64_t) sample_count * hs) % ha);

    int out_count = 0;
    while (out_count < out_needed) {
        int16_t raw;
        if (!s_fifo_pop(t, &raw))
            break;
        downsampled_buffer[out_count++] = dsp_apply_output_gain(raw, agc_enabled);
    }

    for (uint32_t i = 0; i < sample_count; i++) {
        int16_t s = pBuffer[i];
        if (s_hpf_enabled)
            s = s_apply_hpf(s, t);
        t->in[t->in_len++] = s;
        while (t->in_len >= win) {
            s_overlap_add_grain(t, downsampled_buffer, &out_count, out_needed,
                                agc_enabled);
        }
    }

    while (out_count < out_needed)
        downsampled_buffer[out_count++] = 0;

    return out_count;
}
