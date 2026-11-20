package com.example.companion.i18n

import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.calls.CallFeatures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 20: JVM tests for language switching and fallback. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available).
 * Dialogs, notifications and the screens themselves need a device; see app/STAGE20_NOTES.md.
 */
class Stage20LocalizationTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN

    // ---------------------------------------------------------------- saved language -> language (what AppLocale.load does)

    @Test fun savedCodeMapsToTheSameLanguageAfterRestart() {
        AppLanguage.values().forEach { assertEquals(it, AppLanguage.fromCode(it.code)) }
        assertEquals(fa, AppLanguage.fromCode("fa"))
        assertEquals(en, AppLanguage.fromCode("en"))
    }

    @Test fun nothingSavedOrUnknownCodeMeansEnglish() {
        assertEquals(en, AppLanguage.fromCode(null))
        assertEquals(en, AppLanguage.fromCode(""))
        assertEquals(en, AppLanguage.fromCode("FA"))        // codes are exact; a damaged value never crashes
        assertEquals(en, AppLanguage.fromCode("de"))
        assertEquals(en, AppLanguage.DEFAULT)
    }

    @Test fun languageSetsDirectionAndLocale() {
        assertTrue(fa.isRtl); assertFalse(en.isRtl)
        assertEquals("fa", fa.toLocale().language); assertEquals("en", en.toLocale().language)
    }

    @Test fun languageNamesAreWrittenInTheirOwnLanguage() {
        assertEquals("English", en.nativeName); assertEquals("فارسی", fa.nativeName)
    }

    // ---------------------------------------------------------------- the language chooser texts (Home, welcome, and now Settings)

    @Test fun languageChooserTextsExistInBothLanguages() {
        listOf(StringKey.LANGUAGE_TITLE, StringKey.LANGUAGE_CURRENT, StringKey.LANGUAGE_HINT, StringKey.ACTION_CANCEL).forEach {
            assertTrue("No Persian: $it", Strings.isTranslated(it, fa))
            assertNotEquals("Persian equals English: $it", it.english, Strings.text(it, fa))
        }
        assertEquals("Language: فارسی", Strings.text(StringKey.LANGUAGE_CURRENT, en, fa.nativeName))
        assertEquals("Language: English", Strings.text(StringKey.LANGUAGE_CURRENT, en, en.nativeName))
        assertEquals("زبان: English", Strings.text(StringKey.LANGUAGE_CURRENT, fa, en.nativeName))
    }

    // ---------------------------------------------------------------- fallback for a missing translation

    @Test fun missingPersianTextFallsBackToEnglishNotToAnEmptyString() {
        val withoutOne = PersianStrings.table - StringKey.LANGUAGE_HINT
        assertEquals(StringKey.LANGUAGE_HINT.english, Strings.text(StringKey.LANGUAGE_HINT, fa, withoutOne))
        assertEquals(PersianStrings.table[StringKey.LANGUAGE_TITLE], Strings.text(StringKey.LANGUAGE_TITLE, fa, withoutOne))
        assertEquals(StringKey.LANGUAGE_HINT.english, Strings.text(StringKey.LANGUAGE_HINT, en, withoutOne))
    }

    @Test fun brokenPersianPatternFallsBackToEnglishPattern() {
        // A wrong number of arguments must not throw on a screen (Strings.text never throws).
        Strings.text(StringKey.LANGUAGE_CURRENT, fa)
        Strings.text(StringKey.LANGUAGE_CURRENT, fa, "a", "b")
        assertEquals("Language: x", Strings.text(StringKey.LANGUAGE_CURRENT, en, "x"))
    }

    // ---------------------------------------------------------------- catalog quality (all keys)

    @Test fun everyKeyHasPersianAndNoArabicFormLetters() {
        val arabicForms = Regex("[\\u064A\\u0643\\u0629\\u0649\\u0660-\\u0669]")   // Arabic yeh, kaf, teh marbuta, alef maksura, Arabic-Indic digits
        StringKey.values().forEach {
            assertTrue("No Persian: $it", Strings.isTranslated(it, fa))
            assertFalse("Arabic-form character in $it", arabicForms.containsMatchIn(Strings.text(it, fa)))
        }
    }

    // ---------------------------------------------------------------- Stage 20 fixes

    @Test fun smsPermissionHintNamesTheScreensInPersianNotEnglish() {
        val text = Strings.text(StringKey.SMS_FAIL_PERMISSION_DENIED, fa)
        assertFalse(text, text.contains("Status"))
        assertFalse(text, text.contains("Set up SMS forwarding"))
        assertTrue(text, text.contains(Strings.text(StringKey.STATUS_TITLE, fa)))
        assertTrue(text, text.contains(Strings.text(StringKey.STATUS_SMS_SETUP, fa)))
        assertTrue(StringKey.SMS_FAIL_PERMISSION_DENIED.english.contains("Status > Set up SMS forwarding"))   // English unchanged
    }

    @Test fun homeErrorLineGoesThroughTheSameMapperAsSettings() {
        val english = StringKey.ERR_BT_OFF.english
        assertEquals(english, ConnectionErrorText.localizeOrNull(english, en))
        assertEquals(Strings.text(StringKey.ERR_BT_OFF, fa), ConnectionErrorText.localizeOrNull(english, fa))
        assertEquals("unknown system text", ConnectionErrorText.localizeOrNull("unknown system text", fa))   // unrecognised text is never lost
        assertEquals(null, ConnectionErrorText.localizeOrNull(null, fa))
    }

    // ---------------------------------------------------------------- source checks (no Android needed)

    private fun src(path: String): String {
        val candidates = listOf("src/main/java/com/example/companion/$path", "app/src/main/java/com/example/companion/$path")
        return candidates.map { File(it) }.first { it.exists() }.readText()
    }

    @Test fun settingsOffersTheLanguageChooser() {
        val settings = src("ui/SettingsActivity.kt")
        assertTrue(settings.contains("showLanguageDialog(app.prefs)"))
        assertTrue(settings.contains("StringKey.LANGUAGE_TITLE"))
    }

    @Test fun everyScreenUsesTheLocalizedBaseClass() {
        File(listOf("src/main/java/com/example/companion/ui", "app/src/main/java/com/example/companion/ui").first { File(it).exists() })
            .listFiles { f -> f.name.endsWith(".kt") }!!.forEach { f ->
                val text = f.readText()
                assertFalse("${f.name} extends the plain Activity", Regex(":\\s*Activity\\(\\)").containsMatchIn(text))
                assertFalse("${f.name} extends AppCompatActivity", text.contains(": AppCompatActivity"))
            }
    }

    @Test fun theSavedLanguageIsWrittenInOnePlaceAndReadAtStartup() {
        // Stage 29: the one write goes through AppPrefs.saveLanguage (a commit that reports failure) instead of the fire-and-forget setter.
        assertTrue(src("i18n/AppLocale.kt").contains("prefs.saveLanguage(language.code)"))
        assertTrue(src("app/CompanionApp.kt").contains("AppLocale.load(prefs)"))
    }

    // ---------------------------------------------------------------- constraints

    @Test fun callingAudioHfpAndMagiskStayDisabled() {
        assertFalse(CallFeatures.isCallingEnabled())
        assertFalse(AudioTransferFeatures.isTransferEnabled())
        assertFalse(AudioTransferFeatures.HFP_ENABLED)
        assertFalse(AudioTransferFeatures.MAGISK_ENABLED)
    }
}
