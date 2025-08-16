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

package org.batgizmo.app.pipeline

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.GET_DEVICES_OUTPUTS
import android.os.Build
import android.os.Parcelable
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.server.usb.descriptors.Usb10ASFormatI
import com.android.server.usb.descriptors.Usb10ASGeneral
import com.android.server.usb.descriptors.Usb20ASFormatI
import com.android.server.usb.descriptors.Usb20ASGeneral
import com.android.server.usb.descriptors.UsbACAudioStreamEndpoint
import com.android.server.usb.descriptors.UsbACInterfaceUnparsed
import com.android.server.usb.descriptors.UsbConfigDescriptor
import com.android.server.usb.descriptors.UsbDescriptorParser
import com.android.server.usb.descriptors.UsbEndpointDescriptor
import com.android.server.usb.descriptors.UsbInterfaceDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.batgizmo.app.UIModel
import org.batgizmo.app.diagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native access to USB. Android objects don't give access to isochronous I/O.
 *
 * References:
 * https://stackoverflow.com/questions/7964315/user-mode-usb-isochronous-transfer-from-device-to-host
 * https://www.kernel.org/doc/html/latest/driver-api/usb/usb.html
 * https://www.kernel.org/doc/html/v4.14/driver-api/usb/usb.html
 * https://www.source-code.biz/snippets/java/UsbIso/
 * https://android.googlesource.com/platform/system/core/+/android-4.4_r1/libusbhost/usbhost.c
 */

class NativeUSB {

    external fun stream(
        fd: Int,
        nConfig: Int,
        ifaceId: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        numChannels: Int,
        sampleRate: Int,
        maxPacketSize: Int
    ): Int
    external fun cancelStream()
    external fun pauseStream()
    external fun resumeStream()
    external fun startRecordingFd(fd: Int): Boolean
    external fun stopRecording()
    external fun startAudio(audioDeviceId: Int, heterodynekHz: Int,
                            heterodyne2kHz: Int, audioBoostFactor: Int): Boolean
    external fun stopAudio()
    external fun setHeterodyne(heterodyne1kHz: Int, heterodyne2kHz: Int)
    external fun copyURBBufferData(sourceOffset: Long, sourceSamples: Int,
                                   targetBuffer: ShortArray, targetBufferOffset: Int, targetBufferSize: Int): Int
}

class UsbService(private val context: Context,
                 private val model: UIModel,
                 private val usbConnectChannel: Channel<UsbConnectResult>,
                 private val usbErrorChannel: Channel<UsbErrorResult>,
                 private val scope: CoroutineScope) {

    companion object {
        // The maximum multiple of 48kHz supported by full speed USB:
        const val MAX_SAMPLING_RATE = 384000
        const val MIN_SAMPLING_RATE = 44100
    }

    data class UsbConnectResult(
        val connectedOK: Boolean,
        val errorMessage: String? = null,
        val deviceName: String? = null,
        val manufacturerName: String? = null,
        val productName: String? = null,
        val sampleRate: Int? = null
    )

    data class UsbErrorResult(
        val errno: Int
    )

    data class AudioStartResult(
        val startedOK: Boolean
    )

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val logTag = this::class.simpleName

    /*
     * Unfortunately the USB permission callback doesn't seem to include information
     * about which device permission was granted or reject for, so we cache the value in
     * the following variable. This is a hack and perhaps one day I will figure out
     * how to include the device info in the intent.
     */
    private var theDevice: UsbDevice? = null

    private val usbPermissionName = "${context.packageName}.USB_PERMISSION"

    // private val nativeSamplingRate = 48000      // Android devices are typically based on 48 kHz.

    private val nativeUsb = NativeUSB()

    // The current list of devices:
    // private var deviceListSerial = 0
    // private var deviceListData = Pair<Int, List<DeviceData>>(deviceListSerial, mutableListOf())
    // private val ldDeviceListItems = MutableLiveData<Pair<Int, List<DeviceData>>>()
    // fun getDeviceListItems() : LiveData<Pair<Int, List<DeviceData>>> = ldDeviceListItems

    // The current list of endpoints:
    // private var endpointListSerial = 0
    // private var endpointListData = Pair<Int, List<EndpointData>>(endpointListSerial, mutableListOf())
    // private val ldEndpointListItems = MutableLiveData<Pair<Int, List<EndpointData>>>()
    // fun getEndpointListItems() : LiveData<Pair<Int, List<EndpointData>>> = ldEndpointListItems

    // The current connection, or nun if nun.
    private var connection: UsbDeviceConnection? = null

    // The thread in use for streaming audio, or nun if nun.
    private var streamingThread: Thread? = null

    private val mutex = Mutex()

    private fun sanitizeUsbText(input: String?, maxLength: Int = 64): String? {
        var sanitized: String? = null
        if (input != null) {
            sanitized = buildString {
                input.take(maxLength).forEach { ch ->
                    when {
                        // ch == '\n' || ch == '\t' -> append(ch)       // allow some whitespace
                        ch.isLetterOrDigit() || ch.isWhitespace() || ch in '!'..'~' -> append(ch) // printable ASCII
                        ch.code in 0x20..0x7E -> append(ch)     // general printable range
                        ch.code > 127 && !Character.isISOControl(ch) -> append(ch) // allow safe extended Unicode
                        else -> append(' ')                             // replace suspicious chars
                    }
                }
            }
        }
        if (sanitized != null)
            sanitized = sanitized.trim()
        return sanitized
    }

    /*
        A broadcast receiver that we will use to handle the asynchronous event when
        the user grants or denies permission to access a USB device.
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // This gets called on the main UI thread.
            if (intent.action == usbPermissionName) {
                scope.launch(context = Dispatchers.Default) {
                    mutex.withLock {
                        Log.i(
                            this::class.simpleName,
                            "Broadcast receiver called for ${intent.action}"
                        )

                        /*
                        I don't know why, but the intent we received doesn't contain any extras,
                        so we can't tell from it what the device is, or whether permission was granted.
                        Duh.
                        */

                        val device = theDevice
                        theDevice = null

                        // We should have assigned the UsbDevice in question to device before
                        // requesting permission:
                        check(device != null) { "Internal error - device is null" }

                        val granted = usbManager.hasPermission(device)
                        val grantedString = if (granted) "granted" else "NOT granted"
                        Log.i(
                            this::class.simpleName,
                            "${device.productName} permission $usbPermissionName: $grantedString"
                        )
                        if (granted) {
                            processDevice(device)
                        } else {
                            // It looks like the user declined to grant permission. This is not exactly
                            // an error, but the handling is similar:

                            usbConnectChannel
                                .send(
                                UsbConnectResult(
                                    false,
                                    manufacturerName = sanitizeUsbText(device.manufacturerName),
                                    productName = sanitizeUsbText(device.productName)
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(usbPermissionName)

        // Set ourselves up to receive intents resulting from requests to grant permission
        // to a USB device:
        Log.i(this::class.simpleName, "Registering receiver for permission $usbPermissionName")
        context.registerReceiver(
            usbReceiver,
            filter,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        )
    }

    /*
        Attempt to connect to a USB microphone and start streaming data from it.
        That is somewhat asynchronous, so we notify the outcome via channel
        liveConnectChannel.

        Return the sample rate of the endpoint we connected to.
     */
    suspend fun connect() {
        mutex.withLock {
            try {
                /*
                    For now we simplify things by assuming there is only one USB device present
                    which we will try use as an audio microphone. We pick an interface
                    and endpoint from the device automatically.

                    A future enhancement could be to add UI to support selection of a device, interface
                    and endpoint, if somehow there are multiple options. This seems like rare occurrence
                    though, so I will wait for someone to ask for it.
                 */

                // Select the first item if present, and request permission to access it:
                val device = usbManager.deviceList.values.firstOrNull()
                if (device == null) {
                    Log.i(this::class.simpleName, "No USB device found")
                    throw RuntimeException("No USB device found")
                }

                val hp = usbManager.hasPermission(device)   // Already got permission?
                if (hp) {
                    Log.i(
                        this::class.simpleName,
                        "Device ${device.deviceName} already has permission"
                    )
                    processDevice(device)
                } else {
                    // A hacky way to communicate with the intent handle callback code:
                    theDevice = device
                    val permissionIntent = PendingIntent.getBroadcast(
                        context, 0, Intent(usbPermissionName),
                        PendingIntent.FLAG_IMMUTABLE
                    )

                    // This following line requests the user interactively for permission, and
                    // if they grant it, calls processDevice:
                    Log.i(
                        this::class.simpleName,
                        "Requesting permission for device ${device.deviceName}"
                    )
                    usbManager.requestPermission(device, permissionIntent)
                }
            }
            finally {
                // Any clean up goes here.
            }
        }
    }

    /**
     * This function attempts to connect to the device cached in theDevice, for which
     * we requested permissions that may or may not have been granted.
     *
     * IMPORTANT: this function can be called asynchronously so its success or failure status
     * is signalled via a channel.
     */
    private fun processDevice(device: UsbDevice) {

        try {
            val b = usbManager.hasPermission(device)
            if (!b) {
                throw RuntimeException("This app does not have permission to access the USB device.")
            }

            var rawDescriptors: ByteArray = readDeviceDescriptor(device)

            diagnosticLogger.log {
                String.format("USB descriptor data:\n%s", Base64.encodeToString(rawDescriptors, Base64.DEFAULT))
            }

            // Get a list of suitable endpoints from the descriptor. Usually there will be zero or one, but it is possible
            // for there to be multiple.
            var endpointToUse: EndpointData? = null
            val endpoints: List<EndpointData> = parseEndpointsFromDescriptor(device, rawDescriptors)
            endpointToUse = endpoints.firstOrNull()
            if (endpointToUse == null) {
                throw RuntimeException("Unable find a suitable audio endpoint in USB device ${device.productName}.")
                }

            val actualSampleRate = connectToEndpoint(endpointToUse, endpoints)
            if (actualSampleRate == null)
                throw RuntimeException("Unable to connect to the endpoint on device ${device.productName}.")

            val result = UsbConnectResult(
                true,
                deviceName = device.deviceName,
                manufacturerName = sanitizeUsbText(device.manufacturerName),
                productName = sanitizeUsbText(device.productName),
                sampleRate = actualSampleRate
            )

            diagnosticLogger.log {
                "Connected to USB device: $result"
            }

            // Signal success back to the UI:
            scope.launch(context=Dispatchers.Default) {
                usbConnectChannel.send(result)
            }
        }
        catch (e: Exception) {
            handleException(e)
        }
        finally {
            // Clean up goes here.
        }
    }

    private fun readDeviceDescriptor(device: UsbDevice): ByteArray {
        // Connect and read the descriptors:
        var rawDescriptors: ByteArray? = null
        var connection: UsbDeviceConnection? = null
        try {
            connection = usbManager.openDevice(device)
            connection?.let {
                rawDescriptors = it.rawDescriptors
            }
        } finally {
            // Clean up whatever happens:
            connection?.close()
        }
        if (rawDescriptors == null)
            throw RuntimeException("Unable to read the raw descriptor from USB device ${sanitizeUsbText(device.productName)}.")

        return rawDescriptors
    }

    private fun findUsbDeviceById(deviceId: Int): UsbDevice? {
        var device: UsbDevice? = null
        for (d in usbManager.deviceList.values) {
            val id = d.deviceId
            if (id == deviceId) {
                device = d
            }
        }
        return device
    }

    private fun connectToEndpoint(endpointData: EndpointData, endpoints: List<EndpointData>): Int? {

        // In case it is already connected:
        internalDisconnect()

        Log.i(this::class.simpleName, "connectToEndpoint: Attempting to connect to endpoint $endpointData")

        val device = endpointData.usbDevice
        connection = usbManager.openDevice(device)
        if (connection == null) {
            throw RuntimeException("Failed to connect to device ${device.deviceName}")
        }

        connection?.let { conn ->
            var usbInterface = getUsbInterfaceObject(device, endpoints, endpointData.endpointAddress)

            // Claim all the interfaces we will need to use:
            /*
            endpointData.interfacesToClaim.forEach { it ->
                // Claim the interface, forcing it away from the kernel if needed:
                val toClaim = device.getInterface(it)
                var isClaimed = conn.claimInterface(toClaim, true)
                require (isClaimed) { "Unable to claim interface $it" }
            }
             */

            // For simplicity, claim all the device's interfaces. That's easier than figuring
            // out exactly which ones we need:
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var isClaimed = conn.claimInterface(iface, true)
                require (isClaimed) { "Unable to claim interface $iface" }
            }

            if (usbInterface != null) {
                Log.i(this::class.simpleName, "Connected to endpoint ${endpointData.endpointAddress} on device ${device.deviceName} " +
                        "(${sanitizeUsbText(device.manufacturerName)} ${sanitizeUsbText(device.productName)})")
                isConnected = true

                return startStreaming(device, usbInterface, endpointData)

            } else {
                conn.close()
                throw RuntimeException("Unable to connect to endpointNumber ${endpointData.endpointAddress} on " +
                        "device ${device.deviceName} (${sanitizeUsbText(device.manufacturerName)} ${sanitizeUsbText(device.productName)})")
            }
        }

        return null
    }

    /**
     * Read a UAC2 sample rate over a connection.
     * Note that the control and streaming interfaces must both be claimed before this can succeed.
     */
    private fun getUac2SampleRate(
        conn: UsbDeviceConnection,
        clockId: Int
    ): Int {

        /*
         * This is what audacity does, but doesn't make it into the wire with android.
         * Documentation on UAVv2 is a bit thin unless you pay to join the USB club. Some
         * reverse engineering was needed. Beware that chatgpt has opinions on this, but they
         * are not always right by any means.
         *
         * Important: the interface must be claimed
         *
         * There's a copy of the UAC 2 spec here: https://xn--p8jqu4215bemxd.com/wp-content/uploads/2022/04/Audio20-final.pdf
         */

        // Now that it is claimed, we can try reading the sample rate using the method for UAC2.
        val uac2CurNumBytes = 4     // Must be this value for UAC2.
        val buffer = ByteArray(uac2CurNumBytes)   // Must be 4 for UAC2
        val usbRecipientInterface =
            1   // The get request is directed to an interface (rather than an endpoint or device) - not the iface number.
        val csSamFreqControl = 1    // See A.17.1
        val classGetCur = 1

        val result = conn.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or usbRecipientInterface,  // 0xA1
            classGetCur,                // 0x01
            csSamFreqControl shl 8,     // 0x0100,
            clockId shl 8,              // 1024
            buffer,
            buffer.size,                // 4
            1000
        )

        require(result == uac2CurNumBytes) { "Unable to read the sample rate from a UAC2 USB device." }

        val sampleRate = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).int
        Log.i("USB", "UAC2 sample rate is $sampleRate Hz")

        return sampleRate
    }

    /**
     * Find the UsbInterface class relating to the endpoint we intend to use.
     */
    private fun getUsbInterfaceObject(device: UsbDevice, endpoints: List<EndpointData>, endpointNumber: Int): UsbInterface? {
        for (item in endpoints) {
            if (item.endpointAddress == endpointNumber) {
                val usbConfiguration = device.getConfiguration(item.configIndex)
                for (i in 0 until usbConfiguration.interfaceCount) {
                    val usbInterface = usbConfiguration.getInterface(i)
                    if (usbInterface.id == item.interfaceNumber && usbInterface.alternateSetting == item.alternateSetting)
                        return usbInterface
                }
            }
        }

        return null
    }

    @Parcelize
    data class EndpointData(
        val usbDevice: UsbDevice,
        val configIndex: Int = 0,           // Note: this is the index, NOT the configValue as supplied to SET CONFIGURATION.
        val configValue: Int = 0,           // This is the value supplied to SET CONFIGURATION.
        val interfaceNumber: Int = IFACE_UNDEFINED, // Value to be provided via SET INTERFACE. It is in fact the index within the configuration.
        val alternateSetting: Int = 0,
        val endpointAddress: Int = 0,       // Note: address excluding bit 7, not index
        val uac1SampleRate: Pair<Int, Boolean>?,
        val uac2ClockId: Int?,
        val numChannels: Int = Int.MIN_VALUE,
        val bitResolution: Int = Int.MIN_VALUE,
        // val interfacesToClaim: List<Int>,
        val packetSize: Int = 0

        ) : Comparable<EndpointData>, Parcelable {

        override fun compareTo(other: EndpointData): Int = compareValuesBy(this, other,
            { it.usbDevice.deviceId },
            { it.configIndex },
            { it.interfaceNumber },
            { it.alternateSetting },
            { it.endpointAddress }
        )

        override fun toString(): String {
            val type = when (numChannels) {
                1 -> "mono"
                2 -> "stereo"
                else -> "$numChannels channels "
            }
            return "Endpoint $endpointAddress: $type $bitResolution bits"
            // ($deviceId.$configurationId.$ifaceId.$alternateSetting.$endpointAddress)
        }

        companion object {
            val IFACE_UNDEFINED: Int = 0xFFFF
        }
    }

    /**
     * Call this method if something went wrong. We log it and signal it back to the caller
     * asynchronously.
     */
    private fun handleException(e: Exception) {
        scope.launch(context = Dispatchers.Default) {
            withContext(Dispatchers.Default) {
                // Log details:
                val stackTraceElement = e.stackTrace.firstOrNull()
                val message = if (stackTraceElement != null) {
                    "Error: ${e.localizedMessage ?: "Unknown error"}\n" +
                            "Module: ${stackTraceElement.fileName}\n" +
                            "Line: ${stackTraceElement.lineNumber}"
                } else {
                    "Error: ${e.localizedMessage ?: "Unknown error"}"
                }

                Log.w(
                    this::class.simpleName,
                    "Exception when connecting to USB device: $message"
                )
                Log.d(
                    this::class.simpleName,
                    "Exception when connecting to USB device: ${e.stackTrace.asList()}"
                )
                diagnosticLogger.log {
                    "Exception when connecting to USB device: $message"
                }

                usbConnectChannel.send(UsbConnectResult(false, errorMessage = e.message))
            }
        }
    }

    @Parcelize
    data class DeviceData(
        // UI display name of the device:
        val deviceName: String,
        val deviceId: Int,
        val sampleRate: Int = Int.MIN_VALUE,
        val numChannels: Int = Int.MIN_VALUE,
        val bitResolution: Int = Int.MIN_VALUE,

        ) : Comparable<DeviceData>, Parcelable {

        override fun compareTo(other: DeviceData): Int = compareValuesBy(this, other,
            { it.deviceId }
        )

        override fun toString(): String {
            return deviceName
        }

    }

     // LiveData used to communicate connection state changes back to the UI:
    private var isConnected: Boolean = true

    enum class RecordingState {
        OFF,
        ON
    }

    // LiveData used to communicate recording state changes back to the UI:
    private val ldRecordingState = MutableLiveData(RecordingState.OFF)
    suspend fun getRecordingState() : LiveData<RecordingState> {
        mutex.withLock {
            return ldRecordingState
        }
    }

    enum class AudioState {
        OFF,
        ON
    }

    enum class AudioMode(val value: Int) {
        NONE(0),                    // !!!
        DIRECT(1),                  // !!! Don't change these values, they match an enum in native code.
        HETERODYNE(2),              // !!!
        FREQUENCY_DIVISION(3)       // !!!
    }

    // LiveData used to communicate audio state changes back to the UI:
    /*
    private val ldAudioState = MutableLiveData<Pair<AudioState, Int>>(Pair(AudioState.OFF, AudioMode.NONE.value))
    suspend fun getAudioState() : MutableLiveData<Pair<AudioState, Int>> {
        mutex.withLock {
            return ldAudioState
        }
    }
     */

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager;

    /*
    * The sequence of descriptors we are looking for. We could make this more table driven
    * with flags and parameters to control each state, and maybe a lambda.
    */
    private enum class DescriptorParserState {
        EXPECTING_CONFIG,
        EXPECTING_SUITABLEINTERFACE,
        EXPECTING_ASGENERALINTERFACE,
        EXPECTING_AS10FORMATI,
        EXPECTING_AS20FORMATI,
        EXPECTING_ENDPOINT,
        EXPECTING_ASENDPOINT
    }

    private fun parseEndpointsFromDescriptor(device: UsbDevice, rawDescriptors: ByteArray): List<EndpointData> {

        var candidateEndpoints = mutableListOf<EndpointData>()

        var parser = UsbDescriptorParser("", rawDescriptors)

        /*
            parser.descriptors now contains the descriptors in order.

            We need to step through the list to find endpoints like this (UAC1 example):

            config descriptor
                ... other descriptors here are ignored
                ... including audio streaming interfaces with zero endpoints
                interface type 1, 2     Audio, streaming
                interface type 36, 1    ?, AS general
                    wFormatTag = 1 (PCM)
                interface type 36, 2    ?, format type
                    bFormatType = 1
                    bNrChannels = 1 or 2
                    bsubframe size = 2 (bytes)
                    bBitresolution = 16 or whatever
                    sample frequency and type = whatever
                endpoint type 5
                    bEndpointAddress must be 0x8x for input
                    attributes: isochronous
                    note packet size
                audio streaming endpoint type 37,1
                // St this point we might get another AS interface, or a new config.
         */

        var state: DescriptorParserState = DescriptorParserState.EXPECTING_CONFIG

        var uac1SampleRate: Pair<Int, Boolean>? = null
        var numChannels: Int = 0
        var bitResolution: Int = 0
        var configIndex: Int = 0
        var configValue: Int = 0
        var configCounter: Int = 0
        var interfaceNumber: Int = 0
        var alternateSetting: Int = 0
        var endpointAddress: Int = 0
        var packetSize: Int = 0
        var uac2ClockId: Int? = null
        // var interfacesToClaim = mutableListOf<Int>()
        var currentlyParsingInterface: Int? = null

        for (desc in parser.descriptors) {

            // Restart any time we see a new config descriptor:
            if (desc is UsbConfigDescriptor) {
                state = DescriptorParserState.EXPECTING_CONFIG
                // XXX Really we should also result many of the variables declared above.
                //  But who has more than one configuration in their device?
            }

            do {
                var consumed = true
                when (state) {
                    DescriptorParserState.EXPECTING_CONFIG -> {
                        if (desc is UsbConfigDescriptor) {
                            var d = desc as UsbConfigDescriptor
                            configIndex = configCounter
                            configCounter += 1
                            configValue = d.configValue
                            state = DescriptorParserState.EXPECTING_SUITABLEINTERFACE
                        }
                        // else stay in this state until we see one.
                    }

                    DescriptorParserState.EXPECTING_SUITABLEINTERFACE -> {
                        /*
                            Keep looping until we see an audio streaming interface
                            with >0 endpoints, or maybe a new config descriptor.
                            Skip over other stuff, such as terminal descriptors.

                            Note things from other descriptors we encounter while we are
                            waiting.
                         */

                        try {
                            val d = desc as UsbInterfaceDescriptor
                            if (d.type == 4.toByte()) {
                                currentlyParsingInterface = d.interfaceNumber
                            }
                        }
                        catch (e: ClassCastException) {
                        }

                        try {
                            val d = desc as UsbACInterfaceUnparsed
                            // Is it a clock source descriptor? There is no android class for this at present,
                            // so we are reduced to extracting values from the raw data:
                            if (d.type == 36.toByte() && d.subtype == 10.toByte()) {
                                val rawData = d.rawData
                                // 4,1,1,1,0 one byte each:
                                val bClockId = rawData[0]
                                val bmAttributes = rawData[1]
                                val bmControls = rawData[2]
                                val bAssocTerminal = rawData[3]
                                val iClockSource = rawData[4]
                                if (bmAttributes == 1.toByte() && bmControls == 1.toByte()) {
                                    // Yes - this interface allows us to ask for the sample rate.
                                    uac2ClockId = bClockId.toInt()
                                    // currentlyParsingInterface?.let { interfacesToClaim.add(currentlyParsingInterface) }
                                }
                            }
                        }
                        catch (e: ClassCastException) {
                        }

                        if (desc is UsbInterfaceDescriptor) {
                            var d = desc as UsbInterfaceDescriptor
                            if (d.numEndpoints > 0) {
                                interfaceNumber = d.interfaceNumber     // Int, index of the interface in the config.
                                alternateSetting = d.alternateSetting.toInt()   // Byte
                                state = DescriptorParserState.EXPECTING_ASGENERALINTERFACE;
                            }
                        }
                    }

                    DescriptorParserState.EXPECTING_ASGENERALINTERFACE -> {
                        // If no match try for another interface.
                        state = DescriptorParserState.EXPECTING_SUITABLEINTERFACE
                        consumed = false

                        if (desc is Usb10ASGeneral) {
                            var d = desc as Usb10ASGeneral
                            if (d.formatTag == 1) {  // PCM
                                state = DescriptorParserState.EXPECTING_AS10FORMATI;
                                consumed = true
                            }
                        }
                        else if (desc is Usb20ASGeneral) {
                            var d = desc as Usb20ASGeneral
                            if (d.formatType == 1.toByte()  // PCM
                                && d.formats == 1           // Signed Linear PCM
                                && d.numChannels == 1.toByte())      // Mono only
                            {
                                state = DescriptorParserState.EXPECTING_AS20FORMATI;
                                consumed = true
                            }
                        }
                    }

                    DescriptorParserState.EXPECTING_AS10FORMATI -> {
                        // If no match try for another interface.
                        state = DescriptorParserState.EXPECTING_SUITABLEINTERFACE
                        consumed = false

                        if (desc is Usb10ASFormatI) {
                            var d = desc as Usb10ASFormatI
                            if (d.sampleFreqType >= 1.toByte()          // Discrete, seems to be the number of frequencies supported.
                                //&& d.subframeSize == (d.numChannels * 2).toByte()  // Bytes per data point - for now only 16 bit.
                                && d.formatType == 1.toByte()           // Format type 1. Duh.
                                && d.bitResolution == 16.toByte()       // For now only 16 bit.
                                && (d.numChannels == 1.toByte() || d.numChannels == 2.toByte()) // Support mono or stereo.
                            ) {
                                // Note the frequency:
                                uac1SampleRate = selectSampleRate(d.sampleRates)

                                if (uac1SampleRate.first > 0) {
                                    numChannels = d.numChannels.toInt()
                                    bitResolution = d.bitResolution.toInt()
                                    //Log.i(TAG, "Usb10ASFormatI: rate = $sampleRate channels = $numChannels")
                                    state = DescriptorParserState.EXPECTING_ENDPOINT
                                    consumed = true
                                }
                            }
                        }
                    }

                    DescriptorParserState.EXPECTING_AS20FORMATI -> {
                        // If no match try for another interface.
                        state = DescriptorParserState.EXPECTING_SUITABLEINTERFACE
                        consumed = false

                        if (desc is Usb20ASFormatI) {
                            var d = desc as Usb20ASFormatI
                            if (d.formatType == 1.toByte()           // Format type 1. Duh.
                                && d.bitResolution == 16.toByte()       // For now only 16 bit.
                                && d.subSlotSize == 2.toByte()          // Support mono only.
                            ) {
                                // Unfortunately the sampling rate isn't available here. For
                                // UAC2 we have to jump through some hoops instead.
                                numChannels = ((d.subSlotSize * 8) / d.bitResolution).toInt()
                                bitResolution = d.bitResolution.toInt()
                                //Log.i(TAG, "Usb10ASFormatI: rate = $sampleRate channels = $numChannels")
                                state = DescriptorParserState.EXPECTING_ENDPOINT
                                consumed = true
                            }
                        }
                    }

                    DescriptorParserState.EXPECTING_ENDPOINT -> {
                        // If no match try for another interface.
                        state = DescriptorParserState.EXPECTING_SUITABLEINTERFACE
                        consumed = false

                        if (desc is UsbEndpointDescriptor) {
                            var d = desc as UsbEndpointDescriptor
                            if (d.direction == UsbEndpointDescriptor.DIRECTION_INPUT
                                && (d.attributes and 3) == UsbEndpointDescriptor.TRANSTYPE_ISO        // For now any kind of isochronous.
                            ) {
                                endpointAddress = d.endpointAddress // Int
                                packetSize = d.getPacketSize()
                                //Log.i(TAG, "endpoint address is $endpointAddress")
                                state = DescriptorParserState.EXPECTING_ASENDPOINT
                                consumed = true
                            }
                        }
                    }

                    DescriptorParserState.EXPECTING_ASENDPOINT -> {
                        // If no match try for another interface.
                        state = DescriptorParserState.EXPECTING_SUITABLEINTERFACE
                        consumed = false

                        if (desc is UsbACAudioStreamEndpoint) {
                            var d = desc as UsbACAudioStreamEndpoint

                            // currentlyParsingInterface?.let { interfacesToClaim.add(it) }

                            // Success - we have found a suitable audio streaming endpoint:
                            var details = EndpointData(
                                device,
                                configIndex,
                                configValue,
                                interfaceNumber,
                                alternateSetting,
                                endpointAddress,
                                uac1SampleRate,
                                uac2ClockId,
                                numChannels,
                                bitResolution,
                                // interfacesToClaim,
                                packetSize,
                            )

                            candidateEndpoints.add(details)

                            Log.i(
                                this::class.simpleName,
                                "Suitable audio streaming endpoint found: $details"
                            )
                            state = DescriptorParserState.EXPECTING_CONFIG    // See if there is another config.
                            consumed = true
                        }
                    }
                }
            } while(!consumed)
        }

        return candidateEndpoints
    }

    private fun selectSampleRate(sampleRates: IntArray?): Pair<Int, Boolean> {

        var rate = -1
        var shouldSet = false

        // Select the fastest sample rate that is a multiple of 48 kHz, or -1 if none.
        sampleRates?. let {
            // Only set the sampling rate back to the device it if supports more than one:
            shouldSet = sampleRates.size > 1

            // The list is most likely short, just walk through it in order to find the maximum:
            for (v in it)
                rate = maxOf(v, rate)
        }
        // return Pair(44100, shouldSet)   // TODO unhack this
        // return Pair(48000, shouldSet)   // TODO unhack this
        return Pair(rate, shouldSet)
    }

    private fun startStreaming(
        device: UsbDevice,
        usbInterface: UsbInterface,
        endpointData: EndpointData
    ): Int? {
        connection?.let { conn ->

            // Do as much setup as we can here via standard Android objects:

            val fd: Int = conn.fileDescriptor

            // Set the configuration. This seems to do nothing on the wire. Maybe if there is only
            // one configuration it doesn't do anything?
            val config = device.getConfiguration(endpointData.configIndex)
            var ok = conn.setConfiguration(config)    // Why does this fail? Is it lack of support in the device itself, that we attempt but ignore?

            ok = conn.setInterface(usbInterface)      // Sets interface id and alt number.
            if (!ok) {
                Log.e(this::class.simpleName, "setInterface failed: $usbInterface")
            } else {
                require(endpointData.uac1SampleRate != null || endpointData.uac2ClockId != null) {
                    "The USB device must be either UAC1, or UAC2 with a fixed readable sampling rate."
                }

                var actualSampleRate = 0
                endpointData.uac1SampleRate?.let { rate ->
                    actualSampleRate = setEndpointSamplingRate(endpointData.uac1SampleRate.first, conn, endpointData.endpointAddress)
                }
                endpointData.uac2ClockId?.let { id ->
                    actualSampleRate = getUac2SampleRate(conn, endpointData.uac2ClockId)
                }

                val range = 1..2
                require(endpointData.numChannels in range) {
                    "The number of channels must be in the range $range"
                }
                require(actualSampleRate >= MIN_SAMPLING_RATE && actualSampleRate <= MAX_SAMPLING_RATE) {
                    "Sampling rate of $actualSampleRate must be in the range $MIN_SAMPLING_RATE..$MAX_SAMPLING_RATE"
                }

                // Run the audio streaming in a thread so the UI can remain responsive:
                streamingThread = Thread( {

                    val errno = nativeUsb.stream(fd, endpointData.configValue, usbInterface.id, usbInterface.alternateSetting,
                        endpointData.endpointAddress, endpointData.numChannels, actualSampleRate, endpointData.packetSize)

                    // We get here if we requested the thread to shut down, or if an error
                    // occurred in the thread. In the latter case, the returned value is errno.

                    if (errno != 0) {
                        usbErrorChannel.trySend(UsbErrorResult(errno))
                    }

                    Log.i(logTag, "USB streaming thread existing with errno = $errno")
                }, "data streaming")
                streamingThread?.start()
                return actualSampleRate
            }
        }
        return null
    }

    private fun setEndpointSamplingRate(
        sampleRate: Int,
        it: UsbDeviceConnection,
        endpointNumber: Int
    ) : Int {

        var actualSampleRate = sampleRate

        // Write the sampling rate to the endpoint via the control interface.
        // See https://www.beyondlogic.org/usbnutshell/usb6.shtml#StandardEndpointRequests.
        // See https://www.usb.org/sites/default/files/audio10.pdf section 5.2.3.2.3

        var buffer = byteArrayOf(
            (sampleRate % 256).toByte(),
            (sampleRate.shr(8) % 256).toByte(),
            (sampleRate.shr(16) % 256).toByte()
        )
        val timeout = 500

        val endpointRequestTypeH2D = 0x22 // 0010 0010: Type 2 (endpoint), host to device.
        val setCurRequest = 1
        val samplingFrequencyControl = 1
        var rc = it.controlTransfer(
            endpointRequestTypeH2D, setCurRequest, samplingFrequencyControl.shl(8),
            endpointNumber or 0x80, buffer, buffer.size, timeout
        )

        val endpointRequestTypeD2H = 0xA2 // 10100010: Type 2 (endpoint), device to host
        // Read the frequency back again to see what we actually got:
        val getCurRequest = 0x81
        rc = it.controlTransfer(
            endpointRequestTypeD2H, getCurRequest, samplingFrequencyControl.shl(8),
            endpointNumber or 0x80, buffer, buffer.size, timeout
        )

        if (rc > 0 && buffer.size == 3) {
            actualSampleRate = buffer[0].toUByte().toInt() + buffer[1].toUByte().toInt() * 256 + buffer[2].toUByte().toInt() * 256 * 256
        }

        Log.i(this::class.simpleName, "Actual sampling rate is $actualSampleRate Hz")

        return actualSampleRate

        // Copied from wireshark for the lapel microphone. This fails here, but succeeds in wireshark.
        /*
        var buffer1 = byteArrayOf(0xf, 0)
        rc = it.controlTransfer(0x21, 1, 0x0200,0x0a00, buffer1, buffer1.size, timeout)
        */
    }

    private fun getAudioOutputDevices (): Array<AudioDeviceInfo> {

        val devices = audioManager.getDevices(GET_DEVICES_OUTPUTS)
        for (device in devices) {
            Log.i(this::class.simpleName, "Output device id ${device.id}: ${device.productName}")
        }
        return devices
    }

    fun internalDisconnect() {
        if (isConnected) {

            // These does nothing if the activity wasn't in progress:
            internalStopRecording()
            internalStopAudio()

            // Flag to the worker thread to stop audio streaming and exit:
            nativeUsb.cancelStream()

            // Wait for the thread to finish:
            streamingThread?.let {
                val threadShutdownTimeoutMs: Long = 1000
                it.join(threadShutdownTimeoutMs)
                streamingThread = null
            }

            Log.i(this::class.simpleName, "disconnecting")
            connection?.close()
            connection = null
        }
    }

    suspend fun disconnect() {
        mutex.withLock {
            internalDisconnect()
        }
    }

    suspend fun startRecording(fd: Int) {
        mutex.withLock {
            if (isConnected && ldRecordingState.value == RecordingState.OFF) {
                Log.i(this::class.simpleName, "startRecording: $fd")
                if (nativeUsb.startRecordingFd(fd))
                    ldRecordingState.value = RecordingState.ON
            }
        }
    }

    private fun internalStopRecording() {
        if (ldRecordingState.value == RecordingState.ON) {
            Log.i(this::class.simpleName, "stopRecording")
            nativeUsb.stopRecording()
            ldRecordingState.value = RecordingState.OFF
        }
    }

    suspend fun stopRecording() {
        mutex.withLock {
            internalStopRecording()
        }
    }

    suspend fun startAudio(heterodyne1kHz: Int, heterodyne2kHz: Int?, audioBoostFactor: Int) {
        mutex.withLock {
            if (isConnected) {
                // It's OK to proceed if audio is already running - that's how we change the audio mode.
                Log.i(this::class.simpleName, "startAudio: heterodynekHz = $heterodyne1kHz")
                val AAUDIO_UNSPECIFIED = 0  // As defined in aaudio and the native layer.
                // Unspecified audio device means that Android chooses one for us, which is
                // typically the right choice:
                val outputDeviceId = AAUDIO_UNSPECIFIED
                if (nativeUsb.startAudio(outputDeviceId, heterodyne1kHz,
                        heterodyne2kHz ?: 0, audioBoostFactor)) {
                    // ldAudioState.value = Pair(AudioState.ON, mode)
                }
            }
        }
    }

    private fun internalStopAudio() {
        Log.i(this::class.simpleName, "internalStopAudio")
        nativeUsb.stopAudio()
    }

     suspend fun stopAudio() {
        mutex.withLock {
            internalStopAudio()
        }
    }

    suspend fun setHeterodyne(heterodyne1kHz: Int, heterodyne2kHz: Int?) {
        mutex.withLock {
            nativeUsb.setHeterodyne(heterodyne1kHz, heterodyne2kHz ?: 0)
        }
    }

    suspend fun resume() {
        mutex.withLock {
            nativeUsb.resumeStream()
        }
    }

    suspend fun pause() {
        mutex.withLock {
            nativeUsb.pauseStream()
        }
    }
}