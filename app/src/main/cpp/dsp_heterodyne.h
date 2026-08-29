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

#ifndef BATGIZMO_DSP_HETERODYNE_STATE_H
#define BATGIZMO_DSP_HETERODYNE_STATE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Per-stream heterodyne phase (LUT indices). */
typedef struct {
    int reference1_index;
    int reference2_index;
} dsp_heterodyne_state_t;

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_DSP_HETERODYNE_STATE_H */

/* API needs a complete dsp_state_t from dsp.h (included after composition). */
#if defined(BATGIZMO_DSP_STATE_COMPLETE) && !defined(BATGIZMO_DSP_HETERODYNE_API_H)
#define BATGIZMO_DSP_HETERODYNE_API_H

#ifdef __cplusplus
extern "C" {
#endif

/* Returns false if references are out of range for samples_per_frame. */
bool dsp_heterodyne_configure(int heterodyne1_kHz, int heterodyne2_kHz,
                              int samples_per_frame,
                              dsp_playback_mode_t playback_mode);
void dsp_heterodyne_clear(void);
int dsp_heterodyne_process(const int16_t *pBuffer, uint32_t sample_count,
                           int16_t *downsampled_buffer, dsp_state_t *state,
                           int decimation_factor, bool agc_enabled);

/* dsp_set_heterodyne is declared in dsp.h (public API). */

#ifdef __cplusplus
}
#endif

#endif /* BATGIZMO_DSP_HETERODYNE_API_H */
