package com.example.companion.ui

import com.example.companion.calls.CallLogEntry
import com.example.companion.calls.CallLogType
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests of contact names in Recent Calls (Stage 19). WRITTEN BUT NOT RUN. */
class RecentCallsContactsTest {

    private fun entry(number: String?) = CallLogEntry("id", number, CallLogType.INCOMING, 5_000L, 65L, null)
    private fun row(number: String?, names: Map<String, String> = emptyMap()) =
        RecentCallsState.row(entry(number), { "T$it" }) { names[it] }

    @Test fun contactNameIsTheTitleAndTheNumberMovesToASecondLine() {
        val r = row("+15551234", mapOf("+15551234" to "Ann"))
        assertEquals("Ann", r.title)
        assertEquals("+15551234", r.numberLine)
        assertEquals("+15551234", r.number)                 // redial still uses the stored number
        assertEquals("Incoming · T5000 · 01:05", r.detail)  // the detail line is unchanged
    }

    @Test fun numberNotInContactsIsShownAsBefore() {
        val r = row("+15551234")
        assertEquals("+15551234", r.title)
        assertEquals("", r.numberLine)
    }

    @Test fun blankNameCountsAsUnknown() {
        val r = row("+15551234", mapOf("+15551234" to "  "))
        assertEquals("+15551234", r.title)
        assertEquals("", r.numberLine)
    }

    @Test fun withheldNumberIsNeverLookedUp() {
        var asked = false
        val r = RecentCallsState.row(entry(null), { "T$it" }) { asked = true; "Nobody" }
        assertEquals(CallScreenState.UNKNOWN_NUMBER, r.title)
        assertEquals(false, asked)
    }
}
