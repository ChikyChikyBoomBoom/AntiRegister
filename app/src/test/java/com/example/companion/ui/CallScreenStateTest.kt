package com.example.companion.ui

import com.example.companion.audio.call.AudioSessionState
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import com.example.companion.protocol.SimAudioReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Main Phone call screen's decisions. WRITTEN BUT NOT RUN. */
class CallScreenStateTest {

    private fun ui(
        snapshot: CallSnapshot?,
        linkUp: Boolean = true,
        lastLive: CallSnapshot? = null,
        ended: EndedCall? = null,
        control: Boolean? = true,
        audio: SimAudioReport? = SimAudioReport(micMuted = false, speakerOn = false),
        seconds: Long? = null,
        audioState: AudioSessionState? = AudioSessionState.UNAVAILABLE
    ) = CallScreenState.from(
        CallScreenInput(linkUp, "disconnected", snapshot, lastLive, ended, control, audio, seconds, audioState, "no path")
    )

    private fun snap(state: CallState, number: String? = "+15551234", cause: String? = null) =
        CallSnapshot(state, number, null, cause)

    @Test fun linkDownIsAlwaysSimUnavailableWhateverTheSnapshot() {
        CallState.values().forEach {
            val u = ui(snap(it), linkUp = false)
            assertEquals(it.name, CallScreenMode.SIM_UNAVAILABLE, u.mode)
            assertFalse(u.showAnswer || u.showReject || u.showHangup || u.showAudioControls)
        }
        assertEquals(CallScreenMode.SIM_UNAVAILABLE, ui(null, linkUp = false).mode)
    }

    @Test fun unavailableViewNamesLastCallAndWarnsItMayContinue() {
        val u = ui(null, linkUp = false, lastLive = snap(CallState.ACTIVE))
        assertTrue(u.numberLine.contains("+15551234"))
        assertTrue(u.statusLine.contains("may still be in progress"))
        assertFalse(ui(null, linkUp = false).statusLine.contains("may still be in progress"))
    }

    @Test fun linkUpWithoutSnapshotIsSyncing() {
        val u = ui(null)
        assertEquals(CallScreenMode.SYNCING, u.mode)
        assertFalse(u.showHangup)
    }

    @Test fun incomingShowsAnswerAndRejectOnly() {
        val u = ui(snap(CallState.RINGING))
        assertEquals(CallScreenMode.INCOMING, u.mode)
        assertTrue(u.showAnswer && u.showReject)
        assertFalse(u.showHangup || u.showAudioControls)
        assertEquals("+15551234", u.numberLine)
    }

    @Test fun missingNumberIsLabelledUnknownNotInvented() {
        assertEquals(CallScreenState.UNKNOWN_NUMBER, ui(snap(CallState.RINGING, number = null)).numberLine)
    }

    @Test fun outgoingStatesShowHangUpAndCallingText() {
        listOf(CallState.DIALING, CallState.CONNECTING, CallState.SELECT_ACCOUNT).forEach {
            val u = ui(snap(it))
            assertEquals(it.name, CallScreenMode.OUTGOING, u.mode)
            assertTrue(u.showHangup)
            assertFalse(u.showAnswer || u.showReject || u.showAudioControls)
        }
        assertEquals("Calling…", ui(snap(CallState.DIALING)).statusLine)
    }

    @Test fun activeShowsDurationAndAudioControlsWhenReported() {
        val u = ui(snap(CallState.ACTIVE), seconds = 65)
        assertEquals(CallScreenMode.ACTIVE, u.mode)
        assertEquals("01:05", u.durationLine)
        assertTrue(u.showHangup && u.showAudioControls && u.muteEnabled && u.speakerEnabled)
    }

    @Test fun audioControlsAreDisabledWhenSimPhoneReportsNothing() {
        val u = ui(snap(CallState.ACTIVE), audio = null)
        assertTrue(u.showAudioControls)
        assertFalse(u.muteEnabled || u.speakerEnabled)
    }

    @Test fun muteStateFollowsTheReportNotTheRequest() {
        assertEquals(true, ui(snap(CallState.ACTIVE), audio = SimAudioReport(true, false)).muteOn)
        assertEquals(false, ui(snap(CallState.ACTIVE), audio = SimAudioReport(false, true)).muteOn)
    }

    @Test fun holdingKeepsHangUpButNoDurationAndNoAudioToggles() {
        val u = ui(snap(CallState.HOLDING), seconds = 30)
        assertEquals(CallScreenMode.ACTIVE, u.mode)
        assertEquals("", u.durationLine)
        assertTrue(u.showHangup)
        assertFalse(u.muteEnabled || u.speakerEnabled)
    }

    @Test fun activeCallWithoutAudioPathSaysSoAndNeverImpliesLocalAudio() {
        val u = ui(snap(CallState.ACTIVE), audioState = AudioSessionState.UNAVAILABLE)
        assertTrue(u.audioNote.contains("audio unavailable"))
        assertTrue(u.audioNote.contains("NOT part of the call"))
        assertEquals("", ui(snap(CallState.ACTIVE), audioState = AudioSessionState.ACTIVE).audioNote)
    }

    @Test fun endingAndEndedHaveNoControls() {
        val ending = ui(snap(CallState.DISCONNECTING))
        assertEquals(CallScreenMode.ENDING, ending.mode)
        assertFalse(ending.showHangup)
        val ended = ui(snap(CallState.DISCONNECTED, cause = "REMOTE"))
        assertEquals(CallScreenMode.ENDED, ended.mode)
        assertEquals("Reason: REMOTE", ended.statusLine)
    }

    @Test fun idleAfterACallStillShowsEndedWithRememberedNumber() {
        val u = ui(snap(CallState.IDLE, number = null), ended = EndedCall("+15559999", null))
        assertEquals(CallScreenMode.ENDED, u.mode)
        assertEquals("+15559999", u.numberLine)
        assertEquals(CallScreenMode.IDLE, ui(CallSnapshot.IDLE).mode)
    }

    @Test fun controlUnavailableShowsBannerOnlyWhileLinked() {
        assertTrue(ui(snap(CallState.RINGING), control = false).banner.isNotEmpty())
        assertEquals("", ui(snap(CallState.RINGING), control = null).banner)
        assertEquals("", ui(snap(CallState.RINGING), control = true).banner)
    }

    @Test fun isLiveCoversExactlyTheInCallStates() {
        val live = setOf(CallState.RINGING, CallState.CONNECTING, CallState.SELECT_ACCOUNT,
            CallState.DIALING, CallState.ACTIVE, CallState.HOLDING)
        CallState.values().forEach { assertEquals(it.name, it in live, CallScreenState.isLive(it)) }
        assertFalse(CallScreenState.isLive(null))
    }

    @Test fun durationFormat() {
        assertEquals("00:00", CallScreenState.formatDuration(0))
        assertEquals("59:59", CallScreenState.formatDuration(3599))
        assertEquals("1:00:00", CallScreenState.formatDuration(3600))
        assertEquals("00:00", CallScreenState.formatDuration(-5))
    }

    // ---- Stage 17: when the call screen is brought forward

    @Test fun reconnectReReportingTheSameLiveCallDoesNotReopenTheScreen() {
        assertFalse(CallScreenState.shouldOpen(previous = CallState.ACTIVE, current = CallState.ACTIVE))
        assertFalse(CallScreenState.shouldOpen(previous = CallState.RINGING, current = CallState.RINGING))
    }

    @Test fun aNewLiveCallOpensTheScreen() {
        assertTrue(CallScreenState.shouldOpen(previous = null, current = CallState.RINGING))
        assertTrue(CallScreenState.shouldOpen(previous = CallState.IDLE, current = CallState.DIALING))
        assertTrue(CallScreenState.shouldOpen(previous = CallState.DISCONNECTED, current = CallState.ACTIVE))
    }

    @Test fun aNewRingAfterACallThatEndedWhileOfflineOpensTheScreen() {
        assertTrue(CallScreenState.shouldOpen(previous = CallState.ACTIVE, current = CallState.RINGING))
    }

    @Test fun nonLiveStatesNeverOpenTheScreen() {
        assertFalse(CallScreenState.shouldOpen(previous = CallState.ACTIVE, current = CallState.IDLE))
        assertFalse(CallScreenState.shouldOpen(previous = null, current = null))
    }

    // ---- Stage 21

    private fun uiAudio(
        state: AudioSessionState?, call: CallState = CallState.ACTIVE,
        muteSup: Boolean = false, muted: Boolean? = null, spkSup: Boolean = false, spk: Boolean? = null
    ) = CallScreenState.from(
        CallScreenInput(true, "disconnected", snap(call), null, null, true, SimAudioReport(false, false), null, state, "no path",
            phoneMuteSupported = muteSup, phoneMicMuted = muted, phoneSpeakerSupported = spkSup, phoneSpeakerOn = spk)
    )

    @Test fun audioStateLine_threeStates_neverFalselyConnected() {
        assertEquals("Audio Connected", uiAudio(AudioSessionState.ACTIVE).audioStateLine)
        assertEquals("Audio Connected", uiAudio(AudioSessionState.CONNECTED).audioStateLine)
        assertEquals("Connecting", uiAudio(AudioSessionState.STARTING).audioStateLine)
        listOf(AudioSessionState.UNAVAILABLE, AudioSessionState.FAILED, AudioSessionState.IDLE, AudioSessionState.STOPPING, null)
            .forEach { assertEquals("Audio Unavailable", uiAudio(it).audioStateLine) }
    }

    @Test fun audioStateLine_onlyWhileConnectedCall() {
        assertEquals("", uiAudio(AudioSessionState.ACTIVE, CallState.HOLDING).audioStateLine)
        assertEquals("", uiAudio(AudioSessionState.ACTIVE, CallState.DIALING).audioStateLine)
    }

    @Test fun idleOrUnknownAudioDuringCall_showsUnavailableNote() {
        assertTrue(uiAudio(AudioSessionState.IDLE).audioNote.contains("audio unavailable"))
        assertTrue(uiAudio(null).audioNote.contains("audio unavailable"))
        assertEquals("", uiAudio(AudioSessionState.STARTING).audioNote)
    }

    @Test fun phoneControls_hiddenWhenUnsupported_evenIfStateLeaks() {
        val u = uiAudio(AudioSessionState.ACTIVE)
        assertFalse(u.showPhoneAudioControls); assertFalse(u.phoneMuteEnabled || u.phoneSpeakerEnabled)
        val unavailable = uiAudio(AudioSessionState.UNAVAILABLE, muteSup = true, muted = true, spkSup = true, spk = true)
        assertFalse(unavailable.phoneMuteEnabled || unavailable.phoneSpeakerEnabled)
        assertEquals(null, unavailable.phoneMuteOn)
    }

    @Test fun phoneControls_followReportedState_whenConnectedAndSupported() {
        val u = uiAudio(AudioSessionState.ACTIVE, muteSup = true, muted = true, spkSup = true, spk = false)
        assertTrue(u.showPhoneAudioControls)
        assertTrue(u.phoneMuteEnabled); assertEquals(true, u.phoneMuteOn)
        assertTrue(u.phoneSpeakerEnabled); assertEquals(false, u.phoneSpeakerOn)
        val connecting = uiAudio(AudioSessionState.STARTING, muteSup = true, muted = false)
        assertFalse(connecting.phoneMuteEnabled)
    }
}
