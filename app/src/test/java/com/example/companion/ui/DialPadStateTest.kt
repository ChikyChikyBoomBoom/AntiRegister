package com.example.companion.ui

import com.example.companion.protocol.CallCommand
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Main Phone dial pad's decisions. WRITTEN BUT NOT RUN. */
class DialPadStateTest {

    private fun ui(
        number: String, linkUp: Boolean = true, callLive: Boolean = false,
        awaiting: Boolean = false, failure: String? = null
    ) = DialPadState.from(number, linkUp, callLive, awaiting, failure)

    @Test fun keypadHasTwelveStandardKeys() {
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"), DialPadState.KEYS.flatten())
    }

    @Test fun appendKeepsOrderAndIgnoresUnknownKeys() {
        var n = ""
        listOf("1", "*", "0", "#").forEach { n = DialPadState.append(n, it) }
        assertEquals("1*0#", n)
        assertEquals("1*0#", DialPadState.append(n, "a"))
        assertEquals("1*0#", DialPadState.append(n, "12"))
        assertEquals("1*0#", DialPadState.append(n, ""))
    }

    @Test fun lengthIsCapped() {
        val full = "1".repeat(DialPadState.MAX_LENGTH)
        assertEquals(full, DialPadState.append(full, "2"))
    }

    @Test fun deleteRemovesLastAndIsSafeWhenEmpty() {
        assertEquals("12", DialPadState.deleteLast("123"))
        assertEquals("", DialPadState.deleteLast(""))
        assertEquals("", DialPadState.clear())
    }

    @Test fun emptyNumberCannotBeDialed() {
        val u = ui("")
        assertFalse(u.callEnabled); assertFalse(u.deleteEnabled); assertFalse(u.clearEnabled)
        assertTrue(u.isEmpty); assertEquals(DialPadState.PLACEHOLDER, u.display)
    }

    @Test fun numberIsShownExactlyAsTyped() {
        assertEquals("0123456789", ui("0123456789").display)
    }

    @Test fun disconnectedSimPhoneDisablesCallButNotEditing() {
        val u = ui("5551234", linkUp = false)
        assertFalse(u.callEnabled)
        assertTrue(u.keysEnabled); assertTrue(u.deleteEnabled); assertTrue(u.clearEnabled)
        assertTrue(u.message.startsWith("SIM Phone not connected"))
    }

    @Test fun connectedWithDigitsEnablesCall() {
        val u = ui("5551234")
        assertTrue(u.callEnabled); assertEquals("Call", u.callLabel); assertEquals("", u.message)
    }

    @Test fun starAndPoundAreEnteredButNotSentBecauseTheSimPhoneRefusesThem() {
        assertFalse(ui("*21#").callEnabled)
        assertFalse(ui("555#").callEnabled)
        assertTrue(ui("*21#").message.contains("digits only"))
        assertTrue(DialPadState.simAccepts("0123456789"))
        assertTrue(DialPadState.simAccepts("+15551234"))
        assertFalse(DialPadState.simAccepts(""))
        assertFalse(DialPadState.simAccepts("12*3"))
        assertFalse(DialPadState.simAccepts("1".repeat(33)))
    }

    @Test fun whileWaitingForTheSimPhoneNothingCanBePressedTwice() {
        val u = ui("5551234", awaiting = true)
        assertFalse(u.callEnabled); assertFalse(u.keysEnabled); assertFalse(u.deleteEnabled); assertFalse(u.clearEnabled)
        assertEquals("Calling…", u.callLabel)
    }

    @Test fun liveCallBlocksSecondDialAndOffersReturn() {
        val u = ui("5551234", callLive = true)
        assertFalse(u.callEnabled); assertTrue(u.showReturnToCall)
        assertFalse(ui("5551234", linkUp = false, callLive = true).showReturnToCall)
    }

    @Test fun failureIsShownAndNumberStaysDialable() {
        val u = ui("5551234", failure = "another call is in progress")
        assertTrue(u.callEnabled)
        assertEquals("Could not dial: another call is in progress", u.message)
    }

    @Test fun spokenFormNamesStarAndPound() {
        assertEquals("1 star 2 pound", DialPadState.spoken("1*2#"))
        assertEquals("No number entered", DialPadState.spoken(""))
    }

    @Test fun outcomeWaitsUntilTheSimPhoneReportsSomething() {
        assertEquals(DialOutcome.WAITING, DialPadState.outcome(CallState.IDLE, CallState.IDLE, null, null))
        assertEquals(DialOutcome.WAITING, DialPadState.outcome(null, null, null, null))
        assertEquals(DialOutcome.WAITING, DialPadState.outcome(CallState.DISCONNECTED, CallState.DISCONNECTED, null, null))
    }

    @Test fun outcomeCallStartedOnLiveStates() {
        listOf(CallState.DIALING, CallState.CONNECTING, CallState.SELECT_ACCOUNT, CallState.ACTIVE).forEach {
            assertEquals(it.name, DialOutcome.CALL_STARTED, DialPadState.outcome(CallState.IDLE, it, null, null))
        }
    }

    @Test fun outcomeCallFailingAtOnceStillOpensTheCallScreen() {
        assertEquals(DialOutcome.CALL_STARTED, DialPadState.outcome(CallState.IDLE, CallState.DISCONNECTED, null, null))
    }

    @Test fun outcomeFailedOnlyForDialErrors() {
        assertEquals(DialOutcome.FAILED, DialPadState.outcome(CallState.IDLE, CallState.IDLE, "NO_ROLE", CallCommand.DIAL))
        assertEquals(DialOutcome.WAITING, DialPadState.outcome(CallState.IDLE, CallState.IDLE, "ROUTE_UNSUPPORTED", CallCommand.SPEAKER))
    }
}
