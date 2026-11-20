package com.example.companion.i18n

import com.example.companion.ui.RecentCallTap
import com.example.companion.ui.RecentCallsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 18: JVM tests for the two localization fixes on Home and Messages. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class Stage18AuditTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN

    /** The Messages call-tap toast now goes through tapMessage; English must still equal the old RecentCallTap.message. */
    @Test fun tapMessageEnglishEqualsOriginalText() {
        RecentCallTap.values().forEach { assertEquals(it.message, RecentCallsState.tapMessage(it, en)) }
    }

    @Test fun tapMessagePersianIsTranslated() {
        RecentCallTap.values().filter { it != RecentCallTap.DIAL }.forEach {
            val text = RecentCallsState.tapMessage(it, fa)
            assertNotEquals(it.message, text)
            assertTrue(Regex("[\\u0600-\\u06FF]").containsMatchIn(text))
        }
    }

    /** Every catalog key has a Persian text (this is what the Stage 18 audit found: no key is missing). */
    @Test fun everyKeyHasPersianText() {
        val missing = StringKey.values().filter { !Strings.isTranslated(it, fa) }
        assertTrue("Missing Persian: $missing", missing.isEmpty())
    }

    /** The Setup row titles used by the Home banner exist in Persian, so readSetupRows(AppLocale.current) can show them. */
    @Test fun setupRowTitlesAreTranslated() {
        listOf(StringKey.SETUP_BT_TITLE, StringKey.SETUP_SMS_TITLE, StringKey.SETUP_NOTIF_TITLE).forEach {
            assertNotEquals(Strings.text(it, en), Strings.text(it, fa))
        }
    }
}
