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

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

#include "kiss_fftr.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define LOG_TAG "MlStft"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr float kLogEps = 1e-10f;

struct MlStft {
    int fftSize = 0;
    int hopSamples = 0;
    int windowSamples = 0;
    int nFreqBins = 0;
    int nFrames = 0;
    kiss_fftr_cfg cfg = nullptr;
    std::vector<float> hann;
    std::vector<float> frame;
    std::vector<kiss_fft_cpx> spectrum;
};

bool isPowerOfTwo(int n) {
    return n >= 2 && (n & (n - 1)) == 0;
}

void fillHann(std::vector<float> *out, int n) {
    out->resize(static_cast<size_t>(n));
    if (n == 1) {
        (*out)[0] = 1.f;
        return;
    }
    for (int i = 0; i < n; ++i) {
        (*out)[static_cast<size_t>(i)] =
                0.5f * (1.f - std::cos(2.f * static_cast<float>(M_PI) * static_cast<float>(i) /
                                       static_cast<float>(n - 1)));
    }
}

MlStft *fromHandle(jlong handle) {
    return reinterpret_cast<MlStft *>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_batgizmo_app_ml_MlProcessor_nativeStftCreate(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jint fftSize,
        jint hopSamples,
        jint windowSamples) {
    if (!isPowerOfTwo(fftSize) || hopSamples <= 0 || hopSamples > fftSize ||
        windowSamples < fftSize) {
        LOGE("nativeStftCreate: invalid params fft=%d hop=%d window=%d",
             fftSize, hopSamples, windowSamples);
        return 0;
    }

    auto stft = std::make_unique<MlStft>();
    stft->fftSize = fftSize;
    stft->hopSamples = hopSamples;
    stft->windowSamples = windowSamples;
    stft->nFreqBins = fftSize / 2 + 1;
    stft->nFrames = 1 + (windowSamples - fftSize) / hopSamples;
    if (stft->nFrames <= 0) {
        LOGE("nativeStftCreate: nFrames <= 0");
        return 0;
    }

    stft->cfg = kiss_fftr_alloc(fftSize, /*is_inverse=*/0, nullptr, nullptr);
    if (stft->cfg == nullptr) {
        LOGE("nativeStftCreate: kiss_fftr_alloc failed");
        return 0;
    }
    fillHann(&stft->hann, fftSize);
    stft->frame.assign(static_cast<size_t>(fftSize), 0.f);
    stft->spectrum.assign(static_cast<size_t>(stft->nFreqBins), kiss_fft_cpx{0.f, 0.f});

    return reinterpret_cast<jlong>(stft.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_batgizmo_app_ml_MlProcessor_nativeStftProcess(
        JNIEnv *env,
        jclass /* clazz */,
        jlong handle,
        jfloatArray pcmWindow) {
    MlStft *stft = fromHandle(handle);
    if (stft == nullptr || stft->cfg == nullptr || pcmWindow == nullptr) {
        return nullptr;
    }

    const jsize len = env->GetArrayLength(pcmWindow);
    if (len != stft->windowSamples) {
        LOGE("nativeStftProcess: expected %d samples, got %d", stft->windowSamples, len);
        return nullptr;
    }

    jfloat *pcm = env->GetFloatArrayElements(pcmWindow, nullptr);
    if (pcm == nullptr) {
        return nullptr;
    }

    const int outLen = stft->nFrames * stft->nFreqBins;
    std::vector<float> out(static_cast<size_t>(outLen));

    for (int frame = 0; frame < stft->nFrames; ++frame) {
        const int start = frame * stft->hopSamples;
        for (int i = 0; i < stft->fftSize; ++i) {
            stft->frame[static_cast<size_t>(i)] =
                    pcm[start + i] * stft->hann[static_cast<size_t>(i)];
        }
        kiss_fftr(stft->cfg, stft->frame.data(), stft->spectrum.data());
        const int outBase = frame * stft->nFreqBins;
        for (int b = 0; b < stft->nFreqBins; ++b) {
            const float re = stft->spectrum[static_cast<size_t>(b)].r;
            const float im = stft->spectrum[static_cast<size_t>(b)].i;
            const float mag = std::sqrt(re * re + im * im);
            out[static_cast<size_t>(outBase + b)] = std::log(mag + kLogEps);
        }
    }

    env->ReleaseFloatArrayElements(pcmWindow, pcm, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(outLen);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, outLen, out.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_batgizmo_app_ml_MlProcessor_nativeStftDestroy(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jlong handle) {
    MlStft *stft = fromHandle(handle);
    if (stft == nullptr) {
        return;
    }
    if (stft->cfg != nullptr) {
        kiss_fftr_free(stft->cfg);
        stft->cfg = nullptr;
    }
    delete stft;
}
