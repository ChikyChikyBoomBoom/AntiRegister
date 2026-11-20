package com.example.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests of the optional Stage 17 "activeMs" field of CALL_STATE. WRITTEN BUT NOT RUN. */
class CallActiveDurationTest {

    private val active = CallSnapshot(CallState.ACTIVE, "+15551234", CallDirection.INCOMING)

    @Test fun activeStateCarriesAndParsesTheDuration() {
        val m = CallMessages.state(active, activeMs = 83_000L)
        assertEquals(83_000L, CallMessages.parseStatus(m)?.activeMs)
    }

    @Test fun durationIsOmittedWhenUnknown() {
        val m = CallMessages.state(active)
        assertEquals(false, m.payload.has("activeMs"))
        assertNull(CallMessages.parseStatus(m)?.activeMs)
    }

    @Test fun durationIsNeverSentForANonActiveCall() {
        val m = CallMessages.state(CallSnapshot(CallState.HOLDING, "+15551234"), activeMs = 5_000L)
        assertEquals(false, m.payload.has("activeMs"))
    }

    @Test fun negativeOrBogusDurationIsDropped() {
        val m = CallMessages.state(active)
        m.payload.put("activeMs", -5L)
        assertNull(CallMessages.parseStatus(m)?.activeMs)
    }

    @Test fun messageWithoutTheFieldStillParsesExactlyAsBefore() {
        val s = CallMessages.parseStatus(CallMessages.state(active))!!
        assertEquals(active, s.snapshot)
    }
}
