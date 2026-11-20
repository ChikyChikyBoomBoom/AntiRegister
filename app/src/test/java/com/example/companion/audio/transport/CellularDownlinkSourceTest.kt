package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 32A tests, adapted in Stage 32B for the new constructor (an injected opener). WRITTEN BUT NOT RUN.
 * Selection behaviour itself is covered in [CellularSourceSelectorTest].
 */
class CellularDownlinkSourceTest {
    private class NoFrames : AudioSource.Listener {
        var frames = 0; var errors = 0
        override fun onFrame(pcm: ByteArray) { frames++ }
        override fun onError(message: String) { errors++ }
    }
    private val failing = CellularRecorderOpener { _, _ -> CellularOpenResult.Failed("x") }

    @Test fun errorTextIsExact() {
        assertEquals("needs CAPTURE_AUDIO_OUTPUT (privileged app)", CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT.message)
    }

    @Test fun whenAllSourcesFailStartReportsPrivilegedError() {
        val s = CellularDownlinkSource({ false }, failing)
        val l = NoFrames()
        assertEquals(null, s.lastError)
        assertTrue(s.start(AudioConfig(), l)!!.startsWith("needs CAPTURE_AUDIO_OUTPUT (privileged app)"))
        assertEquals(CellularDownlinkError.NEEDS_CAPTURE_AUDIO_OUTPUT, s.lastError)
        assertEquals(0, l.frames); assertEquals(0, l.errors)
    }

    @Test fun isACellularAudioSourceWithName() {
        val s: AudioSource = CellularDownlinkSource({ false }, failing)
        assertTrue(s is CellularAudioSource)
        assertEquals("Cellular downlink", s.name)
    }
}
