/*
 * Copyright (c) 2025 John Mears
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
        s_already_initialized = true;
        return rc;
    }

    jshort *pData = env->GetShortArrayElements(colour_map, nullptr);
    if (pData != nullptr) {
        if (s_colourMapData != nullptr) {
            // Paranoia.
            delete [] s_colourMapData;
        }

        // Make our own copy to use later:
        s_colourMapDataSize = colour_map_size;
        s_colourMapData = new uint16_t[s_colourMapDataSize];
        for (int i = 0; i < s_colourMapDataSize; i++)
            s_colourMapData[i] = pData[i];

        // JNI_ABORT means don't copy elements back, just free the memory:
        env->ReleaseShortArrayElements(colour_map, pData, JNI_ABORT);
    }
    else {
        rc = -1;
    }

    return rc;
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
 *dB is 10 log10(power).
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
    jfloat *transformedData = env->GetFloatArrayElements(output_slice_buffer, nullptr);
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
        env->ReleaseFloatArrayElements(input_slice_buffer, unwrappedRawData, 0);    // Change back to JNI_ABORT
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
                                                                        jint transformed_time_bucket_count,
                                                                        jint transformed_frequency_bucket_count,
                                                                        jobject bitmap,
                                                                        jfloat offset, jfloat multiplier) {

    AndroidBitmapInfo info;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0)
        return -1;
    if (info.format != ANDROID_BITMAP_FORMAT_RGB_565)
        return -1;

    // Lock the bitmap for writing
    uint16_t *rgb565Pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, (void**) &rgb565Pixels) < 0)
        return - 1;

    jfloat *transformedData = env->GetFloatArrayElements(transformed_data_buffer, nullptr);

    int rc = 0;
    if (transformedData == nullptr || rgb565Pixels == nullptr) {
        rc = -1;
    } else {
        const uint32_t indexStride = info.stride / sizeof(uint16_t);

        float *inputPtr = transformedData + first * transformed_frequency_bucket_count;
        for (int timeBucket = first; timeBucket < second; timeBucket++) {
            for (int frequencyBucket = 0; frequencyBucket < transformed_frequency_bucket_count; frequencyBucket++) {

                float value = *inputPtr++;

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
    }

    if (transformedData) {
        // JNI_ABORT means don't copy elements back, just free the memory:
        env->ReleaseFloatArrayElements(transformed_data_buffer, transformedData, JNI_ABORT);
    }
    if (rgb565Pixels != nullptr) {
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    return rc;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_org_batgizmo_app_pipeline_AbstractPipeline_00024Companion_findBnCRange(JNIEnv *env, jobject thiz,
                                                                          jint x_min, jint x_max,
                                                                          jint y_min, jint y_max,
                                                                          jint frequency_buckets,
                                                                          jfloatArray transformed_data_buffer) {

    if (x_min == x_max || y_min == y_max)
        return nullptr;     // No data available.

    // Convert Java float array to C++ array
    jfloat *data = env->GetFloatArrayElements(transformed_data_buffer, nullptr);
    if (data == nullptr) {
        return nullptr;     // Memory allocation failure
    }

    float minDB = std::numeric_limits<float>::max();
    float maxDB = std::numeric_limits<float>::lowest();
    bool first = true;

    // Loop through the range
    for (int timeIndex = x_min; timeIndex <= x_max; ++timeIndex) {
        int offset = timeIndex * frequency_buckets;
        // Reflect the Y indices:
        const jint y1 = frequency_buckets - y_max - 1;
        const jint y2 = frequency_buckets - y_min - 1;
        for (int frequencyIndex = y1; frequencyIndex <= y2; frequencyIndex++) {
            float dB = data[offset + frequencyIndex];

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
    env->ReleaseFloatArrayElements(transformed_data_buffer, data, JNI_ABORT);

    // Create a float array to return the result
    jfloatArray result = env->NewFloatArray(2);
    if (result == nullptr) {
        return nullptr;  // Memory allocation failure
    }

    jfloat resultValues[2] = {minDB, maxDB};
    env->SetFloatArrayRegion(result, 0, 2, resultValues);
    return result;
}
