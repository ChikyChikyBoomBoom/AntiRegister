package com.example.companion.audio.diag

import android.media.MediaRecorder

/** Sources tested by the diagnostic. Existence of a constant proves nothing about access; see [AudioProbe]. */
object AudioSources {
    val ALL: List<TestSource> = listOf(
        TestSource("VOICE_CALL", MediaRecorder.AudioSource.VOICE_CALL, SourceKind.CALL_PATH,
            "uplink+downlink; documented as requiring a system-level capture permission"),
        TestSource("VOICE_DOWNLINK", MediaRecorder.AudioSource.VOICE_DOWNLINK, SourceKind.CALL_PATH,
            "remote side only; documented as requiring a system-level capture permission"),
        TestSource("VOICE_UPLINK", MediaRecorder.AudioSource.VOICE_UPLINK, SourceKind.CALL_PATH,
            "local side only; documented as requiring a system-level capture permission"),
        TestSource("MIC", MediaRecorder.AudioSource.MIC, SourceKind.MICROPHONE_PATH, "plain microphone"),
        TestSource("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION, SourceKind.MICROPHONE_PATH,
            "microphone tuned for VoIP (echo cancellation etc. may apply)"),
        TestSource("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION, SourceKind.MICROPHONE_PATH,
            "microphone tuned for speech recognition"),
        TestSource("UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED, SourceKind.MICROPHONE_PATH,
            "raw microphone, not supported on every device")
    )
}
