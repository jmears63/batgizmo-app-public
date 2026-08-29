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
#include "dsp_te.h"

#include <string.h>
#include <android/log.h>

/*
 * Classic time expansion at fixed F_out:
 * each output sample advances Fin/(E*Fout) through the input (linear interpolation).
 * Pitch ÷E, wall-clock duration ×E.
 */
static int s_fin = 1;
static int s_fout = TARGET_AUDIO_OUT_RATE;
static int s_expansion = 8;
static int32_t s_step_q16 = 1 << 16;

void dsp_te_configure(int input_rate_hz, int output_rate_hz, int expansion_factor) {
    s_fin = input_rate_hz < 1 ? 1 : input_rate_hz;
    s_fout = output_rate_hz < 1 ? TARGET_AUDIO_OUT_RATE : output_rate_hz;
    s_expansion = expansion_factor < 1 ? 1 : expansion_factor;

    int64_t denom = (int64_t) s_expansion * s_fout;
    if (denom < 1)
        denom = 1;
    s_step_q16 = (int32_t) (((int64_t) s_fin << 16) / denom);
    if (s_step_q16 < 1)
        s_step_q16 = 1;

    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Time expansion: Fin=%d Fout=%d E=%d step_q16=%d (%.4f in/out)",
                        s_fin, s_fout, s_expansion, s_step_q16,
                        (double) s_step_q16 / 65536.0);
}

int dsp_te_input_samples_for_output(int output_frames) {
    if (output_frames <= 0)
        return 0;
    int64_t denom = (int64_t) s_expansion * s_fout;
    if (denom < 1)
        denom = 1;
    return (int) (((int64_t) output_frames * s_fin + denom - 1) / denom);
}

static int16_t s_src_at(const dsp_te_state_t *te, const int16_t *buf, uint32_t buf_n,
                        int32_t index) {
    if (index < te->leftover_n)
        return te->leftover[index];
    index -= te->leftover_n;
    if (index >= 0 && (uint32_t) index < buf_n)
        return buf[index];
    /* Hold last available sample. */
    if (buf_n > 0)
        return buf[buf_n - 1];
    if (te->leftover_n > 0)
        return te->leftover[te->leftover_n - 1];
    return 0;
}

int dsp_te_process(const int16_t *pBuffer, uint32_t sample_count,
                   int16_t *downsampled_buffer, dsp_state_t *state,
                   bool agc_enabled) {
    dsp_te_state_t *te = &state->te;
    const int fin = s_fin;
    const int64_t out_scale = (int64_t) s_expansion * s_fout;
    const int32_t step_q16 = s_step_q16;
    const int32_t total_in = te->leftover_n + (int32_t) sample_count;

    const int out_needed =
            (int) ((te->rate_phase + (int64_t) sample_count * out_scale) / fin);
    te->rate_phase =
            (int32_t) ((te->rate_phase + (int64_t) sample_count * out_scale) % fin);

    int32_t pos_q16 = te->frac_q16; /* absolute Q16 index into leftover||buffer */
    int out_count = 0;

    while (out_count < out_needed) {
        int32_t i0 = pos_q16 >> 16;
        int32_t frac = pos_q16 & 0xFFFF;
        if (i0 < 0)
            i0 = 0;
        int16_t x0 = s_src_at(te, pBuffer, sample_count, i0);
        int16_t x1 = s_src_at(te, pBuffer, sample_count, i0 + 1);
        int32_t sample =
                ((int32_t) x0 * (65536 - frac) + (int32_t) x1 * frac) >> 16;
        downsampled_buffer[out_count++] =
                dsp_apply_output_gain(dsp_saturate_i32_to_i16(sample), agc_enabled);
        pos_q16 += step_q16;
    }

    /* Samples fully consumed: floor(pos_q16/65536), keep the rest as leftover. */
    int32_t consumed = pos_q16 >> 16;
    if (consumed < 0)
        consumed = 0;
    if (consumed > total_in)
        consumed = total_in;

    int16_t new_leftover[DSP_TE_LEFTOVER_MAX];
    int new_n = 0;
    for (int32_t i = consumed; i < total_in && new_n < DSP_TE_LEFTOVER_MAX; i++)
        new_leftover[new_n++] = s_src_at(te, pBuffer, sample_count, i);

    memcpy(te->leftover, new_leftover, (size_t) new_n * sizeof(int16_t));
    te->leftover_n = new_n;
    te->frac_q16 = pos_q16 & 0xFFFF;

    return out_count;
}
