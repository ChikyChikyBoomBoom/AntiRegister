package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 39 JVM tests of [UplinkController] with a fake transport port, fake sources and fake sinks. WRITTEN BUT NOT RUN
 * (no Kotlin compiler / Gradle available when authored). [AudioTransport] itself needs Handler and has no JVM test; the pure
 * helpers it uses are covered in [UplinkRoutingTest].
 */
class UplinkControllerTest {

    private class FakeSource(override val direction: AudioDirection = AudioDirection.MAIN_TO_SIM) : DirectionalAudioSource {
        override val name = "fake uplink mic"
        var starts = 0; var stops = 0
        override fun start(config: AudioConfig, listener: AudioSource.Listener): String? { starts++; return null }
        override fun stop() { stops++ }
    }

    private class FakePort(var direction: AudioDirection? = AudioDirection.MAIN_TO_SIM) : UplinkTransportPort {
        override var isLinkUp = true
        override val localDirection get() = direction
        override var isSending = false
        override var callUplinkRxEnabled = false
        var startError: String? = null
        val started = mutableListOf<Pair<AudioSource, AudioConfig>>()
        val stops = mutableListOf<Boolean>()                 // userInitiated flag of each stopSending
        var slotHolder: ((AudioStartOutcome) -> Unit)? = null
        var foreignSlot = false
        override fun startSending(source: AudioSource, config: AudioConfig): String? {
            if (startError != null) return startError
            started += source to config; isSending = true; return null
        }
        override fun stopSending(reason: String?, userInitiated: Boolean) { stops += userInitiated; isSending = false }
        override fun claimOutcomeListener(listener: (AudioStartOutcome) -> Unit): Boolean {
            if (foreignSlot) return false
            slotHolder = listener; return true
        }
        override fun releaseOutcomeListener() { slotHolder = null }
        fun outcome(o: AudioStartOutcome) { slotHolder!!.invoke(o) }
    }

    private class FakeSink : UplinkSink {
        var closed = 0
        override val name = "fake sink"
        override val capability = UplinkCapability.notSupported(UplinkUnsupportedReason.NO_VERIFIED_SINK, "fake")
        override fun open(config: AudioConfig): String? = UplinkMessages.NOT_SUPPORTED
        override fun write(pcm: ByteArray) = false
        override val bufferMs = 0
        override val underruns = 0
        override fun close() { closed++ }
    }

    private fun controller(port: FakePort, src: FakeSource = FakeSource(), sink: () -> UplinkSink = { UnsupportedUplinkSink() }) =
        UplinkController(port, src, sink)

    // ---- start (Main Phone)
    @Test fun startNegotiatesOnceThroughTheTransportWithTheSharedSourceAndDoesNotTouchTheMicrophone() {
        val port = FakePort(); val src = FakeSource()
        val c = controller(port, src)
        assertNull(c.start())
        assertEquals(1, port.started.size)
        assertTrue(port.started[0].first === src)
        assertEquals(UplinkPhase.STARTING, c.status().phase)
        assertEquals(0, src.starts)                          // the transport starts the source only after AUDIO_READY
    }

    @Test fun nothingStartsByItself() {
        val port = FakePort()
        val c = controller(port)
        assertEquals(UplinkPhase.IDLE, c.status().phase)
        assertTrue(port.started.isEmpty())
        assertFalse(port.callUplinkRxEnabled)
    }

    @Test fun duplicateStartIsRefused() {
        val port = FakePort(); val c = controller(port)
        assertNull(c.start())
        assertEquals("Uplink already running", c.start())
        assertEquals(1, port.started.size)
        port.outcome(AudioStartOutcome.Ready)
        assertEquals("Uplink already running", c.start())
        assertEquals(1, port.started.size)
    }

    @Test fun startIsRefusedWhenAnotherOutgoingStreamExists() {
        val port = FakePort().apply { isSending = true }
        assertEquals("Another outgoing audio stream is already running", controller(port).start())
        assertTrue(port.started.isEmpty())
    }

    @Test fun startIsRefusedWhenAnotherOwnerHoldsTheOutcomeSlot() {
        val port = FakePort().apply { foreignSlot = true }
        assertEquals("Another audio start is in progress", controller(port).start())
        assertTrue(port.started.isEmpty())
    }

    @Test fun startIsRefusedOnTheSimPhoneAndWithoutARole() {
        assertEquals("Uplink source runs on the Main Phone only", controller(FakePort(AudioDirection.SIM_TO_MAIN)).start())
        assertEquals("Uplink source runs on the Main Phone only", controller(FakePort(null)).start())
    }

    @Test fun startIsRefusedWithoutBluetooth() {
        val port = FakePort().apply { isLinkUp = false }
        val c = controller(port)
        assertEquals("Bluetooth is not connected", c.start())
        assertTrue(port.started.isEmpty())
        assertEquals(UplinkPhase.FAILED, c.status().phase)
    }

    @Test fun transportRejectionIsReportedAndTheSlotIsReleased() {
        val port = FakePort().apply { startError = "Already sending" }
        val c = controller(port)
        assertEquals("Already sending", c.start())
        assertNull(port.slotHolder)
        assertEquals(UplinkPhase.FAILED, c.status().phase)
        port.startError = null
        assertNull(c.start())                                // can try again
    }

    // ---- outcomes
    @Test fun readyMeansStreaming() {
        val port = FakePort(); val c = controller(port)
        c.start(); port.outcome(AudioStartOutcome.Ready)
        assertEquals(UplinkPhase.STREAMING, c.status().phase)
        assertNotNull(port.slotHolder)
    }

    @Test fun peerRefusalWithUnsupportedSinkIsReportedHonestlyAndNeverAsSuccess() {
        val port = FakePort(); val c = controller(port)
        c.start()
        port.outcome(AudioStartOutcome.Refused(UplinkMessages.NOT_SUPPORTED))
        val st = c.status()
        assertEquals(UplinkPhase.NOT_SUPPORTED, st.phase)
        assertTrue(st.detail!!.startsWith("Not supported / not verified"))
        assertFalse(st.capability.isSupported)
        assertNull(port.slotHolder)
        assertEquals(listOf(true), port.stops)               // automatic resume cleared
    }

    @Test fun otherRefusalsAreFailuresNotUnsupported() {
        for (r in listOf(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN, "No role selected", null)) {
            val port = FakePort(); val c = controller(port)
            c.start(); port.outcome(AudioStartOutcome.Refused(r))
            assertEquals(UplinkPhase.FAILED, c.status().phase)
            assertTrue(c.status().detail!!.startsWith("Peer refused"))
        }
    }

    @Test fun sourceFailureIsReportedAndCleanedUp() {
        val port = FakePort(); val c = controller(port)
        c.start(); port.outcome(AudioStartOutcome.SourceFailed("Microphone permission denied"))
        assertEquals(UplinkPhase.FAILED, c.status().phase)
        assertEquals("Microphone could not start: Microphone permission denied", c.status().detail)
        assertNull(port.slotHolder)
        assertEquals(listOf(true), port.stops)
    }

    @Test fun timeoutIsAFailure() {
        val port = FakePort(); val c = controller(port)
        c.start(); port.outcome(AudioStartOutcome.TimedOut)
        assertEquals(UplinkPhase.FAILED, c.status().phase)
        assertNull(port.slotHolder)
    }

    // ---- Bluetooth disconnection
    @Test fun linkDownWhileNegotiatingFailsAndCancelsAutomaticResume() {
        val port = FakePort(); val c = controller(port)
        c.start()
        port.outcome(AudioStartOutcome.LinkDown)
        assertEquals(UplinkPhase.FAILED, c.status().phase)
        assertEquals("Bluetooth disconnected - uplink stopped", c.status().detail)
        assertEquals(listOf(true), port.stops)               // userInitiated = true clears the transport's `desired`
        assertNull(port.slotHolder)
        assertFalse(port.isSending)
    }

    @Test fun linkDownWhileStreamingFailsAndCancelsAutomaticResume() {
        val port = FakePort(); val c = controller(port)
        c.start(); port.outcome(AudioStartOutcome.Ready)
        port.isSending = false                               // the transport already stopped its TX
        port.outcome(AudioStartOutcome.LinkDown)
        assertEquals(UplinkPhase.FAILED, c.status().phase)
        assertEquals(listOf(true), port.stops)
        assertNull(port.slotHolder)
    }

    @Test fun canStartAgainAfterALinkDrop() {
        val port = FakePort(); val c = controller(port)
        c.start(); port.outcome(AudioStartOutcome.LinkDown)
        assertNull(c.start())
        assertEquals(2, port.started.size)
    }

    @Test fun peerEndingTheStreamIsNoticedLazily() {
        val port = FakePort(); val c = controller(port)
        c.start(); port.outcome(AudioStartOutcome.Ready)
        port.isSending = false                               // peer sent AUDIO_STOP; no outcome callback exists for that
        assertEquals(UplinkPhase.IDLE, c.status().phase)
        assertEquals(listOf(true), port.stops)               // Stage 41: the transport's automatic-resume request is cleared too
        assertNull(port.slotHolder)
        assertNull(c.start())
    }

    // ---- stop / cleanup
    @Test fun stopEndsOwnStreamClearsResumeAndReleasesTheSlot() {
        val port = FakePort(); val c = controller(port)
        c.start(); port.outcome(AudioStartOutcome.Ready)
        c.stop()
        assertEquals(listOf(true), port.stops)
        assertFalse(port.isSending)
        assertNull(port.slotHolder)
        assertEquals(UplinkPhase.IDLE, c.status().phase)
    }

    @Test fun stopIsIdempotentAndSafeWithoutStart() {
        val port = FakePort(); val c = controller(port)
        c.stop(); c.stop()
        assertTrue(port.stops.isEmpty())
        c.start(); c.stop(); c.stop()
        assertEquals(1, port.stops.size)
    }

    @Test fun stopNeverEndsAForeignStream() {
        val port = FakePort().apply { isSending = true }     // e.g. the Audio Test screen's own TX
        val c = controller(port)
        assertNotNull(c.start())                             // refused: not ours
        c.stop()
        assertTrue(port.stops.isEmpty())
        assertTrue(port.isSending)
    }

    // ---- sink side (SIM Phone)
    @Test fun armingWorksOnTheSimPhoneOnlyAndOpensNothing() {
        val sim = FakePort(AudioDirection.SIM_TO_MAIN); val c = controller(sim)
        assertNull(c.armReceiver())
        assertTrue(c.receiverArmed); assertTrue(sim.callUplinkRxEnabled)
        c.disarmReceiver()
        assertFalse(sim.callUplinkRxEnabled)
        val main = FakePort(AudioDirection.MAIN_TO_SIM)
        assertEquals("Uplink sink runs on the SIM Phone only", controller(main).armReceiver())
        assertFalse(main.callUplinkRxEnabled)
        assertEquals("Uplink sink runs on the SIM Phone only", controller(FakePort(null)).armReceiver())
    }

    // ---- sink capability reporting
    @Test fun simPhoneReportsTheSinkCapabilityAndClosesTheProbeSink() {
        val probe = FakeSink()
        val c = controller(FakePort(AudioDirection.SIM_TO_MAIN), FakeSource(), { probe })
        val cap = c.capability()
        assertFalse(cap.isSupported)
        assertEquals("Not supported / not verified", cap.headline)
        assertEquals(1, probe.closed)
    }

    @Test fun simPhoneWithTheDefaultSinkSaysNoVerifiedSink() {
        val cap = controller(FakePort(AudioDirection.SIM_TO_MAIN)).capability()
        assertEquals(UplinkUnsupportedReason.NO_VERIFIED_SINK, cap.reason)
    }

    @Test fun mainPhoneCannotKnowThePeerSinkSoItReportsNotVerified() {
        val cap = controller(FakePort(AudioDirection.MAIN_TO_SIM)).capability()
        assertEquals(UplinkUnsupportedReason.PEER_NOT_VERIFIED, cap.reason)
        assertFalse(cap.isSupported)
    }

    @Test fun noRoleIsNeverSupported() {
        assertFalse(controller(FakePort(null)).capability().isSupported)
    }

    @Test fun capabilityStaysUnsupportedInEveryPhase() {
        val port = FakePort(); val c = controller(port)
        assertFalse(c.status().capability.isSupported)
        c.start(); assertFalse(c.status().capability.isSupported)
        port.outcome(AudioStartOutcome.Ready); assertFalse(c.status().capability.isSupported)
    }
}
