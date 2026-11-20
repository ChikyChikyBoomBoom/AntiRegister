package com.example.companion.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Stage 12 localization core. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class LocalizationTest {

    @Test fun defaultAndUnknownCodesAreEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.DEFAULT)
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("de"))
        assertEquals(AppLanguage.PERSIAN, AppLanguage.fromCode("fa"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
    }

    @Test fun onlyPersianIsRtl() {
        assertFalse(AppLanguage.ENGLISH.isRtl)
        assertTrue(AppLanguage.PERSIAN.isRtl)
    }

    @Test fun everyKeyHasEnglishText() {
        StringKey.values().forEach { assertTrue(it.name, it.english.isNotBlank()) }
        assertEquals("AntiRegister", Strings.text(StringKey.APP_NAME, AppLanguage.ENGLISH))
    }

    @Test fun persianNamesTheApp() {
        assertEquals("آنتی رجیستر", Strings.text(StringKey.APP_NAME, AppLanguage.PERSIAN))
    }

    @Test fun missingPersianTextFallsBackToEnglish() {
        val missing = StringKey.values().firstOrNull { !PersianStrings.table.containsKey(it) } ?: return   // nothing missing: nothing to check
        assertEquals(missing.english, Strings.text(missing, AppLanguage.PERSIAN))
        assertFalse(Strings.isTranslated(missing, AppLanguage.PERSIAN))
    }

    @Test fun persianTableHasNoUnknownOrBlankEntries() {
        PersianStrings.table.values.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test fun persianPlaceholdersMatchEnglish() {
        val re = Regex("%\\d+\\$[sd]")
        PersianStrings.table.forEach { (k, fa) ->
            assertEquals(k.name, re.findAll(k.english).map { it.value }.toSet(), re.findAll(fa).map { it.value }.toSet())
        }
    }

    @Test fun formatsArguments() {
        assertEquals("Language: English", Strings.text(StringKey.LANGUAGE_CURRENT, AppLanguage.ENGLISH, "English"))
        assertEquals("زبان: فارسی", Strings.text(StringKey.LANGUAGE_CURRENT, AppLanguage.PERSIAN, "فارسی"))
    }

    @Test fun wrongArgumentsNeverThrow() {
        // %1$s with no argument: returns the unformatted pattern instead of crashing
        assertEquals("Language: %1\$s", Strings.text(StringKey.LANGUAGE_CURRENT, AppLanguage.ENGLISH, *arrayOf<Any?>()))
        Strings.text(StringKey.LANGUAGE_CURRENT, AppLanguage.ENGLISH, 1, 2, 3)
    }

    @Test fun persianDigits() {
        assertEquals("۰۱۲۳۴۵۶۷۸۹", TextDirection.localizeDigits("0123456789", AppLanguage.PERSIAN))
        assertEquals("0123", TextDirection.localizeDigits("0123", AppLanguage.ENGLISH))
        assertEquals("ab ۱:۰۵", TextDirection.localizeDigits("ab 1:05", AppLanguage.PERSIAN))
    }

    @Test fun ltrIsolateWrapsText() {
        assertEquals("⁦+98912⁩", TextDirection.isolateLtr("+98912"))
    }
}
