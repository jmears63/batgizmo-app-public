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
#include <android/bitmap.h>
#include "TDigest.h"
#include <android/log.h>

extern "C" {
#include "kissfft/kiss_fftr.h"
}

static void cleanup_fft();

static size_t XYToBitmapOffset(int x, int y, int max_y, uint32_t indexStride);

static kiss_fftr_cfg kfft_cfg = nullptr;
static int s_fft_window_size = 0;
static int s_fft_frequency_buckets = 0;
static kiss_fft_cpx *s_fft_temp_buffer = nullptr;
static kiss_fft_scalar canaryValue = -1.0;

static bool s_already_initialized = false;
static uint16_t *s_colourMapData = nullptr;
static int s_colourMapDataSize = 0;
static uint16_t s_amplitude_graph_colour = 0xFFFF;

static jint installColourMap(JNIEnv *env, jshortArray colour_map, jint colour_map_size) {
    if (colour_map == nullptr || colour_map_size <= 0)
        return -1;

    jshort *pData = env->GetShortArrayElements(colour_map, nullptr);
    if (pData == nullptr)
        return -1;

    if (s_colourMapData != nullptr) {
        delete [] s_colourMapData;
        s_colourMapData = nullptr;
        s_colourMapDataSize = 0;
    }

    s_colourMapDataSize = colour_map_size;
    s_colourMapData = new uint16_t[s_colourMapDataSize];
    for (int i = 0; i < s_colourMapDataSize; i++)
        s_colourMapData[i] = static_cast<uint16_t>(pData[i]);

    env->ReleaseShortArrayElements(colour_map, pData, JNI_ABORT);
    return 0;
}

/**
 * This is invoked from the ViewModel so should only get called once, regardless of
 * screen reconfiguration etc. So one off leaks from this function are OK.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_UIModel_00024Companion_nativeInitialize(JNIEnv *env, jobject thiz,
                                                              jshortArray colour_map,
                                                              jint colour_map_size,
                                                              jshort amplitude_graph_colour) {
    jint rc = 0;

    s_amplitude_graph_colour = amplitude_graph_colour;

    // This is called from onCreate() so can get called multiple times from the UI layer:
    if (s_already_initialized) {
        return rc;
    }
    s_already_initialized = true;

    rc = installColourMap(env, colour_map, colour_map_size);
    return rc;
}

/** Replace the spectrogram colour map used by doColourMapping. Safe to call after init. */
extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_UIModel_00024Companion_nativeSetColourMap(JNIEnv *env, jobject thiz,
                                                                jshortArray colour_map,
                                                                jint colour_map_size) {
    return installColourMap(env, colour_map, colour_map_size);
}


// One day, merge the following function with doFft, to avoid a JNI call overhead.
extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_TransformStep_00024Companion_unwrapSlices(JNIEnv *env, jobject thiz,
                                                                     jshortArray raw_data_buffer,
                                                                     jint raw_data_entries,
                                                                     jint start_index,
                                                                     jint window_count,
                                                                     jint fft_stride,
                                                                     jfloatArray window,
                                                                     jint fft_window_size,
                                                                     jfloatArray input_slice_buffer) {

    jint rc = 0;
    jshort *rawData = env->GetShortArrayElements(raw_data_buffer, nullptr);
    jfloat *sliceBufferData = env->GetFloatArrayElements(input_slice_buffer, nullptr);
    jsize sliceBufferDataLength = env->GetArrayLength(input_slice_buffer);
    jfloat *windowData = env->GetFloatArrayElements(window, nullptr);
    if (rawData == nullptr || sliceBufferData == nullptr || sliceBufferData == windowData) {
        rc = -1;
    } else {
        int unwrapped_index = 0;
        for (int i = 0; i < window_count; i++) {
            int end_index = start_index + fft_window_size;  // Half open range.
            // The last window may extend beyond the range of raw data. That's expected because the final slice
            // is truncated to the file size. In that case, skip it.
            if (end_index <= raw_data_entries) {
                int window_index = 0;
                for (int j = start_index; j < end_index; j++) {
                    sliceBufferData[unwrapped_index++] =
                        static_cast<float>(rawData[j]) * windowData[window_index++];
                }
            }

            start_index += fft_stride;
        }
    }

    if (rawData) {
        // JNI_ABORT means don't copy elements back, just free the memory:
        env->ReleaseShortArrayElements(raw_data_buffer, rawData, JNI_ABORT);
    }
    if (sliceBufferData) {
        // 0 means copy changes back and free memory:
        env->ReleaseFloatArrayElements(input_slice_buffer, sliceBufferData, 0);
    }
    if (windowData) {
        // JNI_ABORT means don't copy elements back, just free the memory:
        env->ReleaseFloatArrayElements(window, windowData, JNI_ABORT);
    }

    return rc;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_TransformStep_00024Companion_initFft(JNIEnv *env, jobject thiz,
                                                                jint fft_window_size) {

    cleanup_fft();  // Paranoia.

    s_fft_window_size = fft_window_size;
    kfft_cfg = kiss_fftr_alloc(fft_window_size, false, nullptr, nullptr);

    s_fft_frequency_buckets = fft_window_size / 2 + 1;
    const size_t allocation_buckets =
            s_fft_frequency_buckets + 1; // Additional +1 for canary value.
    s_fft_temp_buffer = (kiss_fft_cpx *) malloc(sizeof(kiss_fft_cpx) * allocation_buckets);

    if (kfft_cfg == nullptr || s_fft_temp_buffer == nullptr) {
        cleanup_fft();
        return -1;
    }

    memset(s_fft_temp_buffer, 0, allocation_buckets * sizeof(kiss_fft_cpx));
    s_fft_temp_buffer[allocation_buckets - 1].r = canaryValue;
    s_fft_temp_buffer[allocation_buckets - 1].i = canaryValue;

    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_TransformStep_00024Companion_cleanupFft(JNIEnv *env, jobject thiz) {
    cleanup_fft();
}

static void cleanup_fft() {
    if (kfft_cfg != nullptr) {
        // The client code has forgotten to call cleanupFft if we get here.
        kiss_fftr_free(kfft_cfg);
        kfft_cfg = nullptr;
    }

    if (s_fft_temp_buffer != nullptr) {
        free(s_fft_temp_buffer);
        s_fft_temp_buffer = nullptr;
    }
}

/*
 * Scaling factor used in scaling the squared amplitude to dB.
 * dB is 10 log10(power).
 *  - We have already squared the signal level so it represents power.
 *  - We use log2 below for efficiency, so scale it to result in log10.
 */
const static float s_dB_factor = 10.0f / log2(10.0f);

extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_TransformStep_00024Companion_doFft(JNIEnv *env, jobject thiz,
                                                              jint num_windows,
                                                              jfloatArray input_slice_buffer,
                                                              jfloatArray output_slice_buffer,
                                                              jint transformed_buffer_index,
                                                              jfloat minDB,
                                                              jintArray trigger_flag,
                                                              jint min_trigger_bucket,
                                                              jint max_trigger_bucket,
                                                              jfloat trigger_threshold) {
    int rc = 0;
    const float *pWindowData = nullptr;

    jfloat *unwrappedRawData = env->GetFloatArrayElements(input_slice_buffer, nullptr);
    jsize unwrappedRawDataLength = env->GetArrayLength(input_slice_buffer);
    jfloat *transformedData = env->GetFloatArrayElements(output_slice_buffer, nullptr);
    jsize transformedDataLength = env->GetArrayLength(output_slice_buffer);
    jint *triggerFlag = env->GetIntArrayElements(trigger_flag, nullptr);

    // jsize length = env->GetArrayLength(output_slice_buffer);
    if (unwrappedRawData == nullptr || transformedData == nullptr || triggerFlag == nullptr) {
        rc = -1;
    } else {
        pWindowData = unwrappedRawData;
        int windowIndex = 0;
        int transformedIndex = 0;  // Index within the output array.
        jfloat *transformedDataTarget = nullptr;

        // We will normalize the result so that it is independent of window size
        // the maximum frequency bin value is A x nFFT / 2, which A is the input magnitude.
        float normalizer = 2.0f / static_cast<float>(s_fft_window_size);
        float normalizer2 = normalizer * normalizer;
        bool triggered = false;

        for (windowIndex = 0;
             windowIndex < num_windows; windowIndex++, pWindowData += s_fft_window_size) {
            // Do the SFFT:
            kiss_fftr(kfft_cfg, pWindowData, s_fft_temp_buffer);

            // Potential for performance improvement: move the magnitude calculate to a separate loop,
            // and use a larger temp buffer, to reduce cache misses.

            // Convert the complex spectral results to a square magnitude:
            transformedDataTarget = transformedData + transformed_buffer_index;
            for (int j = 0; j < s_fft_frequency_buckets; j++) {
                float re = s_fft_temp_buffer[j].r;
                float im = s_fft_temp_buffer[j].i;
                const float mag_squared = (re * re + im * im) * normalizer2;

                /**
                 * This is probably the most expensive calculation per pixel. This version
                 * of log2 is based on floats, so hopefully faster than the one based on doubles,
                 * and faster than log10 because it avoids a division.
                 *
                 * I did try assigning the value into a 64 bit integer and using the compiler
                 * built-in to count the number of leading zeroes. This was truly very fast, but
                 * has the problem that brightness/contrast scaling would have to be done previously,
                 * in linear rather than log space, and would have resulted in only 64 levels
                 * of colour mapping which is a bit coarse. So, I settled for a proper log calculation,
                 * which is actually plenty fast enough.
                 *
                 * Multiple by 10 to get a db value, as the square has already given us x 2.
                 */
                float db_value = minDB;
                if (mag_squared > 0.0) { // Avoid log(0).
                    db_value = s_dB_factor * log2(mag_squared);
                }

                transformedDataTarget[transformedIndex++] = db_value;

                // See if the value results in a trigger:
                if (j >= min_trigger_bucket && j <= max_trigger_bucket) {
                    if (db_value >= trigger_threshold)
                        triggered = true;
                }
            }
        }
        triggerFlag[0] = triggered;
        rc = windowIndex;
    }

    if (unwrappedRawData) {
        // JNI_ABORT means don't copy elements back, just free the memory:
        env->ReleaseFloatArrayElements(input_slice_buffer, unwrappedRawData, JNI_ABORT);
    }
    if (transformedData) {
        // 0 means copy changes back and free memory:
        env->ReleaseFloatArrayElements(output_slice_buffer, transformedData, 0);
    }
    if (triggerFlag) {
        env->ReleaseIntArrayElements(trigger_flag, triggerFlag, 0);
    }

    return rc;
}

size_t XYToBitmapOffset(int x, int y, int max_y, uint32_t indexStride) {
    uint32_t row_start = (max_y - y - 1) * indexStride;
    uint32_t pixel_index = row_start + x;
    return pixel_index;
}

/*
static inline int coerceRange(int v, int min, int max) {
    if (v < min)
        v = min;
    if (v > max)
        v = max;
    return v;
}
*/

extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_TransformStep_00024Companion_doAmplitude(JNIEnv *env, jobject thiz,
                                                                  jint num_windows,
                                                                  jint fftWindowSize,
                                                                  jfloatArray input_slice_buffer,
                                                                  jint transformed_time_bucket_index,
                                                                  jint transformed_time_bucket_size,
                                                                  jint transformed_slice_time_bucket_size,
                                                                  jobject bitmap
                                                          ) {
    int rc = 0;
    const float *pWindowData = nullptr;

    AndroidBitmapInfo info;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0)
        return -1;
    if (info.format != ANDROID_BITMAP_FORMAT_RGB_565)
        return -1;

    // Lock the bitmap for writing:
    uint16_t *rgb565Pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, (void**) &rgb565Pixels) < 0)
        return - 1;

    jfloat *unwrappedRawData = env->GetFloatArrayElements(input_slice_buffer, nullptr);
    if (unwrappedRawData == nullptr || rgb565Pixels == nullptr) {
        rc = -1;
    } else {
        pWindowData = unwrappedRawData;
        int windowIndex = 0;
        const uint32_t indexStride = info.stride / sizeof(uint16_t);
        const uint32_t height = info.height;

        const float range_min = -0x7FFF;
        const float range_max = 0x7FFF;
        const float range_delta = range_max - range_min;
        const size_t maxOffset = height * indexStride - 1;
        const float scaling = (float) height / range_delta;

        // For each window:
        int x = transformed_time_bucket_index;
        for (windowIndex = 0; windowIndex < num_windows; windowIndex++, pWindowData += s_fft_window_size) {
            const float *pValue = pWindowData;
            // Initialize based on the first point in the window:
            float min = *pValue++, max = min;
            // Work out the range of values in the window:
            for (int i = 1; i < fftWindowSize; i++, pValue++) {
                float v = *pValue;
                if (v < min)
                    min = v;
                if (v > max)
                    max = v;
            }

            // Scale those values into the height of the bitmap:
            int y_min = (int) ((min - range_min) * scaling);
            int y_max = (int) ((max - range_min) * scaling);

            size_t offset = XYToBitmapOffset(x, height - 1, (int) height, indexStride);
            x += 1;

            // We need to draw the black as well as the colour so that we overwrite
            // previous amplitudes.

            const uint16_t black = 0;
            int colour = black;
            for (int y = height; y > 0; y--) {
                if (y == y_max)
                    colour = s_amplitude_graph_colour;
                if (y + 1 == y_min)
                    colour = black;
                // Paranoia:
                if (offset >= 0 && offset <= maxOffset)
                    rgb565Pixels[offset] = colour;
                offset += indexStride;
            }
        }

        rc = windowIndex;
    }

    if (unwrappedRawData) {
        // JNI_ABORT means don't copy elements back, just free the memory:
        env->ReleaseFloatArrayElements(input_slice_buffer, unwrappedRawData, 0);    // Change back to JNI_ABORT
    }
    if (rgb565Pixels != nullptr) {
        AndroidBitmap_unlockPixels(env, bitmap);
    }


    return rc;
}

// static float max_value = FLT_MIN, min_value = FLT_MAX;

extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_ColourMapStep_00024Companion_doColourMapping(JNIEnv *env, jobject thiz,
                                                                        jint first, jint second,
                                                                        jfloatArray transformed_data_buffer,
                                                                        jfloatArray noise_baseline_buffer,
                                                                        jint transformed_time_bucket_count,
                                                                        jint transformed_frequency_bucket_count,
                                                                        jobject bitmap,
                                                                        jfloat offset, jfloat multiplier) {

    AndroidBitmapInfo info;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0)
        return -1;
    if (info.format != ANDROID_BITMAP_FORMAT_RGB_565)
        return -1;

    int rc = -1;

    // Lock the bitmap for writing
    uint16_t *rgb565Pixels = nullptr;
    jfloat *transformedData = nullptr;
    jfloat *noiseBaseline = nullptr;
    float value = 0;
    float *inputPtr = nullptr, *baselinePtr = nullptr;
    uint32_t indexStride = 0;

    if (AndroidBitmap_lockPixels(env, bitmap, (void**) &rgb565Pixels) < 0 || rgb565Pixels == nullptr)
        goto cleanup;
    transformedData = env->GetFloatArrayElements(transformed_data_buffer, nullptr);
    if (transformedData == nullptr)
        goto cleanup;
    if (noise_baseline_buffer != nullptr) {
        noiseBaseline = env->GetFloatArrayElements(noise_baseline_buffer, nullptr);
        if (noiseBaseline == nullptr)
            goto cleanup;
    }

    rc = 0;

    indexStride = info.stride / sizeof(uint16_t);
    inputPtr = transformedData + first * transformed_frequency_bucket_count;
    for (int timeBucket = first; timeBucket < second; timeBucket++) {
        baselinePtr = noiseBaseline;
        for (int frequencyBucket = 0; frequencyBucket < transformed_frequency_bucket_count; frequencyBucket++) {
            value = *inputPtr++;
            if (baselinePtr)
                value -= *baselinePtr++;

            // Apply brightness and contrast:
            value = (value - offset) * multiplier;

            int int_value = static_cast<int>(value);

            // Do the colour map:
            if (int_value > s_colourMapDataSize - 1)
                int_value = s_colourMapDataSize - 1;
            else if (int_value < 0)
                int_value = 0;
            int_value = s_colourMapData[int_value];

            /**
             * I'd love to find a way of having the following code do sequential
             * access in both the source and destination locations, but the FFT generates
             * data in the opposite sequencing than bitmap buffer requires. I don't
             * think there is anything I can do about this. Hopefully both the source and
             * destination can be served by cache reasonably efficiently.
             */
            const size_t index = XYToBitmapOffset(timeBucket, frequencyBucket,
                                                   transformed_frequency_bucket_count, indexStride);
            rgb565Pixels[index] = static_cast<jshort>(int_value);
        }
    }

cleanup:
    if (transformedData) {
        // JNI_ABORT means don't copy elements back, just free the memory:
        env->ReleaseFloatArrayElements(transformed_data_buffer, transformedData, JNI_ABORT);
    }
    if (noiseBaseline) {
        // JNI_ABORT means don't copy elements back, just free the memory:
        env->ReleaseFloatArrayElements(noise_baseline_buffer, noiseBaseline, JNI_ABORT);
    }
    if (rgb565Pixels != nullptr) {
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    return rc;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_org_batgizmo_app_pipeline_AbstractPipeline_00024Companion_nativeFindBnCRange(JNIEnv *env, jobject thiz,
                                                                                  jint x_min, jint x_max,
                                                                                  jint y_min, jint y_max,
                                                                                  jint frequency_buckets,
                                                                                  jfloatArray transformed_data,
                                                                                  jfloatArray noise_baseline_optional
                                                                          ) {

    if (x_min == x_max || y_min == y_max)
        return nullptr;     // No data available.

    jfloat *data = env->GetFloatArrayElements(transformed_data, nullptr);
    if (data == nullptr) {
        return nullptr;
    }

    jfloat *noise_baseline_data = nullptr;
    if (noise_baseline_optional) {
        noise_baseline_data = env->GetFloatArrayElements(noise_baseline_optional,
                                                         nullptr);
        if (noise_baseline_data == nullptr) {
            // Free the array we allocated a moment before as it is no longer wanted:
            env->ReleaseFloatArrayElements(transformed_data, data, JNI_ABORT);
            return nullptr;
        }
    }

    float minDB = std::numeric_limits<float>::max();
    float maxDB = std::numeric_limits<float>::lowest();
    bool first = true;

    // Future: consider nesting this loop the other way around as an optimization.
    for (int timeIndex = x_min; timeIndex <= x_max; ++timeIndex) {
        int data_offset = timeIndex * frequency_buckets;
        // Reflect the Y indices:
        const jint y1 = frequency_buckets - y_max - 1;
        const jint y2 = frequency_buckets - y_min - 1;
        float *p_baseline_data = noise_baseline_data ? noise_baseline_data + y1 : nullptr;
        for (int frequencyIndex = y1; frequencyIndex <= y2; frequencyIndex++) {
            float dB = data[data_offset + frequencyIndex];
            if (p_baseline_data) {
                float offsetDb = *p_baseline_data++;
                dB -= offsetDb;
            }

            if (first) {
                minDB = dB;
                maxDB = dB;
                first = false;
            } else {
                if (dB < minDB)
                    minDB = dB;
                if (dB > maxDB)
                    maxDB = dB;
            }
        }
    }

    // Release memory
    env->ReleaseFloatArrayElements(transformed_data, data, JNI_ABORT);
    if (noise_baseline_data)
        env->ReleaseFloatArrayElements(noise_baseline_optional, noise_baseline_data, JNI_ABORT);

    // Create a float array to return the result
    jfloatArray result = env->NewFloatArray(2);
    if (result == nullptr) {
        return nullptr;  // Memory allocation failure
    }

    jfloat resultValues[2] = {minDB, maxDB};
    env->SetFloatArrayRegion(result, 0, 2, resultValues);
    return result;
}

#define CHECK_OVERFLOW 0

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_AbstractPipeline_00024Companion_nativeFindNoiseBaseline(JNIEnv *env, jobject thiz,
                                                                                       jint x_min, jint x_max_exclusive,
                                                                                       jint frequency_buckets,
                                                                                       jfloatArray transformed_data_buffer,
                                                                                       jfloatArray noise_baseline_buffer) {
    jfloat *transformed_data = nullptr;
    jfloat *noise_baseline_data = nullptr;
    jsize transformedDataLength = 0;
    double sum = 0;
    int count = 0;
    float average = 0;
    int decimationStepSize = 0;

    if (x_min + 1 == x_max_exclusive)
        goto cleanup;

    transformed_data = env->GetFloatArrayElements(transformed_data_buffer, nullptr);
    if (transformed_data == nullptr)
        goto cleanup;
    transformedDataLength = env->GetArrayLength(transformed_data_buffer);

    noise_baseline_data = env->GetFloatArrayElements(noise_baseline_buffer, nullptr);
    if (noise_baseline_data == nullptr)
        goto cleanup;

    /*
     * For each frequency bucket use t-digest to estimate percentiles:
     *  https://github.com/tdunning/t-digest/
     *
     * The time taken to find the noise baseline is a little confusing.
     * The entire page of data from the data file is processed. However, zooming
     * in may change the FFT window size. Decreasing the visible time range results
     * in a smaller window, changing the number of frequency buckets. The net effect
     * is a decrease in processing time, even though we adjust decimation so
     * that approximately the same number of time points are processed.
     *
     * t-digest scales are O(log n) per insert and per result.
     */

    for (int frequencyIndex = 0; frequencyIndex < frequency_buckets; frequencyIndex++) {
        // compression on the low side to limit memory and CPU usage, accepting lower accuracy.
        // Though, the difference between 100 and 1000 is only about 20% difference in CPU time taken.
        const int compression = 100;
        tdigest::TDigest digest(compression);

        // Decimation to improve performance:
        const int targetSamplesToUse = 100;     // Chosen by trial and error.
        decimationStepSize = (x_max_exclusive - x_min) / targetSamplesToUse;
        if (decimationStepSize < 1)
            decimationStepSize = 1;
        // __android_log_print(ANDROID_LOG_INFO, __FILE__, "Baseline x range = %d - %d, step size: %d", x_min, x_max_exclusive, decimationStepSize);

        // Optimisation for stepping through the transformed_data array:
        jfloat *ptr = transformed_data + frequencyIndex;
        int frequencyStepSize = frequency_buckets * decimationStepSize;
        for (int timeIndex = x_min;
            timeIndex < x_max_exclusive;
            timeIndex += decimationStepSize, ptr += frequencyStepSize) {
#if CHECK_OVERFLOW
            if ((ptr - transformed_d    for (int frequencyIndex = 0; frequencyIndex < frequency_buckets; frequencyIndex++) {
ata) >= transformedDataLength) {
                int i;
                i = 42;     // Put a breakpoint here.
            }
#endif
            double dB = *ptr;

            // The add() method is inline and batches transparently:
            digest.add(dB);
        }

        const float quantile_percent = 20.0;
        const auto v = (float) digest.quantile(quantile_percent / 100.0);
        noise_baseline_data[frequencyIndex] = v;
        sum += v;
        count++;
    }

    /*
     * Offset the baseline by the average, so that the average of the baseline is zero.
     * That means that any manual set BnC range will apply equally whether baseline correction
     * is enabled or not.
     */

    // __android_log_print(ANDROID_LOG_INFO, __FILE__, "Baseline sample count: %d, decimation step: %d", count, decimationStepSize);

    average = (float) (sum / count);
    for (int frequencyIndex = 0; frequencyIndex < frequency_buckets; frequencyIndex++) {
        noise_baseline_data[frequencyIndex] -= average;
    }

cleanup:    // Oh for a finally block in c++.
    if (transformed_data)
        env->ReleaseFloatArrayElements(transformed_data_buffer, transformed_data, JNI_ABORT);
    if (noise_baseline_data) {
        // 0 => Copy the data back to the Java array and free the native buffer:
        env->ReleaseFloatArrayElements(noise_baseline_buffer, noise_baseline_data, 0);
    }
}
