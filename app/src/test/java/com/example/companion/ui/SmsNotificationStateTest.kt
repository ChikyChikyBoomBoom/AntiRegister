package com.example.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the SMS notification rules. WRITTEN BUT NOT RUN. */
class SmsNotificationStateTest {

    private val on = NotificationAccessStatus.ON

    // ---- decide

    @Test fun backgroundedMainPhoneWithNotificationsOnNotifies() {
        assertEquals(NotifyDecision.NOTIFY, SmsNotificationState.decide(isMainPhone = true, appVisible = false, access = on))
    }

    @Test fun nothingIsShownWhileTheAppIsVisible() {
        assertEquals(NotifyDecision.SKIP_APP_VISIBLE, SmsNotificationState.decide(true, true, on))
    }

    @Test fun nothingIsShownOnTheSimPhone() {
        assertEquals(NotifyDecision.SKIP_NOT_MAIN_PHONE, SmsNotificationState.decide(false, false, on))
        assertEquals(NotifyDecision.SKIP_NOT_MAIN_PHONE, SmsNotificationState.decide(false, true, on))
    }

    @Test fun nothingIsShownWhenAndroidDoesNotAllowIt() {
        NotificationAccessStatus.values().filter { it != on }.forEach {
            assertEquals(NotifyDecision.SKIP_NOTIFICATIONS_OFF, SmsNotificationState.decide(true, false, it))
        }
    }

    // ---- access

    @Test fun accessReportsTheMostActionableReasonFirst() {
        assertEquals(on, SmsNotificationState.access(permissionGranted = true, appEnabled = true, channelBlocked = false))
        assertEquals(NotificationAccessStatus.PERMISSION_DENIED, SmsNotificationState.access(false, true, false))
        assertEquals(NotificationAccessStatus.PERMISSION_DENIED, SmsNotificationState.access(false, false, true))
        assertEquals(NotificationAccessStatus.APP_DISABLED, SmsNotificationState.access(true, false, false))
        assertEquals(NotificationAccessStatus.APP_DISABLED, SmsNotificationState.access(true, false, true))
        assertEquals(NotificationAccessStatus.CHANNEL_BLOCKED, SmsNotificationState.access(true, true, true))
    }

    @Test fun noticeIsEmptyOnlyWhenNotificationsWork() {
        assertEquals("", SmsNotificationState.accessNotice(on))
        NotificationAccessStatus.values().filter { it != on }.forEach {
            assertTrue(SmsNotificationState.accessNotice(it).isNotEmpty())
        }
    }

    // ---- sender

    @Test fun senderIsTheNumberUnlessANameIsKnown() {
        assertEquals("+15551234", SmsNotificationState.senderTitle("+15551234") { null })
        assertEquals("Alex", SmsNotificationState.senderTitle("+15551234") { "Alex" })
        assertEquals("+15551234", SmsNotificationState.senderTitle("+15551234") { "  " })
        assertEquals("Shop", SmsNotificationState.senderTitle("Shop") { null })       // alphanumeric sender
    }

    @Test fun aBlankSenderGetsAPlaceholder() {
        assertEquals(SmsNotificationState.UNKNOWN_SENDER, SmsNotificationState.senderTitle("   ") { null })
        assertEquals(SmsNotificationState.UNKNOWN_SENDER, SmsNotificationState.senderTitle("") { null })
    }

    // ---- preview

    @Test fun previewIsOneLine() {
        assertEquals("a b c", SmsNotificationState.preview("  a\nb \r\n  c\t"))
    }

    @Test fun anEmptyTextGetsAPlaceholder() {
        assertEquals(SmsNotificationState.EMPTY_BODY_TEXT, SmsNotificationState.preview(""))
        assertEquals(SmsNotificationState.EMPTY_BODY_TEXT, SmsNotificationState.preview(" \n\t "))
    }

    @Test fun aTextThatFitsIsNotChanged() {
        val s = "x".repeat(SmsNotificationState.MAX_PREVIEW)
        assertEquals(s, SmsNotificationState.preview(s))
    }

    @Test fun aLongTextIsCutWithAnEllipsisWithinTheLimit() {
        val p = SmsNotificationState.preview("y".repeat(SmsNotificationState.MAX_PREVIEW + 1))
        assertEquals(SmsNotificationState.MAX_PREVIEW, p.length)
        assertTrue(p.endsWith("…"))
        assertEquals("abcd…", SmsNotificationState.preview("abcdefghij", max = 5))
    }

    @Test fun aCutNeverSplitsAnEmoji() {
        val emoji = "😀"                                    // two UTF-16 chars
        val p = SmsNotificationState.preview("ab" + emoji + "cdefgh", max = 4)      // naive cut after 3 chars would keep half of it
        assertEquals("ab…", p)
        assertTrue(p.none { Character.isSurrogate(it) })
    }

    @Test fun trailingSpacesBeforeTheEllipsisAreDropped() {
        assertEquals("ab…", SmsNotificationState.preview("ab   cdefgh", max = 4))      // cut lands right after "ab "
    }

    // ---- unread label

    @Test fun unreadLabelOnlyForSeveralMessages() {
        assertEquals("", SmsNotificationState.unreadLabel(0))
        assertEquals("", SmsNotificationState.unreadLabel(1))
        assertEquals("2 new messages", SmsNotificationState.unreadLabel(2))
        assertEquals("99 new messages", SmsNotificationState.unreadLabel(99))
        assertEquals("99+ new messages", SmsNotificationState.unreadLabel(100))
        assertEquals("", SmsNotificationState.unreadLabel(-3))
    }
}
