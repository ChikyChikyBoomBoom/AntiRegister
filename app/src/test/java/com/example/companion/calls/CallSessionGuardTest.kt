package com.example.companion.calls

import com.example.companion.protocol.CallDirection
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Stage 27 stale-report filter. WRITTEN BUT NOT RUN. */
class CallSessionGuardTest {

    private val g = CallSessionGuard()
    private fun s(state: CallState, id: String? = "A") = CallSnapshot(state, "+15551234", CallDirection.INCOMING, callId = id)

    @Test fun repeatedRingingOfTheSameSessionIsAccepted() {
        assertTrue(g.accept(s(CallState.RINGING))); assertTrue(g.accept(s(CallState.RINGING)))
    }

    @Test fun ringingAfterTheCallWasAnsweredIsStale() {
        assertTrue(g.accept(s(CallState.RINGING))); assertTrue(g.accept(s(CallState.ACTIVE)))
        assertFalse(g.accept(s(CallState.RINGING)))
    }

    @Test fun anythingLiveAfterTheEndIsStale() {
        g.accept(s(CallState.RINGING)); assertTrue(g.accept(s(CallState.DISCONNECTED)))
        CallState.values().filter { it != CallState.IDLE && it != CallState.DISCONNECTED }.forEach {
            assertFalse("state $it", g.accept(s(it)))
        }
        assertTrue(g.hasEnded("A"))
    }

    @Test fun aSecondDisconnectedIsDroppedWhileAStateIsKnown() {
        g.accept(s(CallState.ACTIVE)); g.accept(s(CallState.DISCONNECTED))
        assertFalse(g.accept(s(CallState.DISCONNECTED), haveState = true))
    }

    @Test fun aRepeatedDisconnectedIsStillShownWhenNothingIsKnown() {
        g.accept(s(CallState.ACTIVE)); g.accept(s(CallState.DISCONNECTED))
        assertTrue(g.accept(s(CallState.DISCONNECTED), haveState = false))
    }

    @Test fun aNewSessionIsAcceptedAfterAnEndedOne() {
        g.accept(s(CallState.RINGING, "A")); g.accept(s(CallState.DISCONNECTED, "A"))
        assertTrue(g.accept(s(CallState.RINGING, "B")))
    }

    @Test fun callWaitingAlternatingBetweenTwoLiveSessionsIsAccepted() {
        assertTrue(g.accept(s(CallState.ACTIVE, "A")))
        assertTrue(g.accept(s(CallState.RINGING, "B")))
        assertTrue(g.accept(s(CallState.ACTIVE, "A")))      // B was declined, A is primary again
        assertTrue(g.accept(s(CallState.HOLDING, "A")))
    }

    @Test fun reportsWithoutAnIdAreAlwaysAccepted() {
        assertTrue(g.accept(s(CallState.ACTIVE, null))); assertTrue(g.accept(s(CallState.RINGING, null)))
        assertTrue(g.accept(s(CallState.DISCONNECTED, null))); assertTrue(g.accept(s(CallState.RINGING, null)))
        assertTrue(g.accept(CallSnapshot.IDLE))
    }

    @Test fun idleIsAlwaysAccepted() {
        g.accept(s(CallState.ACTIVE)); g.accept(s(CallState.DISCONNECTED))
        assertTrue(g.accept(CallSnapshot.IDLE))
    }

    @Test fun onlyTheNewestSessionsAreRemembered() {
        g.accept(s(CallState.DISCONNECTED, "old"))
        repeat(CallSessionGuard.MAX_REMEMBERED) { g.accept(s(CallState.DISCONNECTED, "n$it")) }
        assertFalse(g.hasEnded("old"))
        assertTrue(g.hasEnded("n${CallSessionGuard.MAX_REMEMBERED - 1}"))
    }
}
