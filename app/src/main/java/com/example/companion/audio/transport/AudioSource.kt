package com.example.companion.audio.transport

/**
 * Where outgoing audio comes from. The transport only knows this interface, so the source is replaceable.
 * Implemented: [MicrophoneSource] (Android-free core: [MicrophoneCapture]) and [CellularDownlinkSource] (Stage 32B/32C; VOICE_DOWNLINK / VOICE_CALL,
 * built per call by AndroidTransportBackendFactory only with DOWNLINK_BRIDGE on; availability on real hardware UNVERIFIED).
 */
interface AudioSource {
    val name: String

    interface Listener {
        /** Exactly [AudioConfig.frameBytes] bytes of PCM16 mono. Called on the source's own thread. */
        fun onFrame(pcm: ByteArray)
        /** The source died (permission revoked, device lost...). It has already released its resources. */
        fun onError(message: String)
    }

    /** Returns null on success, otherwise a human-readable reason. Must release everything it opened on failure. */
    fun start(config: AudioConfig, listener: Listener): String?
    /** Idempotent. Releases all resources. */
    fun stop()
}

/**
 * A source of the SIM Phone's cellular call audio. [CellularDownlinkSource] (Stage 32A-32C) implements it; it has never run on a
 * device, so whether Android lets this app read call audio is UNVERIFIED. It is handed to AudioTransport unchanged.
 */
interface CellularAudioSource : AudioSource

/** Stage 39: a source that declares which way its stream goes. [AudioTransport.startSending] refuses a mismatch with the phone's role. */
interface DirectionalAudioSource : AudioSource { val direction: AudioDirection }

/** Where incoming audio goes. Implemented: [AudioTrackOutput] (local speaker) and, for UPLINK streams, [UplinkSink] ([UnsupportedUplinkSink] only). */
interface AudioOutput {
    val name: String
    /** Returns null on success, otherwise a human-readable reason. */
    fun open(config: AudioConfig): String?
    /** Blocking, paced by the hardware. Playback thread only. False = output is broken, stop. */
    fun write(pcm: ByteArray): Boolean
    val bufferMs: Int
    val underruns: Int
    /** Idempotent; must also unblock a write() running on another thread. */
    fun close()
}
