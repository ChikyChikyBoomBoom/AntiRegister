package com.example.companion.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of the pure parts of Stage 16 (RTL layout support). WRITTEN BUT NOT RUN (no Gradle / Android SDK was available).
 * The view-level behaviour (LocalizedActivity, applyAppLayoutDirection, alignment helpers) needs a device or emulator; see STAGE16_NOTES.md.
 */
class Stage16RtlTest {

    @After fun resetLanguage() { AppLocale.setForTest(AppLanguage.DEFAULT) }

    @Test fun directionFollowsTheSelectedLanguage() {
        AppLocale.setForTest(AppLanguage.ENGLISH)
        assertFalse(AppLocale.isRtl)
        AppLocale.setForTest(AppLanguage.PERSIAN)
        assertTrue(AppLocale.isRtl)
        AppLocale.setForTest(AppLanguage.ENGLISH)
        assertFalse(AppLocale.isRtl)          // switching back returns to left-to-right
    }

    @Test fun onlyPersianIsRightToLeft() {
        assertTrue(AppLanguage.values().filter { it.isRtl } == listOf(AppLanguage.PERSIAN))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.DEFAULT)
        assertFalse(AppLanguage.DEFAULT.isRtl)
    }

    @Test fun ltrDataIsIsolatedOnlyInPersian() {
        val number = "+98 912 345 6789"
        assertEquals(number, TextDirection.ltrData(number, AppLanguage.ENGLISH))        // English output unchanged
        assertEquals("\u2066$number\u2069", TextDirection.ltrData(number, AppLanguage.PERSIAN))
        assertEquals("", TextDirection.ltrData("", AppLanguage.PERSIAN))
    }

    @Test fun ltrDataNeverReordersTheCharacters() {
        val time = "11/20/26, 3:45 PM"
        val wrapped = TextDirection.ltrData(time, AppLanguage.PERSIAN)
        assertEquals(time, wrapped.removePrefix("\u2066").removeSuffix("\u2069"))       // only invisible marks are added
    }

    @Test fun existingIsolatesAreUnchanged() {
        assertEquals("⁦+98912⁩", TextDirection.isolateLtr("+98912"))
        assertEquals("abc", TextDirection.isolate("abc", AppLanguage.ENGLISH))
    }
}
