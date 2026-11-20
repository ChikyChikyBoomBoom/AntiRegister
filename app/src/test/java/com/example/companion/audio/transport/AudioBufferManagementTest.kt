package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Stage 38 JVM tests for [JitterBuffer] (virtual clock) and [SendBackPressure]. Written; NOT executed (no Kotlin /
 * Gradle / JUnit in the authoring environment). take() is always called with a positive wait of 1 ms.
 */
class AudioBufferManagementTest {

    private val frameMs = 20
    private var now = 0L
    private fun buffer(target: Int = 40, max: Int = 100) = JitterBuffer(frameMs, JitterConfig(target, max)) { now }
    private fun frame(tag: Int) = ByteArray(640) { tag.toByte() }
    private fun JitterBuffer.putSeq(seq: Long) = put(seq, frame(seq.toInt()), seq * frameMs)

    // ---- buffer limits
    @Test fun neverHoldsMoreThanMaxMs() {
        val b = buffer(max = 100)                       // 5 frames
        for (i in 0L until 50) b.putSeq(i)
        assertEquals(5, b.maxFrames)
        assertEquals(5, b.depthFrames)
        assertEquals(45L, b.overflowDropped)
        assertEquals(50L, b.received)
    }

    @Test fun absoluteCeilingBoundsMemoryEvenForABadConfig() {
        val b = buffer(target = 80, max = 3_600_000)
        assertEquals(JitterBuffer.ABSOLUTE_MAX_MS / frameMs, b.maxFrames)
        for (i in 0L until 500) b.putSeq(i)
        assertEquals(JitterBuffer.ABSOLUTE_MAX_MS / frameMs, b.depthFrames)
    }

    @Test fun oversizedAndEmptyFramesAreRejectedNotQueued() {
        val b = buffer()
        b.put(0, ByteArray(0), 0)
        b.put(1, ByteArray(JitterBuffer.MAX_FRAME_BYTES + 1), 20)
        assertEquals(2L, b.rejected); assertEquals(0, b.depthFrames); assertEquals(0L, b.received)
        b.put(2, frame(2), 40)
        assertEquals(1L, b.received)
    }

    // ---- overflow policy: oldest goes first, newest is kept (latency cannot creep up)
    @Test fun overflowDiscardsOldestAndKeepsNewest() {
        val b = buffer(target = 20, max = 60)           // cap 3
        for (i in 0L until 6) b.putSeq(i)
        val out = (0 until 3).map { b.take(1)!![0].toInt() }
        assertEquals(listOf(3, 4, 5), out)
    }

    // ---- prebuffer / jitter management
    @Test fun doesNotPlayUntilTargetIsBuffered() {
        val b = buffer(target = 60, max = 200)          // target 3 frames
        b.putSeq(0); b.putSeq(1)
        assertNull(b.take(1)); assertFalse(b.isPlaying)
        b.putSeq(2)
        assertNotNull(b.take(1)); assertTrue(b.isPlaying)
    }

    @Test fun targetAboveMaxCannotStallPlayback() {
        val b = buffer(target = 200, max = 60)          // misconfigured: target > cap
        for (i in 0L until 3) b.putSeq(i)
        assertNotNull(b.take(1))
    }

    @Test fun starvationCountsUnderrunsAndReturnsToPrebuffering() {
        val b = buffer(target = 20, max = 100)
        b.putSeq(0)
        assertNotNull(b.take(1))
        repeat(3) { assertNull(b.take(1)) }
        assertEquals(3L, b.underruns)
        assertFalse(b.isPlaying)
    }

    @Test fun zeroWaitDoesNotBlockForever() {
        val b = buffer()
        val t = thread { assertNull(b.take(0)) }
        t.join(2000)
        assertFalse("take(0) blocked", t.isAlive)
    }

    @Test fun jitterEstimateReactsToIrregularArrival() {
        val b = buffer()
        now = 0; b.putSeq(0)
        now = 20; b.putSeq(1)                            // on time
        assertEquals(0.0, b.jitterMs, 1e-9)
        now = 120; b.putSeq(2)                           // 80 ms late
        assertTrue(b.jitterMs > 0.0)
    }

    // ---- frame ordering
    @Test fun inOrderFramesComeOutInOrder() {
        val b = buffer(target = 20, max = 400)
        for (i in 0L until 5) b.putSeq(i)
        assertEquals(listOf(0, 1, 2, 3, 4), (0 until 5).map { b.take(1)!![0].toInt() })
        assertEquals(0L, b.lostGap); assertEquals(0L, b.lateDiscarded)
    }

    @Test fun duplicateAndOlderFramesAreDiscarded() {
        val b = buffer(target = 20, max = 400)
        b.putSeq(0); b.putSeq(1); b.putSeq(1); b.putSeq(0)
        assertEquals(2, b.depthFrames); assertEquals(2L, b.lateDiscarded)
    }

    @Test fun gapsAreCountedAndOrderIsPreserved() {
        val b = buffer(target = 20, max = 400)
        b.putSeq(0); b.putSeq(4)
        assertEquals(3L, b.lostGap)
        assertEquals(listOf(0, 4), (0 until 2).map { b.take(1)!![0].toInt() })
    }

    @Test fun sequenceWrapAround32BitIsNotTreatedAsLate() {
        val b = buffer(target = 20, max = 400)
        b.put(0xFFFFFFFEL, frame(1), 0); b.put(0xFFFFFFFFL, frame(2), 20); b.put(0L, frame(3), 40); b.put(1L, frame(4), 60)
        assertEquals(4, b.depthFrames); assertEquals(0L, b.lateDiscarded); assertEquals(0L, b.lostGap)
        b.put(0xFFFFFFFFL, frame(9), 80)                 // old one after the wrap
        assertEquals(1L, b.lateDiscarded)
    }

    // ---- cleanup / disconnection
    @Test fun clearEmptiesQueueAndReturnsToPrebuffering() {
        val b = buffer(target = 20, max = 400)
        for (i in 0L until 4) b.putSeq(i)
        assertNotNull(b.take(1)); assertTrue(b.isPlaying)
        b.clear()
        assertEquals(0, b.depthFrames); assertFalse(b.isPlaying); assertNull(b.take(1))
    }

    @Test fun clearWakesABlockedPlayer() {
        val b = buffer(target = 20, max = 400)
        b.putSeq(0); b.take(1)
        var result: ByteArray? = byteArrayOf(1)
        val t = thread { result = b.take(5_000) }
        Thread.sleep(50); b.clear(); t.join(2000)
        assertFalse(t.isAlive); assertNull(result)
    }

    // ---- thread safety
    @Test fun concurrentProducerAndConsumerStayConsistent() {
        val b = buffer(target = 20, max = 200)
        val total = 5_000L
        var taken = 0L
        val consumer = thread { while (taken + b.overflowDropped < total && !Thread.currentThread().isInterrupted) { if (b.take(1) != null) taken++ } }
        val producer = thread { for (i in 0 until total) b.putSeq(i) }
        producer.join(10_000); consumer.join(10_000)
        assertFalse(producer.isAlive); assertFalse(consumer.isAlive)
        assertEquals(total, b.received)
        assertEquals(total, taken + b.overflowDropped + b.depthFrames)
    }

    // ---- back-pressure
    @Test fun congestionNeedsConsecutiveFailures() {
        val p = SendBackPressure(congestAfter = 3, recoverAfter = 5)
        p.record(false); p.record(false); p.record(true); p.record(false)
        assertFalse(p.congested); assertEquals(3L, p.dropped)
        p.record(false); p.record(false)
        assertTrue(p.congested)
    }

    @Test fun congestionClearsAfterSustainedRecovery() {
        val p = SendBackPressure(congestAfter = 2, recoverAfter = 3)
        p.record(false); p.record(false); assertTrue(p.congested)
        p.record(true); p.record(true); assertTrue(p.congested)   // hysteresis
        p.record(true); assertFalse(p.congested)
    }

    @Test fun resetClearsEverything() {
        val p = SendBackPressure(1, 1)
        p.record(false); assertTrue(p.congested)
        p.reset(); assertFalse(p.congested); assertEquals(0L, p.dropped)
    }

    @Test fun backPressureObservesButNeverBlocks() {
        val p = SendBackPressure()
        val start = System.nanoTime()
        repeat(100_000) { p.record(it % 7 != 0) }
        assertTrue((System.nanoTime() - start) < 2_000_000_000L)
    }
}
