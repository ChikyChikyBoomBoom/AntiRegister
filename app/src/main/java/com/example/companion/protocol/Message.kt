package com.example.companion.protocol

import org.json.JSONObject

/** Wire message types. PING/PONG are transport keepalive; CALL_* / DIAL are the Stage 3 call protocol. */
enum class MessageType {
    // Stage 2: transport-level keepalive / diagnostics (handled inside ConnectionManager)
    PING, PONG,
    // Stage 3: call protocol. Host -> Client: CALL_INCOMING, CALL_STATE. Client -> Host: CALL_ANSWER, CALL_REJECT, CALL_HANGUP, DIAL.
    CALL_INCOMING, CALL_STATE, CALL_ANSWER, CALL_REJECT, CALL_HANGUP, DIAL,
    // Stage 7 (Client -> Host): mute / speaker of the SIM PHONE's own call audio via Telecom. payload {"on": bool}.
    // The Host reports the real state in CALL_STATE (micMuted, speakerOn). Both phones must run the Stage 7 build.
    CALL_MUTE, CALL_SPEAKER,
    // Stage 28 (Client -> Host): "send me your current call state now". Empty payload. The Host answers with the usual CALL_INCOMING / CALL_STATE.
    // A Host from before Stage 28 drops it (unknown type) and still pushes its state on every connect, so nothing breaks.
    CALL_SYNC_REQUEST,
    // Stage 8: SMS. SIM Phone -> Main Phone: SMS_RECEIVED, SMS_SEND_RESULT. Main Phone -> SIM Phone: SMS_SEND, SMS_RECEIVED_ACK.
    // Every SMS payload carries "smsV" (SMS protocol version, currently 1); see SmsMessages.
    SMS_RECEIVED, SMS_SEND, SMS_SEND_RESULT, SMS_RECEIVED_ACK,
    // Stage 5: Bluetooth audio transport test (control only; the audio itself travels as binary AUDIO frames).
    // AUDIO_START (sender->receiver), AUDIO_READY (receiver->sender), AUDIO_STOP (either way, may carry an error),
    // AUDIO_STATS (receiver->sender, ~1/s)
    AUDIO_START, AUDIO_READY, AUDIO_STOP, AUDIO_STATS
}

data class Message(val type: MessageType, val payload: JSONObject = JSONObject()) {
    fun encode(): ByteArray =
        JSONObject().put("v", 1).put("type", type.name).put("payload", payload)
            .toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): Message? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Message(MessageType.valueOf(o.getString("type")), o.optJSONObject("payload") ?: JSONObject())
        } catch (e: Exception) { null }
    }
}
