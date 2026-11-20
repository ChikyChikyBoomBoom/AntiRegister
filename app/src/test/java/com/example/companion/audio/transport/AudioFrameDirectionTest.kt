package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Stage 38 JVM tests (written; NOT executed - no Kotlin/Gradle/JUnit available when authored). */
class AudioFrameDirectionTest {

    private fun pkt(dir: AudioDirection, seq: Long = 0, session: Int = 7, payload: ByteArray = ByteArray(640) { it.toByte() }) =
        AudioPacket(dir, CodecId.PCM16, session, seq, 1234, payload)

    // ---- identification
    @Test fun uplinkIsMainToSimAndDownlinkIsSimToMain() {
        assertEquals(FrameDirection.UPLINK, AudioDirection.MAIN_TO_SIM.frameDirection)
        assertEquals(FrameDirection.DOWNLINK, AudioDirection.SIM_TO_MAIN.frameDirection)
        assertEquals(AudioDirection.MAIN_TO_SIM, FrameDirection.UPLINK.wire)
        assertEquals(AudioDirection.SIM_TO_MAIN, FrameDirection.DOWNLINK.wire)
    }

    @Test fun packetIdentifiesItsDirection() {
        assertTrue(pkt(AudioDirection.MAIN_TO_SIM).isUplink)
        assertFalse(pkt(AudioDirection.MAIN_TO_SIM).isDownlink)
        assertTrue(pkt(AudioDirection.SIM_TO_MAIN).isDownlink)
        assertFalse(pkt(AudioDirection.SIM_TO_MAIN).isUplink)
    }

    @Test fun decodedPacketKeepsDirection() {
        assertTrue(AudioPacket.decode(pkt(AudioDirection.MAIN_TO_SIM).encode())!!.isUplink)
        assertTrue(AudioPacket.decode(pkt(AudioDirection.SIM_TO_MAIN).encode())!!.isDownlink)
    }

    // ---- wire compatibility (bytes pinned)
    @Test fun wireIdsAndVersionAreUnchanged() {
        assertEquals(1, AudioDirection.MAIN_TO_SIM.id)
        assertEquals(2, AudioDirection.SIM_TO_MAIN.id)
        assertEquals(1, AudioPacket.VERSION)
        assertEquals(14, AudioPacket.HEADER_SIZE)
    }

    @Test fun headerBytesAreExactlyTheStage5Layout() {
        val b = pkt(AudioDirection.MAIN_TO_SIM, seq = 0x01020304, session = 9, payload = byteArrayOf(5, 6)).encode()
        assertEquals(16, b.size)
        assertEquals(1, b[0].toInt())                 // version
        assertEquals(1, b[1].toInt())                 // direction id (uplink)
        assertEquals(0, b[2].toInt())                 // codec PCM16
        assertEquals(9, b[3].toInt())                 // session
        assertEquals(0x01020304, ByteBuffer.wrap(b, 4, 4).int)
        assertEquals(1234, ByteBuffer.wrap(b, 8, 4).int)
        assertEquals(2, ByteBuffer.wrap(b, 12, 2).short.toInt())
        assertEquals(5, b[14].toInt()); assertEquals(6, b[15].toInt())
    }

    @Test fun handBuiltStage5FrameStillDecodesAsDownlink() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bb = ByteBuffer.allocate(14 + 4)
        bb.put(1).put(2).put(0).put(3).putInt(42).putInt(100).putShort(4).put(payload)
        val p = AudioPacket.decode(bb.array())!!
        assertTrue(p.isDownlink); assertEquals(3, p.session); assertEquals(42L, p.sequence)
        assertEquals(100L, p.timestampMs); assertTrue(payload.contentEquals(p.payload))
    }

    // ---- serialization round trip
    @Test fun roundTripBothDirections() {
        for (d in AudioDirection.values()) {
            val src = pkt(d, seq = 99, session = 200)
            val got = AudioPacket.decode(src.encode())!!
            assertEquals(d, got.direction); assertEquals(99L, got.sequence); assertEquals(200, got.session)
            assertEquals(1234L, got.timestampMs); assertTrue(src.payload.contentEquals(got.payload))
        }
    }

    @Test fun sequenceUsesFullUnsigned32BitRange() {
        val got = AudioPacket.decode(pkt(AudioDirection.SIM_TO_MAIN, seq = 0xFFFFFFFFL).encode())!!
        assertEquals(0xFFFFFFFFL, got.sequence)
    }

    // ---- invalid frames
    @Test fun emptyAndShortInputIsRejected() {
        assertNull(AudioPacket.decode(ByteArray(0)))
        assertNull(AudioPacket.decode(ByteArray(13)))
    }

    @Test fun unsupportedVersionIsRejected() {
        val b = pkt(AudioDirection.SIM_TO_MAIN).encode(); b[0] = 2
        assertNull(AudioPacket.decode(b))
        b[0] = 0; assertNull(AudioPacket.decode(b))
    }

    @Test fun unknownDirectionIsRejected() {
        for (bad in listOf(0, 3, 255)) {
            val b = pkt(AudioDirection.SIM_TO_MAIN).encode(); b[1] = bad.toByte()
            assertNull("direction $bad", AudioPacket.decode(b))
        }
    }

    @Test fun unknownCodecIsRejected() {
        val b = pkt(AudioDirection.SIM_TO_MAIN).encode(); b[2] = 9
        assertNull(AudioPacket.decode(b))
    }

    @Test fun lengthMismatchIsRejected() {
        val ok = pkt(AudioDirection.SIM_TO_MAIN).encode()
        assertNull(AudioPacket.decode(ok.copyOf(ok.size - 1)))          // truncated
        assertNull(AudioPacket.decode(ok.copyOf(ok.size + 1)))          // trailing byte
    }

    @Test fun oversizedPayloadIsRejected() {
        val big = pkt(AudioDirection.SIM_TO_MAIN, payload = ByteArray(AudioPacket.MAX_PAYLOAD + 1)).encode()
        assertNull(AudioPacket.decode(big))
        val max = pkt(AudioDirection.SIM_TO_MAIN, payload = ByteArray(AudioPacket.MAX_PAYLOAD)).encode()
        assertNotNull(AudioPacket.decode(max))
    }

    @Test fun randomGarbageNeverThrows() {
        val r = java.util.Random(38)
        repeat(2000) { AudioPacket.decode(ByteArray(r.nextInt(80)).also { r.nextBytes(it) }) }
    }

    // ---- receive filter (used by AudioTransport.onAudioBytes)
    @Test fun filterAcceptsMatchingFrame() {
        val r = AudioFrameFilter.classify(pkt(AudioDirection.SIM_TO_MAIN, session = 7).encode(), AudioDirection.SIM_TO_MAIN, 7, CodecId.PCM16)
        assertEquals(AudioFrameFilter.Verdict.ACCEPT, r.verdict); assertNotNull(r.packet)
    }

    @Test fun filterRejectsOppositeDirectionSoUplinkNeverEntersDownlinkStream() {
        val r = AudioFrameFilter.classify(pkt(AudioDirection.MAIN_TO_SIM, session = 7).encode(), AudioDirection.SIM_TO_MAIN, 7, CodecId.PCM16)
        assertEquals(AudioFrameFilter.Verdict.WRONG_DIRECTION, r.verdict)
    }

    @Test fun filterRejectsStaleSessionAndMalformed() {
        val e = pkt(AudioDirection.SIM_TO_MAIN, session = 7).encode()
        assertEquals(AudioFrameFilter.Verdict.WRONG_SESSION, AudioFrameFilter.classify(e, AudioDirection.SIM_TO_MAIN, 8, CodecId.PCM16).verdict)
        val bad = AudioFrameFilter.classify(ByteArray(3), AudioDirection.SIM_TO_MAIN, 7, CodecId.PCM16)
        assertEquals(AudioFrameFilter.Verdict.MALFORMED, bad.verdict); assertNull(bad.packet)
    }
}
