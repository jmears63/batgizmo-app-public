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

package org.batgizmo.app.pipeline

import com.android.server.usb.descriptors.UsbTerminalTypes

/**
 * UAC1 capture (microphone) volume control resolved from the Audio Control graph.
 *
 * [volumeChannels] are Feature Unit channel indexes for GET/SET CUR Volume
 * (0 = master, 1 = left, …). [unmuteChannels] are indexes that advertise Mute
 * on the same unit (recorded for diagnostics; not written on connect).
 */
internal data class UsbCaptureVolume(
    val featureUnitId: Int,
    val audioControlInterfaceNumber: Int,
    val volumeChannels: IntArray,
    val unmuteChannels: IntArray,
)

internal data class UsbAcInputTerminal(
    val id: Int,
    val terminalType: Int,
)

internal data class UsbAcOutputTerminal(
    val id: Int,
    val terminalType: Int,
    val sourceId: Int,
)

internal data class UsbAcFeatureUnit(
    val id: Int,
    val sourceId: Int,
    val channelControls: IntArray,
)

internal data class UsbAcSelectorUnit(
    val id: Int,
    val sourceIds: IntArray,
)

internal class UsbAcTopology {
    val inputs = mutableMapOf<Int, UsbAcInputTerminal>()
    val outputs = mutableMapOf<Int, UsbAcOutputTerminal>()
    val features = mutableMapOf<Int, UsbAcFeatureUnit>()
    val selectors = mutableMapOf<Int, UsbAcSelectorUnit>()
    var audioControlInterfaceNumber: Int? = null

    fun clear() {
        inputs.clear()
        outputs.clear()
        features.clear()
        selectors.clear()
        audioControlInterfaceNumber = null
    }
}

internal object UsbCaptureVolumeResolver {
    const val MUTE_BIT = 0x0001
    const val VOLUME_BIT = 0x0002

    /**
     * UAC1 Feature Unit payload after bLength, bDescriptorType, bDescriptorSubtype:
     * bUnitID, bSourceID, bControlSize, bmaControls[0..n], iFeature.
     */
    fun parseFeatureUnit(rawData: ByteArray?): UsbAcFeatureUnit? {
        if (rawData == null || rawData.size < 4)
            return null
        val unitId = rawData[0].toInt() and 0xFF
        val sourceId = rawData[1].toInt() and 0xFF
        val controlSize = rawData[2].toInt() and 0xFF
        if (controlSize < 1 || controlSize > 4)
            return null
        val afterHeader = rawData.size - 3
        if (afterHeader < controlSize + 1)
            return null
        val controlsBytes = afterHeader - 1
        if (controlsBytes % controlSize != 0)
            return null
        val n = controlsBytes / controlSize
        val controls = IntArray(n)
        var off = 3
        for (i in 0 until n) {
            var value = 0
            for (b in 0 until controlSize) {
                value = value or ((rawData[off].toInt() and 0xFF) shl (8 * b))
                off++
            }
            controls[i] = value
        }
        return UsbAcFeatureUnit(unitId, sourceId, controls)
    }

    fun volumeChannels(unit: UsbAcFeatureUnit): IntArray =
        channelsWithBit(unit, VOLUME_BIT)

    fun muteChannels(unit: UsbAcFeatureUnit): IntArray =
        unit.channelControls.indices
            .filter { unit.channelControls[it] and MUTE_BIT != 0 }
            .toIntArray()

    /**
     * Walk from a streaming [terminalLink] (usually a USB Streaming output terminal)
     * back to a microphone-class input terminal. Use the Feature Unit closest to
     * that microphone that advertises Volume.
     */
    fun resolve(terminalLink: Int, topology: UsbAcTopology): UsbCaptureVolume? {
        val acInterface = topology.audioControlInterfaceNumber ?: return null
        val featureUnitsOnPath = ArrayList<UsbAcFeatureUnit>()
        val seen = HashSet<Int>()
        var id: Int? = terminalLink
        while (id != null && seen.add(id)) {
            val input = topology.inputs[id]
            val output = topology.outputs[id]
            val feature = topology.features[id]
            val selector = topology.selectors[id]
            when {
                input != null -> {
                    if (!isCaptureInputTerminal(input.terminalType))
                        return null
                    val unit = featureUnitsOnPath.lastOrNull { volumeChannels(it).isNotEmpty() }
                        ?: return null
                    return UsbCaptureVolume(
                        featureUnitId = unit.id,
                        audioControlInterfaceNumber = acInterface,
                        volumeChannels = volumeChannels(unit),
                        unmuteChannels = muteChannels(unit),
                    )
                }
                output != null -> id = output.sourceId
                feature != null -> {
                    featureUnitsOnPath.add(feature)
                    id = feature.sourceId
                }
                selector != null -> id = selector.sourceIds.firstOrNull()
                else -> return null
            }
        }
        return null
    }

    fun isCaptureInputTerminal(terminalType: Int): Boolean {
        val high = terminalType and 0xFF00
        return high == UsbTerminalTypes.TERMINAL_IN_UNDEFINED ||
            terminalType == UsbTerminalTypes.TERMINAL_EXTERN_ANALOG
    }

    /**
     * Prefer master (channel 0) when it has Volume; otherwise every logical
     * channel that has Volume (ganged L/R for a single slider).
     */
    private fun channelsWithBit(unit: UsbAcFeatureUnit, bit: Int): IntArray {
        val controls = unit.channelControls
        if (controls.isEmpty())
            return intArrayOf()
        if (controls[0] and bit != 0)
            return intArrayOf(0)
        return controls.indices.drop(1).filter { controls[it] and bit != 0 }.toIntArray()
    }
}
