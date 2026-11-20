package com.example.companion.audio.transport

import com.example.companion.audio.transport.CellularCaptureSource.VOICE_CALL
import com.example.companion.audio.transport.CellularCaptureSource.VOICE_DOWNLINK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stage 33A JVM tests (configuration order + fallback selection). WRITTEN BUT NOT RUN: no Gradle / Android SDK was available. Fakes only, no Android classes, no Bluetooth. */
class CellularAudioConfigurationTest {
    private val dl16 = CellularAudioConfiguration(VOICE_DOWNLINK, 16_000)
    private val call16 = CellularAudioConfiguration(VOICE_CALL, 16_000)
    private val dl8 = CellularAudioConfiguration(VOICE_DOWNLINK, 8_000)
    private val call8 = CellularAudioConfiguration(VOICE_CALL, 8_000)

    // ---- 1. exact order -------------------------------------------------------------------------------------------------------

    @Test fun orderIsExactlyTheFourConfigurations() {
        assertEquals(listOf(dl16, call16, dl8, call8), CellularAudioConfiguration.ORDER)
    }

    @Test fun orderIsUsedByDefaultAndAttemptedInThatOrder() {
        val tried = ArrayList<CellularAudioConfiguration>()
        val result = CellularConfigFallback().selectFirst { tried += it; "fail" }
        assertEquals(listOf(dl16, call16, dl8, call8), tried)
        result as CellularConfigResult.AllFailed
        assertEquals(listOf(dl16, call16, dl8, call8), result.attempts.map { it.config })
    }

    @Test fun toAudioConfigCarriesRateAndKeepsDefaultFormat() {
        val c = dl8.toAudioConfig()
        assertEquals(8_000, c.sampleRate)
        assertEquals(1, c.channels)
        assertEquals(AudioConfig.DEFAULT_FRAME_MS, c.frameMs)
        assertEquals(16_000, call16.toAudioConfig().sampleRate)
        // both rates must be ones AudioConfig accepts
        assertTrue(CellularAudioConfiguration.ORDER.all { it.sampleRate in AudioConfig.SUPPORTED_RATES })
    }

    @Test fun firstConfigurationIsCurrentAtStart() {
        assertEquals(dl16, CellularConfigFallback().current)
    }

    // ---- 2. transition after initialization failure ---------------------------------------------------------------------------

    @Test fun initFailureMovesToTheNextConfigurationOnly() {
        val f = CellularConfigFallback()
        val d1 = f.onEvent(CellularConfigEvent.InitFailed("state=0")) as CellularConfigDecision.Fallback
        assertEquals(call16, d1.next)
        assertEquals(call16, f.current)
        val d2 = f.onEvent(CellularConfigEvent.InitFailed("SecurityException")) as CellularConfigDecision.Fallback
        assertEquals(dl8, d2.next)
        val d3 = f.onEvent(CellularConfigEvent.InitFailed("recordingState=1")) as CellularConfigDecision.Fallback
        assertEquals(call8, d3.next)
        assertEquals(listOf(dl16, call16, dl8), f.failedAttempts.map { it.config })
        assertEquals(listOf("state=0", "SecurityException", "recordingState=1"), f.failedAttempts.map { it.reason })
    }

    @Test fun lastFailureExhaustsAndKeepsEveryReason() {
        val f = CellularConfigFallback()
        repeat(3) { f.onEvent(CellularConfigEvent.InitFailed("r$it")) }
        val d = f.onEvent(CellularConfigEvent.InitFailed("r3")) as CellularConfigDecision.Exhausted
        assertEquals(listOf("r0", "r1", "r2", "r3"), d.attempts.map { it.reason })
        assertNull(f.current)
        assertTrue(f.onEvent(CellularConfigEvent.Started) is CellularConfigDecision.Exhausted)   // nothing to keep
    }

    @Test fun runSucceedsOnTheSecondConfigurationAfterOneFailure() {
        val tried = ArrayList<CellularAudioConfiguration>()
        val result = CellularConfigFallback().selectFirst { tried += it; if (it == call16) null else "no" }
        assertEquals(listOf(dl16, call16), tried)
        result as CellularConfigResult.Selected
        assertEquals(call16, result.config)
        assertEquals(listOf(CellularConfigAttempt(dl16, "no")), result.failedBefore)
    }

    @Test fun rateDropsOnlyAfterBothSourcesFailedAtTheHigherRate() {
        val tried = ArrayList<CellularAudioConfiguration>()
        val result = CellularConfigFallback().selectFirst { tried += it; if (it.sampleRate == 8_000) null else "no" }
        assertEquals(listOf(dl16, call16, dl8), tried)
        result as CellularConfigResult.Selected
        assertEquals(dl8, result.config)
    }

    @Test fun attemptExceptionCountsAsFailureAndFallsBack() {
        val result = CellularConfigFallback().selectFirst { if (it == dl16) throw IllegalStateException("boom") else null }
        result as CellularConfigResult.Selected
        assertEquals(call16, result.config)
        assertEquals("IllegalStateException: boom", result.failedBefore.single().reason)
    }

    @Test fun resetStartsOverFromTheFirstConfiguration() {
        val f = CellularConfigFallback()
        repeat(4) { f.onEvent(CellularConfigEvent.InitFailed("x")) }
        f.reset()
        assertEquals(dl16, f.current)
        assertTrue(f.failedAttempts.isEmpty())
    }

    // ---- 3. no fallback when a configuration succeeds -------------------------------------------------------------------------

    @Test fun successOnFirstConfigurationAttemptsNothingElse() {
        val tried = ArrayList<CellularAudioConfiguration>()
        val result = CellularConfigFallback().selectFirst { tried += it; null }
        assertEquals(listOf(dl16), tried)
        result as CellularConfigResult.Selected
        assertEquals(dl16, result.config)
        assertTrue(result.failedBefore.isEmpty())
    }

    @Test fun startedEventKeepsTheCurrentConfiguration() {
        val f = CellularConfigFallback()
        val d = f.onEvent(CellularConfigEvent.Started) as CellularConfigDecision.Keep
        assertEquals(dl16, d.config)
        assertEquals(dl16, f.current)
        assertTrue(f.failedAttempts.isEmpty())
    }

    // ---- 4. NOT_LISTENING never lowers quality --------------------------------------------------------------------------------

    @Test fun notListeningKeepsTheCurrentConfiguration() {
        val f = CellularConfigFallback()
        val d = f.onEvent(CellularConfigEvent.ReceiverRefused(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN)) as CellularConfigDecision.Keep
        assertEquals(dl16, d.config)
        assertTrue(d.notListening)
        assertEquals(dl16, f.current)
        assertTrue(f.failedAttempts.isEmpty())
    }

    @Test fun repeatedNotListeningNeverReducesSampleRateOrSource() {
        val f = CellularConfigFallback()
        repeat(10) { f.onEvent(CellularConfigEvent.ReceiverRefused(AudioTransportMessages.NOT_LISTENING)) }
        assertEquals(dl16, f.current)
        assertEquals(16_000, f.current!!.sampleRate)
    }

    @Test fun notListeningAfterAFallbackStaysOnThatConfiguration() {
        val f = CellularConfigFallback()
        f.onEvent(CellularConfigEvent.InitFailed("no"))
        f.onEvent(CellularConfigEvent.InitFailed("no"))          // now dl8
        f.onEvent(CellularConfigEvent.ReceiverRefused(AudioTransportMessages.NOT_LISTENING))
        assertEquals(dl8, f.current)
        assertEquals(2, f.failedAttempts.size)
    }

    @Test fun anyReceiverRefusalIsNotAnInitializationFailure() {
        val f = CellularConfigFallback()
        val other = f.onEvent(CellularConfigEvent.ReceiverRefused("Unsupported audio format")) as CellularConfigDecision.Keep
        assertEquals(false, other.notListening)
        assertTrue(f.onEvent(CellularConfigEvent.ReceiverRefused(null)) is CellularConfigDecision.Keep)
        assertEquals(dl16, f.current)
    }

    // ---- with the real CellularDownlinkSource (fake opener, no Android) ---------------------------------------------------------

    private class BlockingRecorder : CellularRecorder {
        val stopped = CountDownLatch(1)
        override fun read(buf: ByteArray, off: Int, len: Int): Int { stopped.await(2, TimeUnit.SECONDS); return -1 }
        override fun stop() { stopped.countDown() }
        override fun release() { stopped.countDown() }
    }

    private class RecordingOpener(private val succeedOn: Pair<CellularCaptureSource, Int>?) : CellularRecorderOpener {
        val calls = ArrayList<Pair<CellularCaptureSource, Int>>()
        override fun open(source: CellularCaptureSource, config: AudioConfig): CellularOpenResult {
            calls += source to config.sampleRate
            return if (succeedOn == (source to config.sampleRate)) CellularOpenResult.Opened(BlockingRecorder())
            else CellularOpenResult.Failed("not scripted")
        }
    }

    private val listener = object : AudioSource.Listener {
        override fun onFrame(pcm: ByteArray) {}
        override fun onError(message: String) {}
    }

    @Test fun sourceTriesEachConfigurationExactlyOnceInOrderWhenAllFail() {
        val opener = RecordingOpener(null)
        val src = CellularDownlinkSource({ false }, opener)
        val result = CellularConfigFallback().selectFirst { src.startWithConfiguration(it, listener) }
        assertTrue(result is CellularConfigResult.AllFailed)
        // no hidden second source attempt inside the source: exactly one open() per configuration
        assertEquals(listOf(VOICE_DOWNLINK to 16_000, VOICE_CALL to 16_000, VOICE_DOWNLINK to 8_000, VOICE_CALL to 8_000), opener.calls)
    }

    @Test fun sourceStopsFallingBackAtTheFirstConfigurationThatStarts() {
        val opener = RecordingOpener(VOICE_DOWNLINK to 8_000)
        val src = CellularDownlinkSource({ false }, opener)
        try {
            val result = CellularConfigFallback().selectFirst { src.startWithConfiguration(it, listener) }
            result as CellularConfigResult.Selected
            assertEquals(dl8, result.config)
            assertEquals(listOf(VOICE_DOWNLINK to 16_000, VOICE_CALL to 16_000, VOICE_DOWNLINK to 8_000), opener.calls)
            assertEquals(VOICE_DOWNLINK, src.selectedSource)
        } finally { src.stop() }
    }

    @Test fun sourceDoesNotFallBackWhenFirstConfigurationStarts() {
        val opener = RecordingOpener(VOICE_DOWNLINK to 16_000)
        val src = CellularDownlinkSource({ false }, opener)
        try {
            val result = CellularConfigFallback().selectFirst { src.startWithConfiguration(it, listener) }
            assertEquals(dl16, (result as CellularConfigResult.Selected).config)
            assertEquals(listOf(VOICE_DOWNLINK to 16_000), opener.calls)
        } finally { src.stop() }
    }

    @Test fun plainStartStillUsesTheOldDownlinkThenCallOrder() {
        val opener = RecordingOpener(null)
        CellularDownlinkSource({ false }, opener).start(AudioConfig(), listener)
        assertEquals(listOf(VOICE_DOWNLINK to 16_000, VOICE_CALL to 16_000), opener.calls)
    }
}
