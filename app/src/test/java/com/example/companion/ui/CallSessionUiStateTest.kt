package com.example.companion.ui

import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of Stage 27: session ids in the call-screen and notification rules. WRITTEN BUT NOT RUN. */
class CallSessionUiStateTest {

    private val none: (String) -> String? = { null }

    // ---- shouldOpen
    @Test fun aDifferentSessionRingingAfterARememberedRingOpensTheScreen() {
        assertTrue(CallScreenState.shouldOpen(CallState.RINGING, CallState.RINGING, "A", "B"))
    }
    @Test fun theSameSessionRingingAgainDoesNotReopenIt() {
        assertFalse(CallScreenState.shouldOpen(CallState.RINGING, CallState.RINGING, "A", "A"))
    }
    @Test fun unknownIdsKeepTheStage17Behaviour() {
        assertFalse(CallScreenState.shouldOpen(CallState.RINGING, CallState.RINGING, null, "B"))
        assertFalse(CallScreenState.shouldOpen(CallState.RINGING, CallState.RINGING, "A", null))
    }
    @Test fun anotherSessionBecomingActiveDoesNotPullTheScreenForward() {
        assertFalse(CallScreenState.shouldOpen(CallState.RINGING, CallState.ACTIVE, "B", "A"))
    }

    // ---- actionAllowed
    @Test fun aPressOnAnEarlierCallsNotificationSendsNothing() {
        assertFalse(CallNotificationState.actionAllowed(CallState.RINGING, true, false, pressedFor = "A", current = "B"))
    }
    @Test fun aPressForTheRingingSessionIsAllowed() {
        assertTrue(CallNotificationState.actionAllowed(CallState.RINGING, true, false, pressedFor = "B", current = "B"))
    }
    @Test fun anUnknownIdNeverBlocksAPress() {
        assertTrue(CallNotificationState.actionAllowed(CallState.RINGING, true, false, pressedFor = null, current = "B"))
        assertTrue(CallNotificationState.actionAllowed(CallState.RINGING, true, false, pressedFor = "A", current = null))
    }

    // ---- content
    @Test fun theSameCallerOnADifferentSessionIsDifferentContent() {
        val a = CallNotificationState.content("+15551234", none, null, "", "A")
        val b = CallNotificationState.content("+15551234", none, null, "", "B")
        assertTrue(a != b)
        assertEquals(a, CallNotificationState.content("+15551234", none, null, "", "A"))
    }

    // ---- isRepeatAlert
    private val memory = CallNotificationState.RingMemory("+15551234", removedAt = 1_000)

    @Test fun aSessionAlreadyAnnouncedStaysSilentWhateverHappenedInBetween() {
        assertTrue(CallNotificationState.isRepeatAlert("A", "A", null, "+15551234", now = 10 * 60 * 1000))
    }
    @Test fun aNewSessionAlertsEvenWithTheSameNumberRightAfterALinkDrop() {
        assertFalse(CallNotificationState.isRepeatAlert("B", "A", memory, "+15551234", now = 2_000))
    }
    @Test fun theFirstRingOfASessionAlerts() {
        assertFalse(CallNotificationState.isRepeatAlert("A", null, null, "+15551234", now = 2_000))
    }
    @Test fun withoutAnIdTheStage17RuleApplies() {
        assertTrue(CallNotificationState.isRepeatAlert(null, null, memory, "+15551234", now = 2_000))
        assertFalse(CallNotificationState.isRepeatAlert(null, null, null, "+15551234", now = 2_000))
    }
}
