package com.example.companion.protocol

/** Call states as reported by Android Telecom (android.telecom.Call), plus IDLE for "no call". */
enum class CallState {
    IDLE,            // no call
    RINGING,         // incoming call ringing
    CONNECTING,      // outgoing call being set up (Call.STATE_NEW / STATE_CONNECTING)
    SELECT_ACCOUNT,  // Android is waiting for a SIM / phone-account choice (Call.STATE_SELECT_PHONE_ACCOUNT)
    DIALING,         // outgoing call dialing
    ACTIVE,          // call connected
    HOLDING,         // call on hold
    DISCONNECTING,   // hang-up in progress
    DISCONNECTED     // call ended
}

enum class CallDirection { INCOMING, OUTGOING }

/**
 * What the SIM phone knows about its current call. Only facts Android actually provided are set:
 * [number] is null when Android did not expose it (private / withheld / unknown), [direction] is null when unknown.
 */
data class CallSnapshot(
    val state: CallState,
    val number: String? = null,
    val direction: CallDirection? = null,
    val disconnectCause: String? = null,
    /**
     * Stage 27: stable identifier of this call session, created by the SIM Phone when Android adds the call and kept until the call
     * is removed. null = none (IDLE, or reported by an older SIM Phone build). Optional on the wire; see [CallSessionId].
     */
    val callId: String? = null
) {
    companion object { val IDLE = CallSnapshot(CallState.IDLE) }
}

/**
 * Stage 27: the call-session identifier. Random (no number, name or time inside), so it reveals nothing, and only compared for
 * equality. It lives as long as the SIM Phone's process remembers the call; if that process is killed mid-call the same call
 * gets a new id when Android re-adds it (the Main Phone then treats it as a new session, as it did before this stage).
 */
object CallSessionId {
    const val MAX_LENGTH = 64
    private val VALID = Regex("^[A-Za-z0-9-]{1,$MAX_LENGTH}$")

    fun generate(): String = java.util.UUID.randomUUID().toString()

    /** Anything that is not a plain token is treated as "no id" on receipt, never repaired. */
    fun isValid(id: String?): Boolean = id != null && VALID.matches(id)
}

/** SIM Phone's Telecom audio state, as reported by Android (never what was merely requested). null field = not reported. */
data class SimAudioReport(val micMuted: Boolean?, val speakerOn: Boolean?)

/** Parsed CALL_INCOMING / CALL_STATE message. */
data class CallStatus(
    val snapshot: CallSnapshot,
    /** Host's report of whether it holds the default-phone-app role (null = not reported). */
    val controlAvailable: Boolean?,
    /** Set when this message reports a failed command. */
    val command: String?,
    val error: String?,
    /** Stage 7: SIM Phone's mute / speaker state, null when not reported. */
    val simAudio: SimAudioReport? = null,
    /** Stage 17: how long the SIM Phone says the call has been ACTIVE (ms); null = not reported (older build, not ACTIVE, unknown). */
    val activeMs: Long? = null
)

/** Command names used in failure reports (same as the wire message types). */
object CallCommand {
    const val ANSWER = "CALL_ANSWER"
    const val REJECT = "CALL_REJECT"
    const val HANGUP = "CALL_HANGUP"
    const val DIAL = "DIAL"
    const val MUTE = "CALL_MUTE"
    const val SPEAKER = "CALL_SPEAKER"
}

/** Failure codes reported in CALL_STATE.error. */
object CallError {
    const val NO_ROLE = "NO_ROLE"                     // app is not the default phone app: Android gives it no calls to control
    const val NO_INCOMING_CALL = "NO_INCOMING_CALL"
    const val NO_ACTIVE_CALL = "NO_ACTIVE_CALL"
    const val INVALID_NUMBER = "INVALID_NUMBER"
    const val CALL_IN_PROGRESS = "CALL_IN_PROGRESS"
    const val PERMISSION_DENIED = "PERMISSION_DENIED" // CALL_PHONE missing
    const val TELECOM_ERROR = "TELECOM_ERROR"         // Telecom threw while executing the command
    const val NOT_CONFIRMED = "NOT_CONFIRMED"         // command issued but Android never reported the expected change
    const val AUDIO_CONTROL_UNAVAILABLE = "AUDIO_CONTROL_UNAVAILABLE" // no InCallService: Telecom mute/route cannot be controlled
    const val ROUTE_UNSUPPORTED = "ROUTE_UNSUPPORTED" // Android does not list that route as supported right now
}
