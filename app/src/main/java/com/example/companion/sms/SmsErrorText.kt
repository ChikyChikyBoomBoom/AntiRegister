package com.example.companion.sms

import com.example.companion.bluetooth.LinkIssue
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection
import com.example.companion.protocol.SmsMessages

/**
 * Stage 13: SMS failure and refusal wording in the selected language. Pure Kotlin (no Android types).
 *
 * It only changes how a text is SHOWN. [SmsError] (codes and its English [SmsError.describe]) and [ClientSmsController.send]
 * (which returns an English reason) are untouched: no SMS sending, receiving or protocol logic was edited. The screens pass what
 * those return through here; a text this class does not know is returned unchanged (English), so nothing is ever lost.
 */
object SmsErrorText {

    /**
     * Stage 26: the English reason [ClientSmsController.send] returns when something unexpected went wrong BEFORE the message was stored
     * or handed over (so "Nothing was sent" is true). Kept here, in the pure class, so the wording and its mapping sit together.
     */
    const val REFUSE_INTERNAL = "Something went wrong on this phone. Nothing was sent."

    /**
     * Stage 27: the English reasons [ClientSmsController.send] returns when the Bluetooth link is not up, by cause. [REFUSE_LINK_DOWN] is the
     * original sentence and still the answer when there is no more specific cause. All of them say "Nothing was sent", which is true:
     * the link is checked before anything is stored or handed over.
     */
    const val REFUSE_LINK_DOWN = "SIM Phone unavailable (Bluetooth is not connected). Nothing was sent."
    const val REFUSE_BT_OFF = "Bluetooth is turned off on this phone. Turn it on and try again. Nothing was sent."
    const val REFUSE_BT_PERMISSION = "This app has no Bluetooth permission. Allow it in system settings. Nothing was sent."
    const val REFUSE_BT_UNSUPPORTED = "This phone does not support Bluetooth, so it cannot reach the SIM Phone. Nothing was sent."
    const val REFUSE_RECONNECTING = "Reconnecting to the SIM Phone. Try again in a moment. Nothing was sent."

    /** The English refusal for a send attempted while the link is down. [issue] comes from `ConnectionManager.linkIssue`; null = no known cause. */
    fun linkDownReason(issue: LinkIssue?): String = when (issue) {
        LinkIssue.UNSUPPORTED -> REFUSE_BT_UNSUPPORTED
        LinkIssue.PERMISSION_MISSING -> REFUSE_BT_PERMISSION
        LinkIssue.BLUETOOTH_OFF -> REFUSE_BT_OFF
        LinkIssue.RECONNECTING -> REFUSE_RECONNECTING
        null -> REFUSE_LINK_DOWN
    }

    /** Same decision tree as [SmsError.describe], but worded in [language]. English output is identical to [SmsError.describe]. */
    fun describe(error: String?, detail: String?, language: AppLanguage): String {
        fun t(key: StringKey, vararg args: Any?) = Strings.text(key, language, *args)
        val base = when (error) {
            SmsError.INVALID_REQUEST -> t(StringKey.SMS_FAIL_INVALID_REQUEST)
            SmsError.PERMISSION_DENIED -> t(StringKey.SMS_FAIL_PERMISSION_DENIED)
            SmsError.NO_TELEPHONY -> t(StringKey.SMS_FAIL_NO_TELEPHONY)
            SmsError.SMS_API_ERROR -> t(StringKey.SMS_FAIL_SMS_API_ERROR)
            SmsError.GENERIC_FAILURE -> t(StringKey.SMS_FAIL_GENERIC)
            SmsError.RADIO_OFF -> t(StringKey.SMS_FAIL_RADIO_OFF)
            SmsError.NO_SERVICE -> t(StringKey.SMS_FAIL_NO_SERVICE)
            SmsError.NULL_PDU -> t(StringKey.SMS_FAIL_NULL_PDU)
            SmsError.LIMIT_EXCEEDED -> t(StringKey.SMS_FAIL_LIMIT_EXCEEDED)
            SmsError.FDN_CHECK_FAILURE -> t(StringKey.SMS_FAIL_FDN)
            SmsError.SHORT_CODE_NOT_ALLOWED -> t(StringKey.SMS_FAIL_SHORT_CODE)
            SmsError.PARTIAL_FAILURE -> t(StringKey.SMS_FAIL_PARTIAL)
            SmsError.NOT_RECEIVED -> t(StringKey.SMS_FAIL_NOT_RECEIVED)
            SmsError.RESULT_UNKNOWN -> t(StringKey.SMS_FAIL_RESULT_UNKNOWN)
            SmsError.STORAGE_ERROR -> t(StringKey.SMS_FAIL_STORAGE)
            SmsError.INTERNAL_ERROR -> t(StringKey.SMS_FAIL_INTERNAL)
            null -> t(StringKey.SMS_FAIL_UNKNOWN)
            else -> t(StringKey.SMS_FAIL_OTHER, error)
        }
        return if (detail.isNullOrBlank()) base else "$base (${detail(detail, language)})"
    }

    private val PARTS_FAILED = Regex("^(\\d+) of (\\d+) parts failed$")
    private val RESULT_CODE = Regex("^Android result code (\\S+)$")

    /**
     * The detail the SIM Phone attached to a failure. The phrases the app itself writes are translated; anything else (for example
     * an exception class name) is shown as it arrived.
     */
    fun detail(detail: String, language: AppLanguage): String {
        if (language == AppLanguage.ENGLISH) return detail
        val d = detail.trim()
        PARTS_FAILED.matchEntire(d)?.let {
            return Strings.text(StringKey.SMS_DETAIL_PARTS_FAILED, language,
                TextDirection.localizeDigits(it.groupValues[1], language), TextDirection.localizeDigits(it.groupValues[2], language))
        }
        RESULT_CODE.matchEntire(d)?.let {
            return Strings.text(StringKey.SMS_DETAIL_RESULT_CODE, language, TextDirection.localizeDigits(it.groupValues[1], language))
        }
        return when (d) {
            "empty message" -> Strings.text(StringKey.SMS_DETAIL_EMPTY, language)
            "invalid recipient, text or protocol version" -> Strings.text(StringKey.SMS_DETAIL_INVALID, language)
            "Android rejected the recipient or text" -> Strings.text(StringKey.SMS_DETAIL_ANDROID_REJECTED, language)
            else -> detail
        }
    }

    /**
     * [ClientSmsController.send] returns the English reason a send was refused (null = accepted). Maps the known reasons to
     * [language]; an unknown reason is returned unchanged. English: always returned unchanged.
     */
    fun refusal(reason: String, language: AppLanguage): String {
        if (language == AppLanguage.ENGLISH) return reason
        val tooLong = Regex("^The message is too long \\(max (\\d+) characters\\)$").matchEntire(reason)
        if (tooLong != null) {
            return Strings.text(StringKey.SMS_REFUSE_TOO_LONG, language, TextDirection.localizeDigits(tooLong.groupValues[1], language))
        }
        val key = when (reason) {
            "Only the Main Phone can send messages" -> StringKey.SMS_REFUSE_NOT_MAIN
            "Enter a valid phone number (digits, optional leading +)" -> StringKey.SMS_RECIPIENT_INVALID
            "The message is empty" -> StringKey.SMS_REFUSE_EMPTY
            "SIM Phone unavailable (Bluetooth is not connected). Nothing was sent." -> StringKey.SMS_REFUSE_LINK_DOWN
            "Could not save the message on this phone. Nothing was sent." -> StringKey.SMS_REFUSE_NOT_SAVED
            REFUSE_INTERNAL -> StringKey.SMS_REFUSE_INTERNAL
            REFUSE_BT_OFF -> StringKey.SMS_REFUSE_BT_OFF                  // Stage 27
            REFUSE_BT_PERMISSION -> StringKey.SMS_REFUSE_BT_PERMISSION
            REFUSE_BT_UNSUPPORTED -> StringKey.SMS_REFUSE_BT_UNSUPPORTED
            REFUSE_RECONNECTING -> StringKey.SMS_REFUSE_RECONNECTING
            else -> null
        }
        return if (key == null) reason else Strings.text(key, language)
    }

    /** The English refusal for an over-long text, built from the same limit the controller uses (for tests and callers that need it). */
    fun tooLongEnglish(): String = "The message is too long (max ${SmsMessages.MAX_SEND_BODY} characters)"
}
