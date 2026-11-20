package com.example.companion.sms

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Stage 26: JVM tests of the SMS error-handling pieces that are pure Kotlin: the developer diagnostics, the two new failure codes and
 * the new Persian / English texts. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). The receivers, controllers and screens
 * need Android and are not covered here (see STAGE26_NOTES.md).
 */
class SmsErrorHandlingTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN
    private val lines = ArrayList<String>()
    private val oldSink = SmsDiagnostics.sink
    private val oldClock = SmsDiagnostics.clock

    @Before fun setUp() { SmsDiagnostics.clear(); lines.clear(); SmsDiagnostics.sink = { lines.add(it) } }
    @After fun tearDown() { SmsDiagnostics.sink = oldSink; SmsDiagnostics.clock = oldClock; SmsDiagnostics.clear() }

    // ------------------------------------------------------------ diagnostics

    @Test fun recordKeepsAreaPhraseAndExceptionClassButNotTheExceptionMessage() {
        val secret = "+15551234567 hello this is private"
        SmsDiagnostics.record(SmsDiagnostics.Area.SIM_RECEIVE, "incoming sms processing failed", IllegalStateException(secret))
        val e = SmsDiagnostics.recent().single()
        assertEquals(SmsDiagnostics.Area.SIM_RECEIVE, e.area)
        assertEquals("java.lang.IllegalStateException", e.exception)
        assertTrue(e.frames.isNotEmpty() && e.frames.size <= 4)
        val line = lines.single()
        assertFalse(line.contains("5551234567")); assertFalse(line.contains("private"))
        assertTrue(line.startsWith("SIM_RECEIVE: incoming sms processing failed"))
    }

    @Test fun recordWithoutAnExceptionIsStillKept() {
        SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_RECEIVE, "dropped: invalid or unsupported received message")
        val e = SmsDiagnostics.recent().single()
        assertEquals(null, e.exception); assertTrue(e.frames.isEmpty())
    }

    @Test fun keepsOnlyTheNewestEntries() {
        repeat(SmsDiagnostics.MAX_ENTRIES + 10) { SmsDiagnostics.record(SmsDiagnostics.Area.UI, "p$it") }
        val all = SmsDiagnostics.recent()
        assertEquals(SmsDiagnostics.MAX_ENTRIES, all.size)
        assertEquals("p${SmsDiagnostics.MAX_ENTRIES + 9}", all.last().what)
        assertEquals("p10", all.first().what)
    }

    @Test fun recordNeverThrowsEvenIfTheSinkOrTheClockDoes() {
        SmsDiagnostics.sink = { throw IllegalStateException("sink broken") }
        SmsDiagnostics.record(SmsDiagnostics.Area.UI, "a", RuntimeException("x"))
        SmsDiagnostics.sink = { lines.add(it) }
        SmsDiagnostics.clock = { throw IllegalStateException("clock broken") }
        SmsDiagnostics.record(SmsDiagnostics.Area.UI, "b")
    }

    @Test fun longTextsAreCut() {
        SmsDiagnostics.record(SmsDiagnostics.Area.UI, "x".repeat(500), detail = "y".repeat(500))
        val e = SmsDiagnostics.recent().single()
        assertTrue(e.what.length <= 80); assertTrue(e.detail!!.length <= 80)
    }

    // ------------------------------------------------------------ codes and texts

    private val allCodes = listOf(
        SmsError.INVALID_REQUEST, SmsError.PERMISSION_DENIED, SmsError.NO_TELEPHONY, SmsError.SMS_API_ERROR, SmsError.GENERIC_FAILURE,
        SmsError.RADIO_OFF, SmsError.NO_SERVICE, SmsError.NULL_PDU, SmsError.LIMIT_EXCEEDED, SmsError.FDN_CHECK_FAILURE,
        SmsError.SHORT_CODE_NOT_ALLOWED, SmsError.PARTIAL_FAILURE, SmsError.NOT_RECEIVED, SmsError.RESULT_UNKNOWN,
        SmsError.STORAGE_ERROR, SmsError.INTERNAL_ERROR
    )

    @Test fun newCodesKeepTheirStableStrings() {
        assertEquals("STORAGE_ERROR", SmsError.STORAGE_ERROR)
        assertEquals("INTERNAL_ERROR", SmsError.INTERNAL_ERROR)
    }

    @Test fun englishLocalizedTextEqualsTheOriginalDescribeForEveryCode() {
        (allCodes + listOf(null, "SOMETHING_ELSE")).forEach { code ->
            assertEquals(code, SmsError.describe(code, null), SmsErrorText.describe(code, null, en))
            assertEquals(code, SmsError.describe(code, "d"), SmsErrorText.describe(code, "d", en))
        }
    }

    @Test fun persianTextExistsAndDiffersFromEnglishForTheNewCodes() {
        listOf(SmsError.STORAGE_ERROR, SmsError.INTERNAL_ERROR).forEach { code ->
            val persian = SmsErrorText.describe(code, null, fa)
            assertNotEquals(code, SmsError.describe(code, null), persian)
            assertTrue(code, Regex("[\u0600-\u06FF]").containsMatchIn(persian))
        }
    }

    @Test fun newKeysAreTranslatedAndHaveNoLatinLetters() {
        val keys = listOf(StringKey.SMS_REFUSE_INTERNAL, StringKey.SMS_FAIL_STORAGE, StringKey.SMS_FAIL_INTERNAL,
            StringKey.SMS_ERROR_LOAD, StringKey.SMS_ERROR_INCOMING_NOT_SAVED)
        keys.forEach { k ->
            assertTrue(k.name, Strings.isTranslated(k, fa))
            assertFalse(k.name, Regex("[A-Za-z]").containsMatchIn(Strings.text(k, fa)))
            assertTrue(k.name, k.english.isNotBlank())
        }
    }

    @Test fun internalRefusalIsMappedToThePickedLanguageAndStaysEnglishInEnglish() {
        assertEquals(SmsErrorText.REFUSE_INTERNAL, SmsErrorText.refusal(SmsErrorText.REFUSE_INTERNAL, en))
        assertEquals(Strings.text(StringKey.SMS_REFUSE_INTERNAL, fa), SmsErrorText.refusal(SmsErrorText.REFUSE_INTERNAL, fa))
        assertEquals(StringKey.SMS_REFUSE_INTERNAL.english, SmsErrorText.REFUSE_INTERNAL)   // the controller's text and the catalog never drift apart
    }

    @Test fun userTextNeverContainsAStackTraceOrAClassName() {
        (allCodes + listOf(null, "X")).forEach { code ->
            listOf(en, fa).forEach { lang ->
                val t = SmsErrorText.describe(code, null, lang)
                assertFalse("$code $lang: $t", t.contains("Exception") || t.contains(".kt") || t.contains(" at "))
            }
        }
    }

    // ------------------------------------------------------------ a guard on the source (pure file scan, like SmsBluetoothIsolationTest)

    @Test fun smsEntryPointsThatRunInsideReceiversAreGuarded() {
        val root = listOf("src/main/java", "app/src/main/java").map { File(it, "com/example/companion/sms") }.firstOrNull { it.exists() } ?: return
        listOf("SmsReceiver.kt", "SmsSentReceiver.kt").forEach { name ->
            val src = File(root, name).readText()
            assertTrue("$name must catch exceptions in onReceive", src.contains("catch (e: Exception)") && src.contains("SmsDiagnostics"))
        }
        listOf("SmsReceiver.kt", "SmsSentReceiver.kt", "HostSmsController.kt", "ClientSmsController.kt", "SmsGateway.kt").forEach { name ->
            val src = File(root, name).readText()
            // Nothing that could leak a message goes to the log: diagnostics get fixed phrases and exception classes only.
            assertFalse("$name must not use android.util.Log directly", src.contains("android.util.Log"))
            assertFalse("$name must not print stack traces", src.contains("printStackTrace"))
        }
    }
}
