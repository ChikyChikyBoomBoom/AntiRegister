package com.example.companion.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 28: the five new catalog keys. Plain JVM. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class Stage28LocalizationTest {

    private val keys = listOf(
        StringKey.CONTACTS_NOTICE_UNAVAILABLE, StringKey.CONTACTS_PICK_UNREADABLE, StringKey.CONTACTS_PICK_NO_NUMBER,
        StringKey.CALLS_SIM_ACCESS_BLOCKED, StringKey.CALLS_SIM_PARTIAL
    )
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test fun everyNewKeyHasAPersianTextWithTheSamePlaceholdersAndNoLatinLetters() {
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

    @Test fun formattedKeyNeverThrowsOnAWrongArgumentCount() {
        assertTrue(Strings.text(StringKey.CALLS_SIM_PARTIAL, AppLanguage.ENGLISH).isNotEmpty())
        assertTrue(Strings.text(StringKey.CALLS_SIM_PARTIAL, AppLanguage.PERSIAN, 1, 2).isNotEmpty())
    }

    @Test fun noTextMentionsAStackTraceOrAnExceptionName() {
        keys.forEach { k ->
            listOf(AppLanguage.ENGLISH, AppLanguage.PERSIAN).forEach { l ->
                val t = Strings.text(k, l)
                assertFalse(t, t.contains("Exception") || t.contains("at com.") || t.contains("Stack"))
            }
        }
    }
}
