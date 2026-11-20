package com.example.companion.audio.transport

/** Android-free view of an already started microphone recorder (Stage 39). Mirrors what the capture loop needs from AudioRecord. */
interface MicrophoneRecorder {
    /** Blocking read of PCM16 mono. Returns bytes read; negative = error (platform AudioRecord codes). */
    fun read(buf: ByteArray, off: Int, len: Int): Int
    /** Makes a blocked [read] return. Idempotent. */
    fun stop()
    /** Releases the recorder. Idempotent. Called ONLY by the capture thread (or by `start` if the thread never ran). */
    fun release()
}

sealed class MicrophoneOpenResult {
    /** The recorder is created AND recording. */
    class Opened(val recorder: MicrophoneRecorder) : MicrophoneOpenResult()
    /** Nothing is left open. [reason] is human readable and never contains audio. */
    class Failed(val reason: String) : MicrophoneOpenResult()
}

/** Creates and starts a recorder for [config]. Must release everything it created on failure. */
fun interface MicrophoneRecorderOpener {
    fun open(config: AudioConfig): MicrophoneOpenResult
}
