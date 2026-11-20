package com.example.companion.audio.transport

/**
 * Stage 39: Android-free routing decisions used by [AudioTransport] (which needs a Handler and is not JVM-testable),
 * kept here so they can be tested.
 */
object UplinkRoute {
    /**
     * An incoming stream is an UPLINK call stream only when this (SIM) phone armed [callUplinkRx] AND the stream is
     * MAIN_TO_SIM. When armed, the call wins over the Audio Test screen: an uplink stream is never played locally.
     */
    fun isUplinkStream(callUplinkRx: Boolean, dir: AudioDirection): Boolean = callUplinkRx && dir == AudioDirection.MAIN_TO_SIM

    /** Refusal text when [source] declares a direction that differs from this phone's [local] direction, otherwise null. */
    fun sourceDirectionError(source: AudioSource, local: AudioDirection): String? =
        if (source is DirectionalAudioSource && source.direction != local) SOURCE_DIRECTION_MISMATCH else null

    const val SOURCE_DIRECTION_MISMATCH = "Source direction does not match this phone's role"
}

/**
 * Marks the shared [MicrophoneSource] as THE uplink source (MAIN_TO_SIM). A wrapper, not a flag on the microphone itself,
 * because the same microphone also serves the SIM Phone's Test B (SIM_TO_MAIN), which must keep working.
 * It delegates to the one wrapped source (no second recorder), so the microphone's own "already running" guard applies.
 */
class UplinkMicrophoneSource(private val mic: AudioSource) : DirectionalAudioSource {
    override val name = "Uplink microphone"
    override val direction = AudioDirection.MAIN_TO_SIM
    override fun start(config: AudioConfig, listener: AudioSource.Listener): String? = mic.start(config, listener)
    override fun stop() = mic.stop()
}
