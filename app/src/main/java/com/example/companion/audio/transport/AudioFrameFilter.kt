package com.example.companion.audio.transport

/**
 * Stage 38: the receive-side "may this frame enter the buffer?" decision, extracted from
 * [AudioTransport.onAudioBytes] so it is Android-free and unit-testable. The accepted set is exactly what the inline
 * condition accepted before: well-formed packet, same direction / session / codec as the open RX stream.
 */
object AudioFrameFilter {
    enum class Verdict { ACCEPT, MALFORMED, WRONG_DIRECTION, WRONG_SESSION, WRONG_CODEC }

    class Result(val verdict: Verdict, val packet: AudioPacket?)

    /** Never throws; any byte array (including empty or garbage) yields a verdict. */
    fun classify(bytes: ByteArray, direction: AudioDirection, session: Int, codec: CodecId): Result {
        val p = AudioPacket.decode(bytes) ?: return Result(Verdict.MALFORMED, null)
        return when {
            p.direction != direction -> Result(Verdict.WRONG_DIRECTION, p)
            p.session != session -> Result(Verdict.WRONG_SESSION, p)
            p.codec != codec -> Result(Verdict.WRONG_CODEC, p)
            else -> Result(Verdict.ACCEPT, p)
        }
    }
}
