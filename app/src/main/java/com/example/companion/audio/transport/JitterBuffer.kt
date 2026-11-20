package com.example.companion.audio.transport

import android.os.SystemClock
import kotlin.math.abs

/**
 * Small reorder-free jitter buffer (RFCOMM is ordered and reliable, so packets are only ever late or missing because
 * the SENDER dropped them). Three jobs:
 *  - PREBUFFER: do not start playing until [JitterConfig.targetMs] of audio is queued, so small timing variations
 *    do not cause instant glitches.
 *  - CAP: never queue more than [JitterConfig.maxMs]; the oldest audio is discarded so latency cannot creep up.
 *  - STARVE: when empty, the player is told "nothing"; after a few empty ticks it goes back to prebuffering.
 * Stage 38 additions (no change for valid configs / frames):
 *  - memory is bounded even for a bad config: the queue never holds more than [ABSOLUTE_MAX_MS] of audio;
 *  - the prebuffer target is clamped to the cap, so maxMs < targetMs can no longer stall playback forever;
 *  - empty or oversized frames are rejected and counted in [rejected] instead of being queued;
 *  - sequence numbers are compared modulo 2^32 (the wire field is u32), so a wrap does not discard a whole stream;
 *  - take(waitMs <= 0) no longer waits forever (Object.wait(0) means "forever").
 * Ordering policy: RFCOMM is ordered, so there is deliberately NO reorder window. A frame that is a duplicate or
 * older than the newest accepted one is discarded and counted in [lateDiscarded]; gaps are counted in [lostGap].
 * Thread-safe: put() runs on the Bluetooth read thread, take() on the playback thread.
 */
class JitterBuffer(
    private val frameMs: Int,
    config: JitterConfig,
    /** Monotonic ms clock. Injectable (Stage 38) so the buffer is JVM-testable; the default is the old behaviour. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    @Volatile var config: JitterConfig = config

    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var lastSeq = -1L
    private var playing = false
    private var emptyTicks = 0
    private var lastArrival = 0L
    private var lastTs = 0L
    private var jitter = 0.0

    @Volatile var received = 0L; private set
    @Volatile var lostGap = 0L; private set          // sequence numbers that never arrived
    @Volatile var lateDiscarded = 0L; private set    // duplicate / older than already accepted
    @Volatile var overflowDropped = 0L; private set  // discarded to cap latency
    @Volatile var rejected = 0L; private set         // empty / oversized frames refused (Stage 38)
    @Volatile var underruns = 0L; private set        // playback asked and nothing was there
    @Volatile var lastTimestampMs = 0L; private set
    @Volatile var lastArrivalElapsed = 0L; private set

    private fun frames(ms: Int) = maxOf(1, (ms + frameMs - 1) / frameMs)
    private fun capFrames() = frames(minOf(config.maxMs, ABSOLUTE_MAX_MS))
    private fun targetFrames() = minOf(frames(config.targetMs), capFrames())

    /** Hard upper bound on queued frames for the current config. */
    val maxFrames get() = capFrames()

    val isPlaying get() = synchronized(lock) { playing }
    val depthFrames get() = synchronized(lock) { queue.size }
    val depthMs get() = depthFrames * frameMs
    /** RFC 3550 style interarrival jitter estimate in ms. */
    val jitterMs get() = synchronized(lock) { jitter }

    fun put(seq: Long, pcm: ByteArray, timestampMs: Long) {
        val now = clock()
        synchronized(lock) {
            if (pcm.isEmpty() || pcm.size > MAX_FRAME_BYTES) { rejected++; return }
            if (lastSeq >= 0) {
                val diff = (seq - lastSeq) and SEQ_MASK
                if (diff == 0L || diff >= SEQ_HALF) { lateDiscarded++; return }
                if (diff > 1) lostGap += diff - 1
                val d = (now - lastArrival) - (timestampMs - lastTs)
                jitter += (abs(d) - jitter) / 16.0
            }
            lastSeq = seq; lastArrival = now; lastTs = timestampMs
            lastTimestampMs = timestampMs; lastArrivalElapsed = now
            queue.addLast(pcm); received++
            val cap = capFrames()
            while (queue.size > cap) { queue.removeFirst(); overflowDropped++ }
            lock.notifyAll()
        }
    }

    /** Next frame, or null when prebuffering / starved. Waits at most [waitMs]. */
    fun take(waitMs: Long): ByteArray? {
        synchronized(lock) {
            if (!playing) {
                if (queue.size < targetFrames() && waitMs > 0) lock.wait(waitMs)
                if (queue.size < targetFrames()) return null
                playing = true
            }
            if (queue.isEmpty()) {
                if (waitMs > 0) lock.wait(waitMs)
                if (queue.isEmpty()) {
                    underruns++
                    if (++emptyTicks >= REBUFFER_AFTER_EMPTY_TICKS) { playing = false; emptyTicks = 0 }
                    return null
                }
            }
            emptyTicks = 0
            return queue.removeFirst()
        }
    }

    fun clear() = synchronized(lock) { queue.clear(); playing = false; lock.notifyAll() }

    companion object {
        private const val REBUFFER_AFTER_EMPTY_TICKS = 3
        /** Absolute ceiling on queued audio, whatever the config says (memory bound). */
        const val ABSOLUTE_MAX_MS = 1_000
        /** Same bound the wire format enforces ([AudioPacket.MAX_PAYLOAD]). */
        const val MAX_FRAME_BYTES = AudioPacket.MAX_PAYLOAD
        private const val SEQ_MASK = 0xFFFFFFFFL
        private const val SEQ_HALF = 0x80000000L
    }
}
