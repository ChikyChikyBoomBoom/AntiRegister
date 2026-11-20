package com.example.companion.audio.call

import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.audio.diag.AudioDiagnostics
import com.example.companion.audio.diag.Evaluator
import com.example.companion.audio.hfp.BluetoothHfpManager
import com.example.companion.audio.hfp.Tri
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.calls.CallManager
import com.example.companion.calls.ClientCallController
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import com.example.companion.protocol.CallState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android wiring for [CallAudioSession]. Same class on both phones; it only feeds the session with facts:
 *  - call state: SIM Phone = Telecom ([CallManager]); Main Phone = what the SIM Phone reported ([ClientCallController],
 *    null = unknown = treated as no call, because audio must never be started on a guess)
 *  - Bluetooth link state ([ConnectionManager])
 *  - the capability resolved from the recorded Stage 4 / Stage 6 results ([CallAudioResolver])
 *
 * Stage 34A: with [downlinkBridge] = false (default) behaviour is unchanged: no backend, mode NONE, nothing is opened.
 * With it true the resolver may pick the ONE-WAY cellular downlink backend, which [backendFactory] creates from the shared
 * `audioTest.transport` (SIM Phone downlink -> Main Phone). Two-way audio / microphone uplink is NOT implemented
 * (`twoWayCellularAudio` stays NO). Privileged capture and Xiaomi HAL behaviour are UNVERIFIED.
 */
class AndroidCallAudioManager(
    private val prefs: AppPrefs,
    private val connection: ConnectionManager,
    private val callManager: CallManager,
    private val clientCalls: ClientCallController,
    private val hfp: BluetoothHfpManager,
    private val audioDiag: AudioDiagnostics,
    /** Stage 34A: creates the downlink backend (needs Context + the shared transport, supplied by CompanionApp). Default = none. */
    private val backendFactory: CallBackendFactory = CallBackendFactory.NONE,
    /** Stage 34A: feature switch, read on every decision. Default false = existing behaviour. */
    private val downlinkBridge: () -> Boolean = { false }
) : CallAudioManager, ConnectionManager.Listener, CallManager.Listener, ClientCallController.Listener {

    private val listeners = CopyOnWriteArrayList<CallAudioManager.Listener>()

    private val session = CallAudioSession(
        capability = { getCapability() },
        backendFactory = { mode -> createBackend(mode) },
        onChanged = { s -> listeners.forEach { it.onCallAudioChanged(s) } }
    )

    init {
        connection.addListener(this)
        callManager.addListener(this)
        clientCalls.addListener(this)
        sync()
    }

    override val status: CallAudioStatus get() = session.status

    override fun startCallAudio() { sync(); session.start() }
    override fun stopCallAudio() = session.stop()

    override fun isAvailable(): Boolean =
        getCapability().mode != AudioMode.NONE &&
            status.state != AudioSessionState.UNAVAILABLE && status.state != AudioSessionState.FAILED

    override fun getAudioMode(): AudioMode = getCapability().mode

    override fun getCapability(): CallAudioCapability = CallAudioResolver.resolve(
        // Stage 6 verdict. Until the probe has run, the documented answer is the same (NO), never an assumption of YES.
        hfpCellularAudio = hfp.latestCapability?.cellularCallAudioViaHfp ?: Tri.NO,
        stage4 = runCatching { Evaluator.evaluate(audioDiag.snapshot()) }.getOrNull(),
        cellularBridgeAvailable = false,
        downlinkBridge = downlinkEnabled()
    )

    /** Stage 34A: a failing switch lookup counts as disabled (never crashes, keeps the old behaviour). */
    private fun downlinkEnabled(): Boolean = AudioTransferFeatures.isTransferEnabled() && CallBackendSelection.switchOn(downlinkBridge)

    /**
     * Only the one-way downlink mode has a backend, and only while the switch is on. A missing role or any exception from the factory
     * ends as UNAVAILABLE / FAILED in [CallAudioSession] (it catches factory exceptions); nothing is thrown out of here.
     */
    private fun createBackend(mode: AudioMode): CallAudioBackend? =
        if (!AudioTransferFeatures.isTransferEnabled()) null   // Stage 1: no backend (transport / downlink) is ever created while disabled
        else CallBackendSelection.select(mode, downlinkBridge, prefs.role, backendFactory)

    override fun setMicrophoneMuted(muted: Boolean): Boolean = session.setMicrophoneMuted(muted)
    override fun setSpeakerOn(on: Boolean): Boolean = session.setSpeakerOn(on)

    override fun onAppVisibilityChanged(visible: Boolean) = session.onAppVisible(visible)

    override fun addListener(l: CallAudioManager.Listener) { listeners.add(l) }
    override fun removeListener(l: CallAudioManager.Listener) { listeners.remove(l) }

    // ---- inputs

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = sync()
    override fun onSnapshotChanged(snapshot: com.example.companion.protocol.CallSnapshot) = sync()
    override fun onCallUiChanged() = sync()

    private fun sync() {
        session.onLink(connection.state == ConnectionManager.State.CONNECTED)
        // Stage 29: the call id lets the session tell a NEW call from the same one (audio resources never carry over to another call).
        val snap = when (prefs.role) {
            Role.SIM_PHONE -> callManager.snapshot
            Role.MAIN_PHONE -> clientCalls.snapshot   // null = unknown = treated as no call
            null -> null
        }
        session.onCallState(snap?.state ?: CallState.IDLE, snap?.callId)
    }
}
