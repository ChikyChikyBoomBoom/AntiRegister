package com.example.companion.audio.transport

/**
 * Where the SIM Phone puts Main Phone microphone audio that arrived as an UPLINK stream. It reuses the [AudioOutput]
 * contract, so [AudioTransport] needs no second receive path. A sink must say honestly whether it reaches the call.
 */
interface UplinkSink : AudioOutput {
    val capability: UplinkCapability
}

/**
 * The only uplink sink in Stage 39. Cellular call injection has NOT been technically verified (Stage 36), so this sink
 * refuses to open with [UplinkMessages.NOT_SUPPORTED], accepts no frame, and never reports success.
 *
 * It deliberately does NOT swallow frames (that would look like a working sink) and does NOT delegate to
 * [AudioTrackOutput] (that would play the Main Phone's microphone on the SIM Phone's own speaker while looking healthy).
 * No hidden API, no `setPreferredDevice`, no audio-mode change.
 */
class UnsupportedUplinkSink(
    detail: String = "no verified cellular uplink sink in this build"
) : UplinkSink {
    override val name = "Unsupported uplink sink"
    override val capability: UplinkCapability = UplinkCapability.notSupported(UplinkUnsupportedReason.NO_VERIFIED_SINK, detail)
    override fun open(config: AudioConfig): String? = UplinkMessages.NOT_SUPPORTED
    override fun write(pcm: ByteArray): Boolean = false
    override val bufferMs: Int get() = 0
    override val underruns: Int get() = 0
    override fun close() {}
}
