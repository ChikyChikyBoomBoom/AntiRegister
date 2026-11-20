package com.example.companion.i18n

import com.example.companion.ui.HistoryState
import com.example.companion.ui.HistoryTab
import com.example.companion.ui.RecentCallsState
import com.example.companion.calls.CallLogEntry
import com.example.companion.calls.CallLogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of the pure parts of Stage 17 (RTL on Contacts, Call History, Settings, Onboarding). WRITTEN BUT NOT RUN (no Gradle / Android SDK was available).
 * Everything that is a View attribute (followLayoutStart, firstStrongOrLtr, the Device Picker row direction) needs a device or emulator; see STAGE17_NOTES.md.
 */
class Stage17RtlTest {

    @Test fun selectedTabDescriptionIsUnchangedInEnglish() {
        HistoryTab.values().forEach {
            assertEquals("${it.label}, selected", HistoryState.tabSelectedDescription(it.label))
            assertEquals("${it.label}, selected", HistoryState.tabSelectedDescription(it.label, AppLanguage.ENGLISH))
        }
    }

    @Test fun selectedTabDescriptionIsPersianInPersian() {
        val text = HistoryState.tabSelectedDescription("تماس‌های اخیر", AppLanguage.PERSIAN)
        assertEquals("تماس‌های اخیر، " + Strings.text(StringKey.PICKER_SELECTED, AppLanguage.PERSIAN), text)
        assertFalse(text.contains("selected"))
    }

    @Test fun titlesThatAreNumbersAreIsolatedOnlyInPersian() {
        val number = "+98 912 345 6789"
        assertEquals(number, TextDirection.isolate(number, AppLanguage.ENGLISH))            // English dialogs and ActionBar: unchanged
        assertEquals("\u2068$number\u2069", TextDirection.isolate(number, AppLanguage.PERSIAN))
    }

    @Test fun callHistoryTimeAndDurationStayIsolatedInPersian() {
        val entry = CallLogEntry(id = "1", number = "+989123456789", type = CallLogType.INCOMING, startedAt = 0L, durationSeconds = 83L, cause = null)
        val fa = RecentCallsState.row(entry, { "11/20/26, 3:45 PM" }, AppLanguage.PERSIAN)
        assertEquals("+989123456789", fa.title)                                          // the number is never edited or wrapped
        assertTrue(fa.whenText.startsWith("\u2066") && fa.whenText.endsWith("\u2069"))   // LTR isolate: keeps its order inside the Persian line
        assertTrue(fa.durationText.startsWith("\u2066") && fa.durationText.endsWith("\u2069"))
        val en = RecentCallsState.row(entry, { "11/20/26, 3:45 PM" }, AppLanguage.ENGLISH)
        assertEquals("11/20/26, 3:45 PM", en.whenText)                                   // English: no direction marks at all
        assertFalse(en.detail.any { it in '\u2066'..'\u2069' })
    }
}
