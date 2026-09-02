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
#include "dsp_heterodyne.h"
#include "dsp_tdola.h"
#include "dsp_te.h"

#include <android/log.h>
#include <math.h>
#include <string.h>

static volatile int s_decimation_factor = 0;

typedef enum {
    DSP_MODE_HETERODYNE = 0,
    DSP_MODE_DIRECT = 1,
    DSP_MODE_PITCH_SHIFT = 2,
    DSP_MODE_TIME_EXPANSION = 3,
} dsp_mode_t;

static volatile dsp_mode_t s_playback_mode = DSP_MODE_HETERODYNE;

extern "C" {

int dsp_configure(int sample_rate,
                  int heterodyne1_kHz,
                  int heterodyne2_kHz,
                  float audio_boost_factor,
                  int samples_per_frame,
                  dsp_playback_mode_t playback_mode,
                  int pitch_ratio,
                  bool pitch_hpf_enabled,
                  dsp_state_t *state) {

    s_decimation_factor = lround((double) sample_rate / TARGET_AUDIO_OUT_RATE);
    if (s_decimation_factor == 0)
        s_decimation_factor = 1;

    int audio_out_rate = sample_rate / s_decimation_factor;
    dsp_set_downsampling_iir_coefficient(
            dsp_calculate_iir_coefficient(DOWNSAMPLING_AA_CUTOFF_HZ, sample_rate));
    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Audio parameters: sample_rate = %d, s_decimation_factor = %d, "
                        "playback_mode = %d, pitch_ratio = %d, pitch_hpf = %d",
                        sample_rate, s_decimation_factor, playback_mode, pitch_ratio,
                        pitch_hpf_enabled ? 1 : 0);

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
            dsp_tdola_configure(sample_rate, TARGET_AUDIO_OUT_RATE, pr, pitch_hpf_enabled);
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
        case DSP_PLAYBACK_AUTO_TUNED_HETERODYNE:
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

void dsp_set_heterodyne(int heterodyne1_kHz, int heterodyne2_kHz) {
    dsp_heterodyne_set_frequencies(heterodyne1_kHz, heterodyne2_kHz);
}

void dsp_set_agc_enabled(bool enabled) {
    dsp_agc_set_enabled(enabled);
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

} /* extern "C" */
