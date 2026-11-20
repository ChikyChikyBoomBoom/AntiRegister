package com.example.companion.audio.transport

/** Production [UplinkTransportPort]: forwards to the ONE [AudioTransport] (no second transport, no second link). Main thread. */
class AudioTransportUplinkPort(
    private val transport: AudioTransport,
    private val direction: () -> AudioDirection?
) : UplinkTransportPort {
    private var listener: ((AudioStartOutcome) -> Unit)? = null
    private val bridge = object : AudioTransport.StartListener {
        override fun onStartOutcome(outcome: AudioStartOutcome) { listener?.invoke(outcome) }
    }

    override val isLinkUp: Boolean get() = transport.isLinkUp
    override val localDirection: AudioDirection? get() = direction()
    override val isSending: Boolean get() = transport.snapshot().txState != AudioTransport.TxState.IDLE
    override var callUplinkRxEnabled: Boolean
        get() = transport.callUplinkRxEnabled
        set(value) { transport.callUplinkRxEnabled = value }

    private fun rxIsUplinkStream(): Boolean {
        val s = transport.snapshot()
        return s.rxState != AudioTransport.RxState.IDLE && s.rxDirection == FrameDirection.UPLINK
    }
    override val isReceivingUplink: Boolean get() = transport.callUplinkRxEnabled && rxIsUplinkStream()
    override fun stopReceivingUplink() { if (rxIsUplinkStream()) transport.stopReceiving("Uplink stopped", notifyPeer = true) }

    override fun startSending(source: AudioSource, config: AudioConfig): String? = transport.startSending(source, config)
    override fun stopSending(reason: String?, userInitiated: Boolean) =
        transport.stopSending(reason, notifyPeer = true, userInitiated = userInitiated)

    override fun claimOutcomeListener(listener: (AudioStartOutcome) -> Unit): Boolean {
        val current = transport.startListener
        if (current != null && current !== bridge) return false
        this.listener = listener
        transport.startListener = bridge
        return true
    }

    override fun releaseOutcomeListener() {
        if (transport.startListener === bridge) transport.startListener = null
        listener = null
    }
}
