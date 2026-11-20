package com.example.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the optional Stage 27 "callId" field. WRITTEN BUT NOT RUN. */
class CallSessionIdTest {

    private val ringing = CallSnapshot(CallState.RINGING, "+15551234", CallDirection.INCOMING, callId = "abc-123")

    @Test fun generatedIdsAreValidAndDifferent() {
        val a = CallSessionId.generate(); val b = CallSessionId.generate()
        assertTrue(CallSessionId.isValid(a)); assertTrue(CallSessionId.isValid(b))
        assertNotEquals(a, b)
    }

    @Test fun idRoundTripsThroughIncomingAndState() {
        assertEquals("abc-123", CallMessages.parseStatus(CallMessages.incoming(ringing))?.snapshot?.callId)
        val active = ringing.copy(state = CallState.ACTIVE)
        assertEquals("abc-123", CallMessages.parseStatus(CallMessages.state(active, activeMs = 1_000))?.snapshot?.callId)
    }

    @Test fun noIdMeansTheFieldIsOmittedAndParsesAsNull() {
        val m = CallMessages.state(CallSnapshot(CallState.ACTIVE, "+15551234", CallDirection.INCOMING))
        assertFalse(m.payload.has("callId"))
        assertNull(CallMessages.parseStatus(m)?.snapshot?.callId)
    }

    @Test fun messageFromAnOlderBuildStillParses() {
        val m = CallMessages.incoming(ringing)
        m.payload.remove("callId")
        val s = CallMessages.parseStatus(m)!!.snapshot
        assertEquals(CallState.RINGING, s.state); assertEquals("+15551234", s.number); assertNull(s.callId)
    }

    @Test fun invalidIdIsDroppedNeverRepaired() {
        listOf("", " ", "a b", "a/b", "x".repeat(CallSessionId.MAX_LENGTH + 1), "id\"quote").forEach {
            val m = CallMessages.incoming(ringing); m.payload.put("callId", it)
            assertNull("id '$it'", CallMessages.parseStatus(m)?.snapshot?.callId)
            assertFalse(CallSessionId.isValid(it))
        }
        assertFalse(CallSessionId.isValid(null))
    }

    @Test fun anInvalidIdIsNeverSent() {
        val m = CallMessages.incoming(ringing.copy(callId = "bad id"))
        assertFalse(m.payload.has("callId"))
    }

    @Test fun existingFieldsAreUnchanged() {
        val p = CallMessages.incoming(ringing, controlAvailable = true).payload
        assertEquals("RINGING", p.getString("state")); assertEquals("+15551234", p.getString("number"))
        assertEquals(true, p.getBoolean("controlAvailable"))
    }
}
