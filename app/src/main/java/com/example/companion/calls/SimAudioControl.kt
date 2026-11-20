package com.example.companion.calls

import android.telecom.CallAudioState
import com.example.companion.protocol.CallError
import com.example.companion.protocol.SimAudioReport
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SIM Phone only. Mute and speaker/earpiece of the SIM PHONE's own call audio, through the supported Telecom API
 * ([android.telecom.InCallService.setMuted] / setAudioRoute), available only while this app is the default phone app.
 *
 * This is the only mute/route control Android legitimately gives us. It acts on the SIM Phone's microphone and speaker,
 * NOT on the Main Phone's, because no Main Phone audio is bridged into the call (see CallAudioManager). The UI says so.
 * Reported state always comes from Telecom, never from what was last requested.
 */
@Suppress("DEPRECATION") // CallAudioState / callAudioState: the only API that covers minSdk 26
class SimAudioControl {

    interface Listener { fun onSimAudioChanged() }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var weMuted = false

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Current Telecom state, or null if there is no InCallService (not the default phone app / not bound). */
    fun read(): SimAudioReport? {
        val st = CompanionInCallService.instance?.callAudioState ?: return null
        return SimAudioReport(micMuted = st.isMuted, speakerOn = (st.route and CallAudioState.ROUTE_SPEAKER) != 0)
    }

    /** Returns null if the request was handed to Telecom, otherwise a [CallError] code. Success is only the later state report. */
    fun setMuted(on: Boolean): String? {
        if (!CallFeatures.isCallingEnabled()) return CallError.AUDIO_CONTROL_UNAVAILABLE   // Stage 7
        val s = CompanionInCallService.instance ?: return CallError.AUDIO_CONTROL_UNAVAILABLE
        return try { s.setMuted(on); weMuted = on; null } catch (e: Exception) { CallError.TELECOM_ERROR }
    }

    /** Speaker on, or off = earpiece / wired headset. Only routes Android reports as supported are requested. */
    fun setSpeaker(on: Boolean): String? {
        if (!CallFeatures.isCallingEnabled()) return CallError.AUDIO_CONTROL_UNAVAILABLE   // Stage 7
        val s = CompanionInCallService.instance ?: return CallError.AUDIO_CONTROL_UNAVAILABLE
        val st = s.callAudioState ?: return CallError.AUDIO_CONTROL_UNAVAILABLE
        val target = if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
        if ((st.supportedRouteMask and target) == 0) return CallError.ROUTE_UNSUPPORTED
        return try { s.setAudioRoute(target); null } catch (e: Exception) { CallError.TELECOM_ERROR }
    }

    /** Called when the call is over: never leave the SIM Phone's microphone muted because of us. */
    fun onCallEnded() {
        if (!weMuted) return
        // Stage 29: forget our mute only once Telecom accepted the unmute. If the service is not bound right now the flag stays,
        // and the next "call over" report tries again.
        val s = CompanionInCallService.instance ?: return
        if (runCatching { s.setMuted(false) }.isSuccess) weMuted = false
    }

    /** From [CompanionInCallService.onCallAudioStateChanged]. */
    fun onPlatformChanged() = listeners.forEach { it.onSimAudioChanged() }
}
