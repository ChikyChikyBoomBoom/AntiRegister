package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 39 JVM tests (written; NOT executed - no Kotlin compiler / Gradle available when authored). */
class UnsupportedUplinkSinkTest {

    @Test fun headlineIsExact() {
        assertEquals("Not supported / not verified", UplinkMessages.NOT_SUPPORTED)
    }

    @Test fun capabilityIsNeverSupported() {
        val c = UnsupportedUplinkSink().capability
        assertFalse(c.isSupported)
        assertEquals(UplinkSupport.NOT_SUPPORTED, c.support)
        assertEquals(UplinkUnsupportedReason.NO_VERIFIED_SINK, c.reason)
        assertEquals("Not supported / not verified", c.headline)
        assertTrue(c.detail.isNotBlank())
    }

    @Test fun everyCapabilityFactoryIsUnsupported() {
        for (r in UplinkUnsupportedReason.values()) {
            val c = UplinkCapability.notSupported(r, "x")
            assertFalse(c.isSupported)
            assertEquals(UplinkMessages.NOT_SUPPORTED, c.headline)
        }
    }

    @Test fun openRefusesWithTheExactHeadline() {
        val sink = UnsupportedUplinkSink()
        for (rate in AudioConfig.SUPPORTED_RATES) assertEquals("Not supported / not verified", sink.open(AudioConfig(sampleRate = rate)))
        assertTrue(UplinkMessages.isUplinkNotSupported(sink.open(AudioConfig())))
    }

    @Test fun writeAcceptsNothingSoSuccessIsNeverSimulated() {
        val sink = UnsupportedUplinkSink()
        sink.open(AudioConfig())
        assertFalse(sink.write(ByteArray(AudioConfig().frameBytes)))
        assertFalse(sink.write(ByteArray(0)))
        assertEquals(0, sink.bufferMs)
        assertEquals(0, sink.underruns)
    }

    @Test fun closeIsIdempotent() {
        val sink = UnsupportedUplinkSink()
        sink.close(); sink.close()
        sink.open(AudioConfig()); sink.close(); sink.close()
    }

    @Test fun isAnAudioOutputAndAnUplinkSinkButNotTheLocalSpeaker() {
        val sink: AudioOutput = UnsupportedUplinkSink()
        assertTrue(sink is UplinkSink)
        assertFalse(sink is AudioTrackOutput)
        assertNotNull(sink.name)
    }

    @Test fun customDetailIsKept() {
        assertEquals("because", UnsupportedUplinkSink("because").capability.detail)
    }

    @Test fun unsupportedRefusalIsNotAnAutomaticRetryReason() {
        // AudioStartRetry retries only "Not listening..." refusals: an unsupported sink must end the run, not loop.
        assertFalse(AudioTransportMessages.isNotListening(UplinkMessages.NOT_SUPPORTED))
    }
}
