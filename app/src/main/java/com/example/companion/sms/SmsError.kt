package com.example.companion.sms

/** Failure codes carried in SMS_SEND_RESULT.error. Codes are stable strings; [describe] turns them into UI text. */
object SmsError {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val NO_TELEPHONY = "NO_TELEPHONY"
    const val SMS_API_ERROR = "SMS_API_ERROR"
    const val GENERIC_FAILURE = "GENERIC_FAILURE"
    const val RADIO_OFF = "RADIO_OFF"
    const val NO_SERVICE = "NO_SERVICE"
    const val NULL_PDU = "NULL_PDU"
    const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    const val FDN_CHECK_FAILURE = "FDN_CHECK_FAILURE"
    const val SHORT_CODE_NOT_ALLOWED = "SHORT_CODE_NOT_ALLOWED"
    const val UNKNOWN_RESULT_CODE = "UNKNOWN_RESULT_CODE"
    const val PARTIAL_FAILURE = "PARTIAL_FAILURE"
    const val NOT_RECEIVED = "NOT_RECEIVED"
    const val RESULT_UNKNOWN = "RESULT_UNKNOWN"
    /** Stage 26: the SIM Phone could not save its send record, so it did not send (nothing left the phone). */
    const val STORAGE_ERROR = "STORAGE_ERROR"
    /** Stage 26: an unexpected problem inside the SIM Phone's SMS code (caught, not a crash). The technical detail stays in SmsDiagnostics. */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    fun describe(error: String?, detail: String?): String {
        val base = when (error) {
            INVALID_REQUEST -> "the SIM Phone rejected the request as invalid"
            PERMISSION_DENIED -> "the SIM Phone has no permission to send SMS (open Status > Set up SMS forwarding on the SIM Phone)"
            NO_TELEPHONY -> "the SIM Phone cannot send SMS"
            SMS_API_ERROR -> "Android refused the request"
            GENERIC_FAILURE -> "the SIM Phone could not send it (generic failure)"
            RADIO_OFF -> "the SIM Phone's radio is off (airplane mode?)"
            NO_SERVICE -> "the SIM Phone has no cellular service"
            NULL_PDU -> "the message could not be encoded"
            LIMIT_EXCEEDED -> "Android's SMS sending limit was reached"
            FDN_CHECK_FAILURE -> "blocked by fixed dialing numbers on the SIM"
            SHORT_CODE_NOT_ALLOWED -> "sending to this short code is not allowed"
            PARTIAL_FAILURE -> "only part of a long message was sent"
            NOT_RECEIVED -> "the SIM Phone never received it, nothing was sent"
            RESULT_UNKNOWN -> "the SIM Phone never reported a result; it may or may not have been sent"
            STORAGE_ERROR -> "the SIM Phone could not save its send record, so nothing was sent"
            INTERNAL_ERROR -> "the SIM Phone had an internal problem with this message"
            null -> "unknown reason"
            else -> "error $error"
        }
        return if (detail.isNullOrBlank()) base else "$base ($detail)"
    }
}
