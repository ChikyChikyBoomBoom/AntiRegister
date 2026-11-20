package com.example.companion.calls

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.example.companion.app.CompanionApp

/**
 * Bound by Android Telecom only while this app holds the default phone (dialer) role.
 * Deliberately thin: forwards call add/remove to [CallManager]; no Bluetooth, no UI here.
 * Stage 4: exposes the running instance so the audio diagnostic can read/request the call audio route (control only).
 */
class CompanionInCallService : InCallService() {
    private val manager get() = (application as CompanionApp).callManager

    // Stage 7: while calling is off this service is inert. It still exists (Telecom may bind it if this app was left as the default
    // phone app), but it publishes no instance (so SimAudioControl / RouteControl find nothing to control) and forwards no call.
    override fun onCreate() { super.onCreate(); if (CallFeatures.isCallingEnabled()) instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onCallAdded(call: Call) { if (CallFeatures.isCallingEnabled()) manager.onCallAdded(call) }
    override fun onCallRemoved(call: Call) { if (CallFeatures.isCallingEnabled()) manager.onCallRemoved(call) }

    /** Stage 7: Telecom changed mute / route of this phone's call audio; the SIM-side controller reports it to the Main Phone. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        if (!CallFeatures.isCallingEnabled()) return
        (application as CompanionApp).simAudio.onPlatformChanged()
    }

    companion object {
        @Volatile var instance: CompanionInCallService? = null
            private set
    }
}
