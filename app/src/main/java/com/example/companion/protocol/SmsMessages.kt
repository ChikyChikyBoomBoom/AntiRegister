package com.example.companion.protocol

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** State of an outgoing SMS as reported by the SIM Phone. SENT means "accepted by the cellular network side", not "delivered". */
enum class SmsSendState { SENDING, SENT, FAILED }

/** An SMS that arrived on the SIM Phone. [transferId] is OUR id for de-duplication / ack; Android gives no stable SMS id to a non-default app. */
data class SmsReceived(
    val transferId: String,
    val sender: String,
    val body: String,
    /** Timestamp from the SMS itself (service-centre time) in epoch millis; null if Android did not provide one. */
    val timestamp: Long?,
    /** Subscription id of the SIM that received it; null if Android did not provide one. */
    val subscriptionId: Int?
)

data class SmsSendRequest(
    val id: String,
    val to: String,
    val body: String,
    /** True when the Main Phone re-sends after a reconnect. The SIM Phone never sends twice for the same id. */
    val retry: Boolean,
    /** True = "only tell me what you know about this id; do NOT send it if you never saw it". */
    val queryOnly: Boolean
)

data class SmsSendResult(val id: String, val state: SmsSendState, val error: String?, val detail: String?)

/**
 * Encoding / decoding / validation of the SMS protocol. Pure functions: no Bluetooth, no Android SMS APIs.
 * Envelope is the existing {"v":1,"type":...,"payload":{...}}. SMS payloads additionally carry "smsV" so SMS can evolve
 * without changing the envelope. A message with a missing or unsupported "smsV" is rejected (parse returns null).
 * Nothing here logs; message bodies must never reach a log.
 */
object SmsMessages {
    const val VERSION = 1
    const val MAX_SEND_BODY = 1600
    const val MAX_RECEIVED_BODY = 8000
    private const val MAX_SENDER = 64
    private const val MAX_TEXT = 200

    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val RECIPIENT_RE = Regex("^\\+?[0-9 ()\\-.]{3,32}$")

    // ---- validation (also used by the UI)

    fun isValidId(s: String?) = s != null && ID_RE.matches(s)

    /** Digits with optional leading '+', spaces, parentheses, dashes, dots; at least 3 digits. No '*' / '#' (no USSD / service codes). */
    fun isValidRecipient(s: String?) = s != null && RECIPIENT_RE.matches(s) && s.count { it.isDigit() } >= 3

    /** Incoming senders can be alphanumeric ("MYBANK"), so only length and control characters are checked. */
    fun isValidSender(s: String?) = s != null && s.isNotEmpty() && s.length <= MAX_SENDER && s.none { it.isISOControl() }

    /**
     * Stage 26: the stable id of a RECEIVED SMS. Android gives a non-default SMS app no message id, so the id is derived from what the
     * SMS itself carries (sender, text, service-centre time): the same SMS always gets the same id, even if Android delivers the broadcast
     * twice. Without a service-centre time nothing stable exists, so a random id is used (as before Stage 26). Valid per [isValidId].
     */
    fun receivedId(sender: String, body: String, timestamp: Long?): String {
        if (timestamp == null) return UUID.randomUUID().toString()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sender.toByteArray(Charsets.UTF_8)); md.update(0)         // 0 separators: ("ab","c") and ("a","bc") differ
        md.update(body.toByteArray(Charsets.UTF_8)); md.update(0)
        md.update(timestamp.toString().toByteArray(Charsets.UTF_8))
        return "r" + md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    // ---- encoding

    fun received(r: SmsReceived) = Message(MessageType.SMS_RECEIVED, JSONObject().apply {
        put("smsV", VERSION); put("id", r.transferId); put("from", r.sender); put("body", r.body)
        r.timestamp?.let { put("ts", it) }
        r.subscriptionId?.let { put("sub", it) }
    })

    fun receivedAck(id: String) = Message(MessageType.SMS_RECEIVED_ACK, JSONObject().put("smsV", VERSION).put("id", id))

    fun send(r: SmsSendRequest) = Message(MessageType.SMS_SEND, JSONObject().apply {
        put("smsV", VERSION); put("id", r.id); put("to", r.to); put("body", r.body)
        if (r.retry) put("retry", true)
        if (r.queryOnly) put("queryOnly", true)
    })

    fun sendResult(r: SmsSendResult) = Message(MessageType.SMS_SEND_RESULT, JSONObject().apply {
        put("smsV", VERSION); put("id", r.id); put("state", r.state.name)
        r.error?.let { put("error", it) }
        r.detail?.let { put("detail", it) }
    })

    // ---- parsing (null = invalid; the caller drops the message)

    fun parseReceived(m: Message): SmsReceived? {
        if (m.type != MessageType.SMS_RECEIVED || !versionOk(m.payload)) return null
        val p = m.payload
        val id = str(p, "id").takeIf { isValidId(it) } ?: return null
        val from = str(p, "from").takeIf { isValidSender(it) } ?: return null
        val body = str(p, "body")?.takeIf { it.isNotEmpty() && it.length <= MAX_RECEIVED_BODY } ?: return null
        val ts = if (p.has("ts")) p.optLong("ts", -1L).takeIf { it > 0 } else null
        val sub = if (p.has("sub")) p.optInt("sub", -1).takeIf { it >= 0 } else null
        return SmsReceived(id, from, body, ts, sub)
    }

    fun parseAck(m: Message): String? {
        if (m.type != MessageType.SMS_RECEIVED_ACK || !versionOk(m.payload)) return null
        return str(m.payload, "id").takeIf { isValidId(it) }
    }

    fun parseSend(m: Message): SmsSendRequest? {
        if (m.type != MessageType.SMS_SEND || !versionOk(m.payload)) return null
        val p = m.payload
        val id = str(p, "id").takeIf { isValidId(it) } ?: return null
        val to = str(p, "to").takeIf { isValidRecipient(it) } ?: return null
        val body = str(p, "body")?.takeIf { it.isNotBlank() && it.length <= MAX_SEND_BODY } ?: return null
        return SmsSendRequest(id, to, body, p.optBoolean("retry", false), p.optBoolean("queryOnly", false))
    }

    /** The id of an SMS_SEND that failed validation, so the SIM Phone can still answer it with FAILED instead of leaving it hanging. */
    fun parseSendId(m: Message): String? =
        if (m.type == MessageType.SMS_SEND) str(m.payload, "id").takeIf { isValidId(it) } else null

    fun parseSendResult(m: Message): SmsSendResult? {
        if (m.type != MessageType.SMS_SEND_RESULT || !versionOk(m.payload)) return null
        val p = m.payload
        val id = str(p, "id").takeIf { isValidId(it) } ?: return null
        val state = str(p, "state")?.let { runCatching { SmsSendState.valueOf(it) }.getOrNull() } ?: return null
        return SmsSendResult(id, state, str(p, "error")?.take(MAX_TEXT), str(p, "detail")?.take(MAX_TEXT))
    }

    // ---- helpers

    private fun versionOk(p: JSONObject) = p.has("smsV") && p.optInt("smsV", -1) == VERSION

    /** Strings only: a number or object in a string field is invalid, not coerced. */
    private fun str(p: JSONObject, key: String): String? = if (!p.has(key) || p.isNull(key)) null else p.opt(key) as? String
}
