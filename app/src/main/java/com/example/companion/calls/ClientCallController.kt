package com.example.companion.calls

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.companion.bluetooth.BluetoothDiagnostics
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import com.example.companion.protocol.CallMessages
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import com.example.companion.protocol.SimAudioReport
import com.example.companion.protocol.Message
import com.example.companion.protocol.MessageType
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Main phone (client) side of the call protocol: mirrors what the SIM phone reports and sends commands.
 * Holds no call logic of its own; [snapshot] is null whenever the real state is unknown (link down / not yet synced).
 */
class ClientCallController(
    private val prefs: AppPrefs,
    private val connection: ConnectionManager
) : CallController, ConnectionManager.Listener {

    interface Listener { fun onCallUiChanged() }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Last state reported by the SIM phone; null = unknown. */
    var snapshot: CallSnapshot? = null; private set
    /** SIM phone's report of whether it can control calls (holds the dialer role); null = unknown. */
    var controlAvailable: Boolean? = null; private set
    /** Failure code of the last rejected command reported by the SIM phone, if any. */
    var lastError: String? = null; private set
    var lastErrorCommand: String? = null; private set
    /** Stage 7: SIM Phone's Telecom mute / speaker state as REPORTED by the SIM Phone; null = unknown. */
    var simAudio: SimAudioReport? = null; private set
    /** elapsedRealtime when THIS phone first saw the call ACTIVE (approximate call timer); null when not active. */
    var activeSince: Long? = null; private set

    /** Stage 17: number of the last RINGING report (null = withheld or none). Kept across a link drop, which resets [snapshot], so a re-reported ring can be recognised. */
    var lastRingNumber: String? = null; private set

    /** Stage 27: drops stale / re-sent reports of a call session that already moved on or ended. */
    private val sessions = CallSessionGuard()

    // ---- Stage 28: ask the SIM Phone for the real state after a (re)connect, and now and then during a call
    private val main = Handler(Looper.getMainLooper())
    private var reportedSinceConnect = false
    private var syncRetries = 0
    private val syncTick = Runnable {
        if (!reportedSinceConnect) syncRetries++
        requestSync()
    }

    /** Sends CALL_SYNC_REQUEST (read-only on the SIM Phone) and schedules the next one if [CallSyncPolicy] wants it. */
    private fun requestSync() {
        if (!isClient || !linkUp || !CallFeatures.isCallingEnabled()) return   // Stage 7: no call traffic while calling is off
        connection.send(CallMessages.syncRequest())
        scheduleSync()
    }

    private fun scheduleSync() {
        main.removeCallbacks(syncTick)
        CallSyncPolicy.nextDelayMs(linkUp, reportedSinceConnect, syncRetries, snapshot?.state)?.let { main.postDelayed(syncTick, it) }
    }

    private val isClient get() = prefs.role == Role.MAIN_PHONE
    val linkUp get() = connection.state == ConnectionManager.State.CONNECTED

    // ---- commands (UI -> SIM phone)

    fun answer() = send(CallMessages.answer())
    fun reject() = send(CallMessages.reject())
    fun hangup() = send(CallMessages.hangup())
    /** [number] is sent exactly as given; the SIM phone decides whether it can be dialed. */
    fun dial(number: String) = send(CallMessages.dial(number))
    /** Mute / unmute the SIM PHONE's microphone (Telecom). The button state follows the SIM Phone's report, not this request. */
    fun setMute(on: Boolean) = send(CallMessages.mute(on))
    /** Speaker (true) / earpiece (false) on the SIM PHONE (Telecom). */
    fun setSpeaker(on: Boolean) = send(CallMessages.speaker(on))

    private fun send(m: Message) {
        // Stage 7: the single choke point of every Main Phone call command (answer, reject, hangup, dial, mute, speaker).
        if (!CallFeatures.isCallingEnabled()) return
        if (!isClient || !linkUp) return
        lastError = null; lastErrorCommand = null
        connection.send(m)
        notifyUi()
    }

    // ---- events from the SIM phone

    override fun onMessage(message: Message) {
        if (!isClient) return
        if (CallFeatures.blocksMessage(message.type)) return   // Stage 7: a call report from the SIM Phone is ignored, so no call UI, notification or audio can start
        if (message.type != MessageType.CALL_INCOMING && message.type != MessageType.CALL_STATE) return
        val status = CallMessages.parseStatus(message) ?: return
        reportedSinceConnect = true       // the SIM Phone answers: no more repeat requests after a connect
        // Stage 27: a report of a session that already ended, or a RINGING for one already answered, is outdated: ignore it.
        if (!sessions.accept(status.snapshot, haveState = snapshot != null)) { scheduleSync(); return }
        val previous = snapshot
        val sameAsBefore = previous == status.snapshot && simAudio == status.simAudio &&
            (status.controlAvailable == null || status.controlAvailable == controlAvailable) && status.error == null
        snapshot = status.snapshot
        if (status.snapshot.state == CallState.RINGING) lastRingNumber = status.snapshot.number
        simAudio = status.simAudio
        val newSession = previous?.callId != null && status.snapshot.callId != null && previous.callId != status.snapshot.callId
        if (newSession && status.snapshot.state != CallState.ACTIVE) activeSince = null
        if (status.snapshot.state == CallState.ACTIVE) {
            val now = SystemClock.elapsedRealtime()
            // Stage 17: the SIM Phone's own duration wins, so the timer is right again after a Bluetooth reconnect.
            // Without it (not reported) fall back to "first seen ACTIVE on this phone", as before.
            // Stage 27: a different call session never inherits the previous call's timer.
            val reported = status.activeMs
            if (reported != null) activeSince = now - reported else if (activeSince == null || newSession) activeSince = now
        } else if (status.snapshot.state != CallState.HOLDING) activeSince = null   // Stage 28: a call on hold keeps its timer base, so it is right again on resume
        controlAvailable = status.controlAvailable ?: controlAvailable
        if (status.error != null) { lastError = status.error; lastErrorCommand = status.command }
        // Stage 27: an exact repeat (a re-push after a reconnect, or a CALL_INCOMING re-sent for an audio change) changes nothing
        // the listeners could act on, so it is not announced to them again.
        scheduleSync()
        if (sameAsBefore) return
        notifyUi()
    }

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) {
        if (!isClient) return
        if (state != ConnectionManager.State.CONNECTED) {
            // Do not pretend to know the call state without a link; the SIM Phone pushes a fresh one on reconnect.
            // Stage 28: nothing about the old link may stay on screen, including the last command's failure text.
            snapshot = null; controlAvailable = null; simAudio = null; activeSince = null
            lastError = null; lastErrorCommand = null
            reportedSinceConnect = false; syncRetries = 0
            main.removeCallbacks(syncTick)
            notifyUi()
        } else {
            // Stage 28: the screen shows "waiting" (snapshot is null) right away, and the real state is requested immediately.
            reportedSinceConnect = false; syncRetries = 0
            notifyUi()
            requestSync()
        }
    }

    // Stage 30: a screen or notifier that throws cannot stop the other listeners or crash the Bluetooth / main-thread caller.
    private fun notifyUi() {
        for (l in listeners) {
            try { l.onCallUiChanged() } catch (e: Exception) { BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LISTENER, "call ui listener", e) }
        }
    }
}
