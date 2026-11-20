package com.example.companion.i18n

import com.example.companion.calls.CallFeatures
import com.example.companion.calls.CallLogEntry
import com.example.companion.calls.CallLogType
import com.example.companion.calls.SimCallHistoryState
import com.example.companion.contacts.ContactsAccess
import com.example.companion.contacts.ContactsState
import com.example.companion.ui.CallScreenState
import com.example.companion.ui.HistoryAction
import com.example.companion.ui.HistoryState
import com.example.companion.ui.HistoryTab
import com.example.companion.ui.RecentCallTap
import com.example.companion.ui.RecentCallsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of the Stage 14 localization of the Contacts card and the Call History screens. WRITTEN BUT NOT RUN (no Gradle / Android SDK
 * was available). Checks: the original English wording is unchanged, every Stage 14 text has a Persian version, a missing Persian text
 * falls back to English, language switching changes what both sections read, and call history is still read-only.
 */
class Stage14LocalizationTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN

    @After fun resetLanguage() { AppLocale.setForTest(AppLanguage.DEFAULT) }

    private val stage14Prefixes = listOf("CONTACTS_", "CALLS_")
    private val stage14Keys get() = StringKey.values().filter { k -> stage14Prefixes.any { k.name.startsWith(it) } }

    private fun hasLatin(s: String) = Regex("[A-Za-z]").containsMatchIn(s.replace(Regex("%\\d+\\$[sd]"), ""))

    private fun entry(type: CallLogType?, duration: Long? = null, cause: String? = null, number: String? = "+15551234") =
        CallLogEntry("id", number, type, 0L, duration, cause)

    // ---------------------------------------------------------------- coverage and fallback

    @Test fun everyStage14KeyHasAPersianTextWithTheSamePlaceholders() {
        assertTrue(stage14Keys.size >= 45)
        val missing = stage14Keys.filter { !Strings.isTranslated(it, fa) }
        assertTrue("missing Persian: $missing", missing.isEmpty())
        val re = Regex("%\\d+\\$[sd]")
        stage14Keys.forEach { k ->
            val persian = Strings.text(k, fa)
            assertNotEquals(k.name, k.english, persian)
            assertFalse("${k.name} has Latin letters: $persian", hasLatin(persian))
            assertEquals(k.name, re.findAll(k.english).map { it.value }.toSet(), re.findAll(persian).map { it.value }.toSet())
        }
    }

    @Test fun missingPersianTextFallsBackToEnglish() {
        val empty = emptyMap<StringKey, String>()
        stage14Keys.forEach { assertEquals(it.name, it.english, Strings.text(it, fa, empty)) }
        val partial = PersianStrings.table - StringKey.CALLS_EMPTY_TITLE
        assertEquals("No recent calls", Strings.text(StringKey.CALLS_EMPTY_TITLE, fa, partial))
        assertEquals(Strings.text(StringKey.CALLS_EMPTY_BODY, fa), Strings.text(StringKey.CALLS_EMPTY_BODY, fa, partial))
    }

    // ---------------------------------------------------------------- English is unchanged (default language, constants)

    @Test fun contactsEnglishIsUnchanged() {
        assertEquals(ContactsState.NOTICE_CAN_ASK, ContactsState.notice(ContactsAccess.CAN_ASK))
        assertEquals(ContactsState.NOTICE_SETTINGS_ONLY, ContactsState.notice(ContactsAccess.SETTINGS_ONLY))
        assertEquals("", ContactsState.notice(ContactsAccess.GRANTED))
        assertEquals("", ContactsState.notice(ContactsAccess.GRANTED, fa))
        assertEquals(ContactsState.BUTTON_CAN_ASK, ContactsState.buttonLabel(ContactsAccess.CAN_ASK))
        assertEquals(ContactsState.BUTTON_SETTINGS, ContactsState.buttonLabel(ContactsAccess.SETTINGS_ONLY))
        assertEquals(ContactsState.NO_CONTACTS_APP, ContactsState.noContactsApp())
        assertEquals(ContactsState.NOT_USABLE, ContactsState.notUsable())
    }

    @Test fun recentCallsEnglishIsUnchanged() {
        assertEquals("Outgoing", RecentCallsState.typeLabel(entry(CallLogType.OUTGOING)))
        assertEquals("Missed", RecentCallsState.typeLabel(entry(CallLogType.MISSED)))
        assertEquals("Call", RecentCallsState.typeLabel(entry(null)))
        assertEquals("Incoming (declined)", RecentCallsState.typeLabel(entry(CallLogType.INCOMING, cause = "REJECTED")))
        assertEquals("Incoming (answered elsewhere)", RecentCallsState.typeLabel(entry(CallLogType.INCOMING, cause = "ANSWERED_ELSEWHERE")))
        assertEquals("Incoming", RecentCallsState.typeLabel(entry(CallLogType.INCOMING, duration = 3)))
        RecentCallTap.values().forEach { assertEquals(it.name, it.message, RecentCallsState.tapMessage(it)) }
        assertEquals("SIM Phone: connected", RecentCallsState.linkLine(true))
        assertEquals("SIM Phone unavailable (Bluetooth not connected). History is still shown; calling is off.", RecentCallsState.linkLine(false))
        assertEquals(CallScreenState.UNKNOWN_NUMBER, RecentCallsState.unknownNumber())
        val r = RecentCallsState.row(entry(CallLogType.INCOMING, duration = 65), { "10:05" })
        assertEquals("Incoming · 10:05 · 01:05", r.detail)
        assertEquals(CallScreenState.UNKNOWN_NUMBER, RecentCallsState.row(entry(null, number = null), { "t" }).title)
    }

    @Test fun historyEnglishIsUnchanged() {
        assertEquals(HistoryState.CALLS_HINT, HistoryState.callsHint())
        assertEquals(HistoryState.NO_SMS_NUMBER, HistoryState.noSmsNumber())
        assertEquals("Clear all calls", HistoryState.clearAllLabel(HistoryTab.CALLS))
        assertEquals("Clear all messages", HistoryState.clearAllLabel(HistoryTab.MESSAGES))
        assertEquals("Clear all calls?", HistoryState.clearAllTitle(HistoryTab.CALLS))
        HistoryAction.values().forEach { assertEquals(it.name, it.label, HistoryState.callsActionLabel(it)) }
        assertEquals("Removes 1 call from Recent Calls on this phone only. This cannot be undone.", HistoryState.clearAllMessage(HistoryTab.CALLS, 1))
        assertEquals("Removes 3 calls from Recent Calls on this phone only. This cannot be undone.", HistoryState.clearAllMessage(HistoryTab.CALLS, 3))
        assertEquals(
            "Removes 2 conversations (7 messages) from this phone only. Nothing is deleted on the SIM Phone. This cannot be undone.",
            HistoryState.clearAllMessage(HistoryTab.MESSAGES, 2, 7)
        )
    }

    @Test fun simCallHistoryEnglishIsUnchanged() {
        assertEquals(SimCallHistoryState.EMPTY_TEXT, SimCallHistoryState.emptyText())
        assertEquals(SimCallHistoryState.LOADING_TEXT, SimCallHistoryState.loadingText())
        assertEquals(SimCallHistoryState.PERMISSION_TEXT, SimCallHistoryState.permissionText())
        listOf("The call log is not available on this phone.", "The call log has an unexpected format.", "The call log could not be read.")
            .forEach { assertEquals(it, SimCallHistoryState.failureText(it)) }
    }

    // ---------------------------------------------------------------- Persian

    @Test fun persianCallHistoryTexts() {
        assertEquals("خروجی", RecentCallsState.typeLabel(entry(CallLogType.OUTGOING), fa))
        assertEquals("بی‌پاسخ", RecentCallsState.typeLabel(entry(CallLogType.MISSED), fa))
        assertEquals("ورودی (رد شده)", RecentCallsState.typeLabel(entry(CallLogType.INCOMING, cause = "LOCAL"), fa))
        assertEquals("شماره‌ی ناشناس", RecentCallsState.row(entry(null, number = null), { "t" }, fa).title)
        RecentCallTap.values().filter { it != RecentCallTap.DIAL }.forEach {
            assertFalse(it.name, hasLatin(RecentCallsState.tapMessage(it, fa)))
        }
        assertEquals("", RecentCallsState.tapMessage(RecentCallTap.DIAL, fa))
    }

    @Test fun persianDetailLineUsesPersianDigitsButKeepsThePhoneNumberAsTyped() {
        val r = RecentCallsState.row(entry(CallLogType.INCOMING, duration = 65), { "10:05" }, fa)
        assertEquals("ورودی · \u2066۱۰:۰۵\u2069 · \u2066۰۱:۰۵\u2069", r.detail)
        assertEquals("+15551234", r.title)          // data, not wording
        assertEquals("+15551234", r.number)
        val named = RecentCallsState.row(entry(CallLogType.OUTGOING), { "10:05" }, fa) { "Ali" }
        assertEquals("Ali", named.title)
        assertEquals("+15551234", named.numberLine)
    }

    @Test fun persianClearAllUsesPersianDigits() {
        assertTrue(HistoryState.clearAllMessage(HistoryTab.CALLS, 3, language = fa).startsWith("۳ تماس"))
        assertTrue(HistoryState.clearAllMessage(HistoryTab.CALLS, 1, language = fa).startsWith("۱ تماس"))
        assertFalse(hasLatin(HistoryState.clearAllMessage(HistoryTab.MESSAGES, 2, 7, fa)))
    }

    @Test fun persianContactsAndSimHistoryTexts() {
        assertEquals("نمایش نام مخاطبین", ContactsState.buttonLabel(ContactsAccess.CAN_ASK, fa))
        assertEquals("باز کردن تنظیمات برنامه", ContactsState.buttonLabel(ContactsAccess.SETTINGS_ONLY, fa))
        assertFalse(hasLatin(ContactsState.notice(ContactsAccess.CAN_ASK, fa)))
        assertEquals(Strings.text(StringKey.HOME_NO_CONTACTS_APP, fa), ContactsState.noContactsApp(fa))
        assertEquals(Strings.text(StringKey.SMS_CONTACT_NOT_USABLE_REASON, fa), ContactsState.notUsable(fa))
        assertEquals("در حال بارگذاری تاریخچه‌ی تماس…", SimCallHistoryState.loadingText(fa))
        assertEquals(Strings.text(StringKey.CALLS_SIM_READ_FAILED, fa), SimCallHistoryState.failureText("The call log could not be read.", fa))
        // a reason the app does not know is shown unchanged, never blank
        assertEquals("something else", SimCallHistoryState.failureText("something else", fa))
        // already-Persian text is left alone
        assertEquals("متن", SimCallHistoryState.failureText("متن", fa))
    }

    // ---------------------------------------------------------------- language switching

    @Test fun switchingLanguageChangesWhatBothSectionsRead() {
        AppLocale.setForTest(fa)
        assertEquals("تماس‌های اخیر", tr(StringKey.CALLS_TITLE))
        assertEquals("تاریخچه‌ی تماس", tr(StringKey.CALLS_SIM_HEADER))
        assertEquals("نمایش نام مخاطبین", ContactsState.buttonLabel(ContactsAccess.CAN_ASK, AppLocale.current))
        assertEquals("خروجی", RecentCallsState.typeLabel(entry(CallLogType.OUTGOING), AppLocale.current))
        AppLocale.setForTest(en)
        assertEquals("Recent Calls", tr(StringKey.CALLS_TITLE))
        assertEquals("Call history", tr(StringKey.CALLS_SIM_HEADER))
        assertEquals("Show contact names", ContactsState.buttonLabel(ContactsAccess.CAN_ASK, AppLocale.current))
        assertEquals("Outgoing", RecentCallsState.typeLabel(entry(CallLogType.OUTGOING), AppLocale.current))
    }

    // ---------------------------------------------------------------- read-only guarantees are untouched

    @Test fun callingAndHistoryEditingStayOff() {
        assertFalse(CallFeatures.isCallingEnabled())
        assertFalse(CallFeatures.isHistoryEditingEnabled())
        assertEquals("این بخش در حال توسعه است", CallFeatures.DISABLED_MESSAGE)   // the Stage 7 constant is unchanged
    }
}
