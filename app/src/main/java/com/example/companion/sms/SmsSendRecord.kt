package com.example.companion.sms

import android.app.Activity
import android.telephony.SmsManager
import com.example.companion.protocol.SmsSendState

/**
 * SIM Phone: persisted knowledge about one outgoing SMS (never the number or the body). Pure Kotlin so the part
 * aggregation can be unit-tested on a JVM. A long message is split by Android into [total] parts; Android reports each
 * part separately, and the whole message is SENT only when every part reported success.
 */
data class SmsSendRecord(
    val id: String,
    val state: SmsSendState,
    val error: String?,
    val detail: String?,
    val total: Int,
    val reported: Int,
    val failed: Int,
    val firstFailureCode: Int?,
    val updatedAt: Long
)

object SmsSendLogic {
    fun started(id: String, totalParts: Int, now: Long) =
        SmsSendRecord(id, SmsSendState.SENDING, null, null, totalParts, 0, 0, null, now)

    fun failed(id: String, error: String, detail: String?, now: Long) =
        SmsSendRecord(id, SmsSendState.FAILED, error, detail, 0, 0, 0, null, now)

    /** Applies one per-part result from Android. Results for a message that is already final are ignored. */
    fun applyPart(r: SmsSendRecord, resultCode: Int, now: Long): SmsSendRecord {
        if (r.state != SmsSendState.SENDING) return r
        val ok = resultCode == Activity.RESULT_OK
        val reported = r.reported + 1
        val failed = r.failed + if (ok) 0 else 1
        val firstFailure = r.firstFailureCode ?: if (ok) null else resultCode
        val next = r.copy(reported = reported, failed = failed, firstFailureCode = firstFailure, updatedAt = now)
        if (reported < r.total) return next
        return when {
            failed == 0 -> next.copy(state = SmsSendState.SENT)
            failed == r.total -> {
                val code = firstFailure ?: -1
                val error = errorFor(code)
                next.copy(state = SmsSendState.FAILED, error = error,
                    detail = if (error == SmsError.UNKNOWN_RESULT_CODE) "Android result code $code" else null)
            }
            else -> next.copy(state = SmsSendState.FAILED, error = SmsError.PARTIAL_FAILURE, detail = "$failed of ${r.total} parts failed")
        }
    }

    /** SENDING for too long without any part result: report it honestly as unknown rather than guessing SENT or FAILED-and-safe-to-resend. */
    fun expireIfStale(r: SmsSendRecord, now: Long, maxAgeMs: Long): SmsSendRecord =
        if (r.state == SmsSendState.SENDING && now - r.updatedAt > maxAgeMs)
            r.copy(state = SmsSendState.FAILED, error = SmsError.RESULT_UNKNOWN, updatedAt = now)
        else r

    fun errorFor(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> SmsError.GENERIC_FAILURE
        SmsManager.RESULT_ERROR_RADIO_OFF -> SmsError.RADIO_OFF
        SmsManager.RESULT_ERROR_NULL_PDU -> SmsError.NULL_PDU
        SmsManager.RESULT_ERROR_NO_SERVICE -> SmsError.NO_SERVICE
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> SmsError.LIMIT_EXCEEDED
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> SmsError.FDN_CHECK_FAILURE
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> SmsError.SHORT_CODE_NOT_ALLOWED
        else -> SmsError.UNKNOWN_RESULT_CODE
    }
}
