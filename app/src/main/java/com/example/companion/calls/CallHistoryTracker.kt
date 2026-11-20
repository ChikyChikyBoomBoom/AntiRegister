package com.example.companion.calls

import com.example.companion.protocol.CallDirection
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState

/**
 * Turns the stream of call snapshots the Main Phone already receives ([ClientCallController.snapshot]) into finished
 * [CallLogEntry]s. It is NOT a second call-management system: it sends nothing, controls nothing and never looks at
 * Bluetooth. It only watches the snapshots it is fed and remembers, per call, what it saw.
 *
 * Duplicate protection:
 *  - A call is remembered once while it is live. Repeated snapshots of the same call (every command, every Bluetooth
 *    reconnect re-push) only update that one record.
 *  - A call is turned into an entry exactly once, when it is removed from the live list.
 *  - A DISCONNECTED snapshot with no live record behind it (e.g. a re-push after reconnect) is ignored.
 *  - [null] (state unknown, link down) changes nothing: the call may still be running on the SIM Phone.
 *  - [CallLogEntry.id] is deterministic, so the store also ignores a second insert of the same call.
 *  - Stage 27: when the SIM Phone reports a call-session id, the call is matched by that id (never by number), and the entry id
 *    is derived from it, so the same call yields the same entry id even if this tracker's memory was lost and rebuilt mid-call.
 *
 * Known limits (see STAGE12_NOTES.md): several calls at once are told apart only by number + direction, and the
 * SIM Phone only reports one "primary" call at a time. Memory only: a call that ends while the app process is dead
 * is not recorded. Pure Kotlin, no Android types, so it can be unit-tested on a plain JVM.
 */
class CallHistoryTracker(private val clock: Clock = Clock.SYSTEM) {

    interface Clock {
        fun wallMs(): Long
        fun monotonicMs(): Long
        companion object {
            val SYSTEM = object : Clock {
                override fun wallMs() = System.currentTimeMillis()
                override fun monotonicMs() = System.nanoTime() / 1_000_000
            }
        }
    }

    private class Tracked(val startedAt: Long, var number: String?, var direction: CallDirection?, var callId: String?) {
        /** Monotonic time when this phone first saw the call connected (or held); null = never connected. */
        var activeSince: Long? = null
        /** Monotonic time when this phone first saw the hang-up in progress. */
        var endingAt: Long? = null
    }

    private val tracked = ArrayList<Tracked>()

    /** Number of calls currently remembered as live. Exposed for tests. */
    val liveCount: Int get() = tracked.size

    /** Feed every snapshot (null = unknown). Returns the calls that finished because of this snapshot; usually empty. */
    fun onSnapshot(s: CallSnapshot?): List<CallLogEntry> {
        if (s == null) return emptyList()
        return when (s.state) {
            CallState.IDLE -> {
                // No call at all: whatever is still remembered has ended without us seeing its DISCONNECTED.
                val out = tracked.map { build(it, null, endObserved = false) }
                tracked.clear()
                out
            }
            CallState.DISCONNECTED -> {
                val t = find(s) ?: return emptyList()
                tracked.remove(t)
                listOf(build(t, s, endObserved = true))
            }
            CallState.DISCONNECTING -> {
                find(s)?.let { if (it.endingAt == null) it.endingAt = clock.monotonicMs() }
                emptyList()
            }
            CallState.RINGING, CallState.CONNECTING, CallState.SELECT_ACCOUNT,
            CallState.DIALING, CallState.ACTIVE, CallState.HOLDING -> {
                val t = find(s) ?: Tracked(clock.wallMs(), s.number, s.direction, s.callId).also { tracked.add(it) }
                if (t.number == null) t.number = s.number
                if (t.direction == null) t.direction = s.direction
                if (t.callId == null) t.callId = s.callId
                if ((s.state == CallState.ACTIVE || s.state == CallState.HOLDING) && t.activeSince == null)
                    t.activeSince = clock.monotonicMs()
                emptyList()
            }
        }
    }

    // ------------------------------------------------------------------ matching

    private fun directionCompatible(a: CallDirection?, b: CallDirection?) = a == null || b == null || a == b

    /** Stage 27: two different session ids are two different calls; a missing id (older SIM Phone build) says nothing. */
    private fun idCompatible(a: String?, b: String?) = a == null || b == null || a == b

    /** Which remembered call a snapshot belongs to; null = none (a new call). */
    private fun find(s: CallSnapshot): Tracked? {
        // 0. same session id: the one reliable match
        if (s.callId != null) tracked.firstOrNull { it.callId == s.callId }?.let { return it }
        val compatible = tracked.filter { directionCompatible(it.direction, s.direction) && idCompatible(it.callId, s.callId) }
        // 1. same number
        if (s.number != null) compatible.firstOrNull { it.number == s.number }?.let { return it }
        // 2. both numbers withheld
        if (s.number == null) compatible.firstOrNull { it.number == null }?.let { return it }
        // 3. the number was not known yet when the call was first seen and arrived with a later update
        if (s.number != null && tracked.size == 1) {
            val only = tracked[0]
            if (only.number == null && directionCompatible(only.direction, s.direction) && idCompatible(only.callId, s.callId)) return only
        }
        return null
    }

    // ------------------------------------------------------------------ building entries

    private fun build(t: Tracked, last: CallSnapshot?, endObserved: Boolean): CallLogEntry {
        val number = t.number ?: last?.number
        val direction = t.direction ?: last?.direction
        val cause = last?.disconnectCause
        val callId = t.callId ?: last?.callId
        val activeSince = t.activeSince
        val duration = if (endObserved && activeSince != null) {
            val end = t.endingAt ?: clock.monotonicMs()
            ((end - activeSince).coerceAtLeast(0) + 500) / 1000     // nearest second
        } else null
        return CallLogEntry(
            // Stage 27: with a session id the entry id is that id (one call = one id); without one, the Stage 12 form as before.
            id = if (callId != null) "c|$callId" else "${t.startedAt}|${number.orEmpty()}|${direction?.name.orEmpty()}",
            number = number,
            type = typeOf(direction, wasConnected = activeSince != null, cause = cause),
            startedAt = t.startedAt,
            durationSeconds = duration,
            cause = cause
        )
    }

    companion object {
        /** An unanswered incoming call that was NOT deliberately declined (or taken by another device) is "missed". */
        private val NOT_MISSED = setOf("REJECTED", "LOCAL", "ANSWERED_ELSEWHERE")

        fun typeOf(direction: CallDirection?, wasConnected: Boolean, cause: String?): CallLogType? = when (direction) {
            null -> null
            CallDirection.OUTGOING -> CallLogType.OUTGOING
            CallDirection.INCOMING ->
                if (wasConnected || cause in NOT_MISSED) CallLogType.INCOMING else CallLogType.MISSED
        }
    }
}
