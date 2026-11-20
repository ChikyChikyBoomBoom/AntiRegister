package com.example.companion.calls

import com.example.companion.protocol.CallDirection
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of Stage 27: call-session ids in the Recent Calls tracker. WRITTEN BUT NOT RUN. */
class CallHistoryTrackerSessionTest {

    private class FakeClock(var wall: Long = 1_000_000L, var mono: Long = 0L) : CallHistoryTracker.Clock {
        override fun wallMs() = wall
        override fun monotonicMs() = mono
    }

    private val clock = FakeClock()
    private val t = CallHistoryTracker(clock)

    private fun snap(state: CallState, id: String?, number: String? = "+15551234", dir: CallDirection? = CallDirection.INCOMING, cause: String? = null) =
        CallSnapshot(state, number, dir, cause, id)

    @Test fun entryIdComesFromTheSessionId() {
        t.onSnapshot(snap(CallState.RINGING, "A"))
        val e = t.onSnapshot(snap(CallState.DISCONNECTED, "A", cause = "MISSED")).single()
        assertEquals("c|A", e.id)
    }

    @Test fun sameCallRebuiltByANewTrackerGetsTheSameEntryId() {
        // The Main Phone's process restarted mid-call: a fresh tracker sees the call for the first time, later than the first one did.
        t.onSnapshot(snap(CallState.ACTIVE, "A"))
        val first = t.onSnapshot(snap(CallState.DISCONNECTED, "A", cause = "REMOTE")).single()
        clock.wall += 90_000
        val t2 = CallHistoryTracker(clock)
        t2.onSnapshot(snap(CallState.ACTIVE, "A"))
        val second = t2.onSnapshot(snap(CallState.DISCONNECTED, "A", cause = "REMOTE")).single()
        assertEquals(first.id, second.id)               // the store's primary key then ignores the second insert
        assertNotEquals(first.startedAt, second.startedAt)
    }

    @Test fun twoCallsFromTheSameNumberAreTwoEntries() {
        t.onSnapshot(snap(CallState.RINGING, "A"))
        val a = t.onSnapshot(snap(CallState.DISCONNECTED, "A", cause = "MISSED")).single()
        t.onSnapshot(snap(CallState.RINGING, "B"))
        val b = t.onSnapshot(snap(CallState.DISCONNECTED, "B", cause = "MISSED")).single()
        assertNotEquals(a.id, b.id)
    }

    @Test fun callWaitingFromTheSameNumberIsToldApartById() {
        t.onSnapshot(snap(CallState.ACTIVE, "A"))
        t.onSnapshot(snap(CallState.RINGING, "B"))           // same number, different session
        assertEquals(2, t.liveCount)
        t.onSnapshot(snap(CallState.ACTIVE, "A"))            // A primary again: still two, not three
        assertEquals(2, t.liveCount)
        assertEquals("c|B", t.onSnapshot(snap(CallState.DISCONNECTED, "B", cause = "LOCAL")).single().id)
        assertEquals("c|A", t.onSnapshot(snap(CallState.DISCONNECTED, "A", cause = "REMOTE")).single().id)
    }

    @Test fun reconnectRepushOfTheRingingCallKeepsOneRecord() {
        t.onSnapshot(snap(CallState.RINGING, "A"))
        t.onSnapshot(null)                                    // link down
        t.onSnapshot(snap(CallState.RINGING, "A"))            // re-pushed after the reconnect
        assertEquals(1, t.liveCount)
        assertEquals(1, t.onSnapshot(CallSnapshot.IDLE).size)
    }

    @Test fun repushedDisconnectedAfterTheEntryWasWrittenIsIgnored() {
        t.onSnapshot(snap(CallState.RINGING, "A"))
        assertEquals(1, t.onSnapshot(snap(CallState.DISCONNECTED, "A", cause = "MISSED")).size)
        assertTrue(t.onSnapshot(snap(CallState.DISCONNECTED, "A", cause = "MISSED")).isEmpty())
    }

    @Test fun idAdoptedWhenItArrivesLaterOnTheSameCall() {
        t.onSnapshot(snap(CallState.RINGING, null))           // older report without an id
        t.onSnapshot(snap(CallState.ACTIVE, "A"))
        assertEquals(1, t.liveCount)
        assertEquals("c|A", t.onSnapshot(snap(CallState.DISCONNECTED, "A", cause = "REMOTE")).single().id)
    }

    @Test fun withoutIdsTheStage12FormIsKept() {
        t.onSnapshot(snap(CallState.RINGING, null))
        val e = t.onSnapshot(snap(CallState.DISCONNECTED, null, cause = "MISSED")).single()
        assertEquals("1000000|+15551234|INCOMING", e.id)
    }
}
