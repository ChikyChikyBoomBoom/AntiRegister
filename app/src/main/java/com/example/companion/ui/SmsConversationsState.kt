package com.example.companion.ui

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection
import com.example.companion.sms.SmsConversation
import com.example.companion.sms.SmsDirection
import java.util.Calendar
import java.util.TimeZone

/** One row of the Messages list, ready to draw. */
data class SmsConversationRow(
    /** Conversation key (see [com.example.companion.sms.threadKey]); used to delete. */
    val key: String,
    /** The address exactly as the latest message carried it; handed to the message screen. */
    val address: String,
    /** Contact name when one is known, otherwise the phone number / sender. */
    val title: String,
    /** Latest message, one line, prefixed "You: " when we sent it. */
    val preview: String,
    /** Time of the latest message: clock time if today, short date otherwise. */
    val timeText: String,
    val unread: Int,
    /** Shown on the badge: the count, or "99+". Empty when nothing is unread. */
    val badgeText: String,
    /** Non-empty when messages in this conversation failed to send. */
    val failedText: String,
    /** Screen reader text for the whole row. */
    val description: String
) {
    val hasUnread get() = unread > 0
}

/** Pure functions (no Android types) so the Messages list's decisions can be unit-tested on a plain JVM. */
object SmsConversationsState {

    const val EMPTY_TEXT = "No messages yet."
    const val MAX_BADGE = 99

    /**
     * Newest message first. The sort is stable, so conversations with the same time keep the order the store gave them.
     * [nameFor] is the seam for Contacts (a later stage); until a contact source exists it returns null and the number is shown.
     */
    fun rows(
        conversations: List<SmsConversation>,
        now: Long,
        nameFor: (String) -> String?,
        formatClock: (Long) -> String,
        formatDate: (Long) -> String,
        timeZone: TimeZone = TimeZone.getDefault(),
        /** Stage 13: language of the texts the rows carry. English by default (the original wording). */
        language: AppLanguage = AppLanguage.ENGLISH
    ): List<SmsConversationRow> =
        conversations.sortedByDescending { it.lastTimestamp }.map { row(it, now, nameFor, formatClock, formatDate, timeZone, language) }

    fun row(
        c: SmsConversation,
        now: Long,
        nameFor: (String) -> String?,
        formatClock: (Long) -> String,
        formatDate: (Long) -> String,
        timeZone: TimeZone = TimeZone.getDefault(),
        language: AppLanguage = AppLanguage.ENGLISH
    ): SmsConversationRow {
        fun t(key: StringKey, vararg args: Any?) = Strings.text(key, language, *args)
        val title = title(c.address, nameFor)
        val preview = preview(c.lastBody, c.lastDirection, language)
        val time = timeText(c.lastTimestamp, now, formatClock, formatDate, timeZone)
        val unread = c.unread.coerceAtLeast(0)
        val failedText = if (c.failed > 0) t(StringKey.SMS_ROW_FAILED, TextDirection.number(c.failed, language)) else ""
        val description = buildString {
            if (unread > 0) append(
                if (unread == 1) t(StringKey.SMS_DESC_UNREAD_ONE) else t(StringKey.SMS_DESC_UNREAD_MANY, TextDirection.number(unread, language))
            )
            append(t(StringKey.SMS_DESC_CONVERSATION, TextDirection.isolate(title, language)))
            append(t(StringKey.SMS_DESC_LATEST, TextDirection.isolate(preview, language), time))
            if (failedText.isNotEmpty()) append(' ').append(
                if (c.failed == 1) t(StringKey.SMS_DESC_FAILED_ONE) else t(StringKey.SMS_DESC_FAILED_MANY, TextDirection.number(c.failed, language))
            )
        }
        return SmsConversationRow(
            key = c.key, address = c.address, title = title, preview = preview, timeText = time,
            unread = unread, badgeText = badge(unread, language), failedText = failedText, description = description
        )
    }

    /** A known contact name, otherwise the number exactly as received. A blank name counts as unknown. */
    fun title(address: String, nameFor: (String) -> String?): String =
        nameFor(address)?.trim()?.takeIf { it.isNotEmpty() } ?: address

    /** One line: line breaks and runs of spaces collapse to a single space; our own messages are marked "You: ". */
    fun preview(body: String, direction: SmsDirection, language: AppLanguage = AppLanguage.ENGLISH): String {
        val oneLine = body.trim().replace(Regex("\\s+"), " ")
        return if (direction == SmsDirection.OUTGOING) Strings.text(StringKey.SMS_ROW_YOU, language, TextDirection.isolate(oneLine, language)) else oneLine
    }

    fun badge(unread: Int, language: AppLanguage = AppLanguage.ENGLISH): String = when {
        unread <= 0 -> ""
        unread > MAX_BADGE -> TextDirection.localizeDigits("$MAX_BADGE+", language)
        else -> TextDirection.number(unread, language)
    }

    /** Clock time for a message from today, short date for anything else (compared in [timeZone], the device's by default). */
    fun timeText(
        ts: Long,
        now: Long,
        formatClock: (Long) -> String,
        formatDate: (Long) -> String,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String = if (sameDay(ts, now, timeZone)) formatClock(ts) else formatDate(ts)

    fun sameDay(a: Long, b: Long, timeZone: TimeZone): Boolean {
        val ca = Calendar.getInstance(timeZone).apply { timeInMillis = a }
        val cb = Calendar.getInstance(timeZone).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** Stage 13: the Messages-list wording that [HistoryState] holds for both history tabs, in [language] (English output is identical). */
    fun conversationCount(n: Int, language: AppLanguage = AppLanguage.ENGLISH): String =
        if (n == 1) Strings.text(StringKey.SMS_COUNT_CONVERSATION_ONE, language)
        else Strings.text(StringKey.SMS_COUNT_CONVERSATION_MANY, language, TextDirection.number(n, language))

    fun messageCount(n: Int, language: AppLanguage = AppLanguage.ENGLISH): String =
        if (n == 1) Strings.text(StringKey.SMS_COUNT_MESSAGE_ONE, language)
        else Strings.text(StringKey.SMS_COUNT_MESSAGE_MANY, language, TextDirection.number(n, language))

    fun clearAllBody(conversations: Int, messages: Int, language: AppLanguage = AppLanguage.ENGLISH): String =
        Strings.text(StringKey.SMS_CLEAR_ALL_BODY, language, conversationCount(conversations, language), messageCount(messages, language))

    /** Long-press menu entry. An action the Messages menu does not use (SMS) keeps its English label. */
    fun actionLabel(action: HistoryAction, language: AppLanguage = AppLanguage.ENGLISH): String = when (action) {
        HistoryAction.OPEN -> Strings.text(StringKey.SMS_ACTION_OPEN, language)
        HistoryAction.CALL -> Strings.text(StringKey.SMS_ACTION_CALL, language)
        HistoryAction.DELETE -> Strings.text(StringKey.SMS_ACTION_DELETE, language)
        HistoryAction.SMS -> action.label
    }

    fun tabLabel(tab: HistoryTab, language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(
        when (tab) {
            HistoryTab.CALLS -> StringKey.SMS_TAB_CALLS
            HistoryTab.MESSAGES -> StringKey.SMS_TAB_MESSAGES
        }, language
    )

    fun linkLine(linkUp: Boolean, language: AppLanguage = AppLanguage.ENGLISH) =
        Strings.text(if (linkUp) StringKey.SMS_LINK_UP else StringKey.SMS_LINK_DOWN_LIST, language)
}
