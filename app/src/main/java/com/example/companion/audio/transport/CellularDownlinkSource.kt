package com.example.companion.audio.transport

/** Why [CellularDownlinkSource] did not start. [message] is the human-readable headline returned by `start()`. */
enum class CellularDownlinkError(val message: String) {
    /** Every tried audio source failed; most likely the privileged system permission is missing (a normal APK cannot hold it). */
    NEEDS_CAPTURE_AUDIO_OUTPUT("needs CAPTURE_AUDIO_OUTPUT (privileged app)")
}

/**
 * Captures the SIM Phone's cellular downlink and delivers it as [AudioSource] frames (exactly [AudioConfig.frameBytes] of PCM16 mono).
 *
 * Stage 32B selection: [CellularSourceSelector] tries VOICE_DOWNLINK, then VOICE_CALL only if the first failed to open / start.
 * Stage 32C lifecycle: the selected, already started recorder is read on its own thread until `stop()`, a read error, or a listener failure.
 * No Bluetooth, no AudioTrack, no uplink here. Whether either source delivers real (non-silent) audio on the POCO F3 / Xiaomi firmware,
 * and whether holding CAPTURE_AUDIO_OUTPUT is enough, is UNVERIFIED.
 *
 * Contract (as [MicrophoneSource]):
 * - `start()` returns null once the recorder is recording and the capture thread runs; otherwise a reason, with everything released.
 *   If both sources fail the reason starts with [CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT]`.message`, followed by the underlying reasons.
 * - One object per capture ([Capture]): a late-finishing old thread can neither stop nor keep alive a newer capture.
 * - The recorder is released ONLY by the capture thread (never while it may be inside `read()`), in `finally`, on every exit path.
 * - `stop()` is idempotent, makes `read()` return, clears the running flag (so no further `onFrame` / `onError` is started), and waits up to
 *   [STOP_JOIN_MS] for the thread. A new `start()` is refused while the old thread is still alive.
 * - A failure (read error, exception, listener exception) calls `listener.onError` once, after the recorder is released. Nothing is thrown to the caller.
 *
 * Android-free on purpose (JVM-testable): the opener and the permission check are injected.
 * [threadInit] runs first on the capture thread; wire it later with `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)`.
 */
class CellularDownlinkSource(
    private val hasCapturePermission: () -> Boolean,
    opener: CellularRecorderOpener,
    private val threadInit: () -> Unit = {}
) : CellularAudioSource {
    override val name = "Cellular downlink"

    private class Capture(val rec: CellularRecorder) { @Volatile var running = true }

    private val selector = CellularSourceSelector(opener)
    private var current: Capture? = null
    private var thread: Thread? = null

    /** The headline of the most recent refused start, or null (cleared by a successful start). Never cleared by `stop()`. */
    @Volatile var lastError: CellularDownlinkError? = null
        private set
    /** Full text of the most recent refusal including the underlying reasons, or null. */
    @Volatile var lastErrorDetail: String? = null
        private set
    /** Failed attempts of the most recent source selection (also when a later source succeeded), in order. */
    @Volatile var lastAttempts: List<CellularAttempt> = emptyList()
        private set
    /** The source of the most recent successful start, or null. */
    @Volatile var selectedSource: CellularCaptureSource? = null
        private set
    /** Text of the most recent runtime failure (read error / exception) that was reported through `onError`, or null. */
    @Volatile var lastRuntimeError: String? = null
        private set

    override fun start(config: AudioConfig, listener: AudioSource.Listener): String? =
        startWith(config, listener, CellularSourceSelector.ORDER)

    /**
     * Stage 33A: start exactly ONE [CellularAudioConfiguration] (its source at its sample rate, no internal source fallback), so the
     * caller's [CellularConfigFallback] controls the attempt order. Same contract and return value as [start].
     */
    fun startWithConfiguration(configuration: CellularAudioConfiguration, listener: AudioSource.Listener): String? =
        startWith(configuration.toAudioConfig(), listener, listOf(configuration.source))

    @Synchronized
    private fun startWith(config: AudioConfig, listener: AudioSource.Listener, order: List<CellularCaptureSource>): String? {
        if (current?.running == true) return "Cellular downlink already running"
        if (thread?.isAlive == true) return "Cellular downlink is still being released - try again in a moment"
        selectedSource = null
        lastRuntimeError = null
        val sel = try { selector.select(config, order) }
                  catch (t: Throwable) { return fail("Cellular source selection crashed: ${t.javaClass.simpleName}: ${t.message}", null) }
        return when (sel) {
            is CellularSelection.AllFailed -> {
                lastAttempts = sel.attempts
                val perm = if (runCatching { hasCapturePermission() }.getOrDefault(false)) "granted" else "not granted"
                val reasons = sel.attempts.joinToString("; ") { "${it.source.label}: ${it.reason}" }
                fail("${CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT.message} [$reasons; CAPTURE_AUDIO_OUTPUT $perm]",
                    CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT)
            }
            is CellularSelection.Selected -> {
                lastAttempts = sel.failedBefore
                val cap = Capture(sel.recorder)
                val t = try {
                    Thread({ capture(cap, config, listener) }, THREAD_NAME).also { it.start() }
                } catch (e: Throwable) {
                    // The thread never ran, so nobody else can be inside read(): release here.
                    runCatching { sel.recorder.stop() }; runCatching { sel.recorder.release() }
                    return fail("Could not start cellular capture thread: ${e.javaClass.simpleName}: ${e.message}", null)
                }
                // The thread's finally needs this monitor, so it cannot clear `current` before we set it.
                current = cap
                thread = t
                selectedSource = sel.source
                lastError = null
                lastErrorDetail = null
                null
            }
        }
    }

    private fun fail(detail: String, error: CellularDownlinkError?): String {
        lastError = error
        lastErrorDetail = detail
        return detail
    }

    private fun capture(cap: Capture, config: AudioConfig, listener: AudioSource.Listener) {
        var error: String? = null
        try {
            threadInit()
            val rec = cap.rec
            var emptyReads = 0
            while (cap.running) {
                val frame = ByteArray(config.frameBytes)
                var off = 0
                while (cap.running && off < frame.size) {
                    val n = rec.read(frame, off, frame.size - off)
                    if (n < 0) { if (cap.running) error = "Cellular downlink read failed: ${describeReadError(n)}"; break }
                    if (n == 0) {
                        if (++emptyReads > MAX_EMPTY_READS) { if (cap.running) error = "Cellular downlink delivered no data ($MAX_EMPTY_READS empty reads)"; break }
                        try { Thread.sleep(EMPTY_READ_PAUSE_MS) } catch (_: InterruptedException) { break }
                        continue
                    }
                    emptyReads = 0
                    off += n
                }
                if (error != null || !cap.running) break
                listener.onFrame(frame)
            }
        } catch (t: Throwable) {
            if (cap.running) error = "Cellular downlink error: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { cap.rec.stop() }
            runCatching { cap.rec.release() }
            val wasRunning = cap.running
            cap.running = false
            synchronized(this) {
                if (current === cap) current = null
                if (thread === Thread.currentThread()) thread = null
            }
            if (error != null && wasRunning) {
                lastRuntimeError = error
                runCatching { listener.onError(error) }
            }
        }
    }

    /** Idempotent. Ends the capture, unblocks a pending read, and waits (briefly) for the capture thread to release the recorder. */
    override fun stop() {
        val (cap, t) = synchronized(this) { current.also { current = null } to thread }
        cap?.running = false
        if (cap != null) runCatching { cap.rec.stop() }
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(STOP_JOIN_MS) }
    }

    companion object {
        /** Permission name only (hidden / privileged API, so there is no `Manifest.permission` constant). Not declared in the manifest. */
        const val CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"
        const val THREAD_NAME = "cellular-capture"
        const val STOP_JOIN_MS = 500L
        const val MAX_EMPTY_READS = 100
        const val EMPTY_READ_PAUSE_MS = 2L

        /** AudioRecord read() error codes (values are the platform constants, kept literal so this stays Android-free). */
        fun describeReadError(code: Int): String = when (code) {
            -1 -> "ERROR (-1)"
            -2 -> "ERROR_BAD_VALUE (-2)"
            -3 -> "ERROR_INVALID_OPERATION (-3)"
            -6 -> "ERROR_DEAD_OBJECT (-6)"
            else -> "code $code"
        }
    }
}
