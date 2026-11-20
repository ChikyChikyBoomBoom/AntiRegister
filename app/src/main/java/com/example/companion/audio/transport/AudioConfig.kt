package com.example.companion.audio.transport

import org.json.JSONObject

/** Codec identifiers carried in every packet header. Only raw PCM exists today; add new ids here later. */
enum class CodecId(val id: Int) {
    PCM16(0);
    companion object { fun fromId(id: Int) = values().firstOrNull { it.id == id } }
}

/** Stream format. Mono, 16-bit little-endian PCM. Rate/frame length are configurable and negotiated in AUDIO_START. */
data class AudioConfig(
    val sampleRate: Int = DEFAULT_RATE,
    val channels: Int = 1,
    val frameMs: Int = DEFAULT_FRAME_MS,
    val codec: CodecId = CodecId.PCM16
) {
    val frameSamples get() = sampleRate * frameMs / 1000
    val frameBytes get() = frameSamples * channels * 2
    /** Raw payload bandwidth in kbit/s (headers add ~2 %). */
    val kbps get() = sampleRate * channels * 16 / 1000

    fun toJson(): JSONObject = JSONObject().put("rate", sampleRate).put("ch", channels).put("frameMs", frameMs).put("codec", codec.id)

    companion object {
        const val DEFAULT_RATE = 16_000
        const val DEFAULT_FRAME_MS = 20
        val SUPPORTED_RATES = intArrayOf(8_000, 16_000)

        /** Returns null for anything this build cannot handle (so a bad or newer peer is refused, never guessed at). */
        fun fromJson(o: JSONObject?): AudioConfig? {
            o ?: return null
            val c = AudioConfig(o.optInt("rate", -1), o.optInt("ch", -1), o.optInt("frameMs", -1),
                CodecId.fromId(o.optInt("codec", -1)) ?: return null)
            val ok = c.sampleRate in SUPPORTED_RATES && c.channels == 1 && c.frameMs in 10..60 &&
                (c.sampleRate * c.frameMs) % 1000 == 0
            return if (ok) c else null
        }
    }
}

/** Receive-side buffering. Local setting only (not negotiated); change it live from the UI. */
data class JitterConfig(val targetMs: Int = 80, val maxMs: Int = 200) {
    companion object { val PRESETS = listOf(40, 80, 120, 200).map { JitterConfig(it, it + 120) } }
}

/** Hook for a future codec (Opus, etc.). Today only [PcmCodec] exists, a pass-through. */
interface AudioCodec {
    val id: CodecId
    fun encode(pcm: ByteArray): ByteArray
    fun decode(data: ByteArray): ByteArray
}

object PcmCodec : AudioCodec {
    override val id = CodecId.PCM16
    override fun encode(pcm: ByteArray) = pcm
    override fun decode(data: ByteArray) = data
}

object Codecs {
    fun forId(id: CodecId): AudioCodec = when (id) { CodecId.PCM16 -> PcmCodec }
}
