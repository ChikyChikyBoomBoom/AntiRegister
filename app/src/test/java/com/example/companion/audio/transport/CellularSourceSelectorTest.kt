package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 32B JVM tests (selection; the source-level success / lifecycle tests moved to CellularCaptureLifecycleTest in 32C). WRITTEN BUT NOT RUN: no Gradle / Android SDK was available. Fakes only, no Android classes. */
class CellularSourceSelectorTest {
    class FakeRecorder : CellularRecorder {
        var releases = 0
        override fun read(buf: ByteArray, off: Int, len: Int) = -1
        override fun stop() {}
        override fun release() { releases++ }
    }

    /** Records every attempt; [results] decides per source. A missing entry = Failed("not scripted"). */
    class FakeOpener(private val results: Map<CellularCaptureSource, () -> CellularOpenResult>) : CellularRecorderOpener {
        val attempts = ArrayList<CellularCaptureSource>()
        override fun open(source: CellularCaptureSource, config: AudioConfig): CellularOpenResult {
            attempts += source
            return results[source]?.invoke() ?: CellularOpenResult.Failed("not scripted")
        }
    }

    private val cfg = AudioConfig()

    @Test fun orderIsDownlinkThenCall() {
        assertEquals(listOf(CellularCaptureSource.VOICE_DOWNLINK, CellularCaptureSource.VOICE_CALL), CellularSourceSelector.ORDER)
        assertEquals("MediaRecorder.AudioSource.VOICE_DOWNLINK", CellularCaptureSource.VOICE_DOWNLINK.label)
        assertEquals("MediaRecorder.AudioSource.VOICE_CALL", CellularCaptureSource.VOICE_CALL.label)
    }

    @Test fun downlinkIsAttemptedFirst() {
        val opener = FakeOpener(emptyMap())
        CellularSourceSelector(opener).select(cfg)
        assertEquals(CellularCaptureSource.VOICE_DOWNLINK, opener.attempts.first())
    }

    @Test fun noFallbackWhenFirstSucceeds() {
        val rec = FakeRecorder()
        val opener = FakeOpener(mapOf(CellularCaptureSource.VOICE_DOWNLINK to { CellularOpenResult.Opened(rec) }))
        val sel = CellularSourceSelector(opener).select(cfg)
        assertEquals(listOf(CellularCaptureSource.VOICE_DOWNLINK), opener.attempts)
        sel as CellularSelection.Selected
        assertEquals(CellularCaptureSource.VOICE_DOWNLINK, sel.source)
        assertTrue(sel.failedBefore.isEmpty())
        assertEquals(0, rec.releases)   // ownership passes to the caller
    }

    @Test fun voiceCallAttemptedAfterDownlinkFails() {
        val rec = FakeRecorder()
        val opener = FakeOpener(mapOf(
            CellularCaptureSource.VOICE_DOWNLINK to { CellularOpenResult.Failed("state=0") },
            CellularCaptureSource.VOICE_CALL to { CellularOpenResult.Opened(rec) }))
        val sel = CellularSourceSelector(opener).select(cfg)
        assertEquals(listOf(CellularCaptureSource.VOICE_DOWNLINK, CellularCaptureSource.VOICE_CALL), opener.attempts)
        sel as CellularSelection.Selected
        assertEquals(CellularCaptureSource.VOICE_CALL, sel.source)
        assertEquals(listOf(CellularAttempt(CellularCaptureSource.VOICE_DOWNLINK, "state=0")), sel.failedBefore)
    }

    @Test fun openerExceptionCountsAsFailureAndFallsBack() {
        val opener = FakeOpener(mapOf(
            CellularCaptureSource.VOICE_DOWNLINK to { throw IllegalStateException("boom") },
            CellularCaptureSource.VOICE_CALL to { CellularOpenResult.Opened(FakeRecorder()) }))
        val sel = CellularSourceSelector(opener).select(cfg)
        sel as CellularSelection.Selected
        assertEquals(CellularCaptureSource.VOICE_CALL, sel.source)
        assertTrue(sel.failedBefore.single().reason.contains("boom"))
    }

    @Test fun bothFailKeepsBothReasonsInOrder() {
        val opener = FakeOpener(mapOf(
            CellularCaptureSource.VOICE_DOWNLINK to { CellularOpenResult.Failed("SecurityException: denied") },
            CellularCaptureSource.VOICE_CALL to { CellularOpenResult.Failed("state=0") }))
        val sel = CellularSourceSelector(opener).select(cfg)
        sel as CellularSelection.AllFailed
        assertEquals(listOf(
            CellularAttempt(CellularCaptureSource.VOICE_DOWNLINK, "SecurityException: denied"),
            CellularAttempt(CellularCaptureSource.VOICE_CALL, "state=0")), sel.attempts)
        assertEquals(2, opener.attempts.size)
    }

    @Test fun sourceMapsFailureToPrivilegedErrorWithUnderlyingReasons() {
        val opener = FakeOpener(mapOf(
            CellularCaptureSource.VOICE_DOWNLINK to { CellularOpenResult.Failed("SecurityException: denied") },
            CellularCaptureSource.VOICE_CALL to { CellularOpenResult.Failed("state=0") }))
        val s = CellularDownlinkSource({ false }, opener)
        val msg = s.start(cfg, NoFrames())!!
        assertTrue(msg.startsWith("needs CAPTURE_AUDIO_OUTPUT (privileged app)"))
        assertTrue(msg.contains("VOICE_DOWNLINK: SecurityException: denied"))
        assertTrue(msg.contains("VOICE_CALL: state=0"))
        assertTrue(msg.contains("CAPTURE_AUDIO_OUTPUT not granted"))
        assertEquals(CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT, s.lastError)
        assertEquals(msg, s.lastErrorDetail)
        assertEquals(2, s.lastAttempts.size)
        assertEquals(null, s.selectedSource)
    }

    @Test fun permissionCheckNeverSkipsAttempts() {
        val opener = FakeOpener(emptyMap())
        CellularDownlinkSource({ false }, opener).start(cfg, NoFrames())
        assertEquals(2, opener.attempts.size)
    }

    @Test fun errorTextIsExact() {
        assertEquals("needs CAPTURE_AUDIO_OUTPUT (privileged app)", CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT.message)
    }

    private class NoFrames : AudioSource.Listener {
        var frames = 0; var errors = 0
        override fun onFrame(pcm: ByteArray) { frames++ }
        override fun onError(message: String) { errors++ }
    }
}
