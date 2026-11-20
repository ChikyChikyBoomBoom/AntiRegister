package com.example.companion.audio.transport

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Plays frames through AudioTrack. Default usage is MEDIA, so Android routes it to whatever output is active
 * (speaker, wired headset, Bluetooth audio). It deliberately does not touch AudioManager mode or the call route.
 */
class AudioTrackOutput(private val usage: Int = AudioAttributes.USAGE_MEDIA) : AudioOutput {
    override val name = "AudioTrack"
    @Volatile private var track: AudioTrack? = null
    @Volatile private var rate = 0
    override val bufferMs: Int get() = track?.let { it.bufferSizeInFrames * 1000 / rate } ?: 0
    override val underruns: Int get() = try { track?.underrunCount ?: 0 } catch (e: IllegalStateException) { 0 }

    @Synchronized
    override fun open(config: AudioConfig): String? {
        if (track != null) return "Output already open"
        val min = AudioTrack.getMinBufferSize(config.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return "Audio output does not support ${config.sampleRate} Hz"
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(config.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, config.frameBytes * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) { return "Could not create audio output: ${e.message}" }
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return "Audio output failed to initialise" }
        try { t.play() } catch (e: IllegalStateException) { t.release(); return "Audio output could not start: ${e.message}" }
        rate = config.sampleRate
        track = t
        return null
    }

    override fun write(pcm: ByteArray): Boolean {
        val t = track ?: return false
        return try { t.write(pcm, 0, pcm.size) >= 0 } catch (e: IllegalStateException) { false }
    }

    @Synchronized
    override fun close() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush() }
        runCatching { t.release() }      // also unblocks a write() in progress
    }
}
