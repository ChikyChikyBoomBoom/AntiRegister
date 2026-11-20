package com.example.companion.audio.call

import com.example.companion.audio.transport.AudioConfig
import com.example.companion.audio.transport.AudioSource
import com.example.companion.audio.transport.AudioStartOutcome
import com.example.companion.audio.transport.AudioTransportMessages
import com.example.companion.audio.transport.CellularAudioConfiguration
import com.example.companion.audio.transport.RetryScheduler
import com.example.companion.audio.transport.RetryTimer
import com.example.companion.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 33C JVM tests. WRITTEN BUT NOT RUN: no Gradle / Android SDK / device was available when this stage was authored.
 * Virtual time, fakes only, no threads, no Android classes (AudioTransport / AudioRecord / AudioTrack are not involved).
 */
class TransportCallAudioBackendTest {

    private class FakeScheduler : RetryScheduler {
        class Task(val at: Long, val run: Runnable) { var cancelled = false }
        var now = 0L
        val tasks = ArrayList<Task>()
        override fun postDelayed(delayMs: Long, task: Runnable): RetryTimer {
            val t = Task(now + delayMs, task); tasks += t
            return RetryTimer { t.cancelled = true }
        }
        val pending get() = tasks.count { !it.cancelled }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val due = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                now = maxOf(now, due.at); tasks.remove(due); due.run.run()
            }
            now = target
        }
    }

    private class FakePort : CallTransportPort {
        override var isLinkUp = true
        override var callRxEnabled = false
        override var isSending = false
        override var isReceiving = false
        override var receivedPackets = 0L
        override var lastError: String? = null
        var startError: String? = null
        val started = ArrayList<Pair<AudioSource, AudioConfig>>()
        val stopSendingCalls = ArrayList<Boolean>()      // userInitiated flags
        var stopReceivingCalls = 0
        @JvmField var startListener: ((AudioStartOutcome) -> Unit)? = null
        @JvmField var changeListener: (() -> Unit)? = null
        override fun startSending(source: AudioSource, config: AudioConfig): String? {
            if (startError != null) return startError
            started += source to config; isSending = true; return null
        }
        override fun stopSending(reason: String?, userInitiated: Boolean) { stopSendingCalls += userInitiated; isSending = false }
        override fun stopReceiving(reason: String?) { stopReceivingCalls++; isReceiving = false }
        override fun setStartListener(l: ((AudioStartOutcome) -> Unit)?) { startListener = l }
        override fun setChangeListener(l: (() -> Unit)?) { changeListener = l }
        fun outcome(o: AudioStartOutcome) = startListener?.invoke(o)
        fun changed() = changeListener?.invoke()
    }

    private class FakeCellular : ConfigurableCellularSource {
        var stops = 0
        val started = ArrayList<CellularAudioConfiguration>()
        override fun startWithConfiguration(configuration: CellularAudioConfiguration, listener: AudioSource.Listener): String? { started += configuration; return null }
        override fun stop() { stops++ }
    }

    private class Cb : CallAudioBackend.Callback {
        var connected = 0; var active = 0
        val failed = ArrayList<Pair<AudioUnavailableReason, String>>()
        override fun onConnected() { connected++ }
        override fun onActive() { active++ }
        override fun onFailed(reason: AudioUnavailableReason, detail: String) { failed += reason to detail }
    }

    private class Env(role: Role) {
        val sched = FakeScheduler(); val port = FakePort(); val cell = FakeCellular(); val cb = Cb()
        val backend = TransportCallAudioBackend(role, port, cell, sched)
    }

    // ------------------------------------------------------------------ mode / one-way

    @Test fun modeIsCustomBluetoothTransport() {
        assertEquals(AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, Env(Role.SIM_PHONE).backend.mode)
        assertEquals(AudioMode.CUSTOM_BLUETOOTH_TRANSPORT, Env(Role.MAIN_PHONE).backend.mode)
    }

    @Test fun simNeverCallsOnActive() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        e.port.outcome(AudioStartOutcome.Ready)
        e.port.changed(); e.sched.advance(60_000)
        assertEquals(1, e.cb.connected)
        assertEquals(0, e.cb.active)
        assertEquals(TransportCallAudioBackend.Phase.AUDIO_FLOWING, e.backend.phase)
    }

    @Test fun mainNeverCallsOnActiveEvenWithAudio() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        e.port.isReceiving = true; e.port.receivedPackets = 50
        e.sched.advance(60_000)
        assertEquals(1, e.cb.connected)
        assertEquals(0, e.cb.active)
        assertTrue(e.cb.failed.isEmpty())
    }

    @Test fun mainArmsCallRxFirstAndNeverSends() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        assertTrue(e.port.callRxEnabled)
        assertTrue(e.port.started.isEmpty())
        assertTrue(e.cell.started.isEmpty())
    }

    @Test fun simUsesFirstConfigurationAndSharedFallbackOrder() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        assertEquals(1, e.port.started.size)
        assertEquals(CellularAudioConfiguration.ORDER[0].sampleRate, e.port.started[0].second.sampleRate)
        e.port.outcome(AudioStartOutcome.SourceFailed("x"))
        assertEquals(2, e.port.started.size)                                   // next configuration = new AUDIO_START
        assertEquals(16_000, e.port.started[1].second.sampleRate)
        assertEquals(0, e.cb.failed.size)
    }

    @Test fun allConfigurationsFailingReportsFailedOnce() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        repeat(4) { e.port.outcome(AudioStartOutcome.SourceFailed("needs CAPTURE_AUDIO_OUTPUT (privileged app)")) }
        assertEquals(1, e.cb.failed.size)
        assertEquals(AudioUnavailableReason.PERMISSION_OR_ROLE_UNAVAILABLE, e.cb.failed[0].first)
        assertEquals(4, e.port.started.size)
        assertEquals(0, e.cb.active)
    }

    // ------------------------------------------------------------------ timeout (12 s, actual audio)

    @Test fun mainTimesOutAfter12sWithoutAnything() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        e.sched.advance(11_999)
        assertTrue(e.cb.failed.isEmpty())
        e.sched.advance(1)
        assertEquals(1, e.cb.failed.size)
        assertEquals(0, e.cb.connected)
        assertEquals(0, e.cb.active)
    }

    @Test fun audioStartAloneDoesNotStopTheTimeout() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        e.sched.advance(1_000)
        e.port.isReceiving = true; e.port.receivedPackets = 0           // AUDIO_START accepted, zero packets
        e.sched.advance(500)
        assertEquals(TransportCallAudioBackend.Phase.STREAM_ANNOUNCED, e.backend.phase)
        assertEquals(0, e.cb.connected)
        e.sched.advance(10_499)
        assertTrue(e.cb.failed.isEmpty())
        e.sched.advance(1)
        assertEquals(1, e.cb.failed.size)
        assertTrue(e.cb.failed[0].second.contains("no audio data"))
        assertEquals(1, e.port.stopReceivingCalls)
    }

    @Test fun actualAudioCancelsTheTimeout() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        e.sched.advance(5_000)
        e.port.isReceiving = true; e.port.receivedPackets = 3
        e.sched.advance(300)
        assertEquals(1, e.cb.connected)
        e.sched.advance(60_000)
        assertTrue(e.cb.failed.isEmpty())
        assertEquals(0, e.sched.pending)
    }

    @Test fun simTimesOutIfStreamNeverReady() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        e.port.outcome(AudioStartOutcome.Refused(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN))
        e.sched.advance(11_000)
        assertTrue(e.cb.failed.isEmpty())
        e.sched.advance(1_000)
        assertEquals(1, e.cb.failed.size)
        assertEquals(0, e.sched.pending)                                      // retry timer cancelled too
    }

    // ------------------------------------------------------------------ retries

    @Test fun notListeningIsRetriedWithSameRequestAfter500ms() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        e.port.outcome(AudioStartOutcome.Refused(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN))
        e.sched.advance(499); assertEquals(1, e.port.started.size)
        e.sched.advance(1); assertEquals(2, e.port.started.size)
        assertEquals(e.port.started[0].first, e.port.started[1].first)
    }

    @Test fun stopCancelsPendingRetry() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        e.port.outcome(AudioStartOutcome.Refused(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN))
        e.backend.stop()
        e.sched.advance(60_000)
        assertEquals(1, e.port.started.size)
        assertEquals(0, e.sched.pending)
        assertTrue(e.cb.failed.isEmpty())
    }

    @Test fun otherRefusalFailsWithoutRetry() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        e.port.outcome(AudioStartOutcome.Refused("Stream direction does not match this phone's role"))
        e.sched.advance(5_000)
        assertEquals(1, e.port.started.size)
        assertEquals(1, e.cb.failed.size)
    }

    // ------------------------------------------------------------------ resource cleanup / stop

    @Test fun simStopReleasesEverythingWithUserInitiatedStop() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        e.port.outcome(AudioStartOutcome.Ready)
        e.backend.stop()
        assertEquals(listOf(true), e.port.stopSendingCalls)                    // userInitiated = true -> no auto resume
        assertEquals(1, e.cell.stops)                                         // AudioRecord owner stopped
        assertNull(e.port.startListener)
        assertNull(e.port.changeListener)
        assertEquals(0, e.sched.pending)
        assertEquals(TransportCallAudioBackend.Phase.STOPPED, e.backend.phase)
    }

    @Test fun mainStopReleasesReceiverAndDisarms() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        e.port.isReceiving = true
        e.backend.stop()
        assertFalse(e.port.callRxEnabled)
        assertEquals(1, e.port.stopReceivingCalls)                            // AudioTrack released by the transport
        assertTrue(e.port.stopSendingCalls.isEmpty())                         // never touches TX
        assertNull(e.port.changeListener)
        assertEquals(0, e.sched.pending)
    }

    @Test fun stopIsIdempotent() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        e.port.outcome(AudioStartOutcome.Ready)
        e.backend.stop(); e.backend.stop(); e.backend.stop()
        assertEquals(1, e.port.stopSendingCalls.size)
        assertEquals(1, e.cell.stops)
        val m = Env(Role.MAIN_PHONE); m.backend.start(m.cb)
        m.backend.stop(); m.backend.stop()
        assertEquals(1, m.port.stopReceivingCalls)
    }

    @Test fun stopBeforeStartIsSafeAndStartAfterStopDoesNothing() {
        val e = Env(Role.SIM_PHONE)
        e.backend.stop()
        e.backend.start(e.cb)
        assertTrue(e.port.started.isEmpty())
        assertEquals(0, e.sched.pending)
    }

    @Test fun noCallbacksAfterStop() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        e.backend.stop()
        e.port.isReceiving = true; e.port.receivedPackets = 5
        e.sched.advance(60_000); e.port.changed()
        assertEquals(0, e.cb.connected); assertTrue(e.cb.failed.isEmpty())
    }

    @Test fun rejectedStartDoesNotStopForeignTx() {
        val e = Env(Role.SIM_PHONE); e.port.startError = "Already sending"
        e.backend.start(e.cb)
        assertEquals(1, e.cb.failed.size)
        assertTrue(e.port.stopSendingCalls.isEmpty())                         // the Audio Test stream (not ours) stays
    }

    // ------------------------------------------------------------------ Bluetooth loss / stream end

    @Test fun simBluetoothLossWhileStreamingReleasesAndFails() {
        val e = Env(Role.SIM_PHONE); e.backend.start(e.cb)
        e.port.outcome(AudioStartOutcome.Ready)
        e.port.isLinkUp = false; e.port.isSending = false
        e.port.changed()
        assertEquals(1, e.cb.failed.size)
        assertEquals(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, e.cb.failed[0].first)
        assertEquals(listOf(true), e.port.stopSendingCalls)
        assertEquals(1, e.cell.stops)
    }

    @Test fun mainBluetoothLossBeforeAudioFailsAndReleases() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        e.port.isLinkUp = false
        e.sched.advance(250)
        assertEquals(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, e.cb.failed.single().first)
        assertFalse(e.port.callRxEnabled)
        assertEquals(1, e.port.stopReceivingCalls)
    }

    @Test fun mainStreamEndingAfterAudioFails() {
        val e = Env(Role.MAIN_PHONE); e.backend.start(e.cb)
        e.port.isReceiving = true; e.port.receivedPackets = 2
        e.sched.advance(250)
        e.port.isReceiving = false; e.port.changed()
        assertEquals(1, e.cb.failed.size)
    }

    @Test fun linkAlreadyDownAtSimStartFails() {
        val e = Env(Role.SIM_PHONE); e.port.isLinkUp = false
        e.backend.start(e.cb)
        assertEquals(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, e.cb.failed.single().first)
        assertTrue(e.port.started.isEmpty())
    }
}
