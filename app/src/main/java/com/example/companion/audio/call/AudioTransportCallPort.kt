package com.example.companion.audio.call

import com.example.companion.audio.transport.AudioConfig
import com.example.companion.audio.transport.AudioSource
import com.example.companion.audio.transport.AudioStartOutcome
import com.example.companion.audio.transport.AudioTransport
import com.example.companion.audio.transport.CellularAudioConfiguration
import com.example.companion.audio.transport.CellularDownlinkSource
import com.example.companion.audio.transport.HandlerRetryScheduler
import com.example.companion.audio.transport.RetryScheduler
import com.example.companion.data.Role

/**
 * Stage 33C: forwards [CallTransportPort] to the ONE existing `AudioTransport` (pass `audioTest.transport`).
 * Creates no `BluetoothAudioLink`, no `AudioTransport`, and never touches the link listener.
 * It occupies the transport's single `startListener` slot only while a backend is started (cleared again on stop).
 * Not instantiated by anything yet (wiring is a later stage).
 */
class AudioTransportCallPort(private val transport: AudioTransport) : CallTransportPort {
    private var changeListener: AudioTransport.Listener? = null

    override val isLinkUp: Boolean get() = transport.isLinkUp
    override var callRxEnabled: Boolean
        get() = transport.callRxEnabled
        set(v) { transport.callRxEnabled = v }

    override fun startSending(source: AudioSource, config: AudioConfig): String? = transport.startSending(source, config)
    override fun stopSending(reason: String?, userInitiated: Boolean) =
        transport.stopSending(reason, notifyPeer = true, userInitiated = userInitiated)
    override fun stopReceiving(reason: String?) = transport.stopReceiving(reason)

    override val isSending: Boolean get() = transport.snapshot().txState != AudioTransport.TxState.IDLE
    override val isReceiving: Boolean get() = transport.snapshot().rxState != AudioTransport.RxState.IDLE
    override val receivedPackets: Long get() = transport.snapshot().rxReceived
    override val lastError: String? get() = transport.lastError

    override fun setStartListener(l: ((AudioStartOutcome) -> Unit)?) {
        transport.startListener = l?.let { f -> object : AudioTransport.StartListener { override fun onStartOutcome(outcome: AudioStartOutcome) = f(outcome) } }
    }

    override fun setChangeListener(l: (() -> Unit)?) {
        changeListener?.let { transport.removeListener(it) }
        changeListener = l?.let { f -> object : AudioTransport.Listener { override fun onTransportChanged() = f() } }
        changeListener?.let { transport.addListener(it) }
    }
}

/** Adapts the existing [CellularDownlinkSource] (Stage 32/33A `startWithConfiguration`) to the backend's seam. */
fun CellularDownlinkSource.asConfigurable(): ConfigurableCellularSource = object : ConfigurableCellularSource {
    override fun startWithConfiguration(configuration: CellularAudioConfiguration, listener: AudioSource.Listener): String? =
        this@asConfigurable.startWithConfiguration(configuration, listener)
    override fun stop() = this@asConfigurable.stop()
}

/** Production construction helper (not called yet). The caller passes `audioTest.transport` and the one [CellularDownlinkSource]. */
object TransportCallAudioBackendFactory {
    fun create(
        role: Role,
        transport: AudioTransport,
        source: CellularDownlinkSource,
        scheduler: RetryScheduler = HandlerRetryScheduler()
    ): TransportCallAudioBackend =
        TransportCallAudioBackend(role, AudioTransportCallPort(transport), source.asConfigurable(), scheduler)
}
