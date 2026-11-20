package com.example.companion.audio.transport

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * The Android side of microphone capture: an [AudioRecord] on [inputSource] (MIC by default = least processing = least latency),
 * PCM16 mono. Only public, documented APIs. Refusal texts are the ones [MicrophoneSource] has always returned.
 * Nothing is written to disk and nothing is logged here.
 */
class AndroidMicrophoneRecorderOpener(
    private val inputSource: Int = MediaRecorder.AudioSource.MIC
) : MicrophoneRecorderOpener {

    private class Rec(private val rec: AudioRecord) : MicrophoneRecorder {
        private var released = false
        override fun read(buf: ByteArray, off: Int, len: Int): Int = rec.read(buf, off, len)
        override fun stop() { runCatching { rec.stop() } }
        @Synchronized override fun release() {
            if (released) return
            released = true
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    override fun open(config: AudioConfig): MicrophoneOpenResult {
        val min = AudioRecord.getMinBufferSize(config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return MicrophoneOpenResult.Failed("Microphone does not support ${config.sampleRate} Hz")
        val rec = try {
            AudioRecord(inputSource, config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, config.frameBytes * 4))
        } catch (e: SecurityException) { return MicrophoneOpenResult.Failed("Microphone permission denied")
        } catch (e: IllegalArgumentException) { return MicrophoneOpenResult.Failed("Could not create microphone recorder: ${e.message}")
        } catch (e: UnsupportedOperationException) { return MicrophoneOpenResult.Failed("Could not create microphone recorder: ${e.message}") }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return MicrophoneOpenResult.Failed("Microphone failed to initialise (in use by another app?)") }
        try { rec.startRecording() } catch (e: IllegalStateException) {
            rec.release(); return MicrophoneOpenResult.Failed("Microphone could not start: ${e.message}")
        } catch (e: SecurityException) {
            rec.release(); return MicrophoneOpenResult.Failed("Microphone permission denied")
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return MicrophoneOpenResult.Failed("Microphone refused to start recording") }
        return MicrophoneOpenResult.Opened(Rec(rec))
    }
}
