package com.example.companion.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stage 6: ordering and de-duplication of the SIM Phone's call-history list. Plain JVM. */
class SimCallHistoryStateTest {

    private fun e(row: Long, at: Long, type: Int = SimCallLogMapper.INCOMING_TYPE) =
        SimCallLogMapper.toEntry(row, "+15551234", type, at, 5)

    @Test fun emptyStaysEmpty() = assertEquals(emptyList<CallLogEntry>(), SimCallHistoryState.normalize(emptyList()))

    @Test fun repeatedIdIsDrawnOnce() {
        val out = SimCallHistoryState.normalize(listOf(e(1, 100), e(1, 100), e(2, 200)))
        assertEquals(listOf("log|2", "log|1"), out.map { it.id })
    }

    @Test fun newestFirst() {
        val out = SimCallHistoryState.normalize(listOf(e(1, 100), e(3, 300), e(2, 200)))
        assertEquals(listOf(300L, 200L, 100L), out.map { it.startedAt })
    }

    @Test fun sameTimeHigherRowIdFirst() {
        val out = SimCallHistoryState.normalize(listOf(e(4, 100), e(9, 100)))
        assertEquals(listOf("log|9", "log|4"), out.map { it.id })
    }

    @Test fun allKindsAreKept() {
        val out = SimCallHistoryState.normalize(listOf(
            e(1, 1, SimCallLogMapper.INCOMING_TYPE), e(2, 2, SimCallLogMapper.OUTGOING_TYPE), e(3, 3, SimCallLogMapper.MISSED_TYPE)))
        assertEquals(3, out.size)
        assertEquals(listOf(CallLogType.MISSED, CallLogType.OUTGOING, CallLogType.INCOMING), out.map { it.type })
    }
}
