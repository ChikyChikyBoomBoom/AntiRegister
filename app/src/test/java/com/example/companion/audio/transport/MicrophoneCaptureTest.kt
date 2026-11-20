package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 39 JVM tests of [MicrophoneCapture] with a fake recorder (silence only; no real microphone, no disk, no log).
 * WRITTEN BUT NOT RUN: no Kotlin compiler / Gradle / network was available when this stage was authored.
 */
class MicrophoneCaptureTest {

    /** Delivers zero-filled PCM like a recorder would; read() fails once stopped (as AudioRecord does). */
    private class FakeRecorder(private val mode: Mode = Mode.SILENCE) : MicrophoneRecorder {
        enum class Mode { SILENCE, READ_ERROR, EMPTY }
        @Volatile var stopped = false
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        val released = CountDownLatch(1)
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (stopped) return -3
            return when (mode) {
                Mode.SILENCE -> { Thread.sleep(1); len }
                Mode.READ_ERROR -> -6
                Mode.EMPTY -> 0
            }
        }
        override fun stop() { stopCalls.incrementAndGet(); stopped = true }
        override fun release() { releaseCalls.incrementAndGet(); released.countDown() }
    }

    private class FakeOpener(private val result: () -> MicrophoneOpenResult) : MicrophoneRecorderOpener {
        val opens = AtomicInteger()
        override fun open(config: AudioConfig): MicrophoneOpenResult { opens.incrementAndGet(); return result() }
    }

    private class Sink(framesWanted: Int = 3) : AudioSource.Listener {
        val frames = AtomicInteger(); val errors = AtomicInteger()
        @Volatile var lastError: String? = null
        @Volatile var frameBytes = -1
        val enough = CountDownLatch(framesWanted)
        val errored = CountDownLatch(1)
        override fun onFrame(pcm: ByteArray) { frameBytes = pcm.size; frames.incrementAndGet(); enough.countDown() }
        override fun onError(message: String) { lastError = message; errors.incrementAndGet(); errored.countDown() }
    }

    private fun source(opener: MicrophoneRecorderOpener, permission: Boolean = true) =
        MicrophoneCapture({ permission }, opener)

    private fun opened(rec: FakeRecorder) = FakeOpener { MicrophoneOpenResult.Opened(rec) }

    // ---- initialization
    @Test fun nameAndInitialState() {
        val s = source(opened(FakeRecorder()))
        assertEquals("Microphone", s.name)
        assertFalse(s.isRunning())
        assertNull(s.lastError)
    }

    @Test fun initializationFailureIsReturnedAndNothingRuns() {
        val op = FakeOpener { MicrophoneOpenResult.Failed("Microphone failed to initialise (in use by another app?)") }
        val s = source(op)
        val l = Sink()
        assertEquals("Microphone failed to initialise (in use by another app?)", s.start(AudioConfig(), l))
        assertFalse(s.isRunning())
        assertEquals(0, l.frames.get()); assertEquals(0, l.errors.get())
        assertNotNull(s.lastError)
    }

    @Test fun openerCrashIsReportedNotThrown() {
        val s = source(FakeOpener { throw IllegalStateException("boom") })
        val r = s.start(AudioConfig(), Sink())
        assertTrue(r!!.startsWith("Could not create microphone recorder"))
        assertFalse(s.isRunning())
    }

    @Test fun missingPermissionIsRefusedBeforeOpening() {
        val op = opened(FakeRecorder())
        val s = source(op, permission = false)
        assertEquals("Microphone permission denied", s.start(AudioConfig(), Sink()))
        assertEquals(0, op.opens.get())
    }

    @Test fun permissionCheckThatThrowsCountsAsDenied() {
        val op = opened(FakeRecorder())
        val s = MicrophoneCapture({ throw SecurityException("x") }, op)
        assertEquals("Microphone permission denied", s.start(AudioConfig(), Sink()))
        assertEquals(0, op.opens.get())
    }

    // ---- sample rates
    @Test fun supportedRatesAreStartedAtTheRequestedRate() {
        for (rate in AudioConfig.SUPPORTED_RATES) {
            var seen = -1
            val rec = FakeRecorder()
            val s = source(MicrophoneRecorderOpener { c -> seen = c.sampleRate; MicrophoneOpenResult.Opened(rec) })
            val l = Sink()
            assertNull(s.start(AudioConfig(sampleRate = rate), l))
            assertTrue(l.enough.await(2, TimeUnit.SECONDS))
            assertEquals(AudioConfig(sampleRate = rate).frameBytes, l.frameBytes)
            s.stop()
            assertEquals(rate, seen)
        }
    }

    @Test fun unsupportedRateIsRefusedBeforeOpening() {
        val op = opened(FakeRecorder())
        val s = source(op)
        assertEquals("Microphone does not support 44100 Hz", s.start(AudioConfig(sampleRate = 44_100), Sink()))
        assertEquals(0, op.opens.get())
        assertFalse(MicrophoneCapture.isSupported(AudioConfig(sampleRate = 22_050)))
        assertFalse(MicrophoneCapture.isSupported(AudioConfig(channels = 2)))
        assertTrue(MicrophoneCapture.isSupported(AudioConfig()))
    }

    @Test fun deviceThatRejectsTheRateReportsIt() {
        val s = source(FakeOpener { MicrophoneOpenResult.Failed("Microphone does not support 8000 Hz") })
        assertEquals("Microphone does not support 8000 Hz", s.start(AudioConfig(sampleRate = 8_000), Sink()))
    }

    // ---- start / stop
    @Test fun startDeliversExactFramesAndStopReleasesOnce() {
        val rec = FakeRecorder()
        val s = source(opened(rec))
        val l = Sink()
        assertNull(s.start(AudioConfig(), l))
        assertTrue(s.isRunning())
        assertTrue(l.enough.await(2, TimeUnit.SECONDS))
        assertEquals(AudioConfig().frameBytes, l.frameBytes)
        s.stop()
        assertTrue(rec.released.await(2, TimeUnit.SECONDS))
        assertEquals(1, rec.releaseCalls.get())
        assertFalse(s.isRunning())
        assertEquals(0, l.errors.get())           // a normal stop is not an error
    }

    @Test fun noFrameAfterStopReturns() {
        val rec = FakeRecorder()
        val s = source(opened(rec))
        val l = Sink()
        s.start(AudioConfig(), l)
        assertTrue(l.enough.await(2, TimeUnit.SECONDS))
        s.stop()
        val after = l.frames.get()
        Thread.sleep(30)
        assertEquals(after, l.frames.get())
    }

    @Test fun stopIsIdempotentAndSafeWithoutStart() {
        val s = source(opened(FakeRecorder()))
        s.stop(); s.stop()
        val rec = FakeRecorder()
        val s2 = source(opened(rec))
        s2.start(AudioConfig(), Sink())
        s2.stop(); s2.stop()
        assertTrue(rec.released.await(2, TimeUnit.SECONDS))
        assertEquals(1, rec.releaseCalls.get())
    }

    @Test fun canStartAgainAfterStop() {
        val recs = mutableListOf<FakeRecorder>()
        val s = source(FakeOpener { MicrophoneOpenResult.Opened(FakeRecorder().also { recs.add(it) }) })
        assertNull(s.start(AudioConfig(), Sink()))
        s.stop()
        assertNull(s.start(AudioConfig(), Sink()))
        s.stop()
        assertEquals(2, recs.size)
        recs.forEach { assertTrue(it.released.await(2, TimeUnit.SECONDS)); assertEquals(1, it.releaseCalls.get()) }
    }

    // ---- duplicate start prevention
    @Test fun duplicateStartIsRefusedAndOpensNothingElse() {
        val rec = FakeRecorder()
        val op = opened(rec)
        val s = source(op)
        assertNull(s.start(AudioConfig(), Sink()))
        assertEquals("Microphone already running", s.start(AudioConfig(), Sink()))
        assertEquals(1, op.opens.get())
        assertTrue(s.isRunning())
        s.stop()
    }

    // ---- error handling
    @Test fun readErrorReportsOnceAndReleases() {
        val rec = FakeRecorder(FakeRecorder.Mode.READ_ERROR)
        val s = source(opened(rec))
        val l = Sink()
        assertNull(s.start(AudioConfig(), l))
        assertTrue(l.errored.await(2, TimeUnit.SECONDS))
        assertTrue(rec.released.await(2, TimeUnit.SECONDS))
        assertEquals(1, l.errors.get())
        assertEquals(1, rec.releaseCalls.get())
        assertEquals("Microphone read failed (code -6)", l.lastError)
        assertEquals(0, l.frames.get())
        assertEquals(l.lastError, s.lastError)
    }

    @Test fun recorderThatNeverDeliversEndsWithAnError() {
        val rec = FakeRecorder(FakeRecorder.Mode.EMPTY)
        val s = source(opened(rec))
        val l = Sink()
        s.start(AudioConfig(), l)
        assertTrue(l.errored.await(5, TimeUnit.SECONDS))
        assertEquals("Microphone delivered no data", l.lastError)
        assertTrue(rec.released.await(2, TimeUnit.SECONDS))
    }

    @Test fun listenerThatThrowsStillReleasesAndNamesOnlyTheClass() {
        val rec = FakeRecorder()
        val s = source(opened(rec))
        val l = Sink()
        val bad = object : AudioSource.Listener {
            override fun onFrame(pcm: ByteArray) { throw IllegalArgumentException("secret payload text") }
            override fun onError(message: String) { l.onError(message) }
        }
        s.start(AudioConfig(), bad)
        assertTrue(l.errored.await(2, TimeUnit.SECONDS))
        assertTrue(rec.released.await(2, TimeUnit.SECONDS))
        assertEquals("Microphone error: IllegalArgumentException", l.lastError)
        assertFalse(l.lastError!!.contains("secret"))
    }

    @Test fun readErrorTextCarriesOnlyTheCodeNotTheAudioReadBefore() {
        // One frame of a recognisable pattern is delivered first; the later error text is only the code.
        val rec = object : MicrophoneRecorder {
            var calls = 0
            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (++calls == 1) { java.util.Arrays.fill(buf, off, off + len, 0x7B.toByte()); return len }
                return -1
            }
            override fun stop() {}
            override fun release() {}
        }
        val l = Sink()
        val s = source(FakeOpener { MicrophoneOpenResult.Opened(rec) })
        s.start(AudioConfig(), l)
        assertTrue(l.errored.await(2, TimeUnit.SECONDS))
        assertEquals("Microphone read failed (code -1)", l.lastError)
    }

    // ---- resource cleanup
    @Test fun stopWhileReadIsBlockedStillReleases() {
        val gate = CountDownLatch(1)
        val rec = object : MicrophoneRecorder {
            val released = CountDownLatch(1)
            override fun read(buf: ByteArray, off: Int, len: Int): Int { gate.await(2, TimeUnit.SECONDS); return -3 }
            override fun stop() { gate.countDown() }       // like AudioRecord.stop(): a blocked read returns
            override fun release() { released.countDown() }
        }
        val s = source(FakeOpener { MicrophoneOpenResult.Opened(rec) })
        val l = Sink()
        assertNull(s.start(AudioConfig(), l))
        s.stop()
        assertTrue(rec.released.await(2, TimeUnit.SECONDS))
        assertEquals(0, l.errors.get())
    }
}
