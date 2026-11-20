package com.example.companion.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stage 5: raw call-log row -> existing [CallLogEntry]. Plain JVM, no Android types. */
class SimCallLogMapperTest {

    private fun map(type: Int, number: String? = "+15551234", duration: Long? = 0, id: Long = 7, date: Long = 1000L) =
        SimCallLogMapper.toEntry(id, number, type, date, duration)

    @Test fun incomingConnectedKeepsDuration() {
        val e = map(SimCallLogMapper.INCOMING_TYPE, duration = 65)
        assertEquals(CallLogType.INCOMING, e.type); assertEquals(65L, e.durationSeconds); assertNull(e.cause)
    }

    @Test fun outgoingConnectedKeepsDuration() {
        val e = map(SimCallLogMapper.OUTGOING_TYPE, duration = 12)
        assertEquals(CallLogType.OUTGOING, e.type); assertEquals(12L, e.durationSeconds)
    }

    @Test fun zeroDurationMeansNeverConnected() {
        assertNull(map(SimCallLogMapper.OUTGOING_TYPE, duration = 0).durationSeconds)
        assertNull(map(SimCallLogMapper.INCOMING_TYPE, duration = null).durationSeconds)
    }

    @Test fun missedIsMissedWithNoDuration() {
        val e = map(SimCallLogMapper.MISSED_TYPE, duration = 30)
        assertEquals(CallLogType.MISSED, e.type); assertNull(e.durationSeconds)
    }

    @Test fun rejectedBlockedAndAnsweredElsewhereAreIncomingWithTheOldCauseWords() {
        assertEquals("REJECTED", map(SimCallLogMapper.REJECTED_TYPE).cause)
        assertEquals("BLOCKED", map(SimCallLogMapper.BLOCKED_TYPE).cause)
        assertEquals("ANSWERED_ELSEWHERE", map(SimCallLogMapper.ANSWERED_EXTERNALLY_TYPE).cause)
        listOf(5, 6, 7).forEach { assertEquals(CallLogType.INCOMING, map(it).type) }
    }

    @Test fun unknownTypeKeepsNullTypeSoTheRowSaysCall() {
        val e = map(99)
        assertNull(e.type); assertNull(e.cause)
    }

    @Test fun blankNumberIsNull() {
        assertNull(map(1, number = "").number); assertNull(map(1, number = "  ").number); assertNull(map(1, number = null).number)
        assertEquals("+15551234", map(1, number = " +15551234 ").number)
    }

    @Test fun idIsStablePerLogRowAndTimeIsKept() {
        val e = map(1, id = 42, date = 123456L)
        assertEquals("log|42", e.id); assertEquals(123456L, e.startedAt)
        assertEquals(e.id, map(1, id = 42).id)
    }
}
