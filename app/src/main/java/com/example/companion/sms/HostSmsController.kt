package com.example.companion.sms

import android.content.Context
import android.content.pm.PackageManager
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import com.example.companion.protocol.Message
import com.example.companion.protocol.MessageType
import com.example.companion.protocol.SmsMessages
import com.example.companion.protocol.SmsReceived
import com.example.companion.protocol.SmsSendResult
import com.example.companion.protocol.SmsSendState

/**
 * SIM Phone (host) side of the SMS protocol. The only class that connects Android's SMS API to Bluetooth messages:
 *  - SMS_SEND from the Main Phone -> [SmsGateway]; the real result (SmsSentReceiver) -> SMS_SEND_RESULT.
 *  - SMS that arrived ([onSmsReceived]) -> SMS_RECEIVED; kept in [SimSmsStore] until the Main Phone sends SMS_RECEIVED_ACK.
 * It never touches sockets and never logs a number or a message body. Runs on the main thread (receivers + listeners).
 *
 * Sending is AT-MOST-ONCE: the id is persisted as SENDING before Android is asked to send, and an id already known is
 * answered with its recorded state instead of being sent again. That makes a re-send after a reconnect harmless.
 */
class HostSmsController(
    private val context: Context,
    private val prefs: AppPrefs,
    private val bluetooth: BluetoothManager,
    private val connection: ConnectionManager,
    private val store: SimSmsStore,
    private val gateway: SmsGateway
) : ConnectionManager.Listener {

    private val isHost get() = prefs.role == Role.SIM_PHONE

    // ---- Main Phone -> SIM Phone

    override fun onMessage(message: Message) {
        if (!isHost) return
        // Stage 26: this runs inside the Bluetooth layer's listener loop. An exception here must not reach it (it would crash the app
        // and skip the other listeners), so anything unexpected is caught and kept for developers.
        try {
            when (message.type) {
                MessageType.SMS_SEND -> handleSend(message)
                MessageType.SMS_RECEIVED_ACK -> SmsMessages.parseAck(message)?.let { store.ackInbound(it) }
                else -> Unit
            }
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_SEND, "message handling failed", e, message.type.name)
        }
    }

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) {
        if (!isHost || state != ConnectionManager.State.CONNECTED) return
        try { flushInbound() } catch (e: Exception) { SmsDiagnostics.record(SmsDiagnostics.Area.SIM_LINK, "flush of queued sms failed", e) }
    }

    private fun handleSend(m: Message) {
        val req = SmsMessages.parseSend(m)
        if (req == null) {                               // invalid: answer if we can identify it, never act on it
            SmsMessages.parseSendId(m)?.let { fail(it, SmsError.INVALID_REQUEST, "invalid recipient, text or protocol version") }
            return
        }
        val now = System.currentTimeMillis()
        val known = store.getSend(req.id)
        if (known != null) {                             // duplicate / re-send after reconnect: report, never send again
            val current = SmsSendLogic.expireIfStale(known, now, STALE_SENDING_MS)
            if (current !== known) store.putSend(current)
            reply(current)
            return
        }
        if (req.queryOnly) { fail(req.id, SmsError.NOT_RECEIVED, null); return }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) { fail(req.id, SmsError.NO_TELEPHONY, null); return }
        if (!SmsPermissions.canSend(context)) { fail(req.id, SmsError.PERMISSION_DENIED, null); return }

        val result = try {
            gateway.send(req.id, req.to, req.body) { parts ->
                // Persisted BEFORE Android is asked to send, so a crash or a re-send can never cause a second SMS.
                val rec = SmsSendLogic.started(req.id, parts, now)
                // Stage 26: if the record could not be saved, do NOT send (the gateway reports STORAGE_ERROR): without the record a
                // re-send after a reconnect could send the same SMS twice.
                if (!store.putSend(rec)) throw SendRecordNotSavedException()
                reply(rec)                               // SENDING: honest, nothing has been sent yet
            }
        } catch (e: Exception) {
            // The real gateway reports its own problems as a result; this covers any other gateway (and a bug): nothing was confirmed sent.
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_SEND, "gateway threw", e)
            GatewayResult.Error(SmsError.INTERNAL_ERROR, null)
        }
        if (result is GatewayResult.Error) fail(req.id, result.code, result.detail)
    }

    /** Android reported the outcome of one SMS part (called by [SmsSentReceiver]). Only the final result is forwarded. */
    fun onPartResult(id: String, resultCode: Int) {
        if (!isHost) return
        try {
            val updated = store.updateSend(id) { SmsSendLogic.applyPart(it, resultCode, System.currentTimeMillis()) } ?: return
            if (updated.state != SmsSendState.SENDING) reply(updated)
        } catch (e: Exception) {
            // Not reported to the Main Phone: its message stays "Sending" until the next reconnect asks again, then the SIM Phone
            // answers from its record (or RESULT_UNKNOWN once it is stale), so nothing is guessed.
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_SENT_RESULT, "part result handling failed", e)
        }
    }

    private fun fail(id: String, error: String, detail: String?) {
        val rec = SmsSendLogic.failed(id, error, detail, System.currentTimeMillis())
        // Stage 26: the answer is sent even if the record cannot be saved (the Main Phone must learn that it failed).
        try { store.putSend(rec) } catch (e: Exception) { SmsDiagnostics.record(SmsDiagnostics.Area.SIM_STORE, "save failed record", e, error) }
        reply(rec)
    }

    private fun reply(r: SmsSendRecord) {
        try {
            connection.send(SmsMessages.sendResult(SmsSendResult(r.id, r.state, r.error, r.detail)))
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_LINK, "reply not sent", e, r.state.name)
        }
    }

    // ---- SIM Phone -> Main Phone

    /** Called by [SmsReceiver] for every SMS that arrives. The SMS is persisted first, so it survives a missing link. */
    fun onSmsReceived(r: SmsReceived) {
        if (!isHost) return
        // Stage 26: if the queue cannot be written, still try to forward the SMS now (it is better delivered once without a
        // retry copy than lost); the Main Phone de-duplicates by id either way.
        try { store.enqueueInbound(r) } catch (e: Exception) { SmsDiagnostics.record(SmsDiagnostics.Area.SIM_STORE, "queue received sms failed", e) }
        try {
            if (connection.state == ConnectionManager.State.CONNECTED) connection.send(SmsMessages.received(r)) else ensureLink()
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.SIM_LINK, "forward received sms failed", e)   // stays queued, so the next connect forwards it
        }
    }

    private fun flushInbound() {
        store.pendingInbound().forEach { connection.send(SmsMessages.received(it)) }   // Main Phone de-duplicates by id
    }

    /** An SMS can arrive while nobody listens (process recreated by the SMS broadcast). Listen again, unless the user pressed Disconnect. */
    private fun ensureLink() {
        val st = connection.state
        if ((st == ConnectionManager.State.DISCONNECTED || st == ConnectionManager.State.ERROR) &&
            !connection.userStopped && BtPermissions.granted(context, Role.SIM_PHONE) && bluetooth.isEnabled
        ) connection.start(userInitiated = false)
    }

    private companion object {
        /** SENDING with no part result for this long is reported as RESULT_UNKNOWN (e.g. the process died mid-send). */
        const val STALE_SENDING_MS = 120_000L
    }
}
