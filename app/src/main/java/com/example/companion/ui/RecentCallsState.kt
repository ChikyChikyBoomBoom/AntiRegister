package com.example.companion.ui

import com.example.companion.calls.CallLogEntry
import com.example.companion.calls.CallLogType
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection

/** One row of the Recent Calls list, ready to draw. */
data class RecentCallRow(
    val id: String,
    /** The raw number, exactly as stored; null when Android did not expose it. Used only for redial. */
    val number: String?,
    /** The contact's name when known (Stage 19), otherwise the number, or "Unknown number" when Android did not expose it. */
    val title: String,
    /** "Incoming", "Outgoing", "Missed", or "Call" when the direction was never reported. */
    val typeLabel: String,
    val whenText: String,
    /** "mm:ss" when the call was connected and its end was seen; empty otherwise. */
    val durationText: String,
    val missed: Boolean,
    /** The second line: type, time and duration joined. */
    val detail: String,
    /** Stage 19: the number as a second line, only when [title] is a contact's name. Empty otherwise. */
    val numberLine: String = ""
)

/** What happens when a row is tapped. */
enum class RecentCallTap(val message: String) {
    DIAL(""),
    NO_NUMBER("This call has no number to dial."),
    CANNOT_DIAL("The SIM Phone can only dial digits with an optional leading +, so this number cannot be called from here."),
    LINK_DOWN("SIM Phone not connected. Calling is off until it reconnects."),
    CALL_IN_PROGRESS("A call is already in progress on the SIM Phone.")
}

/** Pure functions (no Android types) so the Recent Calls screen's decisions can be unit-tested on a plain JVM. */
object RecentCallsState {

    const val EMPTY_TEXT = "No recent calls yet."

    /**
     * [formatTime] turns epoch milliseconds into text (the screen passes the device's own date/time format).
     * [nameFor] is the contact lookup (Stage 19); the default knows nobody, so the number is shown, exactly as before.
     */
    fun row(
        e: CallLogEntry,
        formatTime: (Long) -> String,
        language: AppLanguage = AppLanguage.ENGLISH,     // Stage 14: before the lambda so `row(e, fmt) { name }` still compiles
        nameFor: (String) -> String? = { null }
    ): RecentCallRow {
        val name = e.number?.let { nameFor(it) }?.trim()?.takeIf { it.isNotEmpty() }
        val label = typeLabel(e, language)
        val duration = localizedClock(e.durationSeconds?.let { CallScreenState.formatDuration(it) }.orEmpty(), language)
        val whenText = localizedClock(formatTime(e.startedAt), language)
        return RecentCallRow(
            id = e.id,
            number = e.number,
            title = name ?: e.number ?: unknownNumber(language),
            typeLabel = label,
            whenText = whenText,
            durationText = duration,
            missed = e.type == CallLogType.MISSED,
            detail = listOf(label, whenText, duration).filter { it.isNotEmpty() }.joinToString(" · "),
            numberLine = com.example.companion.contacts.ContactsState.secondaryNumber(e.number, name)
        )
    }

    fun typeLabel(e: CallLogEntry, language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(
        when (e.type) {
            CallLogType.OUTGOING -> StringKey.CALLS_TYPE_OUTGOING
            CallLogType.MISSED -> StringKey.CALLS_TYPE_MISSED
            CallLogType.INCOMING -> when {
                e.durationSeconds != null -> StringKey.CALLS_TYPE_INCOMING
                e.cause == "REJECTED" || e.cause == "LOCAL" -> StringKey.CALLS_TYPE_DECLINED
                e.cause == "ANSWERED_ELSEWHERE" -> StringKey.CALLS_TYPE_ELSEWHERE
                else -> StringKey.CALLS_TYPE_INCOMING
            }
            null -> StringKey.CALLS_TYPE_UNKNOWN
        }, language
    )

    /** Stage 14: "Unknown number" in [language] (the constant [CallScreenState.UNKNOWN_NUMBER] is unchanged and still used by the call screens). */
    fun unknownNumber(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CALLS_UNKNOWN_NUMBER, language)

    /**
     * Stage 14: a clock time, date or duration as text. English: unchanged. Persian: Persian digits, kept left-to-right so "۰۵:۲۳" and
     * "۱۴۰۵/۰۳/۰۷"-style device formats are not re-ordered inside a right-to-left sentence. The device's own formatter still decides the layout.
     */
    fun localizedClock(text: String, language: AppLanguage): String =
        if (language == AppLanguage.PERSIAN && text.isNotEmpty()) TextDirection.isolateLtr(TextDirection.localizeDigits(text, language)) else text

    /** Stage 14: the toast text of a refused tap, in [language]. [RecentCallTap.message] (English) is unchanged. */
    fun tapMessage(tap: RecentCallTap, language: AppLanguage = AppLanguage.ENGLISH): String = when (tap) {
        RecentCallTap.DIAL -> ""
        RecentCallTap.NO_NUMBER -> Strings.text(StringKey.CALLS_TAP_NO_NUMBER, language)
        RecentCallTap.CANNOT_DIAL -> Strings.text(StringKey.CALLS_TAP_CANNOT_DIAL, language)
        RecentCallTap.LINK_DOWN -> Strings.text(StringKey.CALLS_TAP_LINK_DOWN, language)
        RecentCallTap.CALL_IN_PROGRESS -> Strings.text(StringKey.CALLS_TAP_IN_PROGRESS, language)
    }

    /**
     * Decides what a tap does. The number is never edited: if the SIM Phone would refuse it, the entry says so instead
     * of sending something it knows will be rejected (same rule as the dial pad).
     */
    fun onTap(number: String?, linkUp: Boolean, callLive: Boolean): RecentCallTap = when {
        number.isNullOrEmpty() -> RecentCallTap.NO_NUMBER
        !DialPadState.simAccepts(number) -> RecentCallTap.CANNOT_DIAL
        !linkUp -> RecentCallTap.LINK_DOWN
        callLive -> RecentCallTap.CALL_IN_PROGRESS
        else -> RecentCallTap.DIAL
    }

    fun linkLine(linkUp: Boolean, language: AppLanguage = AppLanguage.ENGLISH) =
        Strings.text(if (linkUp) StringKey.CALLS_LINK_UP else StringKey.CALLS_LINK_DOWN, language)
}
