package com.example.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stage 31B: strings that arrive over Bluetooth are bounded. Written, NOT executed here. */
class CallMessageBoundsTest {
    @Test fun normalDialNumberIsKeptExactly() {
        assertEquals("+98 912 345 6789", CallMessages.parseDialNumber(CallMessages.dial("+98 912 345 6789")))
    }

    @Test fun oversizedDialNumberIsRefusedNotCutShort() {
        assertNull(CallMessages.parseDialNumber(CallMessages.dial("1".repeat(65))))
        assertEquals("1".repeat(64), CallMessages.parseDialNumber(CallMessages.dial("1".repeat(64))))
    }

    @Test fun oversizedStatusTextIsCut() {
        val m = Message(MessageType.CALL_STATE, org.json.JSONObject().put("state", "ACTIVE").put("number", "9".repeat(5000)).put("error", "e".repeat(5000)))
        val s = CallMessages.parseStatus(m)!!
        assertEquals(65, s.snapshot.number!!.length)
        assertEquals(200, s.error!!.length)
    }
}
