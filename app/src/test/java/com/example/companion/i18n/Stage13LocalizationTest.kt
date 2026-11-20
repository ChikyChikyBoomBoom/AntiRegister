package com.example.companion.i18n

import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.contacts.ContactsState
import com.example.companion.sms.SmsConversation
import com.example.companion.sms.SmsDirection
import com.example.companion.sms.SmsEntry
import com.example.companion.sms.SmsError
import com.example.companion.sms.SmsErrorText
import com.example.companion.sms.SmsStatus
import com.example.companion.ui.HistoryAction
import com.example.companion.ui.HistoryState
import com.example.companion.ui.HistoryTab
import com.example.companion.ui.HomeStatus
import com.example.companion.ui.LinkStatus
import com.example.companion.ui.NotificationAccessStatus
import com.example.companion.ui.SmsConversationsState
import com.example.companion.ui.SmsNotificationState
import com.example.companion.ui.SmsThreadState
import com.example.companion.ui.UiPolish
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of the Stage 13 localization of Home and the SMS screens. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available).
 * Three things are checked: the original English wording is unchanged, every Stage 13 text has a Persian version, and a missing
 * Persian text falls back to English.
 */
class Stage13LocalizationTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN

    @After fun resetLanguage() { AppLocale.setForTest(AppLanguage.DEFAULT) }

    private fun english(key: StringKey) = Strings.text(key, en)
    private fun hasLatin(s: String) = Regex("[A-Za-z]").containsMatchIn(s)

    // ---------------------------------------------------------------- coverage and fallback

    private val stage13Prefixes = listOf("HOME_", "LINK_", "EMPTY_NO_DEVICE_", "SMS_")

    private val stage13Keys get() = StringKey.values().filter { k -> stage13Prefixes.any { k.name.startsWith(it) } }

    @Test fun everyStage13KeyHasAPersianText() {
        val missing = stage13Keys.filter { !Strings.isTranslated(it, fa) }
        assertTrue("missing Persian: $missing", missing.isEmpty())
        assertTrue(stage13Keys.size > 100)
    }

    @Test fun persianDiffersFromEnglishAndIsMostlyPersianScript() {
        val allowedLatin = setOf(StringKey.SMS_FAIL_PERMISSION_DENIED, StringKey.SMS_FAIL_FDN)   // name an English menu / an abbreviation
        stage13Keys.filter { it != StringKey.SMS_COUNTER }.forEach { k ->
            val persian = Strings.text(k, fa)
            assertNotEquals(k.name, k.english, persian)
            if (k !in allowedLatin) assertFalse("${k.name} has Latin letters: $persian", hasLatin(persian.replace(Regex("%\\d+\\\$[sd]"), "")))
        }
    }

    @Test fun missingPersianTextFallsBackToEnglishForTheNewKeys() {
        val empty = emptyMap<StringKey, String>()
        stage13Keys.forEach { assertEquals(it.name, it.english, Strings.text(it, fa, empty)) }
        // one key missing, the others present: only that one falls back
        val partial = PersianStrings.table - StringKey.HOME_TILE_MESSAGES
        assertEquals("Messages", Strings.text(StringKey.HOME_TILE_MESSAGES, fa, partial))
        assertEquals("گوشی اصلی", Strings.text(StringKey.HOME_TITLE, fa, partial))
    }

    @Test fun languageSelectionSwitchesTheTextTheScreensRead() {
        AppLocale.setForTest(fa)
        assertEquals("گوشی اصلی", tr(StringKey.HOME_TITLE))
        assertEquals("پیام‌ها", tr(StringKey.SMS_TITLE))
        AppLocale.setForTest(en)
        assertEquals("Main Phone", tr(StringKey.HOME_TITLE))
        assertEquals("Messages", tr(StringKey.SMS_TITLE))
        assertEquals("Attempt 2 of 5", tr(StringKey.HOME_DETAIL_ATTEMPT_OF, 2, 5))
    }

    // ---------------------------------------------------------------- English is exactly what it was

    @Test fun homeEnglishEqualsTheOriginalLiterals() {
        assertEquals("Main Phone", english(StringKey.HOME_TITLE))
        assertEquals("Menu", english(StringKey.HOME_MENU_HEADER))
        assertEquals(HomeStatus.SELECT_DEVICE_DETAIL, english(StringKey.HOME_DETAIL_SELECT_DEVICE))
        assertEquals(HomeStatus.SELECT_DEVICE_DETAIL, HomeStatus.selectDeviceDetail(en))
        assertEquals(UiPolish.NO_DEVICE.title, english(StringKey.EMPTY_NO_DEVICE_TITLE))
        assertEquals(UiPolish.NO_DEVICE.message, english(StringKey.EMPTY_NO_DEVICE_BODY))
        assertEquals(UiPolish.NO_DEVICE.actionLabel, english(StringKey.EMPTY_NO_DEVICE_ACTION))
        assertEquals(ContactsState.NO_CONTACTS_APP, english(StringKey.HOME_NO_CONTACTS_APP))
        LinkStatus.values().forEach { assertEquals(it.label, it.label(en)) }
        assertEquals("Setup incomplete: Bluetooth, Notifications", HomeStatus.setupBanner(listOf("Bluetooth", "Notifications"), en))
        assertEquals("", HomeStatus.setupBanner(emptyList(), en))
    }

    private fun home(
        state: State, retry: Int = 0, max: Int = 5, last: String? = null, name: String? = "Pixel", perm: Boolean = true,
        supported: Boolean = true, enabled: Boolean = true, language: AppLanguage = en
    ) = HomeStatus.from(state, null, name, null, retry, max, last, supported, enabled, perm, language)

    @Test fun homeStatusEnglishIsUnchangedAndMatchesDefault() {
        State.values().forEach { st ->
            assertEquals(home(st).copy(), HomeStatus.from(st, null, "Pixel", null, 0, 5, null, true, true, true))
        }
        assertEquals("Selected SIM Phone: Pixel", home(State.DISCONNECTED).deviceLine)
        assertEquals("Attempt 2 of 5", home(State.RECONNECTING, retry = 2).detail)
        assertEquals("Attempt 7", home(State.RECONNECTING, retry = 7).detail)
        assertEquals("Trying to reach the SIM Phone…", home(State.RECONNECTING).detail)
        assertEquals("Pairing: confirm on both phones", home(State.PAIRING).detail)
        assertEquals("Open Settings to connect.", home(State.DISCONNECTED).detail)
        assertEquals("No SIM Phone selected", home(State.DISCONNECTED, name = null).deviceLine)
    }

    @Test fun homeStatusInPersianUsesPersianTextAndKeepsDataAsIs() {
        val connecting = home(State.CONNECTING, language = fa)
        assertEquals("در حال باز کردن اتصال بلوتوث…", connecting.detail)
        assertEquals("گوشی سیم‌کارت: \u2068Pixel\u2069", connecting.deviceLine)
        assertEquals("تلاش ۲ از ۵", home(State.RECONNECTING, retry = 2, language = fa).detail)
        assertEquals("بلوتوث خاموش است.", home(State.DISCONNECTED, enabled = false, language = fa).detail)
        // a raw error from the Bluetooth layer is shown as it arrived
        assertEquals("Connection timed out", home(State.ERROR, last = "Connection timed out", language = fa).detail)
        assertEquals(HomeStatus.selectDeviceDetail(fa), home(State.DISCONNECTED, name = null, language = fa).detail)
        assertEquals("متصل", LinkStatus.CONNECTED.label(fa))
        assertEquals("قطع شده", LinkStatus.DISCONNECTED.label(fa))
    }

    @Test fun smsListEnglishEqualsTheOriginalLiterals() {
        assertEquals(UiPolishNoMessages.title, english(StringKey.SMS_EMPTY_TITLE))
        assertEquals(UiPolishNoMessages.message, english(StringKey.SMS_EMPTY_BODY))
        assertEquals(HistoryState.MESSAGES_HINT, english(StringKey.SMS_LIST_HINT))
        assertEquals(HistoryState.clearAllLabel(HistoryTab.MESSAGES), english(StringKey.SMS_CLEAR_ALL))
        assertEquals(HistoryState.clearAllTitle(HistoryTab.MESSAGES), english(StringKey.SMS_CLEAR_ALL_TITLE))
        for ((c, m) in listOf(0 to 0, 1 to 1, 1 to 2, 3 to 10)) {
            assertEquals(HistoryState.clearAllMessage(HistoryTab.MESSAGES, c, m), SmsConversationsState.clearAllBody(c, m, en))
        }
        HistoryTab.values().forEach { assertEquals(it.label, SmsConversationsState.tabLabel(it, en)) }
        HistoryAction.values().forEach { assertEquals(it.label, SmsConversationsState.actionLabel(it, en)) }
        assertEquals("SIM Phone: connected", SmsConversationsState.linkLine(true))
        assertEquals(
            "SIM Phone unavailable (Bluetooth not connected). Messages are shown from this phone; new messages cannot be sent.",
            SmsConversationsState.linkLine(false)
        )
    }

    private object UiPolishNoMessages {
        val title = UiPolish.NO_MESSAGES.title
        val message = UiPolish.NO_MESSAGES.message
    }

    private fun conv(unread: Int = 0, failed: Int = 0, dir: SmsDirection = SmsDirection.INCOMING, body: String = "hi") =
        SmsConversation("+15551234", "+15551234", body, 1000, dir, 1, failed, unread)

    private fun row(c: SmsConversation, language: AppLanguage) =
        SmsConversationsState.row(c, 1000, { null }, { "C$it" }, { "D$it" }, TimeZone.getTimeZone("UTC"), language)

    @Test fun smsRowEnglishIsUnchanged() {
        val r = row(conv(unread = 3, failed = 2, dir = SmsDirection.OUTGOING), en)
        assertEquals("You: hi", r.preview)
        assertEquals("⚠ 2 failed", r.failedText)
        assertEquals(
            "3 unread messages. Conversation with +15551234. Latest: You: hi, C1000. 2 messages failed to send.", r.description
        )
        assertEquals("99+", SmsConversationsState.badge(150))
        assertEquals("1 unread message. Conversation with +15551234. Latest: hi, C1000.", row(conv(unread = 1), en).description)
    }

    @Test fun smsRowInPersian() {
        val r = row(conv(unread = 3, failed = 2, dir = SmsDirection.OUTGOING), fa)
        assertTrue(r.preview.startsWith("شما: "))
        assertEquals("⚠ ۲ ناموفق", r.failedText)
        assertEquals("۳", r.badgeText)
        assertTrue(r.description.startsWith("۳ پیام خوانده‌نشده. گفتگو با "))
        assertTrue(r.description.endsWith("۲ پیام ارسال نشد."))
        assertEquals("۹۹+", SmsConversationsState.badge(150, fa))
        assertEquals("یک پیام ارسال نشد.", row(conv(failed = 1), fa).description.substringAfterLast(". "))
        assertEquals("۲ گفتگو", SmsConversationsState.conversationCount(2, fa))
        assertEquals("یک پیام", SmsConversationsState.messageCount(1, fa))
    }

    // ---- conversation screen

    private fun out(status: SmsStatus, error: String? = null, detail: String? = null) =
        SmsEntry("o", "+15551234", "hello", 1, SmsDirection.OUTGOING, status, error, detail)

    @Test fun threadEnglishEqualsTheOriginalLiterals() {
        assertEquals(SmsThreadState.LINK_UP_TEXT, english(StringKey.SMS_LINK_UP))
        assertEquals(SmsThreadState.LINK_DOWN_TEXT, english(StringKey.SMS_LINK_DOWN))
        assertEquals(SmsThreadState.SEND_BLOCKED_TEXT, english(StringKey.SMS_SEND_BLOCKED))
        assertEquals(SmsThreadState.NOT_MAIN_PHONE_TEXT, english(StringKey.SMS_NOT_MAIN_SHORT))
        assertEquals(SmsThreadState.RECIPIENT_ERROR, english(StringKey.SMS_RECIPIENT_INVALID))
        assertEquals("that contact's number has characters the SIM Phone cannot use (only digits with an optional leading +)",
            english(StringKey.SMS_CONTACT_NOT_USABLE_REASON))
        assertEquals(ContactsState.NOT_USABLE, english(StringKey.SMS_CONTACT_NOT_USABLE_REASON))
        assertEquals("Sending… (waiting for the SIM Phone)", SmsThreadState.statusText(out(SmsStatus.PENDING), true))
        assertEquals("Sending… (SIM Phone unavailable, will be checked on reconnect)", SmsThreadState.statusText(out(SmsStatus.PENDING), false))
        assertEquals("Sending…", SmsThreadState.statusText(out(SmsStatus.SENDING), true))
        assertEquals("Sending… (SIM Phone unavailable, result will be checked on reconnect)", SmsThreadState.statusText(out(SmsStatus.SENDING), false))
        assertEquals("Sent", SmsThreadState.statusText(out(SmsStatus.SENT), true))
        assertEquals("", SmsThreadState.statusText(out(SmsStatus.RECEIVED), true))
        assertEquals("Failed: the SIM Phone has no cellular service (x)",
            SmsThreadState.statusText(out(SmsStatus.FAILED, SmsError.NO_SERVICE, "x"), true))
        val r = SmsThreadState.row(out(SmsStatus.SENT), true) { "T$it" }
        assertEquals("You: hello. T1. Sent.", r.description)
    }

    @Test fun composerEnglishIsUnchangedAndPersianIsTranslated() {
        val long = "x".repeat(com.example.companion.protocol.SmsMessages.MAX_SEND_BODY + 1)
        val c = SmsThreadState.composer(true, true, "+15551234", "", long)
        assertEquals("Too long: ${long.length}/${com.example.companion.protocol.SmsMessages.MAX_SEND_BODY} characters", c.hintText)
        val cf = SmsThreadState.composer(true, false, null, "abc", "hello", fa)
        assertEquals(Strings.text(StringKey.SMS_SEND_BLOCKED, fa), cf.unavailableText)
        assertEquals(Strings.text(StringKey.SMS_RECIPIENT_INVALID, fa), cf.recipientError)
        assertFalse(cf.sendEnabled)
        val tooLongFa = SmsThreadState.composer(true, true, "+15551234", "", long, fa)
        assertTrue(tooLongFa.hintText.startsWith("بیش از حد طولانی: "))
    }

    @Test fun everySmsFailureCodeIsTranslatedAndEnglishMatchesSmsError() {
        val codes = listOf(
            SmsError.INVALID_REQUEST, SmsError.PERMISSION_DENIED, SmsError.NO_TELEPHONY, SmsError.SMS_API_ERROR, SmsError.GENERIC_FAILURE,
            SmsError.RADIO_OFF, SmsError.NO_SERVICE, SmsError.NULL_PDU, SmsError.LIMIT_EXCEEDED, SmsError.FDN_CHECK_FAILURE,
            SmsError.SHORT_CODE_NOT_ALLOWED, SmsError.PARTIAL_FAILURE, SmsError.NOT_RECEIVED, SmsError.RESULT_UNKNOWN,
            SmsError.UNKNOWN_RESULT_CODE, "SOMETHING_NEW", null
        )
        for (code in codes) {
            assertEquals(code, SmsError.describe(code, null), SmsErrorText.describe(code, null, en))
            assertEquals(code, SmsError.describe(code, "d"), SmsErrorText.describe(code, "d", en))
            if (code != SmsError.UNKNOWN_RESULT_CODE && code != "SOMETHING_NEW") {
                assertNotEquals(code, SmsError.describe(code, null), SmsErrorText.describe(code, null, fa))
            }
        }
        // a code the app does not know yet: the code is kept so it can still be reported
        assertEquals("خطای SOMETHING_NEW", SmsErrorText.describe("SOMETHING_NEW", null, fa))
    }

    @Test fun smsFailureDetailsAreTranslatedWhenKnownAndKeptOtherwise() {
        assertEquals("۳ از ۴ بخش ناموفق بود", SmsErrorText.detail("3 of 4 parts failed", fa))
        assertEquals("کد نتیجه‌ی اندروید ۱۲", SmsErrorText.detail("Android result code 12", fa))
        assertEquals("پیام خالی", SmsErrorText.detail("empty message", fa))
        assertEquals("SecurityException", SmsErrorText.detail("SecurityException", fa))
        assertEquals("3 of 4 parts failed", SmsErrorText.detail("3 of 4 parts failed", en))
        assertTrue(SmsErrorText.describe(SmsError.PARTIAL_FAILURE, "3 of 4 parts failed", fa).endsWith("(۳ از ۴ بخش ناموفق بود)"))
    }

    @Test fun sendRefusalsAreMappedForDisplayOnly() {
        // The controller's own wording (copied here; ClientSmsController was not edited).
        val reasons = listOf(
            "Only the Main Phone can send messages",
            "Enter a valid phone number (digits, optional leading +)",
            "The message is empty",
            SmsErrorText.tooLongEnglish(),
            "SIM Phone unavailable (Bluetooth is not connected). Nothing was sent.",
            "Could not save the message on this phone. Nothing was sent."
        )
        for (r in reasons) {
            assertEquals(r, SmsErrorText.refusal(r, en))
            val persian = SmsErrorText.refusal(r, fa)
            assertNotEquals(r, persian)
            assertFalse(persian, hasLatin(persian))
        }
        assertEquals("a reason nobody knows", SmsErrorText.refusal("a reason nobody knows", fa))
        assertEquals("پیام بیش از حد طولانی است (حداکثر ${TextDirection.number(com.example.companion.protocol.SmsMessages.MAX_SEND_BODY, fa)} نویسه)",
            SmsErrorText.refusal(SmsErrorText.tooLongEnglish(), fa))
    }

    // ---------------------------------------------------------------- notifications

    @Test fun notificationEnglishIsUnchanged() {
        assertEquals(SmsNotificationState.PUBLIC_TITLE, SmsNotificationState.publicTitle())
        assertEquals(SmsNotificationState.UNKNOWN_SENDER, SmsNotificationState.senderTitle("  ", { null }))
        assertEquals(SmsNotificationState.EMPTY_BODY_TEXT, SmsNotificationState.preview("  \n "))
        assertEquals("", SmsNotificationState.unreadLabel(1))
        assertEquals("5 new messages", SmsNotificationState.unreadLabel(5))
        assertEquals("99+ new messages", SmsNotificationState.unreadLabel(500))
        assertEquals("New messages will not notify you: the notification permission was not granted.",
            SmsNotificationState.accessNotice(NotificationAccessStatus.PERMISSION_DENIED))
        assertEquals("", SmsNotificationState.accessNotice(NotificationAccessStatus.ON))
        assertEquals("Messages", english(StringKey.SMS_NOTIF_CHANNEL_NAME))
        assertEquals("New text messages received by the SIM Phone", english(StringKey.SMS_NOTIF_CHANNEL_DESC))
    }

    @Test fun notificationInPersian() {
        assertEquals("پیام جدید", SmsNotificationState.publicTitle(fa))
        assertEquals("فرستنده‌ی ناشناس", SmsNotificationState.senderTitle("", { null }, fa))
        assertEquals("(پیام خالی)", SmsNotificationState.preview(" ", language = fa))
        assertEquals("۵ پیام جدید", SmsNotificationState.unreadLabel(5, fa))
        assertEquals("۹۹+ پیام جدید", SmsNotificationState.unreadLabel(500, fa))
        // the text of the message itself is never touched
        assertEquals("Hello  there".replace("  ", " "), SmsNotificationState.preview("Hello  there", language = fa))
        NotificationAccessStatus.values().filter { it != NotificationAccessStatus.ON }.forEach {
            assertNotEquals(SmsNotificationState.accessNotice(it), SmsNotificationState.accessNotice(it, fa))
        }
    }

    // ---------------------------------------------------------------- helpers

    @Test fun numbersAndIsolatesOnlyChangeInPersian() {
        assertEquals("12", TextDirection.number(12, en))
        assertEquals("۱۲", TextDirection.number(12, fa))
        assertEquals("+98912", TextDirection.isolate("+98912", en))
        assertEquals("\u2068+98912\u2069", TextDirection.isolate("+98912", fa))
        assertEquals("", TextDirection.isolate("", fa))
    }
}
