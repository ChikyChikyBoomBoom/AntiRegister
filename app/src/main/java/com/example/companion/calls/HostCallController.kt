package com.example.companion.calls

import android.content.Context
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import com.example.companion.protocol.CallMessages
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import com.example.companion.protocol.CallCommand
import com.example.companion.protocol.CallError
import com.example.companion.protocol.Message
import com.example.companion.protocol.MessageType

/**
 * SIM phone (host) side of the call protocol. The only class that connects [CallManager] to Bluetooth messages:
 *  - Client commands (CALL_ANSWER / CALL_REJECT / CALL_HANGUP / DIAL) -> [CallManager]
 *  - [CallManager] state changes and command failures -> CALL_INCOMING / CALL_STATE via [ConnectionManager.send]
 * It never touches sockets and never ends a call because the link dropped.
 */
class HostCallController(
    private val context: Context,
    private val prefs: AppPrefs,
    private val bluetooth: BluetoothManager,
    private val calls: CallManager,
    private val connection: ConnectionManager,
    private val simAudio: SimAudioControl
) : CallController, ConnectionManager.Listener, CallManager.Listener, SimAudioControl.Listener {

    private val isHost get() = prefs.role == Role.SIM_PHONE

    private companion object {
        val AUDIO_CONTROL_STATES = setOf(CallState.ACTIVE, CallState.HOLDING, CallState.DIALING, CallState.CONNECTING)
    }

    // ---- Client -> Host

    override fun onMessage(message: Message) {
        if (!isHost) return
        if (CallFeatures.blocksMessage(message.type)) return   // Stage 7: no call command from the Main Phone is acted on, and none is answered
        when (message.type) {
            MessageType.CALL_ANSWER -> calls.answer()
            MessageType.CALL_REJECT -> calls.reject()
            MessageType.CALL_HANGUP -> calls.hangup()
            MessageType.DIAL -> {
                val number = CallMessages.parseDialNumber(message)
                if (number == null) onCommandFailed(CallCommand.DIAL, CallError.INVALID_NUMBER, calls.snapshot)
                else calls.dial(number)
            }
            // Stage 28: the Main Phone asks for the real state (after a reconnect, or as a periodic check during a call). Read-only: changes nothing.
            MessageType.CALL_SYNC_REQUEST -> resync()
            MessageType.CALL_MUTE, MessageType.CALL_SPEAKER -> audioControl(message)
            else -> Unit
        }
    }

    /** Stage 7: mute / speaker of THIS phone's call audio via Telecom. Success is reported by the real state change, never assumed. */
    private fun audioControl(m: Message) {
        val command = if (m.type == MessageType.CALL_MUTE) CallCommand.MUTE else CallCommand.SPEAKER
        val on = CallMessages.parseOn(m) ?: return onCommandFailed(command, CallError.TELECOM_ERROR, calls.snapshot)
        if (calls.snapshot.state !in AUDIO_CONTROL_STATES) return onCommandFailed(command, CallError.NO_ACTIVE_CALL, calls.snapshot)
        val error = if (m.type == MessageType.CALL_MUTE) simAudio.setMuted(on) else simAudio.setSpeaker(on)
        if (error != null) onCommandFailed(command, error, calls.snapshot)
    }

    /** Telecom reported a mute / route change: tell the Main Phone the real state. */
    override fun onSimAudioChanged() { if (isHost && CallFeatures.isCallingEnabled()) publish(calls.snapshot) }

    // ---- Link events: resynchronise whenever Bluetooth (re)connects

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) {
        if (isHost && state == ConnectionManager.State.CONNECTED) resync()
    }

    /** Sends the current call state (also used after the link comes back, and when the role status may have changed). */
    fun resync() {
        if (isHost && CallFeatures.isCallingEnabled()) publish(calls.snapshot)
    }

    // ---- Host -> Client

    override fun onSnapshotChanged(snapshot: CallSnapshot) {
        if (!isHost) return
        // Never leave the SIM Phone's microphone muted because of a remote Mute command once the call is over.
        if (snapshot.state == CallState.IDLE || snapshot.state == CallState.DISCONNECTED) simAudio.onCallEnded()
        if (!CallFeatures.isCallingEnabled()) return   // Stage 7: no call report is sent and no call restarts the Bluetooth listener
        ensureLink(snapshot)
        publish(snapshot)
    }

    override fun onCommandFailed(command: String, error: String, snapshot: CallSnapshot) {
        if (!isHost || !CallFeatures.isCallingEnabled()) return
        connection.send(CallMessages.state(snapshot, calls.controlAvailable, command, error, simAudio.read(), calls.activeDurationMs()))
    }

    private fun publish(s: CallSnapshot) {
        val control = calls.controlAvailable
        val audio = simAudio.read()
        connection.send(if (s.state == CallState.RINGING) CallMessages.incoming(s, control, audio) else CallMessages.state(s, control, simAudio = audio, activeMs = calls.activeDurationMs()))
    }

    /**
     * A call can arrive while the Bluetooth listener is not running (e.g. the app process was recreated by Telecom).
     * Start listening again, unless the user explicitly pressed Disconnect.
     */
    private fun ensureLink(s: CallSnapshot) {
        if (s.state == CallState.IDLE) return
        val st = connection.state
        if ((st == ConnectionManager.State.DISCONNECTED || st == ConnectionManager.State.ERROR) &&
            !connection.userStopped && BtPermissions.granted(context, Role.SIM_PHONE) && bluetooth.isEnabled
        ) connection.start(userInitiated = false)
    }
}
