package com.example.companion.audio.transport

import java.nio.ByteBuffer

/**
 * Stage 38: the call-audio meaning of a stream direction.
 *  - [UPLINK]   = Main Phone -> SIM Phone (what the Main Phone user says; wire id 1)
 *  - [DOWNLINK] = SIM Phone -> Main Phone (what the far end says; wire id 2)
 * This is a VIEW of the existing wire direction, not a new wire value: the header byte and the AUDIO_START `dir`
 * field keep ids 1 and 2, so Stage 30-37 peers interoperate unchanged and the packet VERSION stays 1.
 * Carrying a frame in this enum says nothing about whether cellular uplink injection works (it is not verified).
 */
enum class FrameDirection {
    UPLINK, DOWNLINK;
    /** The wire direction this maps to. */
    val wire: AudioDirection get() = if (this == UPLINK) AudioDirection.MAIN_TO_SIM else AudioDirection.SIM_TO_MAIN
}

/** Direction of an audio stream. Fixed by role, so a phone can recognise (and refuse) its own echo. */
enum class AudioDirection(val id: Int) {
    MAIN_TO_SIM(1), SIM_TO_MAIN(2);
    val opposite get() = if (this == MAIN_TO_SIM) SIM_TO_MAIN else MAIN_TO_SIM
    /** Stage 38: MAIN_TO_SIM is UPLINK, SIM_TO_MAIN is DOWNLINK. */
    val frameDirection: FrameDirection get() = if (this == MAIN_TO_SIM) FrameDirection.UPLINK else FrameDirection.DOWNLINK
    companion object { fun fromId(id: Int) = values().firstOrNull { it.id == id } }
}

/**
 * One binary audio frame (big-endian). Travels inside a MessageTransport frame of kind AUDIO, never as JSON.
 *
 *   u8  version      = 1
 *   u8  direction    (AudioDirection.id)
 *   u8  codec        (CodecId.id; payload is PCM16 little-endian mono for codec 0)
 *   u8  session      low 8 bits of the AUDIO_START session id (discards stale packets after a restart)
 *   u32 sequence     +1 per frame, starts at 0
 *   u32 timestamp    ms since the stream started (sender's monotonic clock)
 *   u16 payloadLen
 *   ... payload
 */
class AudioPacket(
    val direction: AudioDirection,
    val codec: CodecId,
    val session: Int,
    val sequence: Long,
    val timestampMs: Long,
    val payload: ByteArray
) {
    /** Stage 38: call-audio direction of this frame. */
    val frameDirection: FrameDirection get() = direction.frameDirection
    val isUplink get() = frameDirection == FrameDirection.UPLINK
    val isDownlink get() = frameDirection == FrameDirection.DOWNLINK

    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        b.put(VERSION.toByte()).put(direction.id.toByte()).put(codec.id.toByte()).put(session.toByte())
        b.putInt(sequence.toInt()).putInt(timestampMs.toInt()).putShort(payload.size.toShort())
        b.put(payload)
        return b.array()
    }

    companion object {
        const val VERSION = 1
        const val HEADER_SIZE = 14
        const val MAX_PAYLOAD = 4096

        /** Null for anything malformed or from another protocol version. Never throws. */
        fun decode(bytes: ByteArray): AudioPacket? {
            if (bytes.size < HEADER_SIZE) return null
            val b = ByteBuffer.wrap(bytes)
            if (b.get().toInt() and 0xFF != VERSION) return null
            val dir = AudioDirection.fromId(b.get().toInt() and 0xFF) ?: return null
            val codec = CodecId.fromId(b.get().toInt() and 0xFF) ?: return null
            val session = b.get().toInt() and 0xFF
            val seq = b.getInt().toLong() and 0xFFFFFFFFL
            val ts = b.getInt().toLong() and 0xFFFFFFFFL
            val len = b.getShort().toInt() and 0xFFFF
            if (len > MAX_PAYLOAD || len != bytes.size - HEADER_SIZE) return null
            return AudioPacket(dir, codec, session, seq, ts, bytes.copyOfRange(HEADER_SIZE, bytes.size))
        }
    }
}
