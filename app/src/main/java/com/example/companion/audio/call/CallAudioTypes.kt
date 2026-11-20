package com.example.companion.audio.call

import com.example.companion.audio.hfp.Tri

/** Lifecycle of the call-audio session on one phone. Pure data: no Android classes. */
enum class AudioSessionState {
    IDLE,         // no audio wanted (no active call, or audio was stopped)
    STARTING,     // a backend exists and is being opened; nothing is proven yet
    CONNECTED,    // the backend reports its path is open (resources held), media not yet confirmed flowing
    ACTIVE,       // the backend reports media flowing in both directions
    STOPPING,     // backend being released (always followed by another state in the same call stack)
    FAILED,       // audio was attempted and failed; not retried automatically
    UNAVAILABLE   // audio is wanted but cannot exist right now (see reason)
}

/** Which transport carries call audio. NONE = no legitimate path exists (the current, evidence-based answer). */
enum class AudioMode { NONE, HFP, CUSTOM_BLUETOOTH_TRANSPORT }

/** The user-visible reasons required by Stage 7. */
enum class AudioUnavailableReason(val text: String) {
    BLUETOOTH_DISCONNECTED("Bluetooth disconnected"),
    AUDIO_API_UNAVAILABLE("Audio API unavailable"),
    HFP_UNAVAILABLE("HFP unavailable"),
    AUDIO_INIT_FAILED("Audio initialization failed"),
    PERMISSION_OR_ROLE_UNAVAILABLE("Permission/role unavailable"),
    DEVICE_LIMITATION("Device limitation")
}

/** What the SIM Phone's Telecom mute acts on. It is NOT the Main Phone's microphone. */
enum class MuteScope { NONE, SIM_PHONE_MICROPHONE }

/** What the SIM Phone's Telecom audio route acts on. It is NOT the Main Phone's speaker. */
enum class RouteScope { NONE, SIM_PHONE_SPEAKER }

data class CallAudioCapability(
    val mode: AudioMode,
    /** Tri.YES only if BOTH cellular downlink -> Main Phone AND Main Phone mic -> cellular uplink are legitimately available. */
    val twoWayCellularAudio: Tri,
    val reason: AudioUnavailableReason?,
    val detail: String,
    val muteScope: MuteScope,
    val routeScope: RouteScope
)

/** Stage 21: the three words the call screen shows. Derived from the session state only. */
enum class AudioIndicator(val text: String) {
    CONNECTED("Audio Connected"),
    CONNECTING("Connecting"),
    UNAVAILABLE("Audio Unavailable")
}

data class CallAudioStatus(
    val state: AudioSessionState,
    val mode: AudioMode,
    val reason: AudioUnavailableReason?,
    val detail: String,
    /** Stage 21: the Main Phone microphone can be muted right now (open backend that supports it). */
    val muteSupported: Boolean = false,
    /** Stage 21: actual mute state reported by the backend; null when mute is not supported / no path is open. */
    val micMuted: Boolean? = null,
    val speakerSupported: Boolean = false,
    /** Stage 21: actual speaker state reported by the backend; null when not supported / no path is open. */
    val speakerOn: Boolean? = null
) {
    /** "Audio Connected" only for a backend-reported open path; STARTING = Connecting; everything else = Unavailable. */
    val indicator: AudioIndicator get() = when (state) {
        AudioSessionState.CONNECTED, AudioSessionState.ACTIVE -> AudioIndicator.CONNECTED
        AudioSessionState.STARTING -> AudioIndicator.CONNECTING
        else -> AudioIndicator.UNAVAILABLE
    }

    /** Short text for the call UI. Never says "connected" for anything but a backend-reported audio path. */
    val label: String get() = when (state) {
        AudioSessionState.IDLE -> "Not started"
        AudioSessionState.STARTING -> "Starting…"
        AudioSessionState.CONNECTED -> "Connected (media not confirmed yet)"
        AudioSessionState.ACTIVE -> "Active (two-way)"
        AudioSessionState.STOPPING -> "Stopping…"
        AudioSessionState.FAILED -> "Failed" + (reason?.let { " — ${it.text}" } ?: "")
        AudioSessionState.UNAVAILABLE -> "Unavailable" + (reason?.let { " — ${it.text}" } ?: "")
    }

    companion object { val IDLE = CallAudioStatus(AudioSessionState.IDLE, AudioMode.NONE, null, "") }
}
