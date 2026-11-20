package com.example.companion.audio.transport

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * The Android side of source selection (Stage 32B) and of reading (Stage 32C). Creates an AudioRecord on the requested source and starts it.
 * On any failure everything created is released and the underlying error is returned as text. The returned recorder only forwards
 * read / stop / release to the AudioRecord; the loop and the thread live in [CellularDownlinkSource].
 * Availability of VOICE_DOWNLINK / VOICE_CALL on the POCO F3 is UNVERIFIED.
 */
class AndroidCellularRecorderOpener : CellularRecorderOpener {
    private class Rec(private val rec: AudioRecord) : CellularRecorder {
        private var released = false
        override fun read(buf: ByteArray, off: Int, len: Int): Int = rec.read(buf, off, len)
        /** Stopping makes a blocked read() return, so the capture thread can release without waiting for audio. */
        override fun stop() { runCatching { rec.stop() } }
        @Synchronized override fun release() {
            if (released) return
            released = true
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    override fun open(source: CellularCaptureSource, config: AudioConfig): CellularOpenResult {
        val androidSource = when (source) {
            CellularCaptureSource.VOICE_DOWNLINK -> MediaRecorder.AudioSource.VOICE_DOWNLINK
            CellularCaptureSource.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
        }
        val min = AudioRecord.getMinBufferSize(config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return CellularOpenResult.Failed("getMinBufferSize(${config.sampleRate} Hz) returned $min")
        val rec = try {
            AudioRecord(androidSource, config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, config.frameBytes * 4))
        } catch (e: SecurityException) { return CellularOpenResult.Failed("SecurityException: ${e.message}")
        } catch (e: IllegalArgumentException) { return CellularOpenResult.Failed("IllegalArgumentException: ${e.message}")
        } catch (e: UnsupportedOperationException) { return CellularOpenResult.Failed("UnsupportedOperationException: ${e.message}") }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            val st = rec.state
            rec.release()
            return CellularOpenResult.Failed("AudioRecord not initialized (state=$st)")
        }
        try { rec.startRecording() } catch (e: IllegalStateException) {
            rec.release(); return CellularOpenResult.Failed("startRecording IllegalStateException: ${e.message}")
        } catch (e: SecurityException) {
            rec.release(); return CellularOpenResult.Failed("startRecording SecurityException: ${e.message}")
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            val st = rec.recordingState
            rec.release()
            return CellularOpenResult.Failed("AudioRecord refused to start recording (recordingState=$st)")
        }
        return CellularOpenResult.Opened(Rec(rec))
    }
}
