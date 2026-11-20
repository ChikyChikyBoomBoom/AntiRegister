package com.example.companion.sms

enum class SmsDirection { INCOMING, OUTGOING }

/** RECEIVED = incoming. Outgoing: PENDING (SIM Phone has not acknowledged yet) -> SENDING -> SENT | FAILED. */
enum class SmsStatus { RECEIVED, PENDING, SENDING, SENT, FAILED }

data class SmsEntry(
    val id: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val direction: SmsDirection,
    val status: SmsStatus,
    val error: String? = null,
    val detail: String? = null
)

data class SmsConversation(
    val key: String,
    val address: String,
    val lastBody: String,
    val lastTimestamp: Long,
    val lastDirection: SmsDirection,
    val count: Int,
    val failed: Int,
    /** Number of received messages in this conversation that have not been opened yet (Stage 13). */
    val unread: Int = 0
)

/**
 * Conversation key. Numbers: digits and a leading '+' only (so "+1 555-123" and "+1555123" match). Alphanumeric senders:
 * lower-cased text. No country-code normalisation is attempted (+1 555... and 555... stay separate conversations).
 */
fun threadKey(address: String): String {
    val a = address.trim()
    return if (a.any { it.isLetter() }) a.lowercase() else a.filter { it.isDigit() || it == '+' }
}
