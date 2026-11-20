package com.example.companion.audio.call

/**
 * One concrete way of carrying call audio between the phones. [CallAudioSession] drives it and the rest of the app
 * never sees it.
 *
 * NO IMPLEMENTATION EXISTS TODAY, on purpose:
 *  - HFP: Stage 6 concluded a normal app cannot be the Hands-Free unit (privileged API).
 *  - CUSTOM_BLUETOOTH_TRANSPORT: needs a CellularAudioSource (read call audio) AND a sink that writes the Main Phone's
 *    microphone into the cellular uplink. Android offers a normal app neither; Stage 4 never ran on a device.
 * The interface exists so the lifecycle rules (no duplicate sessions, release on call end / Bluetooth loss) are
 * written and unit-tested once, and a real backend could be added later without touching UI or call control.
 *
 * Contract for implementers (all calls on the main thread):
 *  - The factory that creates a backend must not open any resource. [start] opens, [stop] releases.
 *  - [start] must not block. Report progress through [Callback]; callbacks after [stop] are ignored by the session.
 *  - [stop] is idempotent and synchronous: afterwards no microphone, AudioTrack, socket, thread or foreground
 *    service created by this backend may remain.
 *  - The two directions (Main mic -> cellular uplink, cellular downlink -> Main speaker) must be separate paths.
 *    A backend must never connect a microphone to a speaker on the same phone (local loopback).
 */
interface CallAudioBackend {
    val mode: AudioMode
    /** True if the backend records from the microphone. Android silences background capture, so it must stop when the app is not visible. */
    val needsForeground: Boolean

    /** Stage 21: true only if this backend can mute the Main Phone microphone WITHOUT releasing the audio path. */
    val supportsMute: Boolean get() = false
    /** Stage 21: true only if this backend's existing audio route can switch loudspeaker on/off without reopening. */
    val supportsSpeaker: Boolean get() = false

    fun start(callback: Callback)
    fun stop()

    /** Stage 21: keep the path open, only silence/unsilence the microphone. Report the real result via [Callback.onMuteChanged]. */
    fun setMuted(muted: Boolean) {}
    /** Stage 21: switch the route on the already-open path. Report the real result via [Callback.onSpeakerChanged]. */
    fun setSpeaker(on: Boolean) {}

    interface Callback {
        fun onConnected()
        fun onActive()
        fun onFailed(reason: AudioUnavailableReason, detail: String)
        /** Stage 21: the ACTUAL mute state of the microphone, as the backend now has it. */
        fun onMuteChanged(muted: Boolean) {}
        /** Stage 21: the ACTUAL speaker state of the route, as the backend now has it. */
        fun onSpeakerChanged(on: Boolean) {}
    }
}
