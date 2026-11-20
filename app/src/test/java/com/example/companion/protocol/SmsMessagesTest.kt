package com.example.companion.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the SMS wire format and its validation. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class SmsMessagesTest {

    private fun roundTrip(m: Message) = Message.decode(m.encode())!!

    @Test fun receivedRoundTripKeepsOnlyWhatWasProvided() {
        val full = SmsMessages.parseReceived(roundTrip(SmsMessages.received(SmsReceived("abc-1", "+15551234567", "Hello", 1_700_000_000_000, 1))))
        assertEquals(SmsReceived("abc-1", "+15551234567", "Hello", 1_700_000_000_000, 1), full)
        val bare = SmsMessages.parseReceived(roundTrip(SmsMessages.received(SmsReceived("abc-2", "MYBANK", "Code 123", null, null))))!!
        assertNull(bare.timestamp); assertNull(bare.subscriptionId)       // nothing is fabricated
    }

    @Test fun sendRoundTripAndFlags() {
        val r = SmsMessages.parseSend(roundTrip(SmsMessages.send(SmsSendRequest("id_1", "+1 555 123", "Hi there", retry = true, queryOnly = false))))!!
        assertTrue(r.retry); assertFalse(r.queryOnly); assertEquals("+1 555 123", r.to)
    }

    @Test fun sendResultRoundTrip() {
        val r = SmsMessages.parseSendResult(roundTrip(SmsMessages.sendResult(SmsSendResult("id_1", SmsSendState.FAILED, "NO_SERVICE", null))))!!
        assertEquals(SmsSendState.FAILED, r.state); assertEquals("NO_SERVICE", r.error)
    }

    @Test fun missingOrWrongVersionIsRejected() {
        val m = SmsMessages.send(SmsSendRequest("id_1", "+15551234", "Hi", false, false))
        m.payload.remove("smsV")
        assertNull(SmsMessages.parseSend(m))
        m.payload.put("smsV", 2)
        assertNull(SmsMessages.parseSend(m))
    }

    @Test fun invalidSendRequestsAreRejected() {
        fun req(to: String, body: String, id: String = "id_1") =
            Message(MessageType.SMS_SEND, JSONObject().put("smsV", 1).put("id", id).put("to", to).put("body", body))
        assertNotNull(SmsMessages.parseSend(req("+1 (555) 123-4567", "ok")))
        assertNull(SmsMessages.parseSend(req("*#06#", "ok")))            // service codes are not phone numbers
        assertNull(SmsMessages.parseSend(req("12", "ok")))              // too few digits
        assertNull(SmsMessages.parseSend(req("+15551234", "   ")))      // blank
        assertNull(SmsMessages.parseSend(req("+15551234", "x".repeat(SmsMessages.MAX_SEND_BODY + 1))))
        assertNull(SmsMessages.parseSend(req("+15551234", "ok", id = "bad id!")))
        assertEquals("id_1", SmsMessages.parseSendId(req("*#06#", "ok")))   // still answerable with FAILED
    }

    @Test fun wrongFieldTypesAreRejectedNotCoerced() {
        val m = Message(MessageType.SMS_RECEIVED, JSONObject().put("smsV", 1).put("id", "a1").put("from", 12345).put("body", "x"))
        assertNull(SmsMessages.parseReceived(m))
    }

    @Test fun ackRoundTrip() {
        assertEquals("a1", SmsMessages.parseAck(roundTrip(SmsMessages.receivedAck("a1"))))
    }

    @Test fun parsersIgnoreOtherMessageTypes() {
        val ping = Message(MessageType.PING)
        assertNull(SmsMessages.parseReceived(ping)); assertNull(SmsMessages.parseSend(ping)); assertNull(SmsMessages.parseSendResult(ping))
    }

    // ---- Stage 26: stable id of a received SMS

    @Test fun sameReceivedSmsAlwaysGetsTheSameValidId() {
        val a = SmsMessages.receivedId("+15551234567", "Hello", 1_700_000_000_000)
        assertEquals(a, SmsMessages.receivedId("+15551234567", "Hello", 1_700_000_000_000))
        assertTrue(SmsMessages.isValidId(a))
        assertTrue(SmsMessages.isValidId("in-$a"))                         // the Main Phone's stored id
    }

    @Test fun differentReceivedSmsGetDifferentIds() {
        val base = SmsMessages.receivedId("+15551234567", "Hello", 1_700_000_000_000)
        assertFalse(base == SmsMessages.receivedId("+15551234568", "Hello", 1_700_000_000_000))
        assertFalse(base == SmsMessages.receivedId("+15551234567", "Hello!", 1_700_000_000_000))
        assertFalse(base == SmsMessages.receivedId("+15551234567", "Hello", 1_700_000_001_000))
        assertFalse(SmsMessages.receivedId("ab", "c", 1) == SmsMessages.receivedId("a", "bc", 1))   // field boundaries matter
    }

    @Test fun receivedSmsWithoutATimeGetsAFreshValidId() {
        val a = SmsMessages.receivedId("MYBANK", "Code 123", null)
        assertTrue(SmsMessages.isValidId(a))
        assertFalse(a == SmsMessages.receivedId("MYBANK", "Code 123", null))
    }
}
