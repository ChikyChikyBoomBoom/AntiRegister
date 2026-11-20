package com.example.companion.sms

import android.app.Activity
import android.telephony.SmsManager
import com.example.companion.protocol.SmsSendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests of how per-part Android results become one SENT / FAILED result. WRITTEN BUT NOT RUN. */
class SmsSendLogicTest {
    private val ok = Activity.RESULT_OK
    private val fail = SmsManager.RESULT_ERROR_NO_SERVICE

    @Test fun singlePartSuccessIsSent() {
        val r = SmsSendLogic.applyPart(SmsSendLogic.started("a", 1, 0), ok, 1)
        assertEquals(SmsSendState.SENT, r.state)
    }

    @Test fun staysSendingUntilEveryPartReported() {
        var r = SmsSendLogic.started("a", 3, 0)
        r = SmsSendLogic.applyPart(r, ok, 1); assertEquals(SmsSendState.SENDING, r.state)
        r = SmsSendLogic.applyPart(r, ok, 2); assertEquals(SmsSendState.SENDING, r.state)
        r = SmsSendLogic.applyPart(r, ok, 3); assertEquals(SmsSendState.SENT, r.state)
    }

    @Test fun allPartsFailedMapsTheAndroidReason() {
        var r = SmsSendLogic.started("a", 2, 0)
        r = SmsSendLogic.applyPart(r, fail, 1); r = SmsSendLogic.applyPart(r, fail, 2)
        assertEquals(SmsSendState.FAILED, r.state); assertEquals(SmsError.NO_SERVICE, r.error)
    }

    @Test fun mixedResultIsAPartialFailureNeverSent() {
        var r = SmsSendLogic.started("a", 2, 0)
        r = SmsSendLogic.applyPart(r, ok, 1); r = SmsSendLogic.applyPart(r, fail, 2)
        assertEquals(SmsSendState.FAILED, r.state); assertEquals(SmsError.PARTIAL_FAILURE, r.error)
    }

    @Test fun finalStateIsNeverChangedByLateResults() {
        val sent = SmsSendLogic.applyPart(SmsSendLogic.started("a", 1, 0), ok, 1)
        assertEquals(sent, SmsSendLogic.applyPart(sent, fail, 2))
    }

    @Test fun staleSendingBecomesResultUnknownNotSent() {
        val s = SmsSendLogic.started("a", 1, 0)
        assertEquals(SmsSendState.SENDING, SmsSendLogic.expireIfStale(s, 1_000, 120_000).state)
        val e = SmsSendLogic.expireIfStale(s, 200_000, 120_000)
        assertEquals(SmsSendState.FAILED, e.state); assertEquals(SmsError.RESULT_UNKNOWN, e.error)
    }

    @Test fun unknownAndroidCodeIsReportedAsUnknown() {
        val r = SmsSendLogic.applyPart(SmsSendLogic.started("a", 1, 0), 9999, 1)
        assertEquals(SmsError.UNKNOWN_RESULT_CODE, r.error); assertNull(null)
    }
}
