package com.example.companion.sms

import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import com.example.companion.protocol.Message
import com.example.companion.protocol.MessageType
import com.example.companion.protocol.SmsMessages
import com.example.companion.protocol.SmsSendRequest
import com.example.companion.protocol.SmsSendState
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Main Phone (client) side of the SMS protocol. Holds no SMS capability of its own: it asks the SIM Phone to send and
 * shows only what the SIM Phone reports. Design for a missing link: PREVENT new messages (nothing is sent or queued
 * for later, so no surprise SMS hours afterwards); messages already handed over are re-checked by id on reconnect,
 * and the SIM Phone answers a known id instead of sending it twice. Never logs numbers or text.
 */
class ClientSmsController(
    private val prefs: AppPrefs,
    private val connection: ConnectionManager,
    private val store: SmsStore
) : ConnectionManager.Listener {

    interface Listener {
        fun onSmsChanged()

        /**
         * Stage 15: a received SMS was stored for the FIRST time (never for a re-delivery after a reconnect).
         * [unread] = unread messages in that conversation, this one included. Optional: existing listeners ignore it.
         */
        fun onIncomingSms(entry: SmsEntry, unread: Int) {}

        /** Stage 15: opening a conversation marked its received messages read ([key] = [threadKey]). Optional. */
        fun onConversationRead(key: String) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val isClient get() = prefs.role == Role.MAIN_PHONE
    val linkUp get() = connection.state == ConnectionManager.State.CONNECTED

    /**
     * Stage 26: true after a received SMS could not be stored on this phone, until the next one is stored. Such an SMS was not
     * acknowledged, so the SIM Phone keeps it and delivers it again on every reconnect. The Messages list shows a notice while this
     * is true. Only a flag: it holds no number and no text.
     */
    @Volatile var incomingNotSaved = false
        private set

    /** Calls [f] for every listener; a listener that throws (a screen in a bad state, a notification failure) cannot stop the others or crash the caller. */
    private inline fun forEachListener(what: String, f: (Listener) -> Unit) {
        for (l in listeners) {
            try { f(l) } catch (e: Exception) { SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_NOTIFY, what, e) }
        }
    }

    /** Returns null if the message was handed to the SIM Phone, otherwise the reason it was refused (nothing was stored or sent). */
    fun send(toRaw: String, bodyRaw: String): String? =
        try {
            sendChecked(toRaw, bodyRaw)
        } catch (e: Exception) {
            // Stage 26: only reached before the message was stored or handed over (the steps after that guard themselves), so
            // "Nothing was sent" is true. The screen shows a plain sentence; the technical detail goes to SmsDiagnostics.
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_SEND, "send failed before hand-over", e)
            SmsErrorText.REFUSE_INTERNAL      // Stage 26: English reason, mapped to the selected language by SmsErrorText.refusal
        }

    private fun sendChecked(toRaw: String, bodyRaw: String): String? {
        if (!isClient) return "Only the Main Phone can send messages"
        val to = toRaw.trim()
        if (!SmsMessages.isValidRecipient(to)) return "Enter a valid phone number (digits, optional leading +)"
        if (bodyRaw.isBlank()) return "The message is empty"
        if (bodyRaw.length > SmsMessages.MAX_SEND_BODY) return "The message is too long (max ${SmsMessages.MAX_SEND_BODY} characters)"
        // Stage 27: same refusal, but it names the Bluetooth cause when there is a plain one (off, no permission, no Bluetooth, reconnecting).
        if (!linkUp) return SmsErrorText.linkDownReason(connection.linkIssue)
        val id = UUID.randomUUID().toString()
        val saved = try {
            store.insert(SmsEntry(id, to, bodyRaw, System.currentTimeMillis(), SmsDirection.OUTGOING, SmsStatus.PENDING))
        } catch (e: Exception) {                      // Stage 26: was SQLiteException only; a closed or locked database throws other types too
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_STORE, "save outgoing message", e); false
        }
        if (!saved) return "Could not save the message on this phone. Nothing was sent."
        // Stored from here on. If handing it to Bluetooth fails, the message stays PENDING: exactly the "link dropped right after
        // Send" case, which the reconnect logic already resolves (same id; an old one is only queried). So this still counts as accepted.
        try {
            connection.send(SmsMessages.send(SmsSendRequest(id, to, bodyRaw, retry = false, queryOnly = false)))
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_SEND, "hand-over to bluetooth failed", e)
        }
        notifyUi()
        return null
    }

    /**
     * Stage 13: the user opened this conversation, so its received messages are read. Local only: nothing is sent over
     * Bluetooth. Returns true if something changed (listeners are told only then, which also keeps a screen that calls this
     * from its own refresh from looping).
     */
    fun markRead(address: String): Boolean {
        if (!isClient) return false
        val changed = try { store.markThreadRead(threadKey(address)) } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_STORE, "mark conversation read", e); false
        }
        if (changed) {
            val key = threadKey(address)
            forEachListener("conversation-read listener") { it.onConversationRead(key) }       // e.g. dismiss that conversation's notification
            notifyUi()
        }
        return changed
    }

    override fun onMessage(message: Message) {
        if (!isClient) return
        // Stage 26: runs inside the Bluetooth layer's listener loop; an exception must not reach it (crash, other listeners skipped).
        try {
            when (message.type) {
                MessageType.SMS_RECEIVED -> onReceived(message)
                MessageType.SMS_SEND_RESULT -> onSendResult(message)
                else -> Unit
            }
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_RECEIVE, "message handling failed", e, message.type.name)
        }
    }

    private fun onReceived(m: Message) {
        val r = SmsMessages.parseReceived(m)                                // invalid / unsupported version: dropped
        if (r == null) { SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_RECEIVE, "dropped: invalid or unsupported received message"); return }
        val entry = SmsEntry("in-${r.transferId}", r.sender, r.body, r.timestamp ?: System.currentTimeMillis(),
            SmsDirection.INCOMING, SmsStatus.RECEIVED,
            detail = if (r.timestamp == null) "time = when this phone received it" else null)
        val isNew = try { store.insert(entry) } catch (e: Exception) {      // not stored -> no ack -> the SIM Phone keeps it and retries
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_STORE, "save received message", e)
            if (!incomingNotSaved) { incomingNotSaved = true; notifyUi() }  // Stage 26: the Messages list tells the user (once, until something is stored again)
            return
        }
        val hadProblem = incomingNotSaved
        incomingNotSaved = false
        try {
            connection.send(SmsMessages.receivedAck(r.transferId))          // also for duplicates, so the SIM Phone can forget it
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_RECEIVE, "ack not sent", e)   // not acknowledged: the SIM Phone re-delivers, the id de-duplicates
        }
        if (isNew) {                                                        // a re-delivery after a reconnect changes nothing on screen
            // Counted before the screens are told: a screen that is open marks the conversation read from its own refresh.
            val unread = try { store.unreadCount(threadKey(entry.address)) } catch (e: Exception) { 1 }
            notifyUi()
            forEachListener("incoming-sms listener") { it.onIncomingSms(entry, unread) }    // Stage 15: only now, and only once per stored message
        } else if (hadProblem) notifyUi()
    }

    private fun onSendResult(m: Message) {
        val r = SmsMessages.parseSendResult(m)
        if (r == null) { SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_RESULT, "dropped: invalid or unsupported send result"); return }
        val changed = try {
            when (r.state) {
                SmsSendState.SENDING -> store.updateStatus(r.id, SmsStatus.SENDING, null, null, setOf(SmsStatus.PENDING))
                SmsSendState.SENT -> store.updateStatus(r.id, SmsStatus.SENT, null, null, setOf(SmsStatus.PENDING, SmsStatus.SENDING))
                SmsSendState.FAILED -> store.updateStatus(r.id, SmsStatus.FAILED, r.error, r.detail, setOf(SmsStatus.PENDING, SmsStatus.SENDING))
            }
        } catch (e: Exception) {
            // The message stays as it was (Sending); the next reconnect asks the SIM Phone again, so the result is not lost for good.
            SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_RESULT, "save send result", e, r.state.name); false
        }
        if (changed) notifyUi()
    }

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) {
        if (!isClient) return
        if (state == ConnectionManager.State.CONNECTED) {
            try { resolveUnresolved() } catch (e: Exception) { SmsDiagnostics.record(SmsDiagnostics.Area.MAIN_SEND, "re-check after reconnect failed", e) }
        }
        notifyUi()
    }

    /**
     * After (re)connecting: ask about every message without a final result. Recent ones are re-sent with the SAME id (the
     * SIM Phone sends it only if it never saw that id). Old ones are only queried, so a stale message is never sent late.
     */
    private fun resolveUnresolved() {
        val now = System.currentTimeMillis()
        store.unresolvedOutgoing().forEach { e ->
            val stale = now - e.timestamp > STALE_MS
            connection.send(SmsMessages.send(SmsSendRequest(e.id, e.address, e.body, retry = true, queryOnly = stale)))
        }
    }

    private fun notifyUi() = forEachListener("sms-changed listener") { it.onSmsChanged() }

    private companion object {
        const val STALE_MS = 10 * 60 * 1000L
    }
}
