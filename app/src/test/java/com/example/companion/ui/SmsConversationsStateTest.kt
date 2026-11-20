package com.example.companion.ui

import com.example.companion.sms.SmsConversation
import com.example.companion.sms.SmsDirection
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Messages list's decisions. WRITTEN BUT NOT RUN. */
class SmsConversationsStateTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }.timeInMillis

    private fun conv(
        address: String = "+15551234", body: String = "hi", ts: Long = at(4, 10),
        dir: SmsDirection = SmsDirection.INCOMING, count: Int = 1, failed: Int = 0, unread: Int = 0, key: String = address
    ) = SmsConversation(key, address, body, ts, dir, count, failed, unread)

    private fun rows(list: List<SmsConversation>, now: Long = at(4, 12), names: (String) -> String? = { null }) =
        SmsConversationsState.rows(list, now, names, { "C$it" }, { "D$it" }, utc)

    @Test fun newestConversationComesFirst() {
        val r = rows(listOf(conv("a", ts = 100), conv("b", ts = 300), conv("c", ts = 200)))
        assertEquals(listOf("b", "c", "a"), r.map { it.key })
    }

    @Test fun equalTimesKeepTheStoresOrder() {
        val r = rows(listOf(conv("x", ts = 100), conv("y", ts = 100), conv("z", ts = 100)))
        assertEquals(listOf("x", "y", "z"), r.map { it.key })
    }

    @Test fun titleIsTheNumberUnlessAContactNameIsKnown() {
        assertEquals("+15551234", rows(listOf(conv())).single().title)
        assertEquals("Alex", rows(listOf(conv())) { "Alex" }.single().title)
        assertEquals("+15551234", rows(listOf(conv())) { "   " }.single().title)     // blank name = unknown
    }

    @Test fun previewIsOneLineAndMarksOurOwnMessages() {
        assertEquals("a b c", SmsConversationsState.preview("  a\nb \r\n  c\t", SmsDirection.INCOMING))
        assertEquals("You: on my way", SmsConversationsState.preview("on my way", SmsDirection.OUTGOING))
    }

    @Test fun unreadIndicatorAndBadge() {
        val none = rows(listOf(conv(unread = 0))).single()
        assertFalse(none.hasUnread); assertEquals("", none.badgeText)
        val three = rows(listOf(conv(unread = 3))).single()
        assertTrue(three.hasUnread); assertEquals("3", three.badgeText)
        assertEquals("99", SmsConversationsState.badge(99))
        assertEquals("99+", SmsConversationsState.badge(100))
        assertEquals("", SmsConversationsState.badge(-1))
    }

    @Test fun negativeUnreadFromACorruptStoreIsTreatedAsRead() {
        assertFalse(rows(listOf(conv(unread = -2))).single().hasUnread)
    }

    @Test fun timeIsClockTodayAndDateOtherwise() {
        val now = at(4, 23, 30)
        assertEquals("C${at(4, 9)}", rows(listOf(conv(ts = at(4, 9))), now).single().timeText)
        assertEquals("D${at(3, 23, 59)}", rows(listOf(conv(ts = at(3, 23, 59))), now).single().timeText)
        assertEquals("D${at(4, 0) + 1}", rows(listOf(conv(ts = at(4, 0) + 1)), at(5, 0, 5)).single().timeText)
    }

    @Test fun failedSendsAreFlagged() {
        assertEquals("", rows(listOf(conv())).single().failedText)
        assertEquals("⚠ 2 failed", rows(listOf(conv(failed = 2))).single().failedText)
    }

    @Test fun screenReaderDescriptionNamesUnreadContactAndLatest() {
        val d = rows(listOf(conv(unread = 2, body = "lunch?"))) { "Alex" }.single().description
        assertTrue(d.startsWith("2 unread messages."))
        assertTrue(d.contains("Conversation with Alex."))
        assertTrue(d.contains("Latest: lunch?"))
        assertTrue(rows(listOf(conv(unread = 1))).single().description.startsWith("1 unread message."))
        assertFalse(rows(listOf(conv(unread = 0))).single().description.contains("unread"))
    }

    @Test fun rowKeepsTheRawAddressForTheMessageScreen() {
        val r = rows(listOf(conv(address = "+1 555-1234", key = "+15551234"))).single()
        assertEquals("+1 555-1234", r.address)
        assertEquals("+15551234", r.key)
    }

    @Test fun listStaysUsableWithoutALink() {
        assertTrue(SmsConversationsState.linkLine(false).contains("cannot be sent"))
        assertEquals("SIM Phone: connected", SmsConversationsState.linkLine(true))
    }
}
