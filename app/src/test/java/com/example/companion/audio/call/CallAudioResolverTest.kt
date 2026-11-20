package com.example.companion.audio.call

import com.example.companion.audio.diag.Avail
import com.example.companion.audio.diag.Capability
import com.example.companion.audio.diag.Evaluation
import com.example.companion.audio.hfp.Tri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 34A JVM tests of [CallAudioResolver] (pure). WRITTEN BUT NOT RUN: no Gradle / Android SDK / device was available.
 * They say nothing about real cellular audio.
 */
class CallAudioResolverTest {

    private fun stage4(result: String) = Evaluation(
        sources = emptyList(),
        remote = Capability(Avail.UNKNOWN, ""), local = Capability(Avail.UNKNOWN, ""), injection = Capability(Avail.UNKNOWN, ""),
        result = result, resultText = ""
    )

    // ---- default value

    @Test fun defaultDownlinkBridgeIsFalse() {
        // The parameter is omitted: must equal the explicit false call.
        val omitted = CallAudioResolver.resolve(Tri.NO, null, cellularBridgeAvailable = false)
        val explicit = CallAudioResolver.resolve(Tri.NO, null, cellularBridgeAvailable = false, downlinkBridge = false)
        assertEquals(explicit, omitted)
        assertEquals(AudioMode.NONE, omitted.mode)
    }

    // ---- existing behaviour when disabled

    @Test fun disabledNoHfpNoStage4IsNone() {
        val c = CallAudioResolver.resolve(Tri.NO, null, cellularBridgeAvailable = false, downlinkBridge = false)
        assertEquals(AudioMode.NONE, c.mode)
        assertEquals(AudioUnavailableReason.AUDIO_API_UNAVAILABLE, c.reason)
        assertEquals(Tri.NO, c.twoWayCellularAudio)
    }

    @Test fun disabledStage4AWithoutBridgeIsStillNone() {
        // This is what the app passes today (cellularBridgeAvailable = false).
        val c = CallAudioResolver.resolve(Tri.NO, stage4("A"), cellularBridgeAvailable = false, downlinkBridge = false)
        assertEquals(AudioMode.NONE, c.mode)
    }

    @Test fun disabledStage4AWithBridgeKeepsOldCustomTransport() {
        val c = CallAudioResolver.resolve(Tri.NO, stage4("A"), cellularBridgeAvailable = true, downlinkBridge = false)
        assertEquals(AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, c.mode)
        assertEquals(Tri.UNKNOWN, c.twoWayCellularAudio)   // old, unchanged result
    }

    @Test fun disabledHfpYesIsStillHfp() {
        val c = CallAudioResolver.resolve(Tri.YES, null, cellularBridgeAvailable = false, downlinkBridge = false)
        assertEquals(AudioMode.HFP, c.mode)
        assertEquals(Tri.YES, c.twoWayCellularAudio)
    }

    // ---- backend selection when enabled

    @Test fun enabledSelectsCustomBluetoothTransport() {
        val c = CallAudioResolver.resolve(Tri.NO, null, cellularBridgeAvailable = false, downlinkBridge = true)
        assertEquals(AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, c.mode)
        assertNull(c.reason)
    }

    @Test fun enabledDoesNotNeedStage4() {
        listOf(null, stage4("A"), stage4("B")).forEach {
            val c = CallAudioResolver.resolve(Tri.NO, it, cellularBridgeAvailable = false, downlinkBridge = true)
            assertEquals(AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, c.mode)
        }
    }

    @Test fun enabledDetailSaysOneWayAndNoMicrophoneUplink() {
        val c = CallAudioResolver.resolve(Tri.NO, null, cellularBridgeAvailable = false, downlinkBridge = true)
        assertTrue(c.detail.contains("One-way"))
        assertTrue(c.detail.contains("no microphone uplink"))
    }

    @Test fun enabledStillPrefersProvenHfp() {
        val c = CallAudioResolver.resolve(Tri.YES, null, cellularBridgeAvailable = false, downlinkBridge = true)
        assertEquals(AudioMode.HFP, c.mode)
    }

    // ---- twoWayCellularAudio stays NO

    @Test fun twoWayStaysNoWhenEnabled() {
        listOf(null, stage4("A"), stage4("B")).forEach { s ->
            listOf(false, true).forEach { bridge ->
                val c = CallAudioResolver.resolve(Tri.NO, s, cellularBridgeAvailable = bridge, downlinkBridge = true)
                assertEquals("stage4=${s?.result} bridge=$bridge", Tri.NO, c.twoWayCellularAudio)
            }
        }
    }

    @Test fun twoWayIsNoWhenDisabledAndNothingIsAvailable() {
        assertEquals(Tri.NO, CallAudioResolver.resolve(Tri.NO, null, false).twoWayCellularAudio)
    }
}
