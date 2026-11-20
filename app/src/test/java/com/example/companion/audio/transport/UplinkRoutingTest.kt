package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 39 JVM tests of the pure routing/gate helpers and the uplink marker source (written; NOT executed). */
class UplinkRoutingTest {

    private class FakeSource(val startResult: String? = null) : AudioSource {
        override val name = "fake"
        var starts = 0; var stops = 0
        override fun start(config: AudioConfig, listener: AudioSource.Listener): String? { starts++; return startResult }
        override fun stop() { stops++ }
    }
    private object NoListener : AudioSource.Listener {
        override fun onFrame(pcm: ByteArray) {}
        override fun onError(message: String) {}
    }

    // ---- UPLINK frame direction
    @Test fun uplinkIsMainToSim() {
        assertEquals(AudioDirection.MAIN_TO_SIM, FrameDirection.UPLINK.wire)
        assertEquals(AudioDirection.MAIN_TO_SIM, UplinkMicrophoneSource(FakeSource()).direction)
        assertEquals(FrameDirection.UPLINK, UplinkMicrophoneSource(FakeSource()).direction.frameDirection)
    }

    @Test fun uplinkStreamNeedsTheArmedFlagAndTheUplinkDirection() {
        assertTrue(UplinkRoute.isUplinkStream(true, AudioDirection.MAIN_TO_SIM))
        assertFalse(UplinkRoute.isUplinkStream(false, AudioDirection.MAIN_TO_SIM))   // default: behaves exactly as before
        assertFalse(UplinkRoute.isUplinkStream(true, AudioDirection.SIM_TO_MAIN))    // DOWNLINK is never routed to the uplink sink
        assertFalse(UplinkRoute.isUplinkStream(false, AudioDirection.SIM_TO_MAIN))
    }

    // ---- source direction check (downlink and test sources untouched)
    @Test fun plainSourcesAreNeverRefusedByTheDirectionCheck() {
        for (d in AudioDirection.values()) assertNull(UplinkRoute.sourceDirectionError(FakeSource(), d))
    }

    @Test fun uplinkMicrophoneIsRefusedOnTheSimPhoneOnly() {
        val mic = UplinkMicrophoneSource(FakeSource())
        assertNull(UplinkRoute.sourceDirectionError(mic, AudioDirection.MAIN_TO_SIM))
        assertEquals("Source direction does not match this phone's role", UplinkRoute.sourceDirectionError(mic, AudioDirection.SIM_TO_MAIN))
    }

    // ---- gate flags
    @Test fun gateDefaultsAreUnchanged() {
        assertEquals(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN, AudioTransportMessages.refusalReason(false, false))
        assertNull(AudioTransportMessages.refusalReason(true, false))
        assertNull(AudioTransportMessages.refusalReason(false, true))
        assertFalse(AudioTransportMessages.acceptsIncoming(false, false))
    }

    @Test fun uplinkFlagAloneOpensTheGateAndNothingElseDoes() {
        assertTrue(AudioTransportMessages.acceptsIncoming(false, false, callUplinkRx = true))
        assertNull(AudioTransportMessages.refusalReason(false, false, true))
        assertEquals(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN, AudioTransportMessages.refusalReason(false, false, false))
    }

    @Test fun uplinkNotSupportedRecognition() {
        assertTrue(UplinkMessages.isUplinkNotSupported("Not supported / not verified"))
        assertTrue(UplinkMessages.isUplinkNotSupported("Not supported / not verified (peer refused)"))
        assertFalse(UplinkMessages.isUplinkNotSupported(null))
        assertFalse(UplinkMessages.isUplinkNotSupported(""))
        assertFalse(UplinkMessages.isUplinkNotSupported(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN))
    }

    // ---- the marker source shares ONE microphone
    @Test fun markerDelegatesStartAndStopToTheSharedMicrophone() {
        val inner = FakeSource()
        val mic = UplinkMicrophoneSource(inner)
        assertNull(mic.start(AudioConfig(), NoListener))
        mic.stop(); mic.stop()
        assertEquals(1, inner.starts); assertEquals(2, inner.stops)
    }

    @Test fun markerPassesTheMicrophoneRefusalThrough() {
        val mic = UplinkMicrophoneSource(FakeSource("Microphone already running"))
        assertEquals("Microphone already running", mic.start(AudioConfig(), NoListener))
    }

    @Test fun markerReallyHoldsOneRecorderEvenWhenTheTestScreenMicIsTheSameObject() {
        // Two start attempts through the marker and the plain object reach the same MicrophoneCapture, which refuses the second.
        var opens = 0
        val rec = object : MicrophoneRecorder {
            override fun read(buf: ByteArray, off: Int, len: Int): Int { Thread.sleep(1); return len }
            override fun stop() {}
            override fun release() {}
        }
        val shared = MicrophoneCapture({ true }, MicrophoneRecorderOpener { opens++; MicrophoneOpenResult.Opened(rec) })
        val marker = UplinkMicrophoneSource(shared)
        assertNull(marker.start(AudioConfig(), NoListener))
        assertEquals("Microphone already running", shared.start(AudioConfig(), NoListener))
        assertEquals("Microphone already running", marker.start(AudioConfig(), NoListener))
        assertEquals(1, opens)
        marker.stop()
    }
}
