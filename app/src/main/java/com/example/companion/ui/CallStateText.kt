package com.example.companion.ui

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.protocol.CallState

/**
 * Stage 19: the call state as words in [language], for the SIM Phone call screen and the call notification. Pure Kotlin.
 * English output of every state except IDLE equals the enum name (what the screen showed before); IDLE is "No call".
 */
object CallStateText {
    fun name(state: CallState, language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(
        when (state) {
            CallState.IDLE -> StringKey.SIMCALL_NO_CALL
            CallState.RINGING -> StringKey.CALLSTATE_RINGING
            CallState.CONNECTING -> StringKey.CALLSTATE_CONNECTING
            CallState.SELECT_ACCOUNT -> StringKey.CALLSTATE_SELECT_ACCOUNT
            CallState.DIALING -> StringKey.CALLSTATE_DIALING
            CallState.ACTIVE -> StringKey.CALLSTATE_ACTIVE
            CallState.HOLDING -> StringKey.CALLSTATE_HOLDING
            CallState.DISCONNECTING -> StringKey.CALLSTATE_DISCONNECTING
            CallState.DISCONNECTED -> StringKey.CALLSTATE_DISCONNECTED
        }, language
    )

    /** "ON" / "OFF" / "unknown" for a mute or speaker flag. */
    fun onOff(value: Boolean?, language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(
        when (value) { true -> StringKey.STATE_ON; false -> StringKey.STATE_OFF; null -> StringKey.STATE_UNKNOWN }, language
    )
}
