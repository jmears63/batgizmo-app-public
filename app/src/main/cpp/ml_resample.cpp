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

#include "CDSPResampler.h"

#define LOG_TAG "MlResample"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** Largest process() input length; larger submissions are chunked. */
constexpr int kMaxInLen = 8192;

inline int16_t clampToInt16(double sample) {
    const long v = std::lround(sample);
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return static_cast<int16_t>(v);
}

struct StreamResampler {
    int inRateHz = 0;
    int outRateHz = 0;
    /** Input samples of silence needed to flush filter delay (queried at create). */
    int flushInSamples = 0;
    std::unique_ptr<r8b::CDSPResampler16> rs;
    std::vector<double> inScratch;
};

jshortArray shortsFromDoubles(JNIEnv *env, const double *data, int count) {
    if (count < 0) count = 0;
    jshortArray out = env->NewShortArray(count);
    if (out == nullptr || count == 0) {
        return out;
    }
    std::vector<jshort> shorts(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        shorts[static_cast<size_t>(i)] = clampToInt16(data[i]);
    }
    env->SetShortArrayRegion(out, 0, count, shorts.data());
    return out;
}

/**
 * Feed [count] input samples (already in [inScratch] or zeros) through the
 * resampler in MaxInLen chunks; append clamped int16 output to [out].
 */
bool processInto(
        StreamResampler *s,
        const double *input,
        int count,
        std::vector<int16_t> *out) {
    if (s == nullptr || s->rs == nullptr || out == nullptr || count < 0) {
        return false;
    }
    int offset = 0;
    while (offset < count) {
        const int chunk = std::min(count - offset, kMaxInLen);
        double *op = nullptr;
        const int nOut = s->rs->process(
                const_cast<double *>(input + offset), chunk, op);
        if (nOut > 0 && op != nullptr) {
            const size_t base = out->size();
            out->resize(base + static_cast<size_t>(nOut));
            for (int i = 0; i < nOut; ++i) {
                (*out)[base + static_cast<size_t>(i)] = clampToInt16(op[i]);
            }
        }
        offset += chunk;
    }
    return true;
}

} // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_org_batgizmo_app_ml_MlProcessor_nativeResamplerCreate(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jint inputSampleRateHz,
        jint outputSampleRateHz) {
    if (inputSampleRateHz <= 0 || outputSampleRateHz <= 0) {
        LOGE("nativeResamplerCreate: invalid rates");
        return 0;
    }
    try {
        auto *s = new StreamResampler();
        s->inRateHz = inputSampleRateHz;
        s->outRateHz = outputSampleRateHz;
        s->inScratch.resize(static_cast<size_t>(kMaxInLen));
        s->rs = std::make_unique<r8b::CDSPResampler16>(
                static_cast<double>(inputSampleRateHz),
                static_cast<double>(outputSampleRateHz),
                kMaxInLen);
        // Query filter delay (clears state — OK right after construction).
        s->flushInSamples = s->rs->getInLenBeforeOutStart(0);
        return reinterpret_cast<jlong>(s);
    } catch (...) {
        LOGE("nativeResamplerCreate: threw");
        return 0;
    }
}

extern "C"
JNIEXPORT jshortArray JNICALL
Java_org_batgizmo_app_ml_MlProcessor_nativeResamplerProcess(
        JNIEnv *env,
        jclass /* clazz */,
        jlong handle,
        jshortArray input,
        jint offset,
        jint count) {
    auto *s = reinterpret_cast<StreamResampler *>(handle);
    if (s == nullptr || s->rs == nullptr || input == nullptr ||
        offset < 0 || count < 0) {
        LOGE("nativeResamplerProcess: invalid arguments");
        return nullptr;
    }
    const jsize inputLen = env->GetArrayLength(input);
    if (offset + count > inputLen) {
        LOGE("nativeResamplerProcess: offset+count exceeds input length");
        return nullptr;
    }
    if (count == 0) {
        return env->NewShortArray(0);
    }

    jshort *inPtr = env->GetShortArrayElements(input, nullptr);
    if (inPtr == nullptr) {
        LOGE("nativeResamplerProcess: GetShortArrayElements failed");
        return nullptr;
    }

    std::vector<int16_t> out;
    // Rough upper bound reduces realloc churn.
    const int64_t est =
            (static_cast<int64_t>(count) * s->outRateHz + s->inRateHz - 1) /
            s->inRateHz;
    if (est > 0 && est < INT32_MAX) {
        out.reserve(static_cast<size_t>(est) + 64);
    }

    int remaining = count;
    int srcOff = offset;
    bool ok = true;
    while (remaining > 0 && ok) {
        const int chunk = std::min(remaining, kMaxInLen);
        for (int i = 0; i < chunk; ++i) {
            s->inScratch[static_cast<size_t>(i)] =
                    static_cast<double>(inPtr[srcOff + i]);
        }
        ok = processInto(s, s->inScratch.data(), chunk, &out);
        srcOff += chunk;
        remaining -= chunk;
    }
    env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
    if (!ok) {
        return nullptr;
    }

    jshortArray result = env->NewShortArray(static_cast<jsize>(out.size()));
    if (result == nullptr) {
        return nullptr;
    }
    if (!out.empty()) {
        env->SetShortArrayRegion(
                result, 0, static_cast<jsize>(out.size()), out.data());
    }
    return result;
}

extern "C"
JNIEXPORT jshortArray JNICALL
Java_org_batgizmo_app_ml_MlProcessor_nativeResamplerFlush(
        JNIEnv *env,
        jclass /* clazz */,
        jlong handle) {
    auto *s = reinterpret_cast<StreamResampler *>(handle);
    if (s == nullptr || s->rs == nullptr) {
        LOGE("nativeResamplerFlush: invalid handle");
        return nullptr;
    }

    std::vector<int16_t> out;
    if (s->flushInSamples > 0) {
        std::fill(s->inScratch.begin(), s->inScratch.end(), 0.0);
        int remaining = s->flushInSamples;
        while (remaining > 0) {
            const int chunk = std::min(remaining, kMaxInLen);
            if (!processInto(s, s->inScratch.data(), chunk, &out)) {
                return nullptr;
            }
            remaining -= chunk;
        }
    }

    jshortArray result = env->NewShortArray(static_cast<jsize>(out.size()));
    if (result == nullptr) {
        return nullptr;
    }
    if (!out.empty()) {
        env->SetShortArrayRegion(
                result, 0, static_cast<jsize>(out.size()), out.data());
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_ml_MlProcessor_nativeResamplerDestroy(
        JNIEnv * /* env */,
        jclass /* clazz */,
        jlong handle) {
    auto *s = reinterpret_cast<StreamResampler *>(handle);
    delete s;
}
