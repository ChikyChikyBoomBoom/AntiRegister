package com.example.companion.audio.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Process

/**
 * Plain microphone capture. NOT the cellular call. Stage 39: the logic moved into the Android-free [MicrophoneCapture]
 * (so it can be unit-tested with a fake recorder); this class only supplies the Android permission check, the
 * [AndroidMicrophoneRecorderOpener] and the audio thread priority. Constructor and behaviour are unchanged for existing callers.
 * Uses the one declared `RECORD_AUDIO` permission; nothing is added.
 */
class MicrophoneSource(
    context: Context,
    inputSource: Int = MediaRecorder.AudioSource.MIC   // MIC = least processing = least latency
) : AudioSource by MicrophoneCapture(
    hasPermission = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
    opener = AndroidMicrophoneRecorderOpener(inputSource),
    threadInit = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
)
