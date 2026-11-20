package com.example.companion.ui

import com.example.companion.protocol.SmsMessages
import com.example.companion.sms.SmsDirection
import com.example.companion.sms.SmsEntry
import com.example.companion.sms.SmsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the conversation screen's decisions. WRITTEN BUT NOT RUN. */
class SmsThreadStateTest {

    private fun out(status: SmsStatus, id: String = "o", ts: Long = 1, error: String? = null, detail: String? = null) =
        SmsEntry(id, "+15551234", "hello", ts, SmsDirection.OUTGOING, status, error, detail)

    private fun inc(id: String = "i", ts: Long = 1) =
        SmsEntry(id, "+15551234", "hi there", ts, SmsDirection.INCOMING, SmsStatus.RECEIVED)

    private fun row(e: SmsEntry, up: Boolean = true) = SmsThreadState.row(e, up) { "T$it" }

    private fun composer(
        main: Boolean = true, up: Boolean = true, addr: String? = "+15551234", to: String = "", body: String = "hello"
    ) = SmsThreadState.composer(main, up, addr, to, body)

    // ---- messages

    @Test fun messagesAreShownOldestFirstAndEqualTimesKeepTheirOrder() {
        val r = SmsThreadState.rows(listOf(inc("c", 300), out(SmsStatus.SENT, "a", 100), inc("b1", 200), inc("b2", 200)), true) { "T$it" }
        assertEquals(listOf("a", "b1", "b2", "c"), r.map { it.id })
    }

    @Test fun receivedMessageHasNoStatus() {
        val r = row(inc())
        assertEquals(BubbleKind.RECEIVED, r.kind); assertFalse(r.outgoing); assertEquals("", r.statusText); assertFalse(r.reusable)
    }

    @Test fun outgoingStatesAreDistinguished() {
        assertEquals(BubbleKind.SENDING, row(out(SmsStatus.PENDING)).kind)
        assertEquals(BubbleKind.SENDING, row(out(SmsStatus.SENDING)).kind)
        assertEquals(BubbleKind.SENT, row(out(SmsStatus.SENT)).kind)
        assertEquals(BubbleKind.FAILED, row(out(SmsStatus.FAILED)).kind)
        assertEquals("Sending…", row(out(SmsStatus.SENDING)).statusText)
        assertEquals("Sent", row(out(SmsStatus.SENT)).statusText)
        assertTrue(row(out(SmsStatus.PENDING)).statusText.contains("waiting for the SIM Phone"))
    }

    @Test fun sentIsNeverShownForAnythingButSent() {
        for (s in listOf(SmsStatus.PENDING, SmsStatus.SENDING, SmsStatus.FAILED)) assertFalse(row(out(s)).statusText == "Sent")
    }

    @Test fun failedShowsTheReasonTheSimPhoneReportedAndCanBeReused() {
        val r = row(out(SmsStatus.FAILED, error = "NO_SERVICE"))
        assertEquals("Failed: the SIM Phone has no cellular service", r.statusText)
        assertTrue(r.reusable)
        val withDetail = row(out(SmsStatus.FAILED, error = "GENERIC_FAILURE", detail = "code 4"))
        assertTrue(withDetail.statusText.endsWith("(code 4)"))
    }

    @Test fun unresolvedMessageSaysSoWhenTheLinkIsDown() {
        assertTrue(row(out(SmsStatus.PENDING), up = false).statusText.contains("reconnect"))
        assertTrue(row(out(SmsStatus.SENDING), up = false).statusText.contains("reconnect"))
        assertEquals("Sent", row(out(SmsStatus.SENT), up = false).statusText)       // a final result does not depend on the link
    }

    @Test fun screenReaderTextNamesDirectionBodyTimeAndStatus() {
        assertEquals("Received: hi there. T1.", row(inc()).description)
        assertEquals("You: hello. T1. Sent.", row(out(SmsStatus.SENT)).description)
    }

    // ---- composer

    @Test fun sendIsEnabledWhenEverythingIsFine() = assertTrue(composer().sendEnabled)

    @Test fun emptyOrBlankMessageCannotBeSent() {
        assertFalse(composer(body = "").sendEnabled)
        assertFalse(composer(body = "   \n\t ").sendEnabled)
    }

    @Test fun disconnectedDisablesSendAndSaysWhy() {
        val c = composer(up = false)
        assertFalse(c.sendEnabled)
        assertEquals(SmsThreadState.SEND_BLOCKED_TEXT, c.unavailableText)
        assertEquals(SmsThreadState.LINK_DOWN_TEXT, SmsThreadState.linkLine(true, false))
        assertEquals(SmsThreadState.LINK_UP_TEXT, SmsThreadState.linkLine(true, true))
        assertEquals("", composer().unavailableText)
    }

    @Test fun simPhoneRoleCannotSend() {
        assertFalse(composer(main = false).sendEnabled)
        assertEquals(SmsThreadState.NOT_MAIN_PHONE_TEXT, SmsThreadState.linkLine(false, true))
    }

    @Test fun newConversationNeedsAValidNumber() {
        assertFalse(composer(addr = null, to = "").sendEnabled)
        assertEquals("", composer(addr = null, to = "").recipientError)               // nothing typed yet: no nagging
        assertFalse(composer(addr = null, to = "12").sendEnabled)
        assertEquals(SmsThreadState.RECIPIENT_ERROR, composer(addr = null, to = "12").recipientError)
        assertFalse(composer(addr = null, to = "*123#").sendEnabled)                  // service codes are refused
        assertFalse(composer(addr = null, to = "abc").sendEnabled)
        assertTrue(composer(addr = null, to = " +1 (555) 123-4567 ").sendEnabled)
    }

    @Test fun existingConversationIgnoresTheRecipientBox() {
        assertTrue(composer(addr = "+15551234", to = "garbage").sendEnabled)
        assertEquals("", composer(addr = "+15551234", to = "garbage").recipientError)
    }

    @Test fun lengthLimitAndCounter() {
        val max = SmsMessages.MAX_SEND_BODY
        assertTrue(composer(body = "x".repeat(max)).sendEnabled)
        assertFalse(composer(body = "x".repeat(max + 1)).sendEnabled)
        assertTrue(composer(body = "x".repeat(max + 1)).hintText.startsWith("Too long"))
        assertEquals("", composer(body = "x".repeat(SmsThreadState.COUNTER_FROM - 1)).hintText)
        assertEquals("${SmsThreadState.COUNTER_FROM}/$max", composer(body = "x".repeat(SmsThreadState.COUNTER_FROM)).hintText)
    }

    @Test fun outgoingBodyIsTrimmedButOtherwiseUntouched() {
        assertEquals("a  b\nc", SmsThreadState.outgoingBody("  a  b\nc \n"))
    }
}
