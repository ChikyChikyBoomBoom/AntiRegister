package com.example.companion.audio.diag

import android.media.AudioManager

object AudioModeProbe {
    fun modeName(m: Int): String = when (m) {
        0 -> "MODE_NORMAL"
        1 -> "MODE_RINGTONE"
        2 -> "MODE_IN_CALL"
        3 -> "MODE_IN_COMMUNICATION"
        4 -> "MODE_CALL_SCREENING"
        5 -> "MODE_CALL_REDIRECT"
        6 -> "MODE_COMMUNICATION_REDIRECT"
        else -> "MODE_UNKNOWN($m)"
    }

    /**
     * Can a normal app request these modes? Only ever called when there is NO call, because a failed restore
     * during a live call could disturb its audio. Always resets to MODE_NORMAL afterwards.
     */
    fun writeTest(am: AudioManager): List<String> {
        val out = ArrayList<String>()
        for (m in intArrayOf(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_IN_CALL)) {
            try {
                am.mode = m
                val after = am.mode
                out += "setMode(${modeName(m)}): no exception, read-back=${modeName(after)} " +
                    if (after == m) "-> ACCEPTED" else "-> IGNORED"
            } catch (e: SecurityException) {
                out += "setMode(${modeName(m)}): REJECTED (SecurityException: ${e.message})"
            } catch (e: Throwable) {
                out += "setMode(${modeName(m)}): FAILED (${e.javaClass.simpleName}: ${e.message})"
            } finally {
                try { am.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
            }
        }
        out += "Note: even an ACCEPTED mode change only changes this app's local audio policy; it is not by itself " +
            "evidence of access to the cellular uplink/downlink."
        return out
    }
}
