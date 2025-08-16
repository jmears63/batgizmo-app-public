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

#include <string.h>
#include <math.h>
#include <stdint.h>
#include <jni.h>
#include <android/log.h>

#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <asm/byteorder.h>
#include <malloc.h>
#include <errno.h>
#include <unistd.h>
#include <fstream>
#include <fcntl.h>
#include <pthread.h>
#include <aaudio/AAudio.h>
#include <assert.h>
#include <memory.h>

extern "C" {
}

#define MAX_CHANNELS 2

// Upper limit that we support based on full speed USB. Allow a little extra as some detectors sometimes
// send a bit more data as a lazy way to keep in sync:
#define MAX_SAMPLES_PER_FRAME (384 + 1)

#define URBS_PER_SECOND 40      // The target update rate that results in a smooth UI, including
                                // reasonably smooth with a 44100 kHz microphone.

// Aim for about 20 URBs per second to give a reasonable UI update rate
// while minimizing overheads:
#define URBS_TO_JUGGLE 10        // At least 2 required. More allows a greater queuing depth without loss.
#define PACKETS_PER_URB (1000 / URBS_PER_SECOND)   // One packet (frame) is 1 ms.
#define MAX_DATA_POINTS_PER_URB (MAX_SAMPLES_PER_FRAME * MAX_CHANNELS * PACKETS_PER_URB)

#define TARGET_AUDIO_OUT_RATE 48000     // Usually this is native for Android devices.

#define STREAM_TO_WAV 0

//#define FAKE_DATA 1

// A type representing the audio data we handle:
typedef int16_t data_t;

// This flag is typically set by the UI thread and read by the worker thread.
static volatile bool s_cancel_pending = false;

static volatile bool s_paused = false;

// This is the file descriptor of the data file we are currently streaming audio data to.
static volatile int s_fd_file = -1;

static volatile int s_num_channels = 0;
static volatile int s_sample_rate = 0;
static volatile int s_nominal_samples_per_frame = 0;

// The Android audio we are writing audio output to, or NULL if we aren't.
static AAudioStream *s_android_stream = nullptr;      // The output stream.

// Note that the downsample factor is constrained to be an integer:
static volatile int s_decimation_factor = 0;
static volatile int s_audio_out_rate = 0;

static int32_t calculate_iir_coefficient(double cutoff_hz, double sample_rate_hz) {
    double exponent = -2.0 * M_PI * cutoff_hz / sample_rate_hz;
    double a = 1.0 - exp(exponent);
    auto s_downsampling_iir_coefficient = (int32_t) lround(a * (1LL << 31));
    return s_downsampling_iir_coefficient;
}

/*
 * This mutex protects static data in this module.
 */
static pthread_mutex_t s_mutex = PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP;

#define CANARY_COUNT 1
#define CANARY_VALUE_32 0xFABDECAF
#define CANARY_DATA_VALUE ((data_t) 0xFACE)

// Any array to hold the heterodyne reference signal:
#define MAX_REFERENCE_LEN 512
static int16_t s_reference_data[MAX_REFERENCE_LEN + CANARY_COUNT];
static int s_reference_len = 0;
static volatile int s_reference1_index = 0, s_reference2_index = 0;
static int s_heterodyne1_kHz = 0, s_heterodyne2_kHz = 0;
static int s_audio_boost_shift = 0;

// Adjust this so that there is no heterodyned audio output visible over
// about 10 kHz:
#define DOWNSAMPLING_AA_CUTOFF_HZ 3000      // Conservative (low) value to minimize bleed through/feedback.
#define DOWNSAMPLING_AA_STAGES 4            // Order of the LPF

typedef struct {
    int32_t previous[DOWNSAMPLING_AA_STAGES];
} aa_filter_state_t;

static int32_t s_downsampling_iir_coefficient = 0;
static aa_filter_state_t s_downsampling_filter_state;

static void downsampling_filter_reset() {
    memset(&s_downsampling_filter_state, 0, sizeof(s_downsampling_filter_state));
}

static bool start_audio_output(jint output_device_id);
static void stop_audio_output();
static void write_audio_output(const data_t *pBuffer, uint32_t sample_count, jint num_channels);
static void stop_recording();

/*
 * Workaround for usbdevfs_iso_packet_desc having size 0 in usbdevfs_urb:
 */
struct my_usbdevfs_urb {
    usbdevfs_urb urb;
    struct usbdevfs_iso_packet_desc packet_desc[PACKETS_PER_URB];
};


/***********************************************************************************/
/* Basic data stream from USB.                                                     */
/***********************************************************************************/

/**
 * Data used for streaming audio data arriving via USB and ioctls.
 *
 * This data is accessed exclusively from the Java_org_batgizmo_app_pipeline_NativeUSB_stream
 * function which is called from a worker thread.
 *
 * This data has to be statically allocated as it may be referenced after a stream has
 * been closed, due to asynchronous processing
 */
static int16_t audio_buffer[URBS_TO_JUGGLE][MAX_DATA_POINTS_PER_URB + CANARY_COUNT];
//static int16_t urb_audio_buffer[MAX_DATA_POINTS_PER_URB + CANARY_COUNT];
static my_usbdevfs_urb urbRequests[URBS_TO_JUGGLE];

static void initialiseRequests(jint endpointAddress, int requested_bytes_per_frame)
{
    memset(audio_buffer, 0, sizeof(audio_buffer));
    for (int i = 0; i < URBS_TO_JUGGLE; i++) {
        audio_buffer[i][MAX_DATA_POINTS_PER_URB] = CANARY_DATA_VALUE;
    }

    for (int i = 0; i < URBS_TO_JUGGLE; i++) {
        my_usbdevfs_urb* req = &urbRequests[i];
        usbdevfs_urb* urb = &req->urb;
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = endpointAddress | 0x80; // 0x80 because this is an input endpoint.
        urb->status = 0;
        urb->flags = USBDEVFS_URB_ISO_ASAP;     // Request isochronous transfer.
        urb->buffer = audio_buffer[i];
        urb->buffer_length = 0;
        urb->actual_length = 0;                 // Not set for isochronous transfers.
        urb->start_frame = 0;
        urb->number_of_packets = PACKETS_PER_URB;
        urb->error_count = 0;
        urb->signr = 0;                         // Optional signal to signal on completion.
        urb->usercontext = req;                 // This is a cookie for client code.

        for (int j = 0; j < PACKETS_PER_URB; j++) {
            usbdevfs_iso_packet_desc *pIsoPacketDesc = &urb->iso_frame_desc[j];
            pIsoPacketDesc->length = requested_bytes_per_frame;   // Requested length.
            pIsoPacketDesc->actual_length = 0;
            pIsoPacketDesc->status = 0;
        }
    }
}

/**
 * Do audio streaming via isochronous USB.
 * This function is called from a worker thread.
 */
extern "C" JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_stream(JNIEnv* env, jobject thiz,
                                          jint fd_usb, jint configId, jint ifaceId, jint alternateSetting, jint endpointAddress,
                                          jint num_channels, jint sample_rate, jint max_packet_size) {

    pthread_mutex_lock(&s_mutex);

    __android_log_print(ANDROID_LOG_INFO, __FILE__,
                        "Java_org_batgizmo_app_pipeline_NativeUSB_stream fd_usb = %d, s_paused = %s",
                        fd_usb, s_paused ? "true" : "false");

    int ret;

    if (num_channels > MAX_CHANNELS || num_channels < 1) {
        __android_log_print(ANDROID_LOG_ERROR, __FILE__,
                            "Java_org_batgizmo_app_pipeline_NativeUSB_stream invalid number of channels: %d", num_channels);
    }

    s_nominal_samples_per_frame = sample_rate / 1000;      // Samples per ms.

    if (s_nominal_samples_per_frame > MAX_SAMPLES_PER_FRAME || s_nominal_samples_per_frame < 1) {
        __android_log_print(ANDROID_LOG_ERROR, __FILE__,
                            "Java_org_batgizmo_app_pipeline_NativeUSB_stream invalid number of s_nominal_samples_per_frame: %d", s_nominal_samples_per_frame);
    }


    // Prepare to call a kotlin callback to signal buffers ready:
    jmethodID onDataBufferReadyMethod = nullptr;
    const char *kotlinClassName = "org/batgizmo/app/LiveDataBridge";
    const char *kotlinMethodName = "onDataBufferReady";
    jclass bridgeClass = env->FindClass(kotlinClassName);
    if (bridgeClass != nullptr)
        onDataBufferReadyMethod = env->GetStaticMethodID(bridgeClass, kotlinMethodName, "(JI)V");
    if (onDataBufferReadyMethod == nullptr) {
        env->DeleteLocalRef(bridgeClass);
        __android_log_print(ANDROID_LOG_ERROR, __FILE__,
                            "Java_org_batgizmo_app_pipeline_NativeUSB_stream unable to find LiveDataBridge.onDataBufferReady method");
    }

    s_cancel_pending = false;
    s_num_channels = num_channels;
    s_sample_rate = sample_rate;

    // Important: often the sample rate will be a multiple of 48kHz, but in rare
    // cases it might not be.
    // Find a downsampling rate the gets us close to 48 kHz audio rate:
    s_decimation_factor = lround((double) sample_rate / TARGET_AUDIO_OUT_RATE);
    if (s_decimation_factor == 0)
        s_decimation_factor = 1;
    // The actual audio out rate may be different from the nominal target value:
    s_audio_out_rate = sample_rate / s_decimation_factor;   // What if this is fractional?
    s_downsampling_iir_coefficient = calculate_iir_coefficient(DOWNSAMPLING_AA_CUTOFF_HZ, sample_rate);
    __android_log_print(ANDROID_LOG_INFO, __FILE__, "Audio parameters: s_audio_out_rate = %d, s_decimation_factor = %d",
                        s_audio_out_rate, s_decimation_factor);

#if 0   // This doesn't seem to be needed if we have claimed all interfaces on the device.
    // Wrench control away from anyone else who may have it. Android itself
    // takes control of microphones in the range of normal sampling rates, though not
    // high sampling rates ones used for bats. That might of course change.
    usbdevfs_ioctl command;
    command.ifno = 2;   // The HID interface is 2.
    command.ioctl_code = USBDEVFS_DISCONNECT;
    command.data = NULL;
    ret = ioctl(fd_usb, USBDEVFS_IOCTL, &command);
    __android_log_print(ANDROID_LOG_INFO, __FILE__, "USBDEVFS_DISCONNECT: %d %d", ret, errno);
#endif

#if 0   // This is moved into the kotlin code.
    // ****** Set config ******

    // Fails with 14, EFAULT. Perhaps the driver doesn't support this? Nothing happens on the wire.
    ret = ioctl(fd_usb, USBDEVFS_SETCONFIGURATION, configId);
    __android_log_print(ANDROID_LOG_INFO,ha __FILE__, "USBDEVFS_SETCONFIGURATION: %d %d", ret, errno);

    // ****** Set interface ******

    struct usbdevfs_setinterface setif;
    setif.altsetting = alternateSetting;
    setif.interface = ifaceId;

    ret = ioctl(fd_usb, USBDEVFS_SETINTERFACE, &setif);
    __android_log_print(ANDROID_LOG_INFO, __FILE__, "USBDEVFS_SETINTERFACE: %d %d", ret, errno);

    // ****** Claim interface ******
    ret = ioctl(fd_usb, USBDEVFS_CLAIMINTERFACE, ifaceId);
    __android_log_print(ANDROID_LOG_INFO, __FILE__,"USBDEVFS_CLAIMINTERFACE: %d %d", ret, errno);
#endif

    // ****** Stream some data ******

    // Important: if request the exact number of samples we expect based on the
    // sampling rate and number of channels, some microphones will occasionally send
    // one more or less. If more samples than our buffer can hold are sent,
    // USBDEVFS_REAPURB hangs, unhelpfully. We could request way more data to be safe,
    // but this causes hangs. It seems as if we have to request the exact number in
    // the USB descriptor endpoint for it to work out:
    const uint32_t requested_bytes_per_frame = max_packet_size;
    initialiseRequests(endpointAddress, requested_bytes_per_frame);

#ifdef FAKE_DATA
    init_fake_data();
#endif

    __android_log_print(ANDROID_LOG_INFO, __FILE__, "starting streaming");

    int balls_in_the_air = 0;

    int s_counter = 0;  // For debugging.

    /**
     * Kick things off by throwing all the balls in the air. We will catch them below
     * and continue juggling them.
     */
    for (int i = 0; i < URBS_TO_JUGGLE; i++) {
        do {
            ret = ioctl(fd_usb, USBDEVFS_SUBMITURB, &urbRequests[i]);
            balls_in_the_air++;
        } while((ret < 0) && (errno == EINTR));
        if (ret != 0) {
            __android_log_print(ANDROID_LOG_ERROR, __FILE__, "USBDEVFS_SUBMITURB: %d %d", ret, errno);

            // No point going any further, we will block on USBDEVFS_REAPURB indefinitely.
            pthread_mutex_unlock(&s_mutex);
            return errno;
        }
    }

    /**
     * Juggle the balls until we get notice to stop - at which point, continue catching them
     * until none remain in the air.
     */
    while ((!s_cancel_pending) || (balls_in_the_air > 0)) {
        usbdevfs_urb *urbReaped = NULL;
        do {
            /*
             * Important: USBDEVFS_REAPURB will hang for ever if the device sends more data then we
             * requested. Usually that doesn't happen, but some microphones will occasionally pad the
             * data if they don't sync their sampling rate to SoF. On the other hand if we request
             * more data it can also hang. The solution seems to be to use the
             * endpoint buffer size from the USB descriptor.
             */

            // Unlock the mutex, so that USB data can be populated into the buffer but
            // other things happen at the same time:
            pthread_mutex_unlock(&s_mutex);
            ret = ioctl(fd_usb, USBDEVFS_REAPURB, &urbReaped);
            pthread_mutex_lock(&s_mutex);
            if (ret == 0) {
                s_counter++;
                balls_in_the_air--;     // We caught one.
                auto *req = (usbdevfs_urb *) urbReaped->usercontext;
                auto *pData = (data_t *) req->buffer;
                // The actual number of samples read might deviate slightly from the number expected,.
                // if the microphone doesn't sync its sampling rate with the host SoF:
                auto actual_samples_read = urbReaped->actual_length / 2;

                // Check the canary value at the end of the buffer:
                assert(pData[MAX_DATA_POINTS_PER_URB] == CANARY_DATA_VALUE);

                if (!s_paused) {

                    if (bridgeClass && onDataBufferReadyMethod) {
                        // Take account of the fact that we often get back fewer data samples
                        // then we requested - the data buffer contains corresponding padding
                        // entries that we need to remove.

                        unsigned int dst_byte_offset = 0, source_byte_offset = 0;
                        auto frame_desc= urbReaped->iso_frame_desc;
                        for (int frame = 0; frame < PACKETS_PER_URB; frame++, frame_desc++) {
                            // memmove because the source and destination overlap:
                            auto actual_length = frame_desc->actual_length;
                            if (frame > 0 && actual_length > 0)
                                memmove((char*) pData + dst_byte_offset, (char*) pData + source_byte_offset, actual_length);
                            dst_byte_offset += frame_desc->actual_length;   // Bytes
                            source_byte_offset += frame_desc->length;       // Bytes
                        }

                        // For stereo data, combine the two channels into a single channel:
                        if (s_num_channels == 2) {
                            // Sample index, not bytes.
                            for (int i = 0, j = 0; i < actual_samples_read; i += 1, j += 2) {
                                // Average of the stereo channel values:
                                pData[i] = (short) (((int) pData[j] + (int) pData[j + 1]) >> 1);
                            }
                            actual_samples_read >>= 1;   // We've just halved the the number of samples.
                        }

                        // Some microphones send empty packets on buffer under run. Avoid wasting time
                        // on them:
                        if (actual_samples_read > 0) {
                            // Notify kotlin that the URB buffer is ready for processing:
                            env->CallStaticVoidMethod(bridgeClass, onDataBufferReadyMethod,
                                                      (jlong) pData, (jint) actual_samples_read);

                            // Write the URB data to the audio output.
                            // Grab the lock to avoid races accessing s_android_stream.
                            pthread_mutex_lock(&s_mutex);
                            if (s_android_stream) {
                                // Number of channels is 1 by this point:
                                write_audio_output((data_t *) pData, actual_samples_read, 1);
                            }
                            pthread_mutex_unlock(&s_mutex);
                        }
                    }
                }

#if STREAM_TO_WAV
                if (s_fd_file >= 0) {
                    wav_writesomedata(s_fd_file, pData, nominal_bytes_per_frame);
                }
#endif

            }
        } while((ret < 0) && (errno == EINTR) && (!s_cancel_pending));
        if (ret != 0) {
            __android_log_print(ANDROID_LOG_ERROR, __FILE__, "USBDEVFS_REAPURB: %d %d", ret, errno);
            if (errno == ENODEV) {
                // Probably the device is unplugged, so give up.
                break;
            }
            continue;
        }

        // Recycle the request:
        if (!s_cancel_pending) {
            usbdevfs_urb *req = (usbdevfs_urb *) urbReaped->usercontext;
            do {
                ret = ioctl(fd_usb, USBDEVFS_SUBMITURB, req);
                if (ret == 0)
                    balls_in_the_air++; // Rethrow the ball.
            } while ((ret < 0) && (errno == EINTR));
            if (ret != 0) {
                __android_log_print(ANDROID_LOG_ERROR, __FILE__, "USBDEVFS_SUBMITURB 2: %d %d", ret, errno);
                if (errno == ENODEV) {
                    // Probably the device is unplugged, so give up.
                    break;
                }
            }
        }
    }

    // These do nothing if the activity wasn't in progress:
    stop_audio_output();
    stop_recording();

    if (bridgeClass != nullptr)
        env->DeleteLocalRef(bridgeClass);   // This also cleans up onDataBufferReadyMethod.

    // Beware: even though streaming has now completed, there may still be calls to
    // Java_org_batgizmo_app_pipeline_NativeUSB_copyBufferData to access the streamed data.

    __android_log_print(ANDROID_LOG_INFO, __FILE__, "ending streaming: ret = %d, errno = %d", ret, errno);

    pthread_mutex_unlock(&s_mutex);

    // If things went bad, return errno, otherwise 0.
    return ret < 0 ? errno : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_cancelStream(JNIEnv* env, jobject thiz) {
    pthread_mutex_lock(&s_mutex);
    s_cancel_pending = true;
    // Reset the pause mode in readiness for the next time we start streaming:
    s_paused = false;
    pthread_mutex_unlock(&s_mutex);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_pauseStream(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&s_mutex);
    __android_log_print(ANDROID_LOG_DEBUG, __FILE__,
                        "Java_org_batgizmo_app_pipeline_NativeUSB_pauseStream pausing");

    s_paused = true;
    pthread_mutex_unlock(&s_mutex);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_resumeStream(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&s_mutex);
    __android_log_print(ANDROID_LOG_DEBUG, __FILE__,
                        "Java_org_batgizmo_app_pipeline_NativeUSB_pauseStream resuming");
    s_paused = false;
    pthread_mutex_unlock(&s_mutex);
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_copyURBBufferData(JNIEnv *env, jobject thiz,
                                                           jlong source_native_offset,
                                                           jint source_samples,
                                                           jshortArray target_buffer,
                                                           jint target_buffer_offset,
                                                           jint target_buffer_size) {

    // The following results in data glitches if uncommented. That's kind of OK
    // as this function doesn't access any module data other than the values in the
    // buffer.
    // pthread_mutex_lock(&s_mutex);

    /*
     * Beware: this function may be called after the stream has closed, due to
     * asynchronous processing as the application disconnects. So the native
     * data offset has to refer to a valid data location at all times.
     */

    jint rc = -1;

    auto pSource = reinterpret_cast<const data_t *>(source_native_offset);
    int samples_to_copy = source_samples;

    jshort *pBuffer = env->GetShortArrayElements(target_buffer, nullptr);
    if (target_buffer) {
        data_t *pTarget = pBuffer + target_buffer_offset;

        // We need to copy to the destination target_buffer with wrap, so there may be two parts to the copy.

        const int part1Space = target_buffer_size - target_buffer_offset;
        const int part1Count = samples_to_copy > part1Space ? part1Space : samples_to_copy;
        for (int i = 0; i < part1Count; i++) {
            *pTarget++ = *pSource;
            // *(data_t *)pSource = 0;     // TODO remove this.
            pSource++;
        }
        samples_to_copy -= part1Count;

        if (samples_to_copy > 0) {
            pTarget = pBuffer;  // Wrap to start of target_buffer.
            const int part2Required = samples_to_copy;
            const int part2Count = part2Required > target_buffer_size ? target_buffer_size : part2Required;
            for (int i = 0; i < part2Count; i++) {
                *pTarget++ = *pSource++;
            }
            samples_to_copy -= part2Count;
        }

        // samples_to_copy should be 0 now.
        rc = source_samples - samples_to_copy;

        env->ReleaseShortArrayElements(target_buffer, pBuffer, 0);
    }

    // pthread_mutex_unlock(&s_mutex);

    return rc;
}

/***********************************************************************************/
/* Support for recording data to file.                                             */
/***********************************************************************************/

static void stop_recording() {
    pthread_mutex_lock(&s_mutex);
    if (s_fd_file >= 0) {
#if STREAM_TO_WAV
        wav_finish(s_fd_file);
#endif
        close(s_fd_file);
        s_fd_file = -1;
    }
    pthread_mutex_unlock(&s_mutex);
}

/**
 * This function takes ownership of the fd passed in, and closes it in due course.
 * @param fd
 */
static bool start_recording(int fd) {
    pthread_mutex_lock(&s_mutex);

    // In case we were already recording, restart:
    stop_recording();

#if STREAM_TO_WAV
    wav_writeheader(fd, s_num_channels, s_sample_rate);
#endif

    // Once the following value is set, we start streaming data into it:
    s_fd_file = fd;

    pthread_mutex_unlock(&s_mutex);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_startRecordingFd(JNIEnv* env, jobject thiz, jint fd) {
    return fd >= 0 && start_recording(fd);  // Order is important.
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_stopRecording(JNIEnv *env, jobject thiz) {
    stop_recording();
}

/***********************************************************************************/
/* Support for forwarding streamed data to audio output.                           */
/***********************************************************************************/

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_startAudio(JNIEnv *env, jobject thiz,
                                              jint audio_device_id,
                                              jint heterodyne1_kHz,
                                              jint heterodyne2_kHz,
                                              jint audio_boost_shift) {
    pthread_mutex_lock(&s_mutex);

    downsampling_filter_reset();

    // In case we are already doing audio:
    stop_audio_output();

    // For now, we only support heterodyne.

    int n = s_nominal_samples_per_frame;
    if (n > MAX_REFERENCE_LEN)      // Paranoia.
        n = MAX_REFERENCE_LEN;

    if (heterodyne1_kHz > n || heterodyne2_kHz > n) {
        __android_log_print(ANDROID_LOG_INFO, __FILE__,
                            "Heterodyne reference outside the valid range for the frame length (%d)", n);
        return false;
    }

    // Don't recalculate this unnecessarily:
    if (n != s_reference_len) {
        /*
         * Set up the correct number of heterodyne data points in a single
         * cycle of a cosine. Having the same number of points as the sampling rate
         * makes it easy to generated references for multiples of kHz.
         */
        const double pi2 = 3.1415927 * 2;
        int i = 0;
        for (i = 0; i < n; i++) {
            double x = ((double) i) * pi2 / n;
            s_reference_data[i] = cos(x) * 0x7FFE;
        }
        s_reference_data[i] = CANARY_DATA_VALUE;
        s_reference_len = n;
    }
    s_heterodyne1_kHz = heterodyne1_kHz;
    s_heterodyne2_kHz = heterodyne2_kHz;
    s_audio_boost_shift = audio_boost_shift;
    s_reference1_index = s_reference2_index = 0;

    jboolean rc = start_audio_output(audio_device_id);

    pthread_mutex_unlock(&s_mutex);

    return rc;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_stopAudio(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&s_mutex);
    stop_audio_output();
    pthread_mutex_unlock(&s_mutex);
}

static bool start_audio_output(jint output_device_id) {

    // Get a stream builder:
    AAudioStreamBuilder *builder;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_INFO, __FILE__,
                            "AAudio_createStreamBuilder returned %d", result);
        return false;
    }

    AAudioStreamBuilder_setDeviceId(builder, output_device_id);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    // AAudioStreamBuilder_setSharingMode(builder, mode);   // Default to shared.
    AAudioStreamBuilder_setSampleRate(builder, s_audio_out_rate);
    const int channel_count = 1;     // Always mono at this point.
    AAudioStreamBuilder_setChannelCount(builder, channel_count);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);

    // AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);

    // Use the builder to open a stream:
    result = AAudioStreamBuilder_openStream(builder, &s_android_stream);
    if (result != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        __android_log_print(ANDROID_LOG_INFO, __FILE__,
                            "AAudioStreamBuilder_openStream returned %d", result);
        return false;
    }

    // Finish with the builder:
    AAudioStreamBuilder_delete(builder);
    builder = nullptr;

    // Check some things about the stream:
    int32_t rate = AAudioStream_getSampleRate(s_android_stream);
    int32_t channels = AAudioStream_getChannelCount(s_android_stream);
    int32_t device_id = AAudioStream_getDeviceId(s_android_stream);
    // int32_t mode = AAudioStream_getPerformanceMode(s_android_stream);

    // aaudio_format_t format = AAudioStream_getFormat(s_android_stream);

    int32_t buffer_frames = AAudioStream_getBufferCapacityInFrames(s_android_stream);
    // A frame here is 1 for mono, 2 for stereo. A sample is a single 16 bit value:
    int32_t samples_per_frame = AAudioStream_getSamplesPerFrame(s_android_stream);
    int32_t frames_per_burst = AAudioStream_getFramesPerBurst(s_android_stream);    // 1368

    // Prime the buffer by half filling it with zero, to reduce the chance of underflow:
    const int bufframes = buffer_frames / 2;
    const int bufsize = buffer_frames * channels * sizeof(int16_t);
    int16_t *pBuf = (int16_t *) malloc(bufsize);
    if (pBuf) {
        memset(pBuf, 0, bufsize);
        aaudio_result_t rc = AAudioStream_write(s_android_stream, pBuf, bufframes, 0);
        free(pBuf);
        pBuf = nullptr;
    }

    // Despite the docs saying otherwise, we do need to start the stream explicitly:
    result = AAudioStream_requestStart(s_android_stream);

    __android_log_print(ANDROID_LOG_INFO, __FILE__, "Audio stream opened: device %d, rate %d, channels %d, buffer %d frames, result %d",
                        device_id, rate, channels, buffer_frames, result);

    return result == 0;
}

static void stop_audio_output()
{
    if (s_android_stream) {
#if STREAM_TO_WAV
        __android_log_print(ANDROID_LOG_INFO, __FILE__, "Closing audio stream.");
        AAudioStream_close(s_android_stream);
#endif
        s_android_stream = nullptr;
    }
}

static void write_audio_output(const data_t *pBuffer, uint32_t sample_count, jint num_channels)
{
    /**
     * Confusingly android audio streaming uses "frame" to mean something different from USB, so we call it
     * sample_count instead. sample_count is the number of mono values one channel mode, or the
     * number of stereo data value pairs in two channel mode.
     */

    static int16_t downsampled_buffer[MAX_DATA_POINTS_PER_URB];

    int decimation_counter = 0;
    int decimated_sample_count = 0;

    // Should this be split into multiple loops that it is more likely to
    // handled entirely in CPU registers? But then we would need more intermediate storage, and
    // more memory accesses.

    for (int i = 0; i < sample_count; i++) {

        // Multiply the raw data by the reference(s).
        int32_t mixed = pBuffer[i] * s_reference_data[s_reference1_index];
        if (s_heterodyne2_kHz != 0)
            mixed += pBuffer[i] * s_reference_data[s_reference2_index];

        // Apply a low pass antialiasing filter. This is important to prevent audio feedback:
        int64_t filtered = mixed;
        for (int order = 0; order < DOWNSAMPLING_AA_STAGES; order++) {
            filtered = (int64_t) s_downsampling_iir_coefficient * filtered +
                               (int64_t) ((1LL << 31) - s_downsampling_iir_coefficient) *
                               s_downsampling_filter_state.previous[order];
            filtered >>= 31;
            s_downsampling_filter_state.previous[order] = (int32_t) filtered;
        }

        // Down sample:
        if (++decimation_counter == s_decimation_factor) {
            decimation_counter = 0;

            // Reduce the result to the range of 16 bit signed. 15 rather than 16 to gain a factor of 2,
            // because 0.5 * 0.5 is 0.25. Note that it remains a 32 bit signed for the moment:
            filtered >>= (15 - s_audio_boost_shift);

#define DO_SATURATION 1
#if DO_SATURATION
            if (filtered > INT16_MAX)
                filtered = INT16_MAX;
            if (filtered < INT16_MIN)
                filtered = INT16_MIN;
#endif

            downsampled_buffer[decimated_sample_count++] = static_cast<int16_t>(filtered);
        }

        // Step through the reference waveforms:
        s_reference1_index += s_heterodyne1_kHz;
        if (s_reference1_index >= s_reference_len)
            s_reference1_index -= s_reference_len;

        s_reference2_index += s_heterodyne2_kHz;
        if (s_reference2_index >= s_reference_len)
            s_reference2_index -= s_reference_len;
    }

#ifdef CAPTURE  // Debug buffer
    static int16_t debug_capture_buffer[MAX_SAMPLES_PER_FRAME * MAX_CHANNELS];     // Bigger than we will ever need.
    static uint32_t capture_count = 0;
#endif

    // See if there was an over or underrun. This can happen if the USB microphone is
    // slower than the device expectation of 48 kHz.
    int32_t xrun_count = AAudioStream_getXRunCount(s_android_stream);
    if (xrun_count > 0) {
        ;
    }

    // The write may block until there is enough room in its write buffer
    // to write all our data. Note buffer_frames and burst size when we opened
    // the audio stream.
    const int timeout_ns = 1000000000 / URBS_PER_SECOND;
    aaudio_result_t rc = AAudioStream_write(s_android_stream,
                                            downsampled_buffer,
                                            decimated_sample_count,
                                            timeout_ns);
    // rc is the number of frames written if positive, otherwise if negative, an error code.
    if (rc < 0) {
        __android_log_print(ANDROID_LOG_INFO, __FILE__,
                            "write_audio_output failed to write data: %s",
                            AAudio_convertResultToText(rc));
    }
    if (rc != decimated_sample_count) {
        ;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_batgizmo_app_pipeline_NativeUSB_setHeterodyne(JNIEnv *env, jobject thiz,
                                                       jint heterodyne1_kHz, jint heterodyne2_kHz) {
    // A smooth change to the heterodyne frequency, no step:
    s_heterodyne1_kHz = heterodyne1_kHz;
    s_heterodyne2_kHz = heterodyne2_kHz;
}