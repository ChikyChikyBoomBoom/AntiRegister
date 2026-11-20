package com.example.companion.ui

import com.example.companion.bluetooth.LinkIssue
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection
import com.example.companion.protocol.SmsMessages
import com.example.companion.sms.SmsDirection
import com.example.companion.sms.SmsEntry
import com.example.companion.sms.SmsErrorText
import com.example.companion.sms.SmsStatus

/** How a message bubble is drawn. SENDING covers both "not yet acknowledged" and "SIM Phone is sending". */
enum class BubbleKind { RECEIVED, SENDING, SENT, FAILED }

/** One message of the conversation, ready to draw. */
data class SmsMessageRow(
    val id: String,
    val body: String,
    val outgoing: Boolean,
    val kind: BubbleKind,
    val timeText: String,
    /** Empty for received messages. For outgoing: "Sending…", "Sent" or "Failed: <reason reported by the SIM Phone>". */
    val statusText: String,
    /** True for a failed message: tapping it puts its text back in the box (it is sent again as a NEW message). */
    val reusable: Boolean,
    /** Screen reader text for the whole bubble. */
    val description: String
)

/** What the composer (recipient / text box / Send) may do right now, and what to tell the user. */
data class SmsComposerState(
    val sendEnabled: Boolean,
    /** Shown next to the composer when the SIM Phone cannot be used. Empty when it can. */
    val unavailableText: String,
    /** Recipient problem for a NEW conversation (empty while nothing is typed yet, or for an existing conversation). */
    val recipientError: String,
    /** Short note about the text box: character count near the limit, or "too long". Empty otherwise. */
    val hintText: String
)

/** Pure functions (no Android types) so the conversation screen's decisions can be unit-tested on a plain JVM. */
object SmsThreadState {

    const val LINK_UP_TEXT = "SIM Phone: connected"
    const val LINK_DOWN_TEXT = "SIM Phone unavailable (Bluetooth not connected)."
    const val SEND_BLOCKED_TEXT = "Sending is disabled until the SIM Phone is connected. Nothing is sent or queued while it is unavailable."
    const val NOT_MAIN_PHONE_TEXT = "Messages are shown on the Main Phone."
    const val RECIPIENT_ERROR = "Enter a valid phone number (digits, optional leading +)"
    const val COUNTER_FROM = 1400

    /** The text that is actually sent: leading and trailing whitespace (an accidental trailing newline) is removed. */
    fun outgoingBody(raw: String): String = raw.trim()

    fun linkLine(isMain: Boolean, linkUp: Boolean, language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(
        when {
            !isMain -> StringKey.SMS_NOT_MAIN_SHORT
            linkUp -> StringKey.SMS_LINK_UP
            else -> StringKey.SMS_LINK_DOWN
        }, language
    )

    /**
     * [existingAddress] is the conversation's address when one was opened (or already started); null while the user is
     * still entering a recipient for a new conversation. Send is enabled only if every condition holds, so an empty
     * message, a bad number, an over-long text or a missing SIM Phone link can never reach the controller from the button.
     */
    fun composer(
        isMain: Boolean, linkUp: Boolean, existingAddress: String?, recipientRaw: String, bodyRaw: String,
        /** Stage 13: language of the texts. English by default (the original wording). */
        language: AppLanguage = AppLanguage.ENGLISH,
        /** Stage 27: the plain Bluetooth cause of a missing link (off, no permission, no Bluetooth, reconnecting), or null. Only changes the notice's words. */
        linkIssue: LinkIssue? = null
    ): SmsComposerState {
        val recipient = recipientRaw.trim()
        val recipientOk = existingAddress != null || SmsMessages.isValidRecipient(recipient)
        val recipientError = if (existingAddress == null && recipient.isNotEmpty() && !recipientOk) Strings.text(StringKey.SMS_RECIPIENT_INVALID, language) else ""
        val len = outgoingBody(bodyRaw).length
        val tooLong = len > SmsMessages.MAX_SEND_BODY
        val lenText = TextDirection.number(len, language)
        val maxText = TextDirection.number(SmsMessages.MAX_SEND_BODY, language)
        val hint = when {
            tooLong -> Strings.text(StringKey.SMS_TOO_LONG, language, lenText, maxText)
            len >= COUNTER_FROM -> Strings.text(StringKey.SMS_COUNTER, language, lenText, maxText)
            else -> ""
        }
        val unavailable = when {
            !isMain -> Strings.text(StringKey.SMS_NOT_MAIN_SHORT, language)
            !linkUp -> Strings.text(sendBlockedKey(linkIssue), language)
            else -> ""
        }
        return SmsComposerState(
            sendEnabled = isMain && linkUp && recipientOk && len > 0 && !tooLong,
            unavailableText = unavailable,
            recipientError = recipientError,
            hintText = hint
        )
    }

    /** Stage 27: the notice shown while Send is disabled for lack of a link. [SmsComposerState.sendEnabled] does not depend on it. */
    fun sendBlockedKey(issue: LinkIssue?): StringKey = when (issue) {
        LinkIssue.BLUETOOTH_OFF -> StringKey.SMS_SEND_BLOCKED_BT_OFF
        LinkIssue.PERMISSION_MISSING -> StringKey.SMS_SEND_BLOCKED_BT_PERMISSION
        LinkIssue.UNSUPPORTED -> StringKey.SMS_SEND_BLOCKED_BT_UNSUPPORTED
        LinkIssue.RECONNECTING -> StringKey.SMS_SEND_BLOCKED_RECONNECTING
        null -> StringKey.SMS_SEND_BLOCKED
    }

    /**
     * Messages oldest first. The store already orders by (time, insertion); the sort here is stable, so it keeps that order
     * for equal times and only protects the screen from an input that is not sorted.
     */
    fun rows(
        entries: List<SmsEntry>, linkUp: Boolean, language: AppLanguage = AppLanguage.ENGLISH, formatTime: (Long) -> String
    ): List<SmsMessageRow> =
        entries.sortedBy { it.timestamp }.map { row(it, linkUp, language, formatTime) }

    /** [language] comes before [formatTime] on purpose: the lambda stays the last parameter, so `row(e, up) { ... }` keeps compiling. */
    fun row(e: SmsEntry, linkUp: Boolean, language: AppLanguage = AppLanguage.ENGLISH, formatTime: (Long) -> String): SmsMessageRow {
        val outgoing = e.direction == SmsDirection.OUTGOING
        val kind = when {
            !outgoing -> BubbleKind.RECEIVED
            e.status == SmsStatus.SENT -> BubbleKind.SENT
            e.status == SmsStatus.FAILED -> BubbleKind.FAILED
            else -> BubbleKind.SENDING                       // PENDING and SENDING (and a stray RECEIVED on an outgoing row)
        }
        val time = formatTime(e.timestamp)
        val status = if (outgoing) statusText(e, linkUp, language) else ""
        val who = Strings.text(if (outgoing) StringKey.SMS_WHO_YOU else StringKey.SMS_WHO_RECEIVED, language)
        val description = buildString {
            append(Strings.text(StringKey.SMS_BUBBLE_DESC, language, who, TextDirection.isolate(e.body, language), time))
            if (status.isNotEmpty()) append(' ').append(status).append('.')
        }
        return SmsMessageRow(
            id = e.id, body = e.body, outgoing = outgoing, kind = kind, timeText = time, statusText = status,
            reusable = kind == BubbleKind.FAILED, description = description
        )
    }

    /**
     * Status of an outgoing message, worded from what the SIM Phone actually reported:
     * PENDING = handed to Bluetooth, no answer yet; SENDING = the SIM Phone answered and is sending; SENT = the SIM Phone reported
     * that the cellular network side accepted it (not a delivery report); FAILED = the SIM Phone's own reason.
     */
    fun statusText(e: SmsEntry, linkUp: Boolean, language: AppLanguage = AppLanguage.ENGLISH): String = when (e.status) {
        SmsStatus.PENDING ->
            Strings.text(if (linkUp) StringKey.SMS_STATUS_WAITING else StringKey.SMS_STATUS_PENDING_OFFLINE, language)
        SmsStatus.SENDING ->
            Strings.text(if (linkUp) StringKey.SMS_STATUS_SENDING else StringKey.SMS_STATUS_SENDING_OFFLINE, language)
        SmsStatus.SENT -> Strings.text(StringKey.SMS_STATUS_SENT, language)
        // SmsErrorText.describe equals SmsError.describe in English; the failure code itself is untouched.
        SmsStatus.FAILED -> Strings.text(StringKey.SMS_STATUS_FAILED, language, SmsErrorText.describe(e.error, e.detail, language))
        SmsStatus.RECEIVED -> ""
    }
}
