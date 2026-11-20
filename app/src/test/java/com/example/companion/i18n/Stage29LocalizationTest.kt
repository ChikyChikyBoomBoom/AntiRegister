package com.example.companion.i18n

import com.example.companion.ui.SettingsIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 29: the Settings / first-run error texts. Plain JVM. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class Stage29LocalizationTest {

    private val keys = SettingsIssue.values().map { it.key }
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test fun everyIssueHasItsOwnCatalogKey() {
        assertEquals(SettingsIssue.values().size, keys.toSet().size)
    }

    @Test fun everyKeyHasAPersianTextWithTheSamePlaceholdersAndNoLatinLetters() {
        keys.forEach { k ->
            assertTrue(k.name, Strings.isTranslated(k, AppLanguage.PERSIAN))
            val fa = Strings.text(k, AppLanguage.PERSIAN)
            assertNotEquals(k.name, k.english, fa)
            assertFalse(k.name + ": " + fa, Regex("[A-Za-z]").containsMatchIn(fa.replace(placeholder, "")))
            assertEquals(k.name, placeholder.findAll(k.english).map { it.value }.toSet(), placeholder.findAll(fa).map { it.value }.toSet())
        }
    }

    @Test fun missingPersianFallsBackToEnglish() {
        keys.forEach { assertEquals(it.name, it.english, Strings.text(it, AppLanguage.PERSIAN, emptyMap<StringKey, String>())) }
    }

    @Test fun issueTextFollowsTheLanguage() {
        SettingsIssue.values().forEach {
            assertEquals(it.name, it.key.english, it.text(AppLanguage.ENGLISH))
            assertEquals(it.name, Strings.text(it.key, AppLanguage.PERSIAN), it.text(AppLanguage.PERSIAN))
        }
    }

    @Test fun noTextShowsATechnicalDetail() {
        keys.forEach { k ->
            listOf(AppLanguage.ENGLISH, AppLanguage.PERSIAN).forEach { l ->
                val t = Strings.text(k, l)
                assertTrue(k.name, t.isNotBlank())
                assertFalse(t, t.contains("Exception") || t.contains("at com.") || t.contains("Stack") || t.contains("null") || t.contains("SharedPreferences"))
            }
        }
    }

    @Test fun roleSaveFailureTextSaysTheRoleWasNotChanged() {
        assertTrue(StringKey.SETTINGS_ERR_ROLE_SAVE_FAILED.english.contains("not changed"))
    }
}
