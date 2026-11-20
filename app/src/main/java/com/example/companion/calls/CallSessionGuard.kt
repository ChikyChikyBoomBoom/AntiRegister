package com.example.companion.calls

import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState

/**
 * Stage 27. Decides whether a call report from the SIM Phone still describes the present, using the optional session id
 * ([CallSnapshot.callId]). It is a filter inside the EXISTING [ClientCallController], not a second call-state system: it sends
 * nothing, controls nothing, and the controller stays the one place that holds the call state.
 *
 * What it remembers is only, per recent session id, how far that session got: seen ringing only / progressed / ended.
 * Rules (a report with no id, i.e. from an older SIM Phone build, is always accepted, exactly as before):
 *  - a report for a session that already ended is stale and dropped (a late or re-sent report must not bring a finished call back
 *    or flash "Call ended" a second time);
 *  - RINGING for a session that was already seen answered, dialing, held or ending is stale (it would show an answered call as ringing);
 *  - everything else is accepted, including the same RINGING again (a re-push after a reconnect), which is a harmless repeat.
 * Call waiting still works: the SIM Phone reports one "primary" call, so reports of two live sessions may alternate and both are accepted.
 *
 * Pure Kotlin, so it can be unit-tested on a plain JVM.
 */
class CallSessionGuard {

    private enum class Phase { RING, PROGRESSED, ENDED }

    /** Insertion-ordered; the oldest sessions are forgotten first. A few calls is all that can ever be in flight. */
    private val phases = object : LinkedHashMap<String, Phase>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Phase>?) = size > MAX_REMEMBERED
    }

    /**
     * True if [s] should be applied. Call it for every report, in arrival order; an accepted report updates what is remembered.
     * [haveState] = the controller currently holds a call state (false right after a link drop). A repeated DISCONNECTED of an ended
     * session is still accepted then, because the controller has nothing to show and the report is the end of that call.
     */
    fun accept(s: CallSnapshot, haveState: Boolean = true): Boolean {
        val id = s.callId ?: return true
        val phase = phases[id]
        when (s.state) {
            CallState.IDLE -> return true
            CallState.RINGING -> {
                if (phase == Phase.PROGRESSED || phase == Phase.ENDED) return false
                if (phase == null) phases[id] = Phase.RING
            }
            CallState.DISCONNECTED -> {
                if (phase == Phase.ENDED) return !haveState
                phases[id] = Phase.ENDED
            }
            else -> {
                if (phase == Phase.ENDED) return false
                phases[id] = Phase.PROGRESSED
            }
        }
        return true
    }

    /** Whether this id belongs to a session that already ended. Exposed for tests. */
    fun hasEnded(id: String?): Boolean = id != null && phases[id] == Phase.ENDED

    companion object { const val MAX_REMEMBERED = 16 }
}
