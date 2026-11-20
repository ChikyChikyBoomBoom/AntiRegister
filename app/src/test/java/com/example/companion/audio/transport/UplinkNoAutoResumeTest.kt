package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 41 regression tests: "no microphone capture without an explicit user action", including after a Bluetooth reconnect.
 *
 * [AudioTransport] keeps `desired` (the last requested stream) so that a reconnect resumes a stream, and clears it only on a
 * user-initiated stop. A stream that ends by itself (microphone failure in `Tx.onError`, a peer stop without a reason) leaves
 * `desired` set. For the Audio Test screen that is intended; for the uplink microphone it would restart capture after the next
 * reconnect with nobody pressing Start. [UplinkController] must therefore clear it whenever it notices the end of its own run.
 *
 * [ResumeModelPort] is a MODEL of those transport rules (the real [AudioTransport] needs a Handler and is not JVM-testable),
 * written from AudioTransport.stopSending / onLinkDown / onLinkUp / Tx.onError / onPeerStop. It proves the controller and the
 * lifecycle do the right thing against that model; it does not prove the real transport. WRITTEN BUT NOT RUN (no Kotlin
 * compiler or Gradle in the authoring environment).
 */
class UplinkNoAutoResumeTest {

    private class FakeSource : DirectionalAudioSource {
        override val name = "fake uplink mic"
        override val direction = AudioDirection.MAIN_TO_SIM
        override fun start(config: AudioConfig, listener: AudioSource.Listener): String? = null
        override fun stop() {}
    }

    /** Transport rules that matter for resume: see class comment. */
    private class ResumeModelPort : UplinkTransportPort {
        override var isLinkUp = true
        override val localDirection: AudioDirection? get() = AudioDirection.MAIN_TO_SIM
        override var isSending = false
        override var callUplinkRxEnabled = false
        var desired: Pair<AudioSource, AudioConfig>? = null
        var startCalls = 0          // every AUDIO_START the transport would send, including automatic resumes
        var resumes = 0             // of those, the ones that were NOT a user press
        var slot: ((AudioStartOutcome) -> Unit)? = null
        var onChanged: (() -> Unit)? = null

        override fun startSending(source: AudioSource, config: AudioConfig): String? {
            if (isSending) return "Already sending"
            startCalls++; desired = source to config; isSending = true; return null
        }
        override fun stopSending(reason: String?, userInitiated: Boolean) {
            if (userInitiated) desired = null
            if (!isSending) return                          // like the transport: `val t = tx ?: return` (no callback)
            isSending = false
            onChanged?.invoke()
        }
        override fun claimOutcomeListener(listener: (AudioStartOutcome) -> Unit): Boolean { slot = listener; return true }
        override fun releaseOutcomeListener() { slot = null }

        /** Tx.onError and onPeerStop(no reason): the stream ends, `desired` stays. No outcome callback. */
        fun streamEndsByItself() { if (isSending) { isSending = false; onChanged?.invoke() } }
        /** onLinkDown: stop without clearing `desired`, then tell the start-outcome slot. */
        fun linkDown() { isLinkUp = false; if (isSending) { isSending = false; onChanged?.invoke() }; slot?.invoke(AudioStartOutcome.LinkDown) }
        /** onLinkUp: resume the last requested stream. */
        fun linkUp() { isLinkUp = true; val d = desired; if (d != null && !isSending) { resumes++; startSending(d.first, d.second) } }
    }

    private class FakeEnv : UplinkEnvironment {
        override fun callActive(): Boolean? = true
        override fun linkUp(): Boolean = true
        override fun microphonePermission(): Boolean? = true
        override fun appVisible(): Boolean = true
        override fun microphoneBusyElsewhere(): Boolean = false
        override fun headsetOutputPresent(): Boolean? = true
    }

    private fun running(port: ResumeModelPort): UplinkController {
        val c = UplinkController(port, FakeSource())
        assertNull(c.start())
        port.slot!!.invoke(AudioStartOutcome.Ready)
        assertEquals(UplinkPhase.STREAMING, c.status().phase)
        return c
    }

    // ---- the model itself must be able to show the problem (otherwise the tests below prove nothing)
    @Test fun modelResumesAStreamNobodyCleared() {
        val port = ResumeModelPort()
        port.startSending(FakeSource(), AudioConfig())      // someone started it and never cleared `desired`
        port.streamEndsByItself()
        port.linkDown(); port.linkUp()
        assertEquals(1, port.resumes)
        assertTrue(port.isSending)
    }

    // ---- controller level
    @Test fun microphoneFailureThenReconnectDoesNotRestartTheUplink() {
        val port = ResumeModelPort(); val c = running(port)
        port.streamEndsByItself()                           // Tx.onError: microphone died, no outcome callback
        assertEquals(UplinkPhase.IDLE, c.status().phase)    // noticed lazily; this is what clears the resume request
        assertNull(port.desired)
        port.linkDown(); port.linkUp()
        assertEquals(0, port.resumes)
        assertEquals(1, port.startCalls)
        assertFalse(port.isSending)
    }

    @Test fun peerStopWithoutReasonThenReconnectDoesNotRestartTheUplink() {
        val port = ResumeModelPort(); val c = running(port)
        port.streamEndsByItself()
        assertEquals(UplinkPhase.IDLE, c.status().phase)
        port.linkDown(); port.linkUp()
        assertEquals(0, port.resumes); assertEquals(1, port.startCalls)
    }

    @Test fun linkDropWhileStreamingDoesNotComeBackOnReconnect() {
        val port = ResumeModelPort(); val c = running(port)
        port.linkDown()                                     // transport stops, tells the slot LinkDown; controller clears `desired`
        port.linkUp()
        assertEquals(0, port.resumes); assertEquals(1, port.startCalls)
        assertEquals(UplinkPhase.FAILED, c.status().phase)
    }

    @Test fun userStopThenReconnectDoesNotRestartTheUplink() {
        val port = ResumeModelPort(); val c = running(port)
        c.stop()
        port.linkDown(); port.linkUp()
        assertEquals(0, port.resumes); assertEquals(1, port.startCalls)
    }

    @Test fun foreignStreamsResumeRequestIsNeverClearedByTheController() {
        val port = ResumeModelPort()
        port.startSending(FakeSource(), AudioConfig())      // a foreign stream (e.g. the Audio Test screen) owns the transport
        val c = UplinkController(port, FakeSource())
        assertTrue(c.start() != null)                       // refused, so the controller owns nothing
        port.streamEndsByItself()
        c.status()
        assertTrue(port.desired != null)                    // the foreign stream's resume request is not ours to clear
    }

    // ---- lifecycle level (the transport's change callback drives onEnvironmentChanged, as in CompanionApp)
    @Test fun lifecycleNoticesMicrophoneFailureAndTheReconnectStartsNothing() {
        val port = ResumeModelPort()
        val controller = UplinkController(port, FakeSource())
        val lifecycle = UplinkLifecycle(controller, FakeEnv(), UplinkFlags(uplinkBridge = true)) { AudioDirection.MAIN_TO_SIM }
        port.onChanged = { lifecycle.onEnvironmentChanged() }
        assertTrue(lifecycle.start().started)
        port.slot!!.invoke(AudioStartOutcome.Ready)
        assertTrue(lifecycle.microphoneActive)

        port.streamEndsByItself()                           // callback -> onEnvironmentChanged -> controller notices
        assertFalse(lifecycle.microphoneActive)
        assertEquals(UplinkLifecycleState.IDLE, lifecycle.state())
        assertNull(port.desired)

        port.linkDown(); port.linkUp()
        assertEquals(0, port.resumes)
        assertEquals(1, port.startCalls)
        assertFalse(lifecycle.microphoneActive)
    }

    @Test fun noCallbackFromTheTransportDuringTheFixCannotRecurseForever() {
        val port = ResumeModelPort()
        val controller = UplinkController(port, FakeSource())
        val lifecycle = UplinkLifecycle(controller, FakeEnv(), UplinkFlags(uplinkBridge = true)) { AudioDirection.MAIN_TO_SIM }
        var calls = 0
        port.onChanged = { calls++; lifecycle.onEnvironmentChanged() }
        lifecycle.start(); port.slot!!.invoke(AudioStartOutcome.Ready)
        port.streamEndsByItself()
        assertTrue("callbacks stay bounded", calls in 1..10)
    }
}
