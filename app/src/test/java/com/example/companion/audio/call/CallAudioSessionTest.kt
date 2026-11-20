package com.example.companion.audio.call

import com.example.companion.audio.hfp.Tri
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests of the Stage 7 state machine using a fake backend. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available
 * when this stage was authored). They test the lifecycle rules only; they say nothing about real cellular audio.
 */
class CallAudioSessionTest {

    private class FakeBackend(
        override val mode: AudioMode = AudioMode.HFP,
        override val needsForeground: Boolean = false
    ) : CallAudioBackend {
        var callback: CallAudioBackend.Callback? = null
        var starts = 0
        var stops = 0
        override fun start(callback: CallAudioBackend.Callback) { starts++; this.callback = callback }
        override fun stop() { stops++ }
        val open get() = starts > 0 && stops == 0
    }

    private class Rig(var mode: AudioMode = AudioMode.HFP, val needsForeground: Boolean = false) {
        val created = ArrayList<FakeBackend>()
        val seen = ArrayList<AudioSessionState>()
        val session = CallAudioSession(
            capability = {
                CallAudioCapability(mode, if (mode == AudioMode.NONE) Tri.NO else Tri.YES,
                    if (mode == AudioMode.NONE) AudioUnavailableReason.AUDIO_API_UNAVAILABLE else null, "test",
                    MuteScope.SIM_PHONE_MICROPHONE, RouteScope.SIM_PHONE_SPEAKER)
            },
            backendFactory = { m -> FakeBackend(m, needsForeground).also { created += it } },
            onChanged = { seen += it.state }
        )
        val state get() = session.status.state
        fun activeCallWithLink() { session.onLink(true); session.onCallState(CallState.ACTIVE) }
    }

    @Test fun noPath_callActive_isUnavailable_andNothingIsCreated() {
        val r = Rig(AudioMode.NONE)
        r.activeCallWithLink()
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
        assertEquals(AudioUnavailableReason.AUDIO_API_UNAVAILABLE, r.session.status.reason)
        assertEquals(0, r.created.size)
        r.session.onCallState(CallState.DISCONNECTED)
        assertEquals(AudioSessionState.IDLE, r.state)
    }

    @Test fun happyPath_startingConnectedActive_thenCallEndReleases() {
        val r = Rig()
        r.activeCallWithLink()
        assertEquals(AudioSessionState.STARTING, r.state)
        r.created[0].callback!!.onConnected()
        assertEquals(AudioSessionState.CONNECTED, r.state)
        r.created[0].callback!!.onActive()
        assertEquals(AudioSessionState.ACTIVE, r.state)
        r.session.onCallState(CallState.DISCONNECTED)
        assertEquals(AudioSessionState.IDLE, r.state)
        assertEquals(1, r.created[0].stops)
    }

    @Test fun startingTwice_neverCreatesASecondBackend() {
        val r = Rig()
        r.activeCallWithLink()
        r.session.start(); r.session.start()
        assertEquals(1, r.created.size)
        assertEquals(1, r.created[0].starts)
    }

    @Test fun bluetoothDrop_releasesAtOnce_reconnectCreatesExactlyOneNewSession() {
        val r = Rig()
        r.activeCallWithLink()
        val first = r.created[0]
        first.callback!!.onActive()
        r.session.onLink(false)
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
        assertEquals(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, r.session.status.reason)
        assertEquals(1, first.stops)
        r.session.onLink(true)
        assertEquals(2, r.created.size)
        assertEquals(AudioSessionState.STARTING, r.state)
        first.callback!!.onActive()                       // stale callback from the released backend
        assertEquals(AudioSessionState.STARTING, r.state)
        assertEquals(1, first.stops)                      // not stopped twice
    }

    @Test fun failure_releasesBackend_isNotRetriedByItself_butExplicitStartRetries() {
        val r = Rig()
        r.activeCallWithLink()
        r.created[0].callback!!.onFailed(AudioUnavailableReason.AUDIO_INIT_FAILED, "boom")
        assertEquals(AudioSessionState.FAILED, r.state)
        assertEquals(1, r.created[0].stops)
        r.session.onAppVisible(false); r.session.onAppVisible(true)   // unrelated events must not retry a failed start
        assertEquals(1, r.created.size)
        assertEquals(AudioSessionState.FAILED, r.state)
        r.session.start()
        assertEquals(2, r.created.size)
        assertEquals(AudioSessionState.STARTING, r.state)
    }

    @Test fun explicitStop_releases_andStaysOffUntilNextStart() {
        val r = Rig()
        r.activeCallWithLink()
        r.session.stop()
        assertEquals(AudioSessionState.IDLE, r.state)
        assertEquals(1, r.created[0].stops)
        r.session.onLink(false); r.session.onLink(true)
        assertEquals(1, r.created.size)
        r.session.start()
        assertEquals(2, r.created.size)
    }

    @Test fun microphoneBackend_stopsWhenAppNotVisible_andResumesWhenVisible() {
        val r = Rig(needsForeground = true)
        r.activeCallWithLink()
        assertEquals(AudioSessionState.STARTING, r.state)
        r.session.onAppVisible(false)
        assertEquals(AudioSessionState.UNAVAILABLE, r.state)
        assertEquals(1, r.created[0].stops)
        r.session.onAppVisible(true)
        assertEquals(2, r.created.size)
    }

    @Test fun holdingCall_releasesAudio() {
        val r = Rig()
        r.activeCallWithLink()
        r.session.onCallState(CallState.HOLDING)
        assertEquals(AudioSessionState.IDLE, r.state)
        assertEquals(1, r.created[0].stops)
    }

    @Test fun noCall_nothingHappens() {
        val r = Rig()
        r.session.onLink(true); r.session.onLink(false); r.session.onAppVisible(false)
        assertEquals(AudioSessionState.IDLE, r.state)
        assertEquals(0, r.created.size)
        assertNull(r.session.status.reason)
    }
}
