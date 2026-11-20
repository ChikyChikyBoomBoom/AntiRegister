package com.example.companion.ui

import com.example.companion.calls.CallLogEntry
import com.example.companion.calls.CallLogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Recent Calls screen's decisions. WRITTEN BUT NOT RUN. */
class RecentCallsStateTest {

    private fun entry(type: CallLogType?, number: String? = "+15551234", duration: Long? = null, cause: String? = null) =
        CallLogEntry("id", number, type, 5_000L, duration, cause)

    private fun row(e: CallLogEntry) = RecentCallsState.row(e, { "T$it" })

    @Test fun outgoingRowShowsNumberTypeTimeAndDuration() {
        val r = row(entry(CallLogType.OUTGOING, duration = 65))
        assertEquals("+15551234", r.title)
        assertEquals("Outgoing", r.typeLabel)
        assertEquals("01:05", r.durationText)
        assertEquals("Outgoing · T5000 · 01:05", r.detail)
        assertFalse(r.missed)
    }

    @Test fun missedRowHasNoDurationAndIsFlagged() {
        val r = row(entry(CallLogType.MISSED, cause = "MISSED"))
        assertEquals("", r.durationText)
        assertEquals("Missed · T5000", r.detail)
        assertTrue(r.missed)
    }

    @Test fun missingNumberAndTypeAreShownHonestly() {
        val r = row(entry(null, number = null))
        assertEquals(CallScreenState.UNKNOWN_NUMBER, r.title)
        assertEquals("Call", r.typeLabel)
        assertEquals(null, r.number)
    }

    @Test fun declinedAndAnsweredElsewhereAreLabelled() {
        assertEquals("Incoming (declined)", RecentCallsState.typeLabel(entry(CallLogType.INCOMING, cause = "REJECTED")))
        assertEquals("Incoming (answered elsewhere)", RecentCallsState.typeLabel(entry(CallLogType.INCOMING, cause = "ANSWERED_ELSEWHERE")))
        assertEquals("Incoming", RecentCallsState.typeLabel(entry(CallLogType.INCOMING, duration = 3, cause = "REMOTE")))
    }

    @Test fun hourLongCallUsesHours() {
        assertEquals("1:01:01", row(entry(CallLogType.INCOMING, duration = 3661)).durationText)
    }

    @Test fun tapDialsOnlyWhenEverythingAllowsIt() {
        assertEquals(RecentCallTap.DIAL, RecentCallsState.onTap("+15551234", linkUp = true, callLive = false))
        assertEquals(RecentCallTap.NO_NUMBER, RecentCallsState.onTap(null, true, false))
        assertEquals(RecentCallTap.NO_NUMBER, RecentCallsState.onTap("", true, false))
        assertEquals(RecentCallTap.CANNOT_DIAL, RecentCallsState.onTap("555-1234", true, false))   // never rewritten
        assertEquals(RecentCallTap.CANNOT_DIAL, RecentCallsState.onTap("*21#", true, false))
        assertEquals(RecentCallTap.LINK_DOWN, RecentCallsState.onTap("5551234", false, false))
        assertEquals(RecentCallTap.CALL_IN_PROGRESS, RecentCallsState.onTap("5551234", true, true))
    }

    @Test fun historyStaysVisibleWithoutALink() {
        assertTrue(RecentCallsState.linkLine(false).contains("History is still shown"))
        assertEquals("SIM Phone: connected", RecentCallsState.linkLine(true))
    }
}
