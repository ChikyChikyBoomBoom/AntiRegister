package com.example.companion.audio.call

import com.example.companion.audio.hfp.Tri
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 21 JVM tests with a fake backend. WRITTEN BUT NOT RUN. They test lifecycle/state rules only, not real audio. */
class CallAudioControlsTest {

    private class Fake(
        override val supportsMute: Boolean = true,
        override val supportsSpeaker: Boolean = true
    ) : CallAudioBackend {
        override val mode = AudioMode.HFP
        override val needsForeground = false
        var cb: CallAudioBackend.Callback? = null
        var starts = 0; var stops = 0
        var muteRequests = ArrayList<Boolean>(); var speakerRequests = ArrayList<Boolean>()
        override fun start(callback: CallAudioBackend.Callback) { starts++; cb = callback }
        override fun stop() { stops++ }
        override fun setMuted(muted: Boolean) { muteRequests += muted }
        override fun setSpeaker(on: Boolean) { speakerRequests += on }
    }

    private class Rig(val mode: AudioMode = AudioMode.HFP, mute: Boolean = true, speaker: Boolean = true) {
        val created = ArrayList<Fake>()
        val session = CallAudioSession(
            capability = {
                CallAudioCapability(mode, if (mode == AudioMode.NONE) Tri.NO else Tri.YES,
                    if (mode == AudioMode.NONE) AudioUnavailableReason.AUDIO_API_UNAVAILABLE else null, "test",
                    MuteScope.NONE, RouteScope.NONE)
            },
            backendFactory = { Fake(mute, speaker).also { created += it } },
            onChanged = {}
        )
        val status get() = session.status
        fun start() { session.onLink(true); session.onCallState(CallState.ACTIVE) }
    }

    @Test fun indicator_mapsStatesHonestly() {
        fun ind(s: AudioSessionState) = CallAudioStatus(s, AudioMode.HFP, null, "").indicator
        assertEquals(AudioIndicator.CONNECTING, ind(AudioSessionState.STARTING))
        assertEquals(AudioIndicator.CONNECTED, ind(AudioSessionState.CONNECTED))
        assertEquals(AudioIndicator.CONNECTED, ind(AudioSessionState.ACTIVE))
        listOf(AudioSessionState.IDLE, AudioSessionState.STOPPING, AudioSessionState.FAILED, AudioSessionState.UNAVAILABLE)
            .forEach { assertEquals(AudioIndicator.UNAVAILABLE, ind(it)) }
    }

    @Test fun noPath_controlsUnsupported_andRequestsAreRefused() {
        val r = Rig(AudioMode.NONE)
        r.start()
        assertEquals(AudioIndicator.UNAVAILABLE, r.status.indicator)
        assertFalse(r.status.muteSupported); assertFalse(r.status.speakerSupported)
        assertFalse(r.session.setMicrophoneMuted(true)); assertFalse(r.session.setSpeakerOn(true))
        assertEquals(0, r.created.size)
    }

    @Test fun beforeConnected_controlsAreRefused() {
        val r = Rig(); r.start()
        assertEquals(AudioIndicator.CONNECTING, r.status.indicator)
        assertFalse(r.session.setMicrophoneMuted(true))
        assertTrue(r.created[0].muteRequests.isEmpty())
    }

    @Test fun mute_followsBackendReport_andKeepsAudioOpen() {
        val r = Rig(); r.start(); val b = r.created[0]
        b.cb!!.onConnected()
        assertTrue(r.status.muteSupported); assertEquals(false, r.status.micMuted)
        assertTrue(r.session.setMicrophoneMuted(true))
        assertEquals(false, r.status.micMuted)          // request alone changes nothing shown
        b.cb!!.onMuteChanged(true)
        assertEquals(true, r.status.micMuted)
        assertEquals(AudioIndicator.CONNECTED, r.status.indicator)
        assertEquals(1, b.starts); assertEquals(0, b.stops)   // audio never reopened or released
    }

    @Test fun speaker_followsBackendReport_andKeepsAudioOpen() {
        val r = Rig(); r.start(); val b = r.created[0]
        b.cb!!.onActive()
        assertTrue(r.session.setSpeakerOn(true))
        assertEquals(false, r.status.speakerOn)
        b.cb!!.onSpeakerChanged(true)
        assertEquals(true, r.status.speakerOn)
        assertEquals(1, r.created.size); assertEquals(0, b.stops)
    }

    @Test fun backendWithoutSupport_isRefused() {
        val r = Rig(mute = false, speaker = false); r.start(); r.created[0].cb!!.onConnected()
        assertFalse(r.status.muteSupported); assertNull(r.status.micMuted)
        assertFalse(r.session.setMicrophoneMuted(true)); assertFalse(r.session.setSpeakerOn(true))
        r.created[0].cb!!.onMuteChanged(true)             // a stray report for an unsupported control is ignored
        assertNull(r.status.micMuted)
    }

    @Test fun callEnd_releasesBackend_andClearsControls() {
        val r = Rig(); r.start(); val b = r.created[0]
        b.cb!!.onConnected(); b.cb!!.onMuteChanged(true)
        r.session.onCallState(CallState.DISCONNECTED)
        assertEquals(1, b.stops)
        assertEquals(AudioSessionState.IDLE, r.status.state)
        assertNull(r.status.micMuted); assertNull(r.status.speakerOn); assertFalse(r.status.muteSupported)
        assertFalse(r.session.setMicrophoneMuted(false))
    }

    @Test fun nextCall_startsUnmuted() {
        val r = Rig(); r.start(); r.created[0].cb!!.onConnected(); r.created[0].cb!!.onMuteChanged(true)
        r.session.onCallState(CallState.DISCONNECTED)
        r.session.onCallState(CallState.ACTIVE)
        r.created[1].cb!!.onConnected()
        assertEquals(false, r.status.micMuted)
    }

    @Test fun bluetoothLoss_releasesAndShowsUnavailable_noStaleMute() {
        val r = Rig(); r.start(); val b = r.created[0]
        b.cb!!.onConnected(); b.cb!!.onMuteChanged(true)
        r.session.onLink(false)
        assertEquals(1, b.stops)
        assertEquals(AudioIndicator.UNAVAILABLE, r.status.indicator)
        assertNull(r.status.micMuted)
        b.cb!!.onMuteChanged(false)                        // late callback from a released backend is ignored
        assertNull(r.status.micMuted)
    }
}
