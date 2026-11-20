package com.example.companion.audio.call

/**
 * The single entry point for call audio on BOTH phones. The rest of the app (call UI, call control) uses only this
 * interface and never learns whether HFP or a custom Bluetooth transport is underneath.
 * All methods and listener callbacks run on the main thread.
 */
interface CallAudioManager {
    interface Listener { fun onCallAudioChanged(status: CallAudioStatus) }

    val status: CallAudioStatus

    /** Requests call audio for the current call. Idempotent: never creates a second session. */
    fun startCallAudio()
    /** Releases everything audio-related. The cellular call itself is never touched. */
    fun stopCallAudio()
    /** True only if a legitimate path exists AND the session is not UNAVAILABLE / FAILED right now. */
    fun isAvailable(): Boolean
    fun getAudioMode(): AudioMode
    fun getCapability(): CallAudioCapability

    /**
     * Stage 21: Main Phone microphone mute on the open audio path. Returns false (nothing changes) when unsupported or no path is open.
     * The state to show is [status].micMuted, which follows the backend's report, never the request.
     */
    fun setMicrophoneMuted(muted: Boolean): Boolean
    /** Stage 21: speaker of the existing audio route; same rules as [setMicrophoneMuted]. State: [status].speakerOn. */
    fun setSpeakerOn(on: Boolean): Boolean

    /** App visibility (microphone capture must stop when the app is not visible). Fed by CompanionApp. */
    fun onAppVisibilityChanged(visible: Boolean)

    fun addListener(l: Listener)
    fun removeListener(l: Listener)
}
