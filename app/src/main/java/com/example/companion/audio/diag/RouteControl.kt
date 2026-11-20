package com.example.companion.audio.diag

import android.telecom.CallAudioState
import com.example.companion.calls.CompanionInCallService

/** Legit route control available to the default phone app (InCallService). Control only; no audio access. */
@Suppress("DEPRECATION")
object RouteControl {
    fun currentRoute(): Int? = CompanionInCallService.instance?.callAudioState?.route

    fun setRoute(route: Int): Boolean {
        val s = CompanionInCallService.instance ?: return false
        s.setAudioRoute(route)
        return true
    }

    fun routeName(r: Int?): String {
        if (r == null) return "unknown (no InCallService)"
        val parts = ArrayList<String>()
        if ((r and CallAudioState.ROUTE_EARPIECE) != 0) parts += "EARPIECE"
        if ((r and CallAudioState.ROUTE_BLUETOOTH) != 0) parts += "BLUETOOTH"
        if ((r and CallAudioState.ROUTE_WIRED_HEADSET) != 0) parts += "WIRED_HEADSET"
        if ((r and CallAudioState.ROUTE_SPEAKER) != 0) parts += "SPEAKER"
        return if (parts.isEmpty()) "route=$r" else parts.joinToString("+")
    }
}
