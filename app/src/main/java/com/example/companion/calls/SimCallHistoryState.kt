package com.example.companion.calls

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection

/**
 * Stage 6: pure decisions for the SIM Phone's call-history list (no Android types, unit-testable on a plain JVM).
 *
 * [SimCallLogReader] already returns newest first, with one entry per call-log row. [normalize] is a defensive pass applied
 * before drawing: it removes any repeated entry id (so a row can never be drawn twice) and re-asserts the order
 * (newest first; same time -> higher row id first, which is how Android orders them). It never invents, edits or drops a call.
 */
object SimCallHistoryState {

    const val EMPTY_TEXT = "No calls in this phone's call log."
    const val LOADING_TEXT = "Loading call history…"
    const val PERMISSION_TEXT = "Call history needs permission to read this phone's call log. " +
        "It is only displayed here: the app does not place, answer or control calls with it."

    /** Stage 14: the same three texts in [language] (English default = the constants above). Missing Persian -> English. */
    fun emptyText(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CALLS_SIM_EMPTY, language)
    fun loadingText(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CALLS_SIM_LOADING, language)
    fun permissionText(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CALLS_SIM_PERMISSION, language)

    /**
     * Stage 14: the reader ([SimCallLogReader]) and the screen word a failure in English. This maps those exact three sentences to
     * [language] for display only; any other text is returned unchanged, so nothing is lost if the reader's wording ever changes.
     */
    fun failureText(reason: String, language: AppLanguage = AppLanguage.ENGLISH): String = when (reason) {
        Strings.text(StringKey.CALLS_SIM_READ_FAILED, AppLanguage.ENGLISH) -> Strings.text(StringKey.CALLS_SIM_READ_FAILED, language)
        Strings.text(StringKey.CALLS_SIM_NOT_AVAILABLE, AppLanguage.ENGLISH) -> Strings.text(StringKey.CALLS_SIM_NOT_AVAILABLE, language)
        Strings.text(StringKey.CALLS_SIM_BAD_FORMAT, AppLanguage.ENGLISH) -> Strings.text(StringKey.CALLS_SIM_BAD_FORMAT, language)
        Strings.text(StringKey.CALLS_SIM_ACCESS_BLOCKED, AppLanguage.ENGLISH) -> Strings.text(StringKey.CALLS_SIM_ACCESS_BLOCKED, language)   // Stage 28
        else -> reason
    }

    /** Stage 28: "N entries could not be read and were left out", or "" when nothing was left out. The count is shown in Persian digits in Persian. */
    fun partialText(skipped: Int, language: AppLanguage = AppLanguage.ENGLISH): String =
        if (skipped <= 0) "" else Strings.text(StringKey.CALLS_SIM_PARTIAL, language, TextDirection.number(skipped, language))

    /**
     * Stage 28: should the "Open app settings" button be offered after a missing permission? Only once the system dialog can no longer
     * help: the permission was asked for before (remembered across restarts) and Android will not show the rationale again (denied
     * for good, or revoked in settings). Before the first request, or after a single denial, the "Allow" button is the right one.
     */
    fun offerSettings(askedBefore: Boolean, showRationale: Boolean): Boolean = askedBefore && !showRationale

    fun normalize(entries: List<CallLogEntry>): List<CallLogEntry> =
        entries.distinctBy { it.id }
            .sortedWith(compareByDescending<CallLogEntry> { it.startedAt }.thenByDescending { rowId(it.id) })

    /** The call-log row id inside an entry id ("log|42" -> 42); non-matching ids sort last among equal times. */
    internal fun rowId(id: String): Long = id.substringAfter('|', "").toLongOrNull() ?: Long.MIN_VALUE
}
