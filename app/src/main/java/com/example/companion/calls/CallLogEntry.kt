package com.example.companion.calls

/** What kind of call an entry is. Only [MISSED] is ever derived (see [CallHistoryTracker]); the rest is what the SIM Phone reported. */
enum class CallLogType { INCOMING, OUTGOING, MISSED }

/**
 * One finished call in the Main Phone's local Recent Calls list.
 *
 * Only facts the SIM Phone reported are set: [number] is null when Android did not expose it, [type] is null when the
 * direction was never reported, [durationSeconds] is null when the call was never connected or the end was not observed.
 * [startedAt] is wall-clock time on THIS phone when it first saw the call (approximate, like the call-screen timer).
 */
data class CallLogEntry(
    /** Stable per call (start time + number + direction), so inserting the same call twice is a no-op. */
    val id: String,
    val number: String?,
    val type: CallLogType?,
    val startedAt: Long,
    val durationSeconds: Long?,
    /** Disconnect cause as reported by the SIM Phone (e.g. "REMOTE", "REJECTED", "MISSED"); null when not reported. */
    val cause: String?
)
