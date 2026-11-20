package com.example.companion.audio.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 32C JVM tests: capture lifecycle and error handling with fake recorders (no AudioRecord, no hardware).
 * WRITTEN BUT NOT RUN: no Gradle / Android SDK was available. They use real threads with short timeouts.
 */
class CellularCaptureLifecycleTest {
    /** Scripted recorder. Script items: ByteArray (data), Int (return code), Throwable (thrown). Blocks (polling) when the script is empty, like a quiet AudioRecord. */
    class FakeRecorder(val throwOnRelease: Boolean = false) : CellularRecorder {
        val script = LinkedBlockingDeque<Any>()
        @Volatile var stopped = false
        val stops = AtomicInteger(); val releases = AtomicInteger()
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if (stopped) return -3
                val item = script.poll(5, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Int -> return item
                    is Throwable -> throw item
                    is ByteArray -> {
                        val n = minOf(len, item.size)
                        System.arraycopy(item, 0, buf, off, n)
                        if (n < item.size) script.putFirst(item.copyOfRange(n, item.size))
                        return n
                    }
                }
            }
        }
        override fun stop() { stopped = true; stops.incrementAndGet() }
        override fun release() { releases.incrementAndGet(); if (throwOnRelease) throw IllegalStateException("release boom") }
    }

    class Opener(private val results: Map<CellularCaptureSource, () -> CellularOpenResult>) : CellularRecorderOpener {
        val attempts = ArrayList<CellularCaptureSource>()
        override fun open(source: CellularCaptureSource, config: AudioConfig): CellularOpenResult {
            attempts += source
            return results[source]?.invoke() ?: CellularOpenResult.Failed("not scripted")
        }
    }

    class Listener(private val onFrameHook: (ByteArray) -> Unit = {}) : AudioSource.Listener {
        val frames = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun onFrame(pcm: ByteArray) { frames += pcm; onFrameHook(pcm) }
        override fun onError(message: String) { errors += message }
    }

    private val cfg = AudioConfig()
    private val D = CellularCaptureSource.VOICE_DOWNLINK
    private val C = CellularCaptureSource.VOICE_CALL

    private fun openerFor(rec: FakeRecorder) = Opener(mapOf(D to { CellularOpenResult.Opened(rec) }))
    private fun waitUntil(ms: Long = 2000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }
    private fun captureThreads() = Thread.getAllStackTraces().keys.count { it.name == CellularDownlinkSource.THREAD_NAME && it.isAlive }

    @Test fun startSucceedsAndDeliversExactFrames() {
        val rec = FakeRecorder(); val l = Listener()
        val s = CellularDownlinkSource({ true }, openerFor(rec))
        assertNull(s.start(cfg, l))
        assertEquals(D, s.selectedSource)
        // 2.5 frames in odd chunks must come out as exactly two frames of frameBytes
        val data = ByteArray(cfg.frameBytes * 5 / 2) { (it % 100).toByte() }
        rec.script.put(data.copyOfRange(0, 7)); rec.script.put(data.copyOfRange(7, 700)); rec.script.put(data.copyOfRange(700, data.size))
        assertTrue(waitUntil { l.frames.size == 2 })
        assertTrue(l.frames.all { it.size == cfg.frameBytes })
        assertTrue(data.copyOfRange(0, cfg.frameBytes).contentEquals(l.frames[0]))
        assertTrue(data.copyOfRange(cfg.frameBytes, cfg.frameBytes * 2).contentEquals(l.frames[1]))
        s.stop()
        assertEquals(2, l.frames.size)   // the half frame is never delivered
    }

    @Test fun stopReleasesRecorderEndsThreadAndStopsCallbacks() {
        val rec = FakeRecorder(); val l = Listener()
        val s = CellularDownlinkSource({ true }, openerFor(rec))
        assertNull(s.start(cfg, l))
        s.stop()
        assertEquals(1, rec.releases.get())
        assertTrue(rec.stops.get() >= 1)
        assertTrue(waitUntil { captureThreads() == 0 })
        val before = l.frames.size
        rec.script.put(ByteArray(cfg.frameBytes))
        Thread.sleep(50)
        assertEquals(before, l.frames.size)
        assertTrue(l.errors.isEmpty())   // a requested stop is not an error
    }

    @Test fun repeatedStopAndStopWithoutStartAreSafe() {
        val rec = FakeRecorder()
        val s = CellularDownlinkSource({ true }, openerFor(rec))
        s.stop(); s.stop()
        assertNull(s.start(cfg, Listener()))
        s.stop(); s.stop(); s.stop()
        assertEquals(1, rec.releases.get())
    }

    @Test fun noFallbackWhenDownlinkStarts() {
        val rec = FakeRecorder()
        val op = openerFor(rec)
        val s = CellularDownlinkSource({ true }, op)
        assertNull(s.start(cfg, Listener()))
        s.stop()
        assertEquals(listOf(D), op.attempts)
    }

    @Test fun fallsBackToVoiceCallAndReadsFromIt() {
        val rec = FakeRecorder(); val l = Listener()
        val op = Opener(mapOf(D to { CellularOpenResult.Failed("state=0") }, C to { CellularOpenResult.Opened(rec) }))
        val s = CellularDownlinkSource({ false }, op)
        assertNull(s.start(cfg, l))
        assertEquals(listOf(D, C), op.attempts)
        assertEquals(C, s.selectedSource)
        assertEquals(listOf(CellularAttempt(D, "state=0")), s.lastAttempts)
        rec.script.put(ByteArray(cfg.frameBytes))
        assertTrue(waitUntil { l.frames.size == 1 })
        s.stop()
    }

    @Test fun bothSourcesFailKeepsPrivilegedErrorStartsNoThread() {
        val op = Opener(mapOf(D to { CellularOpenResult.Failed("SecurityException: denied") }, C to { CellularOpenResult.Failed("state=0") }))
        val s = CellularDownlinkSource({ false }, op)
        val msg = s.start(cfg, Listener())!!
        assertTrue(msg.startsWith("needs CAPTURE_AUDIO_OUTPUT (privileged app)"))
        assertTrue(msg.contains("VOICE_DOWNLINK: SecurityException: denied"))
        assertTrue(msg.contains("VOICE_CALL: state=0"))
        assertEquals(CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT, s.lastError)
        assertEquals(msg, s.lastErrorDetail)
        assertNull(s.selectedSource)
        assertEquals(0, captureThreads())
        s.stop()
    }

    @Test fun openerCrashDoesNotCrashStart() {
        val op = Opener(mapOf(D to { throw RuntimeException("hal died") }, C to { CellularOpenResult.Failed("x") }))
        val msg = CellularDownlinkSource({ false }, op).start(cfg, Listener())!!
        assertTrue(msg.contains("hal died"))
    }

    @Test fun readErrorReportsOnceAfterReleaseAndAllowsRestart() {
        val recs = ArrayList<FakeRecorder>()
        val op = CellularRecorderOpener { _, _ -> CellularOpenResult.Opened(FakeRecorder().also { recs += it }) }
        val l = Listener()
        val s = CellularDownlinkSource({ true }, op)
        assertNull(s.start(cfg, l))
        recs[0].script.put(-6)
        assertTrue(waitUntil { l.errors.size == 1 })
        assertTrue(l.errors[0].contains("ERROR_DEAD_OBJECT"))
        assertEquals(1, recs[0].releases.get())
        assertEquals(l.errors[0], s.lastRuntimeError)
        assertTrue(waitUntil { captureThreads() == 0 })
        // the same instance can start again with a fresh recorder
        assertNull(s.start(cfg, Listener()))
        assertEquals(2, recs.size)
        s.stop()
        assertEquals(1, recs[1].releases.get())
        assertEquals(1, l.errors.size)
    }

    @Test fun readThrowingIsReportedNotPropagated() {
        val rec = FakeRecorder(); val l = Listener()
        val s = CellularDownlinkSource({ true }, openerFor(rec))
        assertNull(s.start(cfg, l))
        rec.script.put(IllegalStateException("dead"))
        assertTrue(waitUntil { l.errors.size == 1 })
        assertTrue(l.errors[0].contains("dead"))
        assertEquals(1, rec.releases.get())
    }

    @Test fun listenerExceptionStopsCaptureAndIsReported() {
        val rec = FakeRecorder()
        val l = Listener { throw IllegalArgumentException("sink broke") }
        val s = CellularDownlinkSource({ true }, openerFor(rec))
        assertNull(s.start(cfg, l))
        rec.script.put(ByteArray(cfg.frameBytes))
        assertTrue(waitUntil { l.errors.size == 1 })
        assertTrue(l.errors[0].contains("sink broke"))
        assertEquals(1, rec.releases.get())
        assertTrue(waitUntil { captureThreads() == 0 })
    }

    @Test fun releaseThrowingDoesNotCrash() {
        val rec = FakeRecorder(throwOnRelease = true)
        val s = CellularDownlinkSource({ true }, openerFor(rec))
        assertNull(s.start(cfg, Listener()))
        s.stop()
        assertTrue(waitUntil { rec.releases.get() == 1 && captureThreads() == 0 })
    }

    @Test fun secondStartWhileRunningIsRefusedWithoutSecondRecorder() {
        val rec = FakeRecorder()
        val op = openerFor(rec)
        val s = CellularDownlinkSource({ true }, op)
        assertNull(s.start(cfg, Listener()))
        assertEquals("Cellular downlink already running", s.start(cfg, Listener()))
        assertEquals(1, op.attempts.size)
        s.stop()
    }

    @Test fun stopFromInsideCallbackDoesNotDeadlockAndStopsFurtherCallbacks() {
        val rec = FakeRecorder()
        val done = CountDownLatch(1)
        lateinit var s: CellularDownlinkSource
        val l = Listener { s.stop(); done.countDown() }
        s = CellularDownlinkSource({ true }, openerFor(rec))
        assertNull(s.start(cfg, l))
        rec.script.put(ByteArray(cfg.frameBytes * 3))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(waitUntil { rec.releases.get() == 1 && captureThreads() == 0 })
        assertEquals(1, l.frames.size)
        assertTrue(l.errors.isEmpty())
    }

    @Test fun endlessEmptyReadsBecomeAnError() {
        val rec = FakeRecorder(); val l = Listener()
        repeat(CellularDownlinkSource.MAX_EMPTY_READS + 5) { rec.script.put(0) }
        val s = CellularDownlinkSource({ true }, openerFor(rec))
        assertNull(s.start(cfg, l))
        assertTrue(waitUntil(5000) { l.errors.size == 1 })
        assertTrue(l.errors[0].contains("no data"))
        assertEquals(1, rec.releases.get())
    }

    @Test fun threadInitRunsOnCaptureThreadBeforeReading() {
        val rec = FakeRecorder()
        val name = java.util.concurrent.atomic.AtomicReference<String>()
        val s = CellularDownlinkSource({ true }, openerFor(rec), { name.set(Thread.currentThread().name) })
        assertNull(s.start(cfg, Listener()))
        assertTrue(waitUntil { name.get() != null })
        assertEquals(CellularDownlinkSource.THREAD_NAME, name.get())
        s.stop()
    }

    @Test fun readErrorTexts() {
        assertTrue(CellularDownlinkSource.describeReadError(-6).contains("DEAD_OBJECT"))
        assertTrue(CellularDownlinkSource.describeReadError(-3).contains("INVALID_OPERATION"))
        assertTrue(CellularDownlinkSource.describeReadError(-2).contains("BAD_VALUE"))
        assertEquals("code -42", CellularDownlinkSource.describeReadError(-42))
        assertFalse(CellularDownlinkSource.describeReadError(-1).isEmpty())
    }
}
