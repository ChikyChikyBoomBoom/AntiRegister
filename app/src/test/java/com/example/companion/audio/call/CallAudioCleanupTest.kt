package com.example.companion.audio.call

import com.example.companion.audio.hfp.Tri
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 29 JVM tests (fake backend). WRITTEN BUT NOT RUN: no Gradle / Android SDK / device was available when this stage was authored.
 * They test the release / no-duplicate / reset rules of [CallAudioSession] only; they say nothing about real audio.
 */
class CallAudioCleanupTest {

    private class Fake(
        override val supportsMute: Boolean = false,
        private val stopThrows: Boolean = false
    ) : CallAudioBackend {
        override val mode = AudioMode.HFP
        override val needsForeground = false
        var cb: CallAudioBackend.Callback? = null
        var starts = 0; var stops = 0
        override fun start(callback: CallAudioBackend.Callback) { starts++; cb = callback }
        override fun stop() { stops++; if (stopThrows) throw IllegalStateException("stop failed") }
        override fun setMuted(muted: Boolean) { cb?.onMuteChanged(muted) }
        val open get() = starts > 0 && stops == 0
    }

    private class Rig(
        private val mute: Boolean = false,
        private val stopThrows: Boolean = false,
        private val factoryThrows: () -> Boolean = { false }
    ) {
        val created = ArrayList<Fake>()
        /** For every creation: was any earlier backend still open at that moment? Must always be false. */
        val overlapAtCreation = ArrayList<Boolean>()
        var onStatus: (CallAudioStatus) -> Unit = {}
        val session = CallAudioSession(
            capability = { CallAudioCapability(AudioMode.HFP, Tri.YES, null, "test", MuteScope.NONE, RouteScope.NONE) },
            backendFactory = {
                if (factoryThrows()) throw IllegalStateException("factory failed")
                overlapAtCreation += created.any { it.open }
                Fake(mute, stopThrows).also { created += it }
            },
            onChanged = { onStatus(it) }
        )
        val state get() = session.status.state
        fun call(id: String? = null) { session.onLink(true); session.onCallState(CallState.ACTIVE, id) }
    }

    @Test fun differentCallIdWhileActive_releasesOldBackend_thenStartsExactlyOneNew() {
        val r = Rig()
        r.call("a")
        r.created[0].cb!!.onActive()
        r.session.onCallState(CallState.ACTIVE, "b")
        assertEquals(1, r.created[0].stops)
        assertEquals(2, r.created.size)
        assertEquals(AudioSessionState.STARTING, r.state)
        assertEquals(listOf(false, false), r.overlapAtCreation)
    }

    @Test fun sameCallIdRepeated_orIdArrivingLater_doesNotRestart() {
        val r = Rig()
        r.call(null)
        r.session.onCallState(CallState.ACTIVE, "a")      // id learned later: same call
        r.session.onCallState(CallState.ACTIVE, "a")
        assertEquals(1, r.created.size)
        assertEquals(0, r.created[0].stops)
    }

    @Test fun newCall_afterExplicitStop_startsAudioAgain() {
        val r = Rig()
        r.call("a")
        r.session.stop()
        assertEquals(AudioSessionState.IDLE, r.state)
        r.session.onCallState(CallState.ACTIVE, "b")
        assertEquals(2, r.created.size)
        assertEquals(AudioSessionState.STARTING, r.state)
    }

    @Test fun newCall_afterFailure_isNotBlockedByTheOldFailure() {
        val r = Rig()
        r.call("a")
        r.created[0].cb!!.onFailed(AudioUnavailableReason.AUDIO_INIT_FAILED, "boom")
        assertEquals(AudioSessionState.FAILED, r.state)
        r.session.onCallState(CallState.ACTIVE, "b")
        assertEquals(2, r.created.size)
        assertEquals(AudioSessionState.STARTING, r.state)
    }

    @Test fun failedCall_thenCallEnds_thenNextCallStartsFresh() {
        val r = Rig()
        r.call("a")
        r.created[0].cb!!.onFailed(AudioUnavailableReason.AUDIO_INIT_FAILED, "boom")
        r.session.onCallState(CallState.DISCONNECTED)
        assertEquals(AudioSessionState.IDLE, r.state)
        assertNull(r.session.status.reason)
        r.session.onCallState(CallState.ACTIVE, "b")
        assertEquals(AudioSessionState.STARTING, r.state)
        assertEquals(2, r.created.size)
    }

    @Test fun listenerCallingBackDuringRelease_neverOverlapsTwoBackends() {
        val r = Rig()
        r.call("a")
        r.created[0].cb!!.onActive()
        var reentered = false
        r.onStatus = { s ->
            if (s.state == AudioSessionState.STOPPING && !reentered) { reentered = true; r.session.start() }
        }
        r.session.stop()                                   // releases; the listener asks for audio again while STOPPING
        assertTrue(reentered)
        assertEquals(1, r.created[0].stops)
        assertEquals(2, r.created.size)
        assertEquals(listOf(false, false), r.overlapAtCreation)   // the old one was fully released before the new one existed
        assertEquals(AudioSessionState.STARTING, r.state)
    }

    @Test fun listenerCallingBackDuringFailureRelease_neverOverlaps() {
        val r = Rig()
        r.call("a")
        var reentered = false
        r.onStatus = { s ->
            if (s.state == AudioSessionState.STOPPING && !reentered) { reentered = true; r.session.start() }
        }
        r.created[0].cb!!.onFailed(AudioUnavailableReason.AUDIO_INIT_FAILED, "boom")
        assertEquals(1, r.created[0].stops)
        assertEquals(false, r.overlapAtCreation.drop(1).any { it })
    }

    @Test fun factoryThrowing_isFailed_notACrash_andOnlyAnExplicitStartRetries() {
        var boom = true
        val r = Rig(factoryThrows = { boom })
        r.call("a")
        assertEquals(AudioSessionState.FAILED, r.state)
        assertEquals(AudioUnavailableReason.AUDIO_INIT_FAILED, r.session.status.reason)
        assertEquals(0, r.created.size)
        r.session.onAppVisible(false); r.session.onAppVisible(true)
        assertEquals(0, r.created.size)
        boom = false
        r.session.start()
        assertEquals(1, r.created.size)
        assertEquals(AudioSessionState.STARTING, r.state)
    }

    @Test fun backendStopThrowing_isTolerated_andStateStillEnds() {
        val r = Rig(stopThrows = true)
        r.call("a")
        r.created[0].cb!!.onActive()
        r.session.onCallState(CallState.DISCONNECTED)
        assertEquals(AudioSessionState.IDLE, r.state)
        assertEquals(1, r.created[0].stops)
        r.session.onCallState(CallState.ACTIVE, "b")
        assertEquals(2, r.created.size)
    }

    @Test fun bluetoothDrop_releasesOnce_lateCallbacksIgnored_reconnectStartsOne() {
        val r = Rig()
        r.call("a")
        val first = r.created[0]
        first.cb!!.onConnected()
        r.session.onLink(false)
        assertEquals(1, first.stops)
        first.cb!!.onFailed(AudioUnavailableReason.AUDIO_INIT_FAILED, "late")   // arrives after the release
        first.cb!!.onActive()
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
        assertEquals(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, r.session.status.reason)
        r.session.onLink(false)                                                  // repeated "down": nothing happens
        r.session.onLink(true); r.session.onLink(true)
        assertEquals(2, r.created.size)
        assertEquals(1, first.stops)
        assertEquals(listOf(false, false), r.overlapAtCreation)
    }

    @Test fun callEnd_releasesEverything_andNextCallStartsWithMuteReset() {
        val r = Rig(mute = true)
        r.call("a")
        r.created[0].cb!!.onConnected()
        assertEquals(false, r.session.status.micMuted)
        assertTrue(r.session.setMicrophoneMuted(true))
        assertEquals(true, r.session.status.micMuted)
        r.session.onCallState(CallState.DISCONNECTED)
        assertEquals(AudioSessionState.IDLE, r.state)
        assertNull(r.session.status.micMuted)
        assertFalse(r.created[0].open)
        assertFalse(r.session.setMicrophoneMuted(true))          // nothing open: request refused, nothing created
        r.session.onCallState(CallState.ACTIVE, "b")
        r.created[1].cb!!.onConnected()
        assertEquals(false, r.session.status.micMuted)           // the muted state of the old call did not carry over
    }

    @Test fun repeatedCallEndAndStop_areHarmless() {
        val r = Rig()
        r.call("a")
        r.session.onCallState(CallState.DISCONNECTED)
        r.session.onCallState(CallState.IDLE)
        r.session.stop(); r.session.stop()
        assertEquals(1, r.created[0].stops)
        assertEquals(AudioSessionState.IDLE, r.state)
    }
}
