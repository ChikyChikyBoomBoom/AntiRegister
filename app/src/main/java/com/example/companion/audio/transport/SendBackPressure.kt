package com.example.companion.audio.transport

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 38: sender-side back-pressure indicator. [AudioLink.sendAudio] is non-blocking and, when the link queue is
 * full, drops the OLDEST queued packet and returns false (bounded memory, bounded latency; nothing here changes
 * that). This class only OBSERVES those results so the condition becomes visible instead of silent:
 *  - [dropped]   total sends that displaced an older packet (or were refused),
 *  - [congested] true after [congestAfter] consecutive failed sends, false again after [recoverAfter] consecutive
 *                good sends (hysteresis, so the flag does not flicker).
 * It never blocks, never queues and never decides to drop anything itself. Thread-safe (capture thread writes,
 * main thread reads).
 */
class SendBackPressure(private val congestAfter: Int = 3, private val recoverAfter: Int = 5) {
    init { require(congestAfter >= 1 && recoverAfter >= 1) }

    private val badRun = AtomicInteger()
    private val goodRun = AtomicInteger()
    private val droppedCount = AtomicLong()
    @Volatile var congested = false; private set

    val dropped: Long get() = droppedCount.get()

    /** [accepted] = the value [AudioLink.sendAudio] returned. */
    fun record(accepted: Boolean) {
        if (accepted) {
            badRun.set(0)
            if (goodRun.incrementAndGet() >= recoverAfter) congested = false
        } else {
            droppedCount.incrementAndGet()
            goodRun.set(0)
            if (badRun.incrementAndGet() >= congestAfter) congested = true
        }
    }

    fun reset() { badRun.set(0); goodRun.set(0); droppedCount.set(0); congested = false }
}
