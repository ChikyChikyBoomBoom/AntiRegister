package com.example.companion.calls

/**
 * Stage 5: turns one raw row of Android's call log into the EXISTING [CallLogEntry], so the existing row drawing
 * (RecentCallsState.row) shows it unchanged. Pure Kotlin, no Android types: unit-testable on a plain JVM.
 *
 * The integers are the values of android.provider.CallLog.Calls.*_TYPE (stable platform constants, copied here only so this
 * file needs no Android class).
 */
object SimCallLogMapper {
    const val INCOMING_TYPE = 1
    const val OUTGOING_TYPE = 2
    const val MISSED_TYPE = 3
    const val VOICEMAIL_TYPE = 4
    const val REJECTED_TYPE = 5
    const val BLOCKED_TYPE = 6
    const val ANSWERED_EXTERNALLY_TYPE = 7

    /**
     * [number] blank = withheld / unknown -> null. [durationSec] <= 0 or null = never connected -> null (same meaning as the
     * Main Phone's list: "empty unless the call was connected"). Unknown [type] values keep type = null ("Call").
     * Rejected / blocked / answered-elsewhere are INCOMING with the same cause words the old tracker used, so the existing
     * labels ("Incoming (declined)", "Incoming (answered elsewhere)") apply.
     */
    fun toEntry(rowId: Long, number: String?, type: Int, dateMs: Long, durationSec: Long?): CallLogEntry {
        val (t, cause) = when (type) {
            INCOMING_TYPE -> CallLogType.INCOMING to null
            OUTGOING_TYPE -> CallLogType.OUTGOING to null
            MISSED_TYPE -> CallLogType.MISSED to "MISSED"
            VOICEMAIL_TYPE -> CallLogType.INCOMING to "VOICEMAIL"
            REJECTED_TYPE -> CallLogType.INCOMING to "REJECTED"
            BLOCKED_TYPE -> CallLogType.INCOMING to "BLOCKED"
            ANSWERED_EXTERNALLY_TYPE -> CallLogType.INCOMING to "ANSWERED_ELSEWHERE"
            else -> null to null
        }
        val connected = type == INCOMING_TYPE || type == OUTGOING_TYPE
        return CallLogEntry(
            id = "log|$rowId",
            number = cleanNumber(number),
            type = t,
            startedAt = dateMs,
            durationSeconds = if (connected) durationSec?.takeIf { it > 0 } else null,
            cause = cause
        )
    }

    /** Longest number drawn. A longer value is not a phone number (a corrupt row); it is cut for display, never rejected. */
    const val MAX_NUMBER_LENGTH = 64

    /**
     * Stage 28: a call-log number made safe to draw. Control characters are removed and the ends trimmed; blank means withheld / unknown
     * -> null. Some phones store a withheld caller as "-1" / "-2" / "-3" instead of leaving the column empty; those mean "no number"
     * too, so the row says "Unknown number" instead of showing "-1". Otherwise the digits and letters are left exactly as stored.
     */
    fun cleanNumber(raw: String?): String? {
        if (raw == null) return null
        val t = raw.filterNot { Character.isISOControl(it) }.trim().take(MAX_NUMBER_LENGTH).trim()
        return t.takeIf { it.isNotEmpty() && it !in WITHHELD_MARKERS }
    }

    private val WITHHELD_MARKERS = setOf("-1", "-2", "-3")

    /**
     * Stage 28: [toEntry] for a row read from a possibly damaged log. null when the row cannot be shown honestly: no usable row id
     * (null or negative), or no date (null, zero or negative: it could not be ordered and would be drawn as 1970). The reader counts
     * such rows and tells the user how many were left out; it never invents a date or an id.
     */
    fun tryEntry(rowId: Long?, number: String?, type: Int?, dateMs: Long?, durationSec: Long?): CallLogEntry? {
        if (rowId == null || rowId < 0) return null
        if (dateMs == null || dateMs <= 0L) return null
        return toEntry(rowId, number, type ?: 0, dateMs, durationSec)
    }
}
