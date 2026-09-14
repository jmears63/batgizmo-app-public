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
#include <vector>

#include "CDSPResampler.h"

#define LOG_TAG "MlResample"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

inline int16_t clampToInt16(double sample) {
    const long v = std::lround(sample);
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return static_cast<int16_t>(v);
}

} // namespace

extern "C"
JNIEXPORT jshortArray JNICALL
Java_org_batgizmo_app_ml_MlProcessor_nativeResample(
        JNIEnv *env,
        jclass /* clazz */,
        jshortArray input,
        jint offset,
        jint count,
        jint inputSampleRateHz,
        jint outputSampleRateHz) {
    if (input == nullptr || offset < 0 || count < 0 ||
        inputSampleRateHz <= 0 || outputSampleRateHz <= 0) {
        LOGE("nativeResample: invalid arguments");
        return nullptr;
    }

    const jsize inputLen = env->GetArrayLength(input);
    if (offset + count > inputLen) {
        LOGE("nativeResample: offset+count exceeds input length");
        return nullptr;
    }

    if (count == 0) {
        return env->NewShortArray(0);
    }

    jshort *inPtr = env->GetShortArrayElements(input, nullptr);
    if (inPtr == nullptr) {
        LOGE("nativeResample: GetShortArrayElements failed");
        return nullptr;
    }

    // Identity path when already at the model rate.
    if (inputSampleRateHz == outputSampleRateHz) {
        jshortArray out = env->NewShortArray(count);
        if (out != nullptr) {
            env->SetShortArrayRegion(out, 0, count, inPtr + offset);
        }
        env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
        return out;
    }

    const int64_t outCount64 =
            (static_cast<int64_t>(count) * outputSampleRateHz + inputSampleRateHz / 2) /
            inputSampleRateHz;
    if (outCount64 < 1 || outCount64 > INT32_MAX) {
        LOGE("nativeResample: bad output length %lld",
             static_cast<long long>(outCount64));
        env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
        return nullptr;
    }
    const int outCount = static_cast<int>(outCount64);

    std::vector<double> inDoubles(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        inDoubles[static_cast<size_t>(i)] = static_cast<double>(inPtr[offset + i]);
    }
    env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);

    std::vector<double> outDoubles(static_cast<size_t>(outCount));
    try {
        // MaxInLen must cover the largest process() input; oneshot feeds in MaxInLen chunks.
        r8b::CDSPResampler16 resampler(
                static_cast<double>(inputSampleRateHz),
                static_cast<double>(outputSampleRateHz),
                count);
        resampler.oneshot(
                inDoubles.data(),
                count,
                outDoubles.data(),
                outCount);
    } catch (...) {
        LOGE("nativeResample: resampler threw");
        return nullptr;
    }

    jshortArray out = env->NewShortArray(outCount);
    if (out == nullptr) {
        LOGE("nativeResample: NewShortArray failed");
        return nullptr;
    }

    std::vector<jshort> outShorts(static_cast<size_t>(outCount));
    for (int i = 0; i < outCount; ++i) {
        outShorts[static_cast<size_t>(i)] = clampToInt16(outDoubles[static_cast<size_t>(i)]);
    }
    env->SetShortArrayRegion(out, 0, outCount, outShorts.data());

    LOGI("resampled %d @ %d Hz -> %d @ %d Hz",
         count, inputSampleRateHz, outCount, outputSampleRateHz);
    return out;
}
