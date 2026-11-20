package com.example.companion.ui

import com.example.companion.protocol.CallCommand
import com.example.companion.protocol.CallState

/** What the dial pad screen draws. Computed by [DialPadState.from]; the screen itself makes no decisions. */
data class DialPadUi(
    /** Text for the number line: the number exactly as typed, or a placeholder when empty. */
    val display: String,
    val isEmpty: Boolean,
    /** Spoken form for screen readers ("1 2 star pound"). */
    val spoken: String,
    val keysEnabled: Boolean,
    val deleteEnabled: Boolean,
    val clearEnabled: Boolean,
    val callEnabled: Boolean,
    val callLabel: String,
    /** A call is already live: offer a way back to the call screen instead of a second dial. */
    val showReturnToCall: Boolean,
    val message: String
)

/** Result of watching the SIM Phone's reports after a DIAL was sent. */
enum class DialOutcome { WAITING, CALL_STARTED, FAILED }

/** Pure functions (no Android types) so the dial pad's decisions can be unit-tested on a plain JVM. */
object DialPadState {

    const val MAX_LENGTH = 32
    /** Slightly longer than the SIM Phone's own 10 s "not confirmed" report, so that report normally wins. */
    const val ATTEMPT_TIMEOUT_MS = 12_000L
    const val PLACEHOLDER = "Enter a number"

    val KEYS: List<List<String>> = listOf(
        listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#")
    )

    // ---- editing (the number is never trimmed, formatted or rewritten)

    fun isKey(k: String) = k.length == 1 && k[0] in "0123456789*#"
    fun append(number: String, key: String): String =
        if (!isKey(key) || number.length >= MAX_LENGTH) number else number + key
    fun deleteLast(number: String): String = number.dropLast(1)
    fun clear(): String = ""

    // ---- what the SIM Phone will accept

    /**
     * Mirrors the rule in the SIM Phone's CallManager (optional leading +, then 1-32 digits). That side refuses USSD/MMI
     * characters (* and #) instead of rewriting them, so the dial pad does not send a number it knows will be refused.
     * If the SIM Phone's rule ever changes, change this with it.
     */
    private val SIM_ACCEPTS = Regex("^\\+?[0-9]{1,32}$")
    fun simAccepts(number: String) = SIM_ACCEPTS.matches(number)
    fun hasSpecialKeys(number: String) = number.any { it == '*' || it == '#' }

    fun spoken(number: String): String =
        if (number.isEmpty()) "No number entered"
        else number.map { when (it) { '*' -> "star"; '#' -> "pound"; else -> it.toString() } }.joinToString(" ")

    // ---- screen state

    fun from(number: String, linkUp: Boolean, callLive: Boolean, awaiting: Boolean, failure: String?): DialPadUi {
        val empty = number.isEmpty()
        val special = hasSpecialKeys(number)
        val message = when {
            !linkUp -> "SIM Phone not connected. Dialing is off until it reconnects." + (failure?.let { "\n$it" } ?: "")
            awaiting -> "Waiting for the SIM Phone…"
            callLive -> "A call is in progress on the SIM Phone."
            special -> "The SIM Phone can dial digits only for now. Remove * and # to call."
            failure != null -> "Could not dial: $failure"
            else -> ""
        }
        return DialPadUi(
            display = if (empty) PLACEHOLDER else number,
            isEmpty = empty,
            spoken = spoken(number),
            keysEnabled = !awaiting,
            deleteEnabled = !empty && !awaiting,
            clearEnabled = !empty && !awaiting,
            callEnabled = linkUp && !awaiting && !callLive && !empty && simAccepts(number),
            callLabel = if (awaiting) "Calling…" else "Call",
            showReturnToCall = linkUp && callLive,
            message = message
        )
    }

    /**
     * Decides from the SIM Phone's reports whether a DIAL has taken effect. Nothing is assumed: a call counts as started
     * only when the SIM Phone reports a live state (or a fresh DISCONNECTED, so a call that fails at once is still shown
     * on the call screen with its reason). A rejection reported for DIAL is a failure.
     */
    fun outcome(baseline: CallState?, current: CallState?, error: String?, errorCommand: String?): DialOutcome = when {
        error != null && errorCommand == CallCommand.DIAL -> DialOutcome.FAILED
        CallScreenState.isLive(current) -> DialOutcome.CALL_STARTED
        current == CallState.DISCONNECTED && baseline != CallState.DISCONNECTED -> DialOutcome.CALL_STARTED
        else -> DialOutcome.WAITING
    }
}
