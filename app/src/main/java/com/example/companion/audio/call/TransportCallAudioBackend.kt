package com.example.companion.audio.call

import com.example.companion.audio.transport.AudioConfig
import com.example.companion.audio.transport.AudioSource
import com.example.companion.audio.transport.AudioStartOutcome
import com.example.companion.audio.transport.AudioStartRequest
import com.example.companion.audio.transport.AudioStartRetry
import com.example.companion.audio.transport.CellularAudioConfiguration
import com.example.companion.audio.transport.CellularConfigDecision
import com.example.companion.audio.transport.CellularConfigEvent
import com.example.companion.audio.transport.CellularConfigFallback
import com.example.companion.audio.transport.CellularDownlinkError
import com.example.companion.audio.transport.RetryScheduler
import com.example.companion.audio.transport.RetryTimer
import com.example.companion.data.Role

/**
 * Stage 33C: the narrow view of the ONE shared `AudioTransport` (`audioTest.transport`) that the backend needs.
 * Exists so the backend logic is JVM-testable (AudioTransport itself uses Handler / SystemClock). The production implementation
 * ([AudioTransportCallPort]) only forwards to the existing transport; it creates no link and no transport. Main thread only.
 */
interface CallTransportPort {
    val isLinkUp: Boolean
    /** Gate for incoming call streams (Stage 31B `callRxEnabled`). Main Phone only. */
    var callRxEnabled: Boolean
    /** As `AudioTransport.startSending`: null = AUDIO_START sent, otherwise why not. */
    fun startSending(source: AudioSource, config: AudioConfig): String?
    /** As `AudioTransport.stopSending(reason, notifyPeer = true, userInitiated)`. */
    fun stopSending(reason: String?, userInitiated: Boolean)
    fun stopReceiving(reason: String?)
    /** Outgoing stream exists (negotiating or streaming). */
    val isSending: Boolean
    /** An incoming stream was opened (AUDIO_START accepted). Says NOTHING about audio data having arrived. */
    val isReceiving: Boolean
    /** Audio packets accepted by the receive jitter buffer of the CURRENT incoming stream (0 when none). This is actual audio data. */
    val receivedPackets: Long
    val lastError: String?
    /** Single slot, like `AudioTransport.startListener`. null clears it. */
    fun setStartListener(l: ((AudioStartOutcome) -> Unit)?)
    /** Single slot for "something in the transport changed" (poll [isSending] / [isReceiving] / [isLinkUp]). null removes it. */
    fun setChangeListener(l: (() -> Unit)?)
}

/** What the backend needs from the cellular capture: start one configuration, stop. [CellularDownlinkSource] is adapted by [ConfigurableCellularSource.of]. */
interface ConfigurableCellularSource {
    fun startWithConfiguration(configuration: CellularAudioConfiguration, listener: AudioSource.Listener): String?
    fun stop()
}

/** An [AudioSource] for exactly ONE configuration, so `AudioTransport` (which calls `start(config, listener)`) starts that source at that rate. */
class ConfiguredCellularSource(
    private val delegate: ConfigurableCellularSource,
    val configuration: CellularAudioConfiguration
) : AudioSource {
    override val name = "Cellular downlink (${configuration.label})"
    override fun start(config: AudioConfig, listener: AudioSource.Listener): String? =
        delegate.startWithConfiguration(configuration, listener)
    override fun stop() = delegate.stop()
}

/**
 * Stage 33C: one-way cellular downlink call audio. SIM Phone -> Bluetooth RFCOMM -> Main Phone, over the existing shared transport.
 *
 * ONE-WAY ONLY. [mode] is [AudioMode.CUSTOM_BLUETOOTH_TRANSPORT], but this backend NEVER calls `onActive` (that word means two-way media),
 * has no microphone uplink and does not claim cellular call audio routing is complete. Whether capture works on the POCO F3 is UNVERIFIED.
 *
 * SIM Phone: tries [CellularAudioConfiguration.ORDER] (Stage 33A) through [CellularConfigFallback]; each attempt is an [AudioStartRetry] run
 * (Stage 33B) that repeats the SAME request every 500 ms while the Main Phone answers NOT_LISTENING. A genuine source failure
 * ([AudioStartOutcome.SourceFailed]) is the only thing that moves to the next configuration (a new AUDIO_START). `onConnected` when the
 * stream is READY and the source started.
 * Main Phone: sets `callRxEnabled` first, then waits. AUDIO_START (stream opened) and actual audio data are tracked separately ([phase]);
 * `onConnected` only when the first audio packet was really received.
 *
 * Timeout ([STREAM_TIMEOUT_MS] = 12 s from `start()`): Main = no actual audio data yet (AUDIO_START alone does not stop it);
 * SIM = stream not READY yet (so an endless NOT_LISTENING retry cannot hang silently).
 *
 * Ownership caveat (Stage 31A item 4 is NOT implemented): stop() stops the transport's TX (SIM) / RX (Main). If the Audio Test screen
 * used the same direction at the same time it would be stopped too. A TX this backend never started (e.g. "Already sending") is not touched.
 *
 * Main thread only. Single use: after stop() / failure it does nothing. Android-free.
 */
class TransportCallAudioBackend(
    private val role: Role,
    private val port: CallTransportPort,
    private val cellular: ConfigurableCellularSource,
    private val scheduler: RetryScheduler,
    private val fallback: CellularConfigFallback = CellularConfigFallback()
) : CallAudioBackend {

    enum class Phase {
        IDLE,
        /** SIM: negotiating / retrying AUDIO_START. Main: armed, nothing received yet. */
        STARTING,
        /** Main only: AUDIO_START accepted, output open, but NO audio data yet. */
        STREAM_ANNOUNCED,
        /** Main: at least one audio packet received. SIM: READY, source running, frames being sent (receipt not confirmed). */
        AUDIO_FLOWING,
        STOPPED,
        FAILED
    }

    override val mode: AudioMode = AudioMode.CUSTOM_BLUETOOTH_TRANSPORT
    /** Not the microphone; unverified whether Android silences privileged cellular capture in the background (owner decision, see notes). */
    override val needsForeground: Boolean = false

    @Volatile var phase: Phase = Phase.IDLE; private set
    /** Why the backend failed, for diagnostics (null otherwise). */
    var failureDetail: String? = null; private set

    private var callback: CallAudioBackend.Callback? = null
    private var started = false
    private var closed = false
    private var txTouched = false
    private var sendingReady = false
    private var announced = false
    private var timeoutTimer: RetryTimer? = null
    private var pollTimer: RetryTimer? = null

    private val retry = AudioStartRetry(
        scheduler = scheduler,
        isLinkUp = { port.isLinkUp },
        isActive = { !closed },
        startAttempt = { req ->
            val err = port.startSending(req.source, req.config)
            if (err == null) txTouched = true
            err
        },
        abortAttempt = { port.stopSending(null, userInitiated = true) }
    )

    override fun start(callback: CallAudioBackend.Callback) {
        if (started || closed) return
        started = true
        this.callback = callback
        phase = Phase.STARTING
        port.setChangeListener { onTransportChanged() }
        timeoutTimer = scheduler.postDelayed(STREAM_TIMEOUT_MS) { onTimeout() }
        when (role) {
            Role.SIM_PHONE -> startSim()
            Role.MAIN_PHONE -> startMain()
        }
    }

    // ------------------------------------------------------------------ SIM Phone

    private fun startSim() {
        port.setStartListener { retry.onOutcome(it) }
        fallback.reset()
        val cfg = fallback.current ?: return fail(AudioUnavailableReason.AUDIO_INIT_FAILED, "No cellular configuration available")
        beginAttempt(cfg)
    }

    private fun beginAttempt(cfg: CellularAudioConfiguration) {
        val request = AudioStartRequest(ConfiguredCellularSource(cellular, cfg), cfg.toAudioConfig())
        retry.begin(request, object : AudioStartRetry.Listener {
            override fun onFinished(outcome: AudioStartOutcome, attempts: Int) = onAttemptFinished(outcome)
        })
    }

    private fun onAttemptFinished(outcome: AudioStartOutcome) {
        if (closed) return
        when (outcome) {
            is AudioStartOutcome.Ready -> {
                fallback.onEvent(CellularConfigEvent.Started)
                sendingReady = true
                cancelTimeout()
                phase = Phase.AUDIO_FLOWING
                callback?.onConnected()            // path open and frames are being sent; NEVER onActive (one-way)
            }
            is AudioStartOutcome.SourceFailed -> when (val d = fallback.onEvent(CellularConfigEvent.InitFailed(outcome.reason))) {
                is CellularConfigDecision.Fallback -> beginAttempt(d.next)      // new AUDIO_START with the next configuration
                is CellularConfigDecision.Exhausted -> {
                    val detail = d.attempts.joinToString("; ") { "${it.config.label}: ${it.reason}" }
                    val privileged = d.attempts.any { it.reason.contains(CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT.message) }
                    fail(if (privileged) AudioUnavailableReason.PERMISSION_OR_ROLE_UNAVAILABLE else AudioUnavailableReason.AUDIO_INIT_FAILED,
                        "Cellular capture failed in every configuration. $detail")
                }
                is CellularConfigDecision.Keep -> {}
            }
            is AudioStartOutcome.Refused ->
                fail(AudioUnavailableReason.AUDIO_INIT_FAILED, "Main Phone refused the audio stream: ${outcome.reason ?: "no reason given"}")
            is AudioStartOutcome.TimedOut ->
                fail(AudioUnavailableReason.AUDIO_INIT_FAILED, "Main Phone did not answer AUDIO_START")
            is AudioStartOutcome.LinkDown ->
                fail(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, "Bluetooth disconnected")
            is AudioStartOutcome.Rejected -> fail(
                when {
                    outcome.reason.startsWith("Bluetooth is not connected") -> AudioUnavailableReason.BLUETOOTH_DISCONNECTED
                    outcome.reason.startsWith("No role selected") -> AudioUnavailableReason.PERMISSION_OR_ROLE_UNAVAILABLE
                    else -> AudioUnavailableReason.AUDIO_INIT_FAILED
                }, "Transport would not start the stream: ${outcome.reason}")
            is AudioStartOutcome.Inactive -> {}
        }
    }

    // ------------------------------------------------------------------ Main Phone

    private fun startMain() {
        port.callRxEnabled = true                   // BEFORE any cellular AUDIO_START can be accepted
        if (!port.isLinkUp) return fail(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, "Bluetooth is not connected")
        schedulePoll()
    }

    private fun schedulePoll() {
        pollTimer = scheduler.postDelayed(POLL_MS) { pollTimer = null; poll() }
    }

    private fun poll() {
        if (closed) return
        if (!port.isLinkUp) return fail(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, "Bluetooth disconnected")
        if (port.receivedPackets > 0) {             // ACTUAL audio data, not just AUDIO_START
            announced = true
            cancelTimeout()
            phase = Phase.AUDIO_FLOWING
            callback?.onConnected()                  // never onActive
            return                                   // later loss is seen through onTransportChanged
        }
        if (port.isReceiving) { announced = true; phase = Phase.STREAM_ANNOUNCED }
        else { announced = false; phase = Phase.STARTING }
        schedulePoll()
    }

    // ------------------------------------------------------------------ events

    private fun onTransportChanged() {
        if (closed) return
        if (!port.isLinkUp) return fail(AudioUnavailableReason.BLUETOOTH_DISCONNECTED, "Bluetooth disconnected")
        when (role) {
            Role.SIM_PHONE -> if (sendingReady && !port.isSending)
                fail(AudioUnavailableReason.AUDIO_INIT_FAILED, port.lastError ?: "Audio transmission ended")
            Role.MAIN_PHONE -> if (phase == Phase.AUDIO_FLOWING && !port.isReceiving)
                fail(AudioUnavailableReason.AUDIO_INIT_FAILED, port.lastError ?: "Incoming audio stream ended")
        }
    }

    private fun onTimeout() {
        timeoutTimer = null
        if (closed) return
        val detail = when {
            role == Role.SIM_PHONE -> "Audio stream was not ready within ${STREAM_TIMEOUT_MS / 1000} s"
            announced || port.isReceiving -> "AUDIO_START arrived but no audio data within ${STREAM_TIMEOUT_MS / 1000} s"
            else -> "No audio stream arrived within ${STREAM_TIMEOUT_MS / 1000} s"
        }
        fail(AudioUnavailableReason.AUDIO_INIT_FAILED, detail)
    }

    // ------------------------------------------------------------------ stop / failure

    private fun cancelTimeout() { timeoutTimer?.cancel(); timeoutTimer = null }

    private fun fail(reason: AudioUnavailableReason, detail: String) {
        if (closed) return
        val cb = callback
        release(Phase.FAILED)
        failureDetail = detail
        cb?.onFailed(reason, detail)
    }

    /** Idempotent and synchronous; no callbacks afterwards. Safe to call repeatedly, before start(), and from a callback. */
    override fun stop() {
        if (closed) return
        release(Phase.STOPPED)
    }

    private fun release(finalPhase: Phase) {
        closed = true
        phase = finalPhase
        callback = null                                              // no callback after this point
        cancelTimeout()
        pollTimer?.cancel(); pollTimer = null
        runCatching { retry.cancel() }                              // pending retry timer + unanswered attempt
        runCatching { port.setChangeListener(null) }
        runCatching { port.setStartListener(null) }
        if (role == Role.SIM_PHONE) {
            // userInitiated = true: clears AudioTransport's automatic resume, so a later Bluetooth reconnect cannot restart the stream.
            if (txTouched) runCatching { port.stopSending(null, userInitiated = true) }
            runCatching { cellular.stop() }                         // releases AudioRecord (idempotent)
        } else {
            runCatching { port.callRxEnabled = false }
            runCatching { port.stopReceiving(null) }               // releases AudioTrack and the player thread
        }
    }

    companion object {
        /** No valid audio within this time -> onFailed. */
        const val STREAM_TIMEOUT_MS = 12_000L
        const val POLL_MS = 250L
    }
}
