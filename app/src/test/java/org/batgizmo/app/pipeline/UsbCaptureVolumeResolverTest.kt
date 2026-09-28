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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UsbCaptureVolumeResolverTest {

    @Test
    fun parseFeatureUnit_perChannelVolumeNoMaster() {
        // FU 2 from the 48 kHz stereo headset dump: master none, L/R volume.
        val unit = UsbCaptureVolumeResolver.parseFeatureUnit(
            byteArrayOf(0x02, 0x01, 0x01, 0x00, 0x02, 0x02, 0x00)
        )
        assertNotNull(unit)
        assertEquals(2, unit!!.id)
        assertEquals(1, unit.sourceId)
        assertArrayEquals(intArrayOf(0x00, 0x02, 0x02), unit.channelControls)
        assertArrayEquals(intArrayOf(1, 2), UsbCaptureVolumeResolver.volumeChannels(unit))
        assertArrayEquals(intArrayOf(), UsbCaptureVolumeResolver.muteChannels(unit))
    }

    @Test
    fun parseFeatureUnit_masterMuteAndVolume() {
        // FU 5: master mute+volume, no per-channel controls.
        val unit = UsbCaptureVolumeResolver.parseFeatureUnit(
            byteArrayOf(0x05, 0x04, 0x01, 0x03, 0x00, 0x00, 0x00)
        )
        assertNotNull(unit)
        assertEquals(5, unit!!.id)
        assertArrayEquals(intArrayOf(0), UsbCaptureVolumeResolver.volumeChannels(unit))
        assertArrayEquals(intArrayOf(0), UsbCaptureVolumeResolver.muteChannels(unit))
    }

    @Test
    fun resolve_usesMicrophoneFeatureUnitNotHeadphones() {
        val topology = headsetTopology()
        val volume = UsbCaptureVolumeResolver.resolve(terminalLink = 3, topology)
        assertNotNull(volume)
        assertEquals(2, volume!!.featureUnitId)
        assertEquals(1, volume.audioControlInterfaceNumber)
        assertArrayEquals(intArrayOf(1, 2), volume.volumeChannels)
        assertArrayEquals(intArrayOf(), volume.unmuteChannels)
    }

    @Test
    fun resolve_playbackTerminalLinkIsIgnored() {
        val topology = headsetTopology()
        // Streaming playback links to USB IT 4, not a microphone.
        assertNull(UsbCaptureVolumeResolver.resolve(terminalLink = 4, topology))
        // Headphones OT walks to USB streaming input, not a mic.
        assertNull(UsbCaptureVolumeResolver.resolve(terminalLink = 6, topology))
    }

    @Test
    fun analogInputTerminalIsTreatedAsCapture() {
        val topology = UsbAcTopology().apply {
            audioControlInterfaceNumber = 0
            inputs[1] = UsbAcInputTerminal(1, UsbTerminalTypes.TERMINAL_EXTERN_ANALOG)
            features[2] = UsbAcFeatureUnit(2, 1, intArrayOf(0x02))
            outputs[3] = UsbAcOutputTerminal(3, UsbTerminalTypes.TERMINAL_USB_STREAMING, 2)
        }
        val volume = UsbCaptureVolumeResolver.resolve(3, topology)
        assertNotNull(volume)
        assertEquals(2, volume!!.featureUnitId)
        assertArrayEquals(intArrayOf(0), volume.volumeChannels)
    }

    @Test
    fun parseFeatureUnit_batgizmoTwoByteControlSize() {
        // FU 2 from BatGizmo: bControlSize=2, master and ch1 mute+volume (LE 0x0003).
        val unit = UsbCaptureVolumeResolver.parseFeatureUnit(
            byteArrayOf(0x02, 0x01, 0x02, 0x03, 0x00, 0x03, 0x00, 0x00)
        )
        assertNotNull(unit)
        assertEquals(2, unit!!.id)
        assertEquals(1, unit.sourceId)
        assertArrayEquals(intArrayOf(0x0003, 0x0003), unit.channelControls)
        assertArrayEquals(intArrayOf(0), UsbCaptureVolumeResolver.volumeChannels(unit))
        assertArrayEquals(intArrayOf(0, 1), UsbCaptureVolumeResolver.muteChannels(unit))
    }

    @Test
    fun resolve_batgizmoCapturePath() {
        val topology = batgizmoTopology()
        val volume = UsbCaptureVolumeResolver.resolve(terminalLink = 3, topology)
        assertNotNull(volume)
        assertEquals(2, volume!!.featureUnitId)
        assertEquals(0, volume.audioControlInterfaceNumber)
        assertArrayEquals(intArrayOf(0), volume.volumeChannels)
        assertArrayEquals(intArrayOf(0, 1), volume.unmuteChannels)
    }

    @Test
    fun parseFeatureUnit_emt2MasterOnly() {
        // FU 6: 8-byte unit, master mute+volume only (no per-channel bitmap).
        val unit = UsbCaptureVolumeResolver.parseFeatureUnit(
            byteArrayOf(0x06, 0x04, 0x01, 0x03, 0x00)
        )
        assertNotNull(unit)
        assertEquals(6, unit!!.id)
        assertEquals(4, unit.sourceId)
        assertArrayEquals(intArrayOf(0x03), unit.channelControls)
        assertArrayEquals(intArrayOf(0), UsbCaptureVolumeResolver.volumeChannels(unit))
        assertArrayEquals(intArrayOf(0), UsbCaptureVolumeResolver.muteChannels(unit))
    }

    @Test
    fun resolve_emt2CapturePath() {
        val topology = emt2Topology()
        val volume = UsbCaptureVolumeResolver.resolve(terminalLink = 5, topology)
        assertNotNull(volume)
        assertEquals(6, volume!!.featureUnitId)
        assertEquals(1, volume.audioControlInterfaceNumber)
        assertArrayEquals(intArrayOf(0), volume.volumeChannels)
        assertArrayEquals(intArrayOf(0), volume.unmuteChannels)
    }

    private fun headsetTopology(): UsbAcTopology {
        return UsbAcTopology().apply {
            audioControlInterfaceNumber = 1
            inputs[1] = UsbAcInputTerminal(1, UsbTerminalTypes.TERMINAL_IN_DESKTOP_MIC)
            features[2] = UsbAcFeatureUnit(2, 1, intArrayOf(0x00, 0x02, 0x02))
            outputs[3] = UsbAcOutputTerminal(3, UsbTerminalTypes.TERMINAL_USB_STREAMING, 2)
            inputs[4] = UsbAcInputTerminal(4, UsbTerminalTypes.TERMINAL_USB_STREAMING)
            features[5] = UsbAcFeatureUnit(5, 4, intArrayOf(0x03, 0x00, 0x00))
            outputs[6] = UsbAcOutputTerminal(6, UsbTerminalTypes.TERMINAL_OUT_HEADPHONES, 5)
        }
    }

    /** VID 1209 / PID 077c: UAC1 mono 384 kHz, Mic IT1 → FU2 → USB OT3. */
    private fun batgizmoTopology(): UsbAcTopology {
        val fu2 = UsbCaptureVolumeResolver.parseFeatureUnit(
            byteArrayOf(0x02, 0x01, 0x02, 0x03, 0x00, 0x03, 0x00, 0x00)
        )!!
        return UsbAcTopology().apply {
            audioControlInterfaceNumber = 0
            inputs[1] = UsbAcInputTerminal(1, UsbTerminalTypes.TERMINAL_IN_MIC)
            features[2] = fu2
            outputs[3] = UsbAcOutputTerminal(3, UsbTerminalTypes.TERMINAL_USB_STREAMING, 2)
        }
    }

    /** VID 2926 / PID 4544: Mic IT4 → FU6 (master mute+vol) → USB OT5. */
    private fun emt2Topology(): UsbAcTopology {
        val fu6 = UsbCaptureVolumeResolver.parseFeatureUnit(
            byteArrayOf(0x06, 0x04, 0x01, 0x03, 0x00)
        )!!
        return UsbAcTopology().apply {
            audioControlInterfaceNumber = 1
            inputs[4] = UsbAcInputTerminal(4, UsbTerminalTypes.TERMINAL_IN_MIC)
            features[6] = fu6
            outputs[5] = UsbAcOutputTerminal(5, UsbTerminalTypes.TERMINAL_USB_STREAMING, 6)
        }
    }

    @Test
    fun parseFeatureUnit_petterssonMasterVolumeNoMute() {
        // FU 2: bControlSize=1, master Volume only (0x02).
        val unit = UsbCaptureVolumeResolver.parseFeatureUnit(
            byteArrayOf(0x02, 0x01, 0x01, 0x02, 0x00, 0x00)
        )
        assertNotNull(unit)
        assertEquals(2, unit!!.id)
        assertEquals(1, unit.sourceId)
        assertArrayEquals(intArrayOf(0x02, 0x00), unit.channelControls)
        assertArrayEquals(intArrayOf(0), UsbCaptureVolumeResolver.volumeChannels(unit))
        assertArrayEquals(intArrayOf(), UsbCaptureVolumeResolver.muteChannels(unit))
    }

    @Test
    fun resolve_petterssonCapturePath() {
        val topology = petterssonTopology()
        val volume = UsbCaptureVolumeResolver.resolve(terminalLink = 3, topology)
        assertNotNull(volume)
        assertEquals(2, volume!!.featureUnitId)
        assertEquals(0, volume.audioControlInterfaceNumber)
        assertArrayEquals(intArrayOf(0), volume.volumeChannels)
        assertArrayEquals(intArrayOf(), volume.unmuteChannels)
    }

    /** VID 287D / PID 0405: Mic IT1 → FU2 (master volume) → USB OT3. */
    private fun petterssonTopology(): UsbAcTopology {
        val fu2 = UsbCaptureVolumeResolver.parseFeatureUnit(
            byteArrayOf(0x02, 0x01, 0x01, 0x02, 0x00, 0x00)
        )!!
        return UsbAcTopology().apply {
            audioControlInterfaceNumber = 0
            inputs[1] = UsbAcInputTerminal(1, UsbTerminalTypes.TERMINAL_IN_MIC)
            features[2] = fu2
            outputs[3] = UsbAcOutputTerminal(3, UsbTerminalTypes.TERMINAL_USB_STREAMING, 2)
        }
    }

    @Test
    fun resolve_petterssonM500HasNoFeatureUnit() {
        val topology = petterssonM500Topology()
        assertNull(UsbCaptureVolumeResolver.resolve(terminalLink = 2, topology))
    }

    /** VID 287D / PID 0250: Mic IT1 → USB OT2 with no Feature Unit. */
    private fun petterssonM500Topology(): UsbAcTopology {
        return UsbAcTopology().apply {
            audioControlInterfaceNumber = 0
            inputs[1] = UsbAcInputTerminal(1, UsbTerminalTypes.TERMINAL_IN_MIC)
            outputs[2] = UsbAcOutputTerminal(2, UsbTerminalTypes.TERMINAL_USB_STREAMING, 1)
        }
    }
}
