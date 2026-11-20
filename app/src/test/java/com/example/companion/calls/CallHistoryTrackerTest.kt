package com.example.companion.calls

import com.example.companion.protocol.CallDirection
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of Recent Calls recording. WRITTEN BUT NOT RUN. */
class CallHistoryTrackerTest {

    private class FakeClock(var wall: Long = 1_000_000L, var mono: Long = 0L) : CallHistoryTracker.Clock {
        override fun wallMs() = wall
        override fun monotonicMs() = mono
    }

    private val clock = FakeClock()
    private val t = CallHistoryTracker(clock)

    private fun snap(state: CallState, number: String? = "+15551234", dir: CallDirection? = CallDirection.OUTGOING, cause: String? = null) =
        CallSnapshot(state, number, dir, cause)

    @Test fun answeredOutgoingCallIsRecordedOnceWithDuration() {
        assertTrue(t.onSnapshot(snap(CallState.DIALING)).isEmpty())
        clock.mono = 1_000; assertTrue(t.onSnapshot(snap(CallState.ACTIVE)).isEmpty())
        clock.mono = 66_000
        val out = t.onSnapshot(snap(CallState.DISCONNECTED, cause = "LOCAL"))
        assertEquals(1, out.size)
        assertEquals(CallLogType.OUTGOING, out[0].type)
        assertEquals("+15551234", out[0].number)
        assertEquals(65L, out[0].durationSeconds)
        assertEquals(1_000_000L, out[0].startedAt)
        assertTrue(t.onSnapshot(CallSnapshot.IDLE).isEmpty())          // the IDLE that always follows adds nothing
        assertEquals(0, t.liveCount)
    }

    @Test fun unansweredIncomingCallIsMissedWithNoDuration() {
        t.onSnapshot(snap(CallState.RINGING, dir = CallDirection.INCOMING))
        val out = t.onSnapshot(snap(CallState.DISCONNECTED, dir = CallDirection.INCOMING, cause = "MISSED"))
        assertEquals(CallLogType.MISSED, out.single().type)
        assertNull(out.single().durationSeconds)
    }

    @Test fun declinedIncomingCallIsNotCalledMissed() {
        t.onSnapshot(snap(CallState.RINGING, dir = CallDirection.INCOMING))
        val out = t.onSnapshot(snap(CallState.DISCONNECTED, dir = CallDirection.INCOMING, cause = "REJECTED"))
        assertEquals(CallLogType.INCOMING, out.single().type)
        assertNull(out.single().durationSeconds)
        assertEquals("REJECTED", out.single().cause)
    }

    @Test fun answeredIncomingCallHasDuration() {
        t.onSnapshot(snap(CallState.RINGING, dir = CallDirection.INCOMING))
        clock.mono = 2_000; t.onSnapshot(snap(CallState.ACTIVE, dir = CallDirection.INCOMING))
        clock.mono = 32_400
        val out = t.onSnapshot(snap(CallState.DISCONNECTED, dir = CallDirection.INCOMING, cause = "REMOTE"))
        assertEquals(CallLogType.INCOMING, out.single().type)
        assertEquals(30L, out.single().durationSeconds)                 // 30.4 s -> nearest second
    }

    @Test fun repeatedSnapshotsNeverCreateExtraEntries() {
        repeat(5) { t.onSnapshot(snap(CallState.ACTIVE)) }
        assertEquals(1, t.liveCount)
        assertEquals(1, t.onSnapshot(snap(CallState.DISCONNECTED)).size)
        assertTrue(t.onSnapshot(snap(CallState.DISCONNECTED)).isEmpty())  // repeated DISCONNECTED
        assertTrue(t.onSnapshot(snap(CallState.DISCONNECTED)).isEmpty())
    }

    @Test fun disconnectedWithNothingRememberedIsIgnored() {
        assertTrue(t.onSnapshot(snap(CallState.DISCONNECTED)).isEmpty())      // e.g. re-push after reconnect
    }

    @Test fun bluetoothReconnectDuringCallKeepsOneRecord() {
        t.onSnapshot(snap(CallState.ACTIVE))
        clock.mono = 10_000
        assertTrue(t.onSnapshot(null).isEmpty())                             // link down: state unknown
        assertTrue(t.onSnapshot(null).isEmpty())
        clock.mono = 20_000
        assertTrue(t.onSnapshot(snap(CallState.ACTIVE)).isEmpty())           // SIM Phone re-pushes the live call
        assertEquals(1, t.liveCount)
        clock.mono = 60_000
        val out = t.onSnapshot(snap(CallState.DISCONNECTED))
        assertEquals(1, out.size)
        assertEquals(60L, out.single().durationSeconds)                      // the call really did continue through the gap
    }

    @Test fun callThatEndedWhileLinkWasDownIsRecordedOnceWithoutDuration() {
        t.onSnapshot(snap(CallState.ACTIVE))
        t.onSnapshot(null)
        val out = t.onSnapshot(CallSnapshot.IDLE)
        assertEquals(1, out.size)
        assertEquals(CallLogType.OUTGOING, out.single().type)
        assertNull(out.single().durationSeconds)                             // end time was not observed
        assertTrue(t.onSnapshot(CallSnapshot.IDLE).isEmpty())
    }

    @Test fun hangUpInProgressEndsTheTimer() {
        t.onSnapshot(snap(CallState.ACTIVE))
        clock.mono = 10_000; t.onSnapshot(snap(CallState.DISCONNECTING))
        clock.mono = 12_000; t.onSnapshot(snap(CallState.DISCONNECTING))
        clock.mono = 15_000
        assertEquals(10L, t.onSnapshot(snap(CallState.DISCONNECTED)).single().durationSeconds)
    }

    @Test fun numberThatArrivesLaterIsAdoptedNotDuplicated() {
        t.onSnapshot(snap(CallState.DIALING, number = null))
        t.onSnapshot(snap(CallState.DIALING, number = "555"))
        assertEquals(1, t.liveCount)
        assertEquals("555", t.onSnapshot(snap(CallState.DISCONNECTED, number = "555")).single().number)
    }

    @Test fun withheldNumberIsKeptAsNull() {
        t.onSnapshot(snap(CallState.RINGING, number = null, dir = CallDirection.INCOMING))
        val e = t.onSnapshot(snap(CallState.DISCONNECTED, number = null, dir = CallDirection.INCOMING, cause = "MISSED")).single()
        assertNull(e.number)
        assertEquals(CallLogType.MISSED, e.type)
    }

    @Test fun unknownDirectionGivesNoTypeAndIsNeverGuessed() {
        t.onSnapshot(snap(CallState.ACTIVE, dir = null))
        val e = t.onSnapshot(snap(CallState.DISCONNECTED, dir = null)).single()
        assertNull(e.type)
    }

    @Test fun callWaitingRecordsEachCallOnce() {
        t.onSnapshot(snap(CallState.ACTIVE, number = "111"))
        t.onSnapshot(snap(CallState.RINGING, number = "222", dir = CallDirection.INCOMING))   // second call rings
        assertEquals(2, t.liveCount)
        val second = t.onSnapshot(snap(CallState.DISCONNECTED, number = "222", dir = CallDirection.INCOMING, cause = "MISSED"))
        assertEquals("222", second.single().number)
        assertEquals(CallLogType.MISSED, second.single().type)
        t.onSnapshot(snap(CallState.ACTIVE, number = "111"))                                  // first call is primary again
        assertEquals(1, t.liveCount)
        assertEquals("111", t.onSnapshot(snap(CallState.DISCONNECTED, number = "111")).single().number)
    }

    @Test fun idIsStableForTheSameCall() {
        t.onSnapshot(snap(CallState.DIALING))
        val a = t.onSnapshot(snap(CallState.DISCONNECTED)).single().id
        val t2 = CallHistoryTracker(FakeClock())                                              // same start time and number
        t2.onSnapshot(snap(CallState.DIALING))
        assertEquals(a, t2.onSnapshot(snap(CallState.DISCONNECTED)).single().id)
    }
}
