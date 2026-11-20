package com.example.companion.audio.transport

/**
 * Stage 39: the Android-free core of microphone capture (the logic that used to live inside [MicrophoneSource]).
 * Captures only between `start()` and `stop()`. Audio exists only as the [AudioSource.Listener.onFrame] argument:
 * it is never written to disk, never logged, and never put into an error message.
 *
 * Contract (same as [CellularDownlinkSource]):
 * - `start()` returns null once the recorder is recording and the capture thread runs; otherwise a reason, with everything released.
 * - Refused (before anything is opened): another capture is running, the previous thread has not released the recorder yet,
 *   the configuration is not one this build supports, or the permission is missing.
 * - One object per capture ([Capture]): a late-finishing old thread can neither stop nor keep alive a newer capture.
 * - The recorder is released ONLY by the capture thread (never while it may be inside `read()`), in `finally`, on every exit path.
 * - `stop()` is idempotent, makes `read()` return, clears the running flag (no further `onFrame` / `onError` is started)
 *   and waits up to [STOP_JOIN_MS] for the thread.
 * - A failure (read error, exception, listener exception) calls `listener.onError` once, after the recorder is released.
 *
 * It never starts by itself: nothing in the app calls `start()` except an explicit user action path (Audio Test screen, or
 * [UplinkController.start]).
 */
class MicrophoneCapture(
    private val hasPermission: () -> Boolean,
    private val opener: MicrophoneRecorderOpener,
    private val threadInit: () -> Unit = {}
) : AudioSource {
    override val name = "Microphone"

    private class Capture(val rec: MicrophoneRecorder) { @Volatile var running = true }

    private var current: Capture? = null
    private var thread: Thread? = null

    /** Text of the most recent refused start or runtime failure, or null (cleared by a successful start). Contains no audio. */
    @Volatile var lastError: String? = null
        private set

    fun isRunning(): Boolean = synchronized(this) { current?.running == true }

    @Synchronized
    override fun start(config: AudioConfig, listener: AudioSource.Listener): String? {
        if (current?.running == true) return refuse("Microphone already running")
        // The previous capture was told to stop but its thread has not released the recorder yet: never hold two recorders.
        if (thread?.isAlive == true) return refuse("Microphone is still being released - try again in a moment")
        if (!isSupported(config)) return refuse("Microphone does not support ${config.sampleRate} Hz")
        if (!runCatching { hasPermission() }.getOrDefault(false)) return refuse("Microphone permission denied")
        val rec = try {
            when (val r = opener.open(config)) {
                is MicrophoneOpenResult.Opened -> r.recorder
                is MicrophoneOpenResult.Failed -> return refuse(r.reason)
            }
        } catch (t: Throwable) {
            return refuse("Could not create microphone recorder: ${t.javaClass.simpleName}")
        }
        val cap = Capture(rec)
        val t = try {
            Thread({ capture(cap, config, listener) }, THREAD_NAME).also { it.start() }
        } catch (e: Throwable) {
            // The thread never ran, so nobody else can be inside read(): release here.
            runCatching { rec.stop() }; runCatching { rec.release() }
            return refuse("Could not start microphone capture thread: ${e.javaClass.simpleName}")
        }
        // The thread's finally needs this monitor, so it cannot clear `current` before we set it.
        current = cap
        thread = t
        lastError = null
        return null
    }

    private fun refuse(reason: String): String { lastError = reason; return reason }

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
                    if (n < 0) { if (cap.running) error = "Microphone read failed (code $n)"; break }
                    if (n == 0) {
                        if (++emptyReads > MAX_EMPTY_READS) { if (cap.running) error = "Microphone delivered no data"; break }
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
            if (cap.running) error = "Microphone error: ${t.javaClass.simpleName}"   // class name only: never message text
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
                lastError = error
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
        const val THREAD_NAME = "audio-capture"
        const val STOP_JOIN_MS = 500L
        const val MAX_EMPTY_READS = 100
        const val EMPTY_READ_PAUSE_MS = 2L

        /** Mono PCM16 at one of [AudioConfig.SUPPORTED_RATES] with a usable frame length. Whether the DEVICE offers it is the opener's answer. */
        fun isSupported(config: AudioConfig): Boolean =
            config.sampleRate in AudioConfig.SUPPORTED_RATES && config.channels == 1 && config.frameBytes > 0
    }
}
