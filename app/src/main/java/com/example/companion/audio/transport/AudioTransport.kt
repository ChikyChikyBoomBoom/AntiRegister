package com.example.companion.audio.transport

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.protocol.Message
import com.example.companion.protocol.MessageType
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Snapshot of everything the diagnostics screen shows. Plain values, safe to read from any thread. */
data class TransportStats(
    val linkUp: Boolean,
    val txState: AudioTransport.TxState,
    val rxState: AudioTransport.RxState,
    val config: AudioConfig?,
    // outgoing stream (this phone's source -> peer)
    val txSent: Long, val txBytes: Long, val txQueueDropped: Long, val txPeak: Int,
    val peerReceived: Long?, val peerLost: Long?, val peerOverflow: Long?, val peerUnderruns: Long?,
    val peerBufferMs: Int?, val peerOutputMs: Int?, val rttMs: Long?, val estLatencyMs: Long?,
    // incoming stream (peer -> this phone's output)
    val rxReceived: Long, val rxLost: Long, val rxLate: Long, val rxOverflow: Long, val rxUnderruns: Long,
    val rxBufferMs: Int, val rxTargetMs: Int, val rxOutputMs: Int, val rxJitterMs: Double, val rxIgnored: Long,
    val rxSideLatencyMs: Long?,
    val lastError: String?,
    // Stage 38 (trailing, defaulted: older call sites keep compiling)
    val txDirection: FrameDirection? = null, val rxDirection: FrameDirection? = null,
    val txCongested: Boolean = false, val txBackPressureDrops: Long = 0, val rxRejected: Long = 0
)

/**
 * Two-way audio transport over an [AudioLink]. Knows nothing about Bluetooth, roles or screens, and nothing about
 * where audio comes from ([AudioSource]) or goes to ([AudioOutput]).
 *
 * Each phone can have at most ONE outgoing stream (TX) and ONE incoming stream (RX) at the same time, so
 * "Main -> SIM" and "SIM -> Main" can run independently or together.
 *
 * Handshake (control messages, JSON; audio itself is binary AudioPacket):
 *   sender   --AUDIO_START{sid,dir,config}-->  receiver   (receiver opens output; refuses unless [rxEnabled] or [callRxEnabled])
 *   sender  <--AUDIO_READY{sid}--              receiver   (only then does the sender touch its microphone)
 *   sender  --binary frames-->                 receiver
 *   sender  <--AUDIO_STATS{...} ~1/s--         receiver   (also gives the sender an RTT / latency estimate)
 *   either  --AUDIO_STOP{sid, from, reason?}-->            (reason present = error)
 * A remote phone can never switch on THIS phone's microphone: START only asks the receiver to PLAY.
 *
 * Threading: all public methods and all listener callbacks run on the MAIN thread. Capture, playback and Bluetooth
 * read threads only touch thread-safe state (atomics, JitterBuffer) and post to main when something fails.
 */
class AudioTransport(
    private val link: AudioLink,
    private val localDirection: () -> AudioDirection?,
    private val outputFactory: () -> AudioOutput,
    jitterConfig: JitterConfig = JitterConfig(),
    /** Stage 39: the sink for an UPLINK call stream (only used while [callUplinkRxEnabled]). Never falls back to [outputFactory]. */
    private val uplinkSinkFactory: () -> UplinkSink = { UnsupportedUplinkSink() },
    /** Stage 1: kill switch, read on every decision. Default = [AudioTransferFeatures.isTransferEnabled] (false in this build). */
    private val audioTransferEnabled: () -> Boolean = AudioTransferFeatures::isTransferEnabled
) : AudioLink.Listener {

    enum class TxState { IDLE, NEGOTIATING, STREAMING }
    enum class RxState { IDLE, PREBUFFERING, PLAYING }

    interface Listener { fun onTransportChanged() }

    /** Stage 33B: told how each outgoing AUDIO_START ended (see [AudioStartOutcome]). Main thread. Single slot, nothing is set by default. */
    interface StartListener { fun onStartOutcome(outcome: AudioStartOutcome) }
    @Volatile var startListener: StartListener? = null
    val isLinkUp: Boolean get() = link.isUp

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun changed() { listeners.forEach { it.onTransportChanged() } }

    @Volatile var lastError: String? = null; private set
    @Volatile var jitterConfig: JitterConfig = jitterConfig
        set(v) { field = v; rx?.jitter?.config = v; changed() }
    /** The UI sets this while the test screen is visible. When false, incoming streams are refused. */
    var rxEnabled = false
    /**
     * Stage 31B (reserved for one-way cellular call audio reception; nothing sets it yet). When true, incoming streams
     * are accepted even though the test screen is not open. Independent of [rxEnabled]: the test screen's
     * onScreenVisible() never touches it. Default false = behaviour exactly as before. Main thread only.
     * It only opens the gate; it does not select an output or a source and does not stop anything by itself.
     */
    var callRxEnabled = false
    /**
     * Stage 39 (SIM Phone only; nothing sets it by default, only [UplinkController.armReceiver]). When true, a MAIN_TO_SIM (UPLINK)
     * stream is handed to the [UplinkSink] from `uplinkSinkFactory` instead of the local speaker. With the only sink of this build
     * ([UnsupportedUplinkSink]) the stream is refused with "Not supported / not verified" before anything is opened or stopped,
     * so the Main Phone microphone is never started. Default false = behaviour exactly as before. Main thread only.
     */
    var callUplinkRxEnabled = false

    // ------------------------------------------------------------------ session objects

    private inner class Tx(val sid: Int, val dir: AudioDirection, val source: AudioSource, val config: AudioConfig) : AudioSource.Listener {
        @Volatile var state = TxState.NEGOTIATING
        val t0 = SystemClock.elapsedRealtime()
        val codec = Codecs.forId(config.codec)
        val seq = AtomicLong(); val sent = AtomicLong(); val bytes = AtomicLong()
        val dropBase = link.txDropped
        @Volatile var peak = 0
        @Volatile var peer: JSONObject? = null
        @Volatile var rtt: Long? = null
        val pressure = SendBackPressure()

        override fun onFrame(pcm: ByteArray) {
            if (state != TxState.STREAMING || tx !== this) return
            var p = 0
            var i = 0
            while (i + 1 < pcm.size) { val v = abs((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)); if (v > p) p = v; i += 2 }
            peak = p
            val ts = SystemClock.elapsedRealtime() - t0
            val pkt = AudioPacket(dir, config.codec, sid, seq.getAndIncrement(), ts, codec.encode(pcm)).encode()
            pressure.record(link.sendAudio(pkt))
            sent.incrementAndGet(); bytes.addAndGet(pkt.size.toLong())
        }

        override fun onError(message: String) {
            main.post { if (tx === this) { fail("Microphone/source stopped: $message"); stopSending(null, notifyPeer = true, userInitiated = false) } }
        }
    }

    private inner class Rx(val sid: Int, val dir: AudioDirection, val config: AudioConfig, val output: AudioOutput) {
        val jitter = JitterBuffer(config.frameMs, jitterConfig)
        val codec = Codecs.forId(config.codec)
        @Volatile var running = true
        var player: Thread? = null
    }

    @Volatile private var tx: Tx? = null
    @Volatile private var rx: Rx? = null
    private val rxIgnored = AtomicLong()
    private var nextSid = 0
    /** What the user last asked to send; lets a Bluetooth reconnect resume the stream. Cleared by an explicit stop. */
    private var desired: Pair<AudioSource, AudioConfig>? = null

    init { link.setListener(this); link.setAudioReceiver(::onAudioBytes) }

    // ------------------------------------------------------------------ outgoing stream

    /** Returns null when negotiation was started, otherwise why it could not be. */
    fun startSending(source: AudioSource, config: AudioConfig): String? {
        if (!audioTransferEnabled()) return AudioTransferFeatures.DISABLED_MESSAGE   // Stage 1: nothing is recorded, no AUDIO_START is sent
        if (tx != null) return "Already sending"
        if (!link.isUp) return "Bluetooth is not connected"
        val dir = localDirection() ?: return "No role selected"
        UplinkRoute.sourceDirectionError(source, dir)?.let { return it }   // Stage 39: only sources that declare a direction
        lastError = null
        desired = source to config
        nextSid = (nextSid % 255) + 1
        val t = Tx(nextSid, dir, source, config)
        tx = t
        link.sendControl(Message(MessageType.AUDIO_START,
            JSONObject().put("sid", t.sid).put("dir", dir.id).put("config", config.toJson())))
        main.postDelayed({ if (tx === t && t.state == TxState.NEGOTIATING) {
            fail(AudioTransportMessages.NEGOTIATE_TIMEOUT)
            stopSending(null, notifyPeer = true, userInitiated = false)
            startListener?.onStartOutcome(AudioStartOutcome.TimedOut)
        } }, NEGOTIATE_TIMEOUT_MS)
        changed()
        return null
    }

    /** [reason] non-null is sent to the peer as an error. [userInitiated] also cancels automatic resume. */
    fun stopSending(reason: String?, notifyPeer: Boolean = true, userInitiated: Boolean = true) {
        if (userInitiated) desired = null
        val t = tx ?: return
        tx = null
        t.state = TxState.IDLE
        t.source.stop()                                   // releases the microphone
        if (notifyPeer && link.isUp) link.sendControl(stopMessage(t.sid, "tx", reason))
        peerCache = t.peer
        changed()
    }

    private var peerCache: JSONObject? = null

    private fun onReady(sid: Int) {
        val t = tx ?: return
        if (t.sid != sid || t.state != TxState.NEGOTIATING) return
        val err = t.source.start(t.config, t)
        if (err != null) {
            fail(err); stopSending(err, notifyPeer = true, userInitiated = false)
            startListener?.onStartOutcome(AudioStartOutcome.SourceFailed(err)); return
        }
        t.state = TxState.STREAMING
        changed()
        startListener?.onStartOutcome(AudioStartOutcome.Ready)
    }

    // ------------------------------------------------------------------ incoming stream

    private fun onStart(o: JSONObject) {
        val sid = o.optInt("sid", -1)
        val dir = AudioDirection.fromId(o.optInt("dir", -1))
        val config = AudioConfig.fromJson(o.optJSONObject("config"))
        fun refuse(why: String, record: Boolean = true) {
            if (record) fail(why)
            link.sendControl(stopMessage(sid, "rx", why))
        }
        if (!audioTransferEnabled()) return refuse(AudioTransferFeatures.DISABLED_MESSAGE, record = false)   // Stage 1: no output, player thread or jitter buffer is created
        if (sid !in 1..255 || dir == null || config == null) return refuse("Unsupported or invalid stream request")
        AudioTransportMessages.refusalReason(rxEnabled, callRxEnabled, callUplinkRxEnabled)?.let { return refuse(it, record = false) }
        val mine = localDirection() ?: return refuse("No role selected")
        if (dir != mine.opposite) return refuse("Stream direction does not match this phone's role")
        // Stage 39: an UPLINK call stream needs a verified sink. Checked BEFORE stopReceiving so a refused uplink request cannot
        // tear down an unrelated running stream, and with no fallback to outputFactory() (that would play the Main Phone's
        // microphone on this phone's own speaker while looking healthy).
        var uplinkSink: UplinkSink? = null
        if (UplinkRoute.isUplinkStream(callUplinkRxEnabled, dir)) {
            val sink = uplinkSinkFactory()
            if (!sink.capability.isSupported) { sink.close(); return refuse(UplinkMessages.NOT_SUPPORTED) }
            uplinkSink = sink
        }
        stopReceiving(null, notifyPeer = false)           // a new stream replaces an old one
        val output: AudioOutput = uplinkSink ?: outputFactory()
        val err = output.open(config)
        if (err != null) { output.close(); return refuse(err) }   // close() is idempotent; frees anything a failed open left
        val r = Rx(sid, dir, config, output)
        rxIgnored.set(0)
        rx = r
        r.player = Thread({ playLoop(r) }, "audio-play").also { it.start() }
        link.sendControl(Message(MessageType.AUDIO_READY, JSONObject().put("sid", sid)))
        main.postDelayed(statsTick, STATS_INTERVAL_MS)
        changed()
    }

    private fun playLoop(r: Rx) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (r.running) {
            val frame = r.jitter.take(r.config.frameMs.toLong()) ?: continue
            if (!r.output.write(frame)) {
                if (r.running) main.post { if (rx === r) { fail("Audio output failed during playback"); stopReceiving("Audio output failed", notifyPeer = true) } }
                break
            }
        }
    }

    fun stopReceiving(reason: String?, notifyPeer: Boolean = true) {
        val r = rx ?: return
        rx = null
        r.running = false
        main.removeCallbacks(statsTick)
        r.jitter.clear()                                  // wakes the player
        r.output.close()                                  // unblocks a write in progress and releases AudioTrack
        r.player?.let { if (it !== Thread.currentThread()) runCatching { it.join(300) } }
        if (notifyPeer && link.isUp) link.sendControl(stopMessage(r.sid, "rx", reason))
        changed()
    }

    /** Bluetooth read thread. Must stay cheap; never touches views or the main thread. */
    private fun onAudioBytes(bytes: ByteArray) {
        val r = rx ?: return
        val res = AudioFrameFilter.classify(bytes, r.dir, r.sid, r.config.codec)
        val p = res.packet
        if (res.verdict != AudioFrameFilter.Verdict.ACCEPT || p == null) { rxIgnored.incrementAndGet(); return }
        r.jitter.put(p.sequence, r.codec.decode(p.payload), p.timestampMs)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val r = rx ?: return
            val j = r.jitter
            val now = SystemClock.elapsedRealtime()
            link.sendControl(Message(MessageType.AUDIO_STATS, JSONObject()
                .put("sid", r.sid).put("rx", j.received).put("lost", j.lostGap).put("late", j.lateDiscarded)
                .put("over", j.overflowDropped).put("under", j.underruns)
                .put("bufMs", j.depthMs).put("outMs", r.output.bufferMs)
                .put("echoTs", j.lastTimestampMs).put("holdMs", if (j.lastArrivalElapsed == 0L) 0 else now - j.lastArrivalElapsed)))
            main.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private fun onStats(o: JSONObject) {
        val t = tx ?: return
        if (o.optInt("sid", -1) != t.sid) return
        t.peer = o
        if (o.optLong("rx", 0) > 0) {
            val sentAt = t.t0 + o.optLong("echoTs")
            val rtt = SystemClock.elapsedRealtime() - sentAt - o.optLong("holdMs")
            if (rtt >= 0) t.rtt = rtt
        }
        changed()
    }

    // ------------------------------------------------------------------ control + link events (main thread)

    override fun onControl(message: Message) {
        val o = message.payload
        when (message.type) {
            MessageType.AUDIO_START -> onStart(o)
            MessageType.AUDIO_READY -> onReady(o.optInt("sid", -1))
            MessageType.AUDIO_STATS -> onStats(o)
            MessageType.AUDIO_STOP -> onPeerStop(o)
            else -> {}
        }
    }

    private fun onPeerStop(o: JSONObject) {
        val sid = o.optInt("sid", -1)
        val reason = o.optString("reason", "").ifEmpty { null }
        if (o.optString("from") == "tx") {                       // the peer's sender stopped -> our receiver
            if (rx?.sid == sid) { if (reason != null) fail("Peer stopped sending: $reason"); stopReceiving(null, notifyPeer = false) }
        } else {                                                  // the peer's receiver stopped/refused -> our sender
            val t = tx
            if (t != null && t.sid == sid) {
                val negotiating = t.state == TxState.NEGOTIATING          // a refusal of AUDIO_START, not a stop of a running stream
                if (reason != null) fail("Peer: $reason"); stopSending(null, notifyPeer = false, userInitiated = reason != null)
                if (negotiating) startListener?.onStartOutcome(AudioStartOutcome.Refused(reason))
            }
        }
    }

    override fun onLinkUp() {
        val d = desired ?: return
        if (!audioTransferEnabled()) { desired = null; return }   // Stage 1: never resume a stream while audio transfer is disabled
        if (tx == null) startSending(d.first, d.second)           // resume after a Bluetooth reconnect
    }

    override fun onLinkDown() {
        // Release microphone and speaker immediately; keep `desired` so a reconnect can resume.
        val wasActive = tx != null || rx != null
        stopSending(null, notifyPeer = false, userInitiated = false)
        stopReceiving(null, notifyPeer = false)
        if (wasActive) fail("Bluetooth disconnected - audio stopped")
        startListener?.onStartOutcome(AudioStartOutcome.LinkDown)
    }

    /** Stops everything and releases every audio resource. [userInitiated] also cancels automatic resume. */
    fun stopAll(reason: String? = null, userInitiated: Boolean = true) {
        stopSending(reason, notifyPeer = true, userInitiated = userInitiated)
        stopReceiving(reason, notifyPeer = true)
        main.removeCallbacksAndMessages(null)
    }

    private fun fail(message: String) { lastError = message; changed() }

    private fun stopMessage(sid: Int, from: String, reason: String?) = Message(MessageType.AUDIO_STOP,
        JSONObject().put("sid", sid).put("from", from).apply { if (reason != null) put("reason", reason) })

    // ------------------------------------------------------------------ stats

    fun snapshot(): TransportStats {
        val t = tx; val r = rx
        val peer = t?.peer ?: peerCache.takeIf { t == null }
        val frameMs = (t?.config ?: r?.config)?.frameMs ?: 0
        val peerBuf = peer?.optInt("bufMs"); val peerOut = peer?.optInt("outMs")
        val rtt = t?.rtt
        val est = if (t != null && rtt != null && peerBuf != null && peerOut != null)
            frameMs + rtt / 2 + peerBuf + peerOut else null
        val rxBuf = r?.jitter?.depthMs ?: 0
        val rxOut = r?.output?.bufferMs ?: 0
        return TransportStats(
            linkUp = link.isUp,
            txState = t?.state ?: TxState.IDLE,
            rxState = when { r == null -> RxState.IDLE; r.jitter.isPlaying -> RxState.PLAYING; else -> RxState.PREBUFFERING },
            config = t?.config ?: r?.config,
            txSent = t?.sent?.get() ?: 0, txBytes = t?.bytes?.get() ?: 0,
            txQueueDropped = if (t != null) link.txDropped - t.dropBase else 0, txPeak = t?.peak ?: 0,
            peerReceived = peer?.optLong("rx"), peerLost = peer?.optLong("lost"),
            peerOverflow = peer?.optLong("over"), peerUnderruns = peer?.optLong("under"),
            peerBufferMs = peerBuf, peerOutputMs = peerOut, rttMs = rtt, estLatencyMs = est,
            rxReceived = r?.jitter?.received ?: 0, rxLost = r?.jitter?.lostGap ?: 0, rxLate = r?.jitter?.lateDiscarded ?: 0,
            rxOverflow = r?.jitter?.overflowDropped ?: 0, rxUnderruns = r?.jitter?.underruns ?: 0,
            rxBufferMs = rxBuf, rxTargetMs = jitterConfig.targetMs, rxOutputMs = rxOut,
            rxJitterMs = r?.jitter?.jitterMs ?: 0.0, rxIgnored = rxIgnored.get(),
            rxSideLatencyMs = if (r != null) (r.config.frameMs + rxBuf + rxOut).toLong() else null,
            lastError = lastError,
            txDirection = t?.dir?.frameDirection, rxDirection = r?.dir?.frameDirection,
            txCongested = t?.pressure?.congested ?: false, txBackPressureDrops = t?.pressure?.dropped ?: 0,
            rxRejected = r?.jitter?.rejected ?: 0
        )
    }

    private companion object {
        const val NEGOTIATE_TIMEOUT_MS = 4_000L
        const val STATS_INTERVAL_MS = 1_000L
    }
}
