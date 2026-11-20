package com.example.companion.protocol

import org.json.JSONObject

/**
 * Encoding / decoding of the call protocol. Pure functions: no Bluetooth, no Telecom.
 * Envelope is the existing {"v":1,"type":...,"payload":{...}}; fields below live in "payload".
 * Absent information is omitted, never filled with a placeholder.
 */
object CallMessages {

    // ---- Host -> Client

    fun incoming(s: CallSnapshot, controlAvailable: Boolean? = null, simAudio: SimAudioReport? = null) =
        Message(MessageType.CALL_INCOMING, payload(s, controlAvailable, null, null, simAudio))

    /** [activeMs] (Stage 17, optional): how long the call has been ACTIVE on the SIM Phone; omitted when not ACTIVE or unknown. */
    fun state(s: CallSnapshot, controlAvailable: Boolean? = null, command: String? = null, error: String? = null, simAudio: SimAudioReport? = null, activeMs: Long? = null) =
        Message(MessageType.CALL_STATE, payload(s, controlAvailable, command, error, simAudio, activeMs))

    // ---- Client -> Host

    fun answer() = Message(MessageType.CALL_ANSWER)
    fun reject() = Message(MessageType.CALL_REJECT)
    fun hangup() = Message(MessageType.CALL_HANGUP)
    /** The number is sent exactly as given. */
    fun dial(number: String) = Message(MessageType.DIAL, JSONObject().put("number", number))
    /** Stage 7: asks the SIM Phone to mute / unmute ITS microphone through Telecom. */
    fun mute(on: Boolean) = Message(MessageType.CALL_MUTE, JSONObject().put("on", on))
    /** Stage 7: asks the SIM Phone to use its speaker (true) or earpiece (false) through Telecom. */
    fun speaker(on: Boolean) = Message(MessageType.CALL_SPEAKER, JSONObject().put("on", on))

    /** Stage 28: asks the SIM Phone to report its current call state (answered with the normal CALL_INCOMING / CALL_STATE). */
    fun syncRequest() = Message(MessageType.CALL_SYNC_REQUEST)

    // ---- Parsing

    fun parseStatus(m: Message): CallStatus? {
        if (m.type != MessageType.CALL_INCOMING && m.type != MessageType.CALL_STATE) return null
        val p = m.payload
        val state = optString(p, "state")?.let { runCatching { CallState.valueOf(it) }.getOrNull() } ?: return null
        val direction = if (m.type == MessageType.CALL_INCOMING) CallDirection.INCOMING
        else optString(p, "direction")?.let { runCatching { CallDirection.valueOf(it) }.getOrNull() }
        val callId = optString(p, "callId")?.takeIf { CallSessionId.isValid(it) }   // Stage 27: absent from older builds
        val snapshot = CallSnapshot(state, optString(p, "number"), direction, optString(p, "disconnectCause"), callId)
        val control = if (p.has("controlAvailable") && !p.isNull("controlAvailable")) p.optBoolean("controlAvailable") else null
        val simAudio = if (p.has("micMuted") || p.has("speakerOn"))
            SimAudioReport(optBool(p, "micMuted"), optBool(p, "speakerOn")) else null
        val activeMs = if (p.has("activeMs") && !p.isNull("activeMs")) p.optLong("activeMs", -1L).takeIf { it >= 0 } else null
        return CallStatus(snapshot, control, optString(p, "command"), optString(p, "error"), simAudio, activeMs)
    }

    /** The "on" flag of CALL_MUTE / CALL_SPEAKER; null if missing (the command is then refused, never guessed). */
    fun parseOn(m: Message): Boolean? =
        if ((m.type == MessageType.CALL_MUTE || m.type == MessageType.CALL_SPEAKER) && m.payload.has("on") && !m.payload.isNull("on"))
            m.payload.optBoolean("on") else null

    /** Returns the number untouched (no trimming, no reformatting); null if missing or empty. */
    fun parseDialNumber(m: Message): String? =
        if (m.type == MessageType.DIAL) optString(m.payload, "number")?.takeIf { it.length <= MAX_NUMBER } else null   // Stage 31B: an oversized number is refused, never cut short

    // ---- helpers

    private fun payload(s: CallSnapshot, control: Boolean?, command: String?, error: String?, simAudio: SimAudioReport? = null, activeMs: Long? = null) = JSONObject().apply {
        put("state", s.state.name)
        s.number?.let { put("number", it) }
        s.direction?.let { put("direction", it.name) }
        s.disconnectCause?.let { put("disconnectCause", it) }
        s.callId?.takeIf { CallSessionId.isValid(it) }?.let { put("callId", it) }   // Stage 27, optional
        control?.let { put("controlAvailable", it) }
        command?.let { put("command", it) }
        error?.let { put("error", it) }
        simAudio?.micMuted?.let { put("micMuted", it) }
        simAudio?.speakerOn?.let { put("speakerOn", it) }
        if (s.state == CallState.ACTIVE) activeMs?.takeIf { it >= 0 }?.let { put("activeMs", it) }
    }

    private fun optBool(p: JSONObject, key: String): Boolean? =
        if (!p.has(key) || p.isNull(key)) null else p.optBoolean(key)

    private fun optString(p: JSONObject, key: String): String? =
        if (!p.has(key) || p.isNull(key)) null else p.optString(key, "").takeIf { it.isNotEmpty() }?.take(if (key == "number") MAX_NUMBER + 1 else MAX_TEXT)

    // Stage 31B (data security): the peer's strings are bounded before they reach the UI, notifications or the local call history.
    // (A number longer than MAX_NUMBER is cut to MAX_NUMBER + 1 so that parseDialNumber can still tell it is too long.)
    private const val MAX_NUMBER = 64
    private const val MAX_TEXT = 200
}
