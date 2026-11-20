package com.example.companion.audio.transport

import android.content.Context
import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role

/**
 * Application-level owner of the Stage 5 transport test. Wires source/output factories, remembers the user's format
 * and buffer choices, and enforces the lifecycle rule: audio only runs while the test screen is visible.
 * Memory only; nothing is recorded or stored.
 */
class AudioTestController(
    context: Context,
    prefs: AppPrefs,
    link: AudioLink,
    private val stage4DiagRunning: () -> Boolean
) {
    private val appContext = context.applicationContext
    private val mic = MicrophoneSource(appContext)

    private val direction: () -> AudioDirection? = {
        when (prefs.role) {
            Role.MAIN_PHONE -> AudioDirection.MAIN_TO_SIM
            Role.SIM_PHONE -> AudioDirection.SIM_TO_MAIN
            null -> null
        }
    }
    /** Stage 39: the only uplink sink of this build refuses ("Not supported / not verified"). Cellular injection is not verified. */
    private val uplinkSinkFactory: () -> UplinkSink = { UnsupportedUplinkSink() }

    val transport = AudioTransport(
        link,
        localDirection = direction,
        outputFactory = { AudioTrackOutput() },
        uplinkSinkFactory = uplinkSinkFactory
    )

    /**
     * Stage 39: explicit UPLINK control over THIS transport and THIS microphone (no second pipeline). Nothing calls it
     * automatically; the app does not start microphone capture on its own.
     */
    val uplink = UplinkController(
        AudioTransportUplinkPort(transport, direction),
        UplinkMicrophoneSource(mic),
        uplinkSinkFactory
    )

    var sampleRate = AudioConfig.DEFAULT_RATE
    val isSending get() = transport.snapshot().txState != AudioTransport.TxState.IDLE

    /** Test A (Main Phone) / Test B (SIM Phone): this phone's microphone -> the other phone's speaker. */
    fun startMicToPeer(): String? {
        if (!AudioTransferFeatures.isTransferEnabled()) return AudioTransferFeatures.DISABLED_MESSAGE   // Stage 1: microphone is never opened
        if (stage4DiagRunning()) return "The Stage 4 diagnostic is using the microphone. Stop it first."
        return transport.startSending(mic, AudioConfig(sampleRate = sampleRate))
    }

    fun stopMic() = transport.stopSending(null)

    /** Call from the test screen's onStart/onStop. Leaving the screen releases microphone, speaker and buffers. */
    fun onScreenVisible(visible: Boolean) {
        transport.rxEnabled = visible && AudioTransferFeatures.isTransferEnabled()   // Stage 1: the receiver stays closed while disabled
        if (!visible) transport.stopAll("Test screen closed")
    }
}
