package com.example.companion.calls

import com.example.companion.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 28: read-only Call History error handling, pure parts. Plain JVM. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class CallLogErrorHandlingTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN

    // ---------------------------------------------------------------- malformed / incomplete rows

    @Test fun healthyRowBecomesTheSameEntryAsBefore() {
        val e = SimCallLogMapper.tryEntry(7, "+15551234", SimCallLogMapper.INCOMING_TYPE, 1000L, 65)!!
        assertEquals(SimCallLogMapper.toEntry(7, "+15551234", SimCallLogMapper.INCOMING_TYPE, 1000L, 65), e)
    }

    @Test fun rowWithoutUsableIdIsSkipped() {
        assertNull(SimCallLogMapper.tryEntry(null, "+1555", 1, 1000L, 5))
        assertNull(SimCallLogMapper.tryEntry(-1, "+1555", 1, 1000L, 5))
        assertNotNull(SimCallLogMapper.tryEntry(0, "+1555", 1, 1000L, 5))
    }

    @Test fun rowWithoutADateIsSkippedNotShownAs1970() {
        assertNull(SimCallLogMapper.tryEntry(1, "+1555", 1, null, 5))
        assertNull(SimCallLogMapper.tryEntry(1, "+1555", 1, 0L, 5))
        assertNull(SimCallLogMapper.tryEntry(1, "+1555", 1, -5L, 5))
    }

    @Test fun missingOptionalPartsStillGiveARow() {
        val e = SimCallLogMapper.tryEntry(3, null, null, 5000L, null)!!
        assertNull(e.number); assertNull(e.type); assertNull(e.durationSeconds); assertEquals(5000L, e.startedAt)
    }

    @Test fun withheldNumbersAreUnknownNotMinusOne() {
        listOf("-1", "-2", "-3", "", "  ", "\u0000").forEach { assertNull(it, SimCallLogMapper.cleanNumber(it)) }
        assertNull(SimCallLogMapper.cleanNumber(null))
        assertEquals("+15551234", SimCallLogMapper.cleanNumber(" +15551234 "))
        assertEquals("MYBANK", SimCallLogMapper.cleanNumber("MYBANK"))
        assertEquals("555-0100", SimCallLogMapper.cleanNumber("555-0100"))
    }

    @Test fun controlCharactersAndAbsurdLengthAreCleanedForDisplay() {
        assertEquals("+1555", SimCallLogMapper.cleanNumber("+15\n55\u0007"))
        assertEquals(SimCallLogMapper.MAX_NUMBER_LENGTH, SimCallLogMapper.cleanNumber("9".repeat(500))!!.length)
    }

    @Test fun normalizeStillDropsOnlyRepeatedIds() {
        val a = SimCallLogMapper.toEntry(1, "+1", 1, 100L, 1); val b = SimCallLogMapper.toEntry(2, "+2", 1, 200L, 1)
        assertEquals(listOf(b, a), SimCallHistoryState.normalize(listOf(a, b, a)))
    }

    // ---------------------------------------------------------------- result type and messages

    @Test fun loadedWithoutASkippedCountMeansNothingWasLeftOut() {
        assertEquals(0, CallLogResult.Loaded(emptyList()).skipped)
        assertEquals(2, CallLogResult.Loaded(emptyList(), 2).skipped)
    }

    @Test fun partialTextIsEmptyWhenNothingWasSkipped() {
        assertEquals("", SimCallHistoryState.partialText(0, en)); assertEquals("", SimCallHistoryState.partialText(-3, fa))
    }

    @Test fun partialTextSaysHowManyInBothLanguages() {
        assertTrue(SimCallHistoryState.partialText(3, en).startsWith("3 "))
        val p = SimCallHistoryState.partialText(3, fa)
        assertTrue(p, p.startsWith("۳ ")); assertTrue(Regex("[\\u0600-\\u06FF]").containsMatchIn(p))
    }

    @Test fun accessBlockedReasonIsLocalizedAndOldReasonsStillAre() {
        val english = "Android did not allow reading the call log even though permission was given. Check this app's permissions in system settings."
        assertEquals(english, SimCallHistoryState.failureText(english, en))
        assertNotEquals(english, SimCallHistoryState.failureText(english, fa))
        assertEquals("The call log could not be read.", SimCallHistoryState.failureText("The call log could not be read.", en))
        assertNotEquals("The call log is not available on this phone.", SimCallHistoryState.failureText("The call log is not available on this phone.", fa))
        assertEquals("some other text", SimCallHistoryState.failureText("some other text", fa))      // unknown text is never lost
    }

    @Test fun settingsButtonIsOfferedOnlyWhenTheDialogCannotHelp() {
        assertEquals(false, SimCallHistoryState.offerSettings(askedBefore = false, showRationale = false))   // first time: "Allow"
        assertEquals(false, SimCallHistoryState.offerSettings(askedBefore = true, showRationale = true))     // denied once: "Allow" again
        assertEquals(true, SimCallHistoryState.offerSettings(askedBefore = true, showRationale = false))     // denied for good / revoked
    }

    @Test fun permissionAndEmptyTextsAreUnchanged() {
        assertEquals(SimCallHistoryState.EMPTY_TEXT, SimCallHistoryState.emptyText())
        assertEquals(SimCallHistoryState.PERMISSION_TEXT, SimCallHistoryState.permissionText())
        assertNotEquals(SimCallHistoryState.EMPTY_TEXT, SimCallHistoryState.emptyText(fa))
    }
}
