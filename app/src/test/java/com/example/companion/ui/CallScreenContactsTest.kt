package com.example.companion.ui

import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests of contact names on the call screen (Stage 19). WRITTEN BUT NOT RUN. */
class CallScreenContactsTest {

    private val names = mapOf("+15551234" to "Ann")
    private fun ui(
        snapshot: CallSnapshot?, linkUp: Boolean = true, lastLive: CallSnapshot? = null, ended: EndedCall? = null,
        lookup: (String) -> String? = { names[it] }
    ) = CallScreenState.from(CallScreenInput(linkUp, "disconnected", snapshot, lastLive, ended, nameFor = lookup))

    private fun snap(state: CallState, number: String? = "+15551234") = CallSnapshot(state, number, null, null)

    @Test fun incomingCallShowsNameAndKeepsTheNumber() {
        val u = ui(snap(CallState.RINGING))
        assertEquals("Ann", u.nameLine)
        assertEquals("+15551234", u.numberLine)
        assertEquals("+15551234", u.callerNumber)
        assertEquals(CallScreenMode.INCOMING, u.mode)
    }

    @Test fun activeCallShowsName() { assertEquals("Ann", ui(snap(CallState.ACTIVE)).nameLine) }

    @Test fun unknownNumberHasNoNameLine() {
        val u = ui(snap(CallState.RINGING, "+15550000"))
        assertEquals("", u.nameLine)
        assertEquals("+15550000", u.numberLine)
    }

    @Test fun withheldNumberIsNotLookedUp() {
        var asked = false
        val u = ui(snap(CallState.RINGING, null)) { asked = true; "Nobody" }
        assertEquals("", u.nameLine); assertNull(u.callerNumber); assertEquals(false, asked)
        assertEquals(CallScreenState.UNKNOWN_NUMBER, u.numberLine)
    }

    @Test fun endedCallUsesTheEndedNumber() {
        val u = ui(snap(CallState.IDLE, null), ended = EndedCall("+15551234", "REMOTE"))
        assertEquals("Ann", u.nameLine)
        assertEquals(CallScreenMode.ENDED, u.mode)
    }

    @Test fun disconnectedWithoutNumberFallsBackToTheLastLiveCall() {
        val u = ui(snap(CallState.DISCONNECTED, null), lastLive = snap(CallState.ACTIVE))
        assertEquals("Ann", u.nameLine)
    }

    @Test fun linkDownNamesTheLastKnownCall() {
        val u = ui(null, linkUp = false, lastLive = snap(CallState.ACTIVE))
        assertEquals("Ann", u.nameLine)
        assertEquals(CallScreenMode.SIM_UNAVAILABLE, u.mode)
    }

    @Test fun noCallMeansNoName() {
        val u = ui(snap(CallState.IDLE, null))
        assertEquals("", u.nameLine); assertNull(u.callerNumber)
    }

    @Test fun lookingUpANameChangesNothingElse() {
        val plain = CallScreenState.from(CallScreenInput(true, "disconnected", snap(CallState.RINGING)))
        val named = ui(snap(CallState.RINGING))
        assertEquals(plain, named.copy(nameLine = ""))
    }
}
