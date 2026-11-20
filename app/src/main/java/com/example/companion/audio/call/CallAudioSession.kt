package com.example.companion.audio.call

import com.example.companion.protocol.CallState

/**
 * The call-audio state machine. Pure Kotlin (no Android classes) so it can be unit-tested on the JVM. Main thread only.
 *
 * Inputs: call state, Bluetooth link state, app visibility, explicit start/stop requests.
 * Output: [status] (IDLE / STARTING / CONNECTED / ACTIVE / STOPPING / FAILED / UNAVAILABLE) via [onChanged].
 *
 * Rules:
 *  - Audio is wanted only while the cellular call is ACTIVE and has not been stopped explicitly.
 *    A call that becomes ACTIVE requests audio automatically; any other call state withdraws the request.
 *  - At most one backend exists at any time. Starting twice is a no-op. Every backend gets a generation number; callbacks
 *    from a backend that was already torn down are ignored, so a reconnect can never produce two sessions.
 *  - Bluetooth down  -> backend stopped and released at once, UNAVAILABLE(BLUETOOTH_DISCONNECTED). Link up -> one new start.
 *  - Mode NONE       -> nothing is created; UNAVAILABLE with the capability's reason. The call itself is never touched.
 *  - A failure       -> backend released, FAILED. Not retried by unrelated events; startCallAudio(), a new call, or a Bluetooth
 *                       drop + reconnect (which is a fresh situation) start one new attempt.
 *  - App not visible -> backends that record the microphone are stopped (Android silences background capture anyway).
 *  - Stage 29: a different call id while a call is ACTIVE is a NEW call: the old backend is released first, then one fresh start.
 *  - Stage 29: a backend is always fully released ([CallAudioBackend.stop] returned) BEFORE another one can be created, even if a
 *    status listener calls back into this class while the old one is being released (re-entrant calls are queued, not nested).
 *  - Stage 29: a backend factory that throws is a FAILED start, never a crash and never a half-created resource.
 */
class CallAudioSession(
    private val capability: () -> CallAudioCapability,
    private val backendFactory: (AudioMode) -> CallAudioBackend?,
    private val onChanged: (CallAudioStatus) -> Unit
) {
    var status: CallAudioStatus = CallAudioStatus.IDLE; private set

    private var callActive = false
    private var activeCallId: String? = null   // Stage 29: id of the call audio was requested for (null = unknown / older SIM Phone build)
    private var requested = false
    private var linkUp = false
    private var appVisible = true
    private var backend: CallAudioBackend? = null
    private var generation = 0
    // Stage 29: re-entrancy. While a backend is being released or the state machine runs, calls that arrive from status listeners
    // are remembered and executed afterwards, so two backends can never overlap.
    private var depth = 0
    private var pending = false
    private var pendingForce = false
    private var pendingFresh = false
    // Stage 21: actual controls state, as reported by the current backend. Reset whenever a backend is released.
    private var micMuted: Boolean? = null
    private var speakerOn: Boolean? = null

    // ------------------------------------------------------------------ inputs

    fun onCallState(state: CallState, callId: String? = null) {
        val active = state == CallState.ACTIVE
        // Stage 29: still ACTIVE but another call id = a different call (the in-between report was missed). It must not inherit the
        // old call's backend, its failure, or an explicit stop().
        val newCall = active && callActive && callId != null && activeCallId != null && callId != activeCallId
        if (active == callActive && !newCall) {
            if (active && activeCallId == null) activeCallId = callId
            return
        }
        callActive = active
        activeCallId = if (active) callId else null
        requested = active
        evaluate(force = newCall, fresh = newCall)
    }

    fun onLink(up: Boolean) {
        if (up == linkUp) return
        linkUp = up
        evaluate(force = false)
    }

    fun onAppVisible(visible: Boolean) {
        if (visible == appVisible) return
        appVisible = visible
        evaluate(force = false)
    }

    /** Explicit request (retries after FAILED). Safe to call repeatedly: it never creates a second backend. */
    fun start() { requested = true; evaluate(force = true) }

    /** Explicit stop: releases everything. Audio stays off until the next call or start(). */
    fun stop() { requested = false; evaluate(force = false) }

    /**
     * Stage 21: asks the open backend to mute / unmute the Main Phone microphone. The audio path is never reopened or released.
     * Returns false (and does nothing) if no open backend supports it. The shown state changes only when the backend reports it.
     */
    fun setMicrophoneMuted(muted: Boolean): Boolean {
        val b = backend ?: return false
        if (!b.supportsMute || !isOpen()) return false
        return try { b.setMuted(muted); true } catch (_: Throwable) { false }
    }

    /** Stage 21: same for the speaker of the existing route. */
    fun setSpeakerOn(on: Boolean): Boolean {
        val b = backend ?: return false
        if (!b.supportsSpeaker || !isOpen()) return false
        return try { b.setSpeaker(on); true } catch (_: Throwable) { false }
    }

    private fun isOpen() = status.state == AudioSessionState.CONNECTED || status.state == AudioSessionState.ACTIVE

    // ------------------------------------------------------------------ core

    private fun evaluate(force: Boolean, fresh: Boolean = false) {
        if (depth > 0) {
            pending = true; pendingForce = pendingForce || force; pendingFresh = pendingFresh || fresh
            return
        }
        guarded { run(force, fresh) }
    }

    /** Runs [block]; anything requested from listeners meanwhile runs right after it, outermost level only. */
    private fun guarded(block: () -> Unit) {
        depth++
        try {
            block()
            if (depth == 1) {
                while (pending) {
                    val f = pendingForce; val fr = pendingFresh
                    pending = false; pendingForce = false; pendingFresh = false
                    run(f, fr)
                }
            }
        } finally { depth-- }
    }

    private fun run(force: Boolean, fresh: Boolean) {
        if (fresh) teardown()
        if (!(requested && callActive)) {
            teardown()
            set(AudioSessionState.IDLE, status.mode, null, "")
            return
        }
        val cap = capability()
        if (!linkUp) {
            teardown()
            set(AudioSessionState.UNAVAILABLE, cap.mode, AudioUnavailableReason.BLUETOOTH_DISCONNECTED, "The Bluetooth link to the other phone is down.")
            return
        }
        if (cap.mode == AudioMode.NONE) {
            teardown()
            set(AudioSessionState.UNAVAILABLE, AudioMode.NONE, cap.reason ?: AudioUnavailableReason.AUDIO_API_UNAVAILABLE, cap.detail)
            return
        }
        backend?.let { b ->
            if (b.needsForeground && !appVisible) {
                teardown()
                set(AudioSessionState.UNAVAILABLE, cap.mode, AudioUnavailableReason.DEVICE_LIMITATION,
                    "Microphone capture needs the app to be visible; audio was released.")
            }
            return   // already starting / running: never a second backend
        }
        if (status.state == AudioSessionState.FAILED && !force) return

        val b = try { backendFactory(cap.mode) } catch (t: Throwable) {
            set(AudioSessionState.FAILED, cap.mode, AudioUnavailableReason.AUDIO_INIT_FAILED, t.message ?: t.javaClass.simpleName)
            return
        }
        if (b == null) {
            set(AudioSessionState.UNAVAILABLE, cap.mode, AudioUnavailableReason.DEVICE_LIMITATION, "No audio backend is available for ${cap.mode}.")
            return
        }
        if (b.needsForeground && !appVisible) {
            set(AudioSessionState.UNAVAILABLE, cap.mode, AudioUnavailableReason.DEVICE_LIMITATION,
                "Microphone capture needs the app to be visible.")
            return
        }
        begin(b)
    }

    /** A fresh path starts unmuted and on the default route; the backend confirms anything else through its callbacks. */
    private fun initControls(b: CallAudioBackend) {
        micMuted = if (b.supportsMute) false else null
        speakerOn = if (b.supportsSpeaker) false else null
    }

    private fun begin(b: CallAudioBackend) {
        val gen = ++generation
        backend = b
        micMuted = null; speakerOn = null
        set(AudioSessionState.STARTING, b.mode, null, "")
        fun current() = gen == generation && backend === b
        try {
            b.start(object : CallAudioBackend.Callback {
                override fun onConnected() {
                    if (current() && status.state == AudioSessionState.STARTING) {
                        initControls(b)
                        set(AudioSessionState.CONNECTED, b.mode, null, "")
                    }
                }
                override fun onActive() {
                    if (current() && (status.state == AudioSessionState.STARTING || status.state == AudioSessionState.CONNECTED)) {
                        if (status.state == AudioSessionState.STARTING) initControls(b)
                        set(AudioSessionState.ACTIVE, b.mode, null, "")
                    }
                }
                override fun onMuteChanged(muted: Boolean) {
                    if (current() && isOpen() && b.supportsMute) { micMuted = muted; set(status.state, b.mode, null, "") }
                }
                override fun onSpeakerChanged(on: Boolean) {
                    if (current() && isOpen() && b.supportsSpeaker) { speakerOn = on; set(status.state, b.mode, null, "") }
                }
                override fun onFailed(reason: AudioUnavailableReason, detail: String) {
                    if (!current()) return
                    guarded {
                        teardown()
                        set(AudioSessionState.FAILED, b.mode, reason, detail)
                    }
                }
            })
        } catch (t: Throwable) {
            if (current()) {
                teardown()
                set(AudioSessionState.FAILED, b.mode, AudioUnavailableReason.AUDIO_INIT_FAILED, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Releases the current backend (if any). Invalidates its callbacks first so nothing can arrive late. */
    private fun teardown() {
        val b = backend ?: return
        backend = null
        generation++
        micMuted = null; speakerOn = null   // mute / speaker never outlive the audio path (next call starts fresh)
        set(AudioSessionState.STOPPING, b.mode, null, "")
        try { b.stop() } catch (_: Throwable) { /* stop must not throw; nothing more can be done */ }
    }

    private fun set(state: AudioSessionState, mode: AudioMode, reason: AudioUnavailableReason?, detail: String) {
        val b = backend
        val open = b != null && (state == AudioSessionState.CONNECTED || state == AudioSessionState.ACTIVE)
        val next = CallAudioStatus(
            state, mode, reason, detail,
            muteSupported = open && b!!.supportsMute, micMuted = if (open) micMuted else null,
            speakerSupported = open && b!!.supportsSpeaker, speakerOn = if (open) speakerOn else null
        )
        if (next == status) return
        status = next
        onChanged(next)
    }
}
