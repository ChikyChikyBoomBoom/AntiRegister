package com.example.companion.audio.transport

/** What [UplinkController] needs from the transport. Android-free so it can be faked; production is [AudioTransportUplinkPort]. */
interface UplinkTransportPort {
    val isLinkUp: Boolean
    /** This phone's outgoing direction (Main Phone = MAIN_TO_SIM, SIM Phone = SIM_TO_MAIN), null = no role. */
    val localDirection: AudioDirection?
    /** True while this phone has an outgoing stream (negotiating or streaming), whoever started it. */
    val isSending: Boolean
    var callUplinkRxEnabled: Boolean
    fun startSending(source: AudioSource, config: AudioConfig): String?
    fun stopSending(reason: String?, userInitiated: Boolean)
    /** The transport has ONE start-outcome slot. False = somebody else holds it (nothing was changed). */
    fun claimOutcomeListener(listener: (AudioStartOutcome) -> Unit): Boolean
    /** Releases the slot only if this controller holds it. */
    fun releaseOutcomeListener()
    /** Stage 40 (SIM Phone): true while an UPLINK stream (MAIN_TO_SIM) is being received because this phone armed it. Default false. */
    val isReceivingUplink: Boolean get() = false
    /** Stage 40 (SIM Phone): ends an incoming UPLINK stream and releases its sink. Default no-op. */
    fun stopReceivingUplink() {}
}

enum class UplinkPhase { IDLE, STARTING, STREAMING, NOT_SUPPORTED, FAILED }

/** [detail] is human readable and never contains audio. */
class UplinkStatus(val phase: UplinkPhase, val capability: UplinkCapability, val detail: String?)

/**
 * Stage 39: explicit, user-triggered control of the UPLINK direction (Main Phone microphone -> existing [AudioTransport] ->
 * existing [BluetoothAudioLink] -> SIM Phone -> [UplinkSink]). It owns no audio pipeline of its own: the source is the shared
 * microphone (wrapped by [UplinkMicrophoneSource]) and the stream is the transport's single TX stream.
 *
 * - Nothing here starts by itself. The microphone is touched only after [start] was called AND the SIM Phone answered READY,
 *   which a sink refusing with [UplinkMessages.NOT_SUPPORTED] never does (so with [UnsupportedUplinkSink] the microphone is never opened).
 * - Duplicate sessions: [start] refuses while a run is active, while another outgoing stream exists, and while another owner holds
 *   the transport's start-outcome slot.
 * - Bluetooth disconnection: the transport already stops the source; this class also clears the transport's automatic-resume
 *   request, so the microphone does NOT restart by itself after a reconnect.
 * - An uplink failure never touches the DOWNLINK path (no `CallAudioBackend` callback is used here).
 * Main thread only (like [AudioTransport]).
 */
class UplinkController(
    private val port: UplinkTransportPort,
    private val source: DirectionalAudioSource,
    private val sinkFactory: () -> UplinkSink = { UnsupportedUplinkSink() },
    private val config: AudioConfig = AudioConfig()
) {
    private var phase = UplinkPhase.IDLE
    private var detail: String? = null
    private var ownsTx = false
    private var ownsSlot = false

    // ------------------------------------------------------------------ Main Phone: source side

    /** Returns null when negotiation started, otherwise why not. The result of the negotiation arrives through [status]. */
    fun start(): String? {
        refreshIfEnded()
        if (phase == UplinkPhase.STARTING || phase == UplinkPhase.STREAMING) return "Uplink already running"
        if (port.localDirection != source.direction) return "Uplink source runs on the Main Phone only"
        if (!port.isLinkUp) return fail("Bluetooth is not connected")
        if (port.isSending) return "Another outgoing audio stream is already running"
        if (!port.claimOutcomeListener(::onOutcome)) return "Another audio start is in progress"
        ownsSlot = true
        val err = port.startSending(source, config)
        if (err != null) { releaseSlot(); return fail(err) }
        ownsTx = true
        phase = UplinkPhase.STARTING
        detail = null
        return null
    }

    /** Idempotent. Stops only a stream this controller started, never a foreign one (e.g. the Audio Test screen's). */
    fun stop() {
        if (ownsTx) port.stopSending(null, userInitiated = true)
        ownsTx = false
        releaseSlot()
        phase = UplinkPhase.IDLE
        detail = null
    }

    private fun onOutcome(outcome: AudioStartOutcome) {
        when (outcome) {
            is AudioStartOutcome.Ready -> { phase = UplinkPhase.STREAMING; detail = null; return }
            is AudioStartOutcome.SourceFailed -> fail("Microphone could not start: ${outcome.reason}")
            is AudioStartOutcome.Refused ->
                if (UplinkMessages.isUplinkNotSupported(outcome.reason)) {
                    phase = UplinkPhase.NOT_SUPPORTED
                    detail = "${UplinkMessages.NOT_SUPPORTED} (peer refused)"
                } else fail("Peer refused: ${outcome.reason ?: "no reason given"}")
            is AudioStartOutcome.TimedOut -> fail("The other phone did not answer")
            is AudioStartOutcome.LinkDown -> fail("Bluetooth disconnected - uplink stopped")
            is AudioStartOutcome.Rejected -> fail(outcome.reason)
            is AudioStartOutcome.Inactive -> fail("Uplink inactive")
        }
        // Terminal outcome: the transport already stopped its TX. Clear its automatic-resume request so nothing restarts by itself.
        port.stopSending(null, userInitiated = true)
        ownsTx = false
        releaseSlot()
    }

    // ------------------------------------------------------------------ SIM Phone: sink side

    /** Arms the SIM Phone to receive an UPLINK stream into the [UplinkSink]. Does not open anything. */
    fun armReceiver(): String? {
        if (port.localDirection != source.direction.opposite) return "Uplink sink runs on the SIM Phone only"
        port.callUplinkRxEnabled = true
        return null
    }

    /** Stage 40: also ends an incoming uplink stream (read BEFORE the flag is cleared, because the port only reports it while armed). */
    fun disarmReceiver() {
        val receiving = port.isReceivingUplink
        port.callUplinkRxEnabled = false
        if (receiving) port.stopReceivingUplink()
    }
    val receiverArmed: Boolean get() = port.callUplinkRxEnabled
    /** Stage 40 (SIM Phone): an UPLINK stream from the Main Phone microphone is currently being received. */
    val receivingUplink: Boolean get() = port.isReceivingUplink

    // ------------------------------------------------------------------ reporting

    /** What this phone can say about uplink injection. Never "supported" in this build. */
    fun capability(): UplinkCapability = when (port.localDirection) {
        source.direction.opposite -> sinkFactory().let { sink -> try { sink.capability } finally { sink.close() } }
        source.direction -> UplinkCapability.notSupported(UplinkUnsupportedReason.PEER_NOT_VERIFIED,
            "peer has not verified an uplink sink (not asked, or the peer refused)")
        else -> UplinkCapability.notSupported(UplinkUnsupportedReason.PEER_NOT_VERIFIED, "no role selected")
    }

    fun status(): UplinkStatus {
        refreshIfEnded()
        return UplinkStatus(phase, capability(), detail)
    }

    private fun fail(why: String): String { phase = UplinkPhase.FAILED; detail = why; return why }

    private fun releaseSlot() { if (ownsSlot) port.releaseOutcomeListener(); ownsSlot = false }

    /**
     * The peer (or a microphone failure, or the Test screen) can end a running stream without an outcome callback; notice that lazily.
     * Stage 41 fix: the transport keeps its automatic-resume request ([AudioTransport] `desired`) when a stream ends by itself
     * (`Tx.onError`, a peer stop without a reason), and would then restart the microphone after the next Bluetooth reconnect without
     * any user action. If this controller started that stream, clear the request here (state first, so a callback cannot recurse;
     * the transport's `stopSending` returns early when it has no stream, so it does not touch anything else).
     */
    private fun refreshIfEnded() {
        if ((phase == UplinkPhase.STARTING || phase == UplinkPhase.STREAMING) && !port.isSending) {
            val owned = ownsTx
            ownsTx = false
            releaseSlot()
            phase = UplinkPhase.IDLE
            detail = "Uplink ended"
            if (owned) port.stopSending(null, userInitiated = true)
        }
    }
}
