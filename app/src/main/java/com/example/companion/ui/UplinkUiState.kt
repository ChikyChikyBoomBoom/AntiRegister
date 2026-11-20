package com.example.companion.ui

import com.example.companion.audio.transport.AudioDirection
import com.example.companion.audio.transport.UplinkBlock
import com.example.companion.audio.transport.UplinkLifecycleState
import com.example.companion.audio.transport.UplinkSnapshot

/** What the Main Phone's uplink card shows. Plain values; the activity only copies them into views. */
data class UplinkUi(
    val showSection: Boolean,
    /** True only while the microphone is really capturing (the red indicator). */
    val microphoneActive: Boolean,
    val microphoneLine: String,
    val capabilityLine: String,
    val connectionLine: String,
    /** Why the feature is unavailable / what the current state means. Never empty while the section is shown. */
    val explanation: String,
    val echoNote: String,
    val buttonText: String,
    val buttonIsStop: Boolean,
    val buttonEnabled: Boolean,
    /** Why the button is greyed out (empty when it is usable). */
    val buttonHint: String
)

/** What the SIM Phone's call screen shows about the Main Phone microphone. */
data class UplinkSimUi(val visible: Boolean, val indicatorActive: Boolean, val indicatorLine: String, val statusLine: String)

/**
 * Stage 40: Android-free text and enable rules for the uplink controls (like [CallScreenState]).
 *
 * Honesty rules, pinned by tests: the words "operational", "working" and "connected to the call" are never produced; the capability
 * line always starts from the capability's own headline; the microphone indicator is active only when the lifecycle says the
 * microphone is capturing; the echo note never says that echo cancellation works.
 */
object UplinkUiState {

    const val ECHO_UNVERIFIED = "Echo cancellation has not been tested."

    private fun yesNo(v: Boolean?, yes: String, no: String, unknown: String) = when (v) { true -> yes; false -> no; null -> unknown }

    fun main(s: UplinkSnapshot): UplinkUi {
        val running = s.state == UplinkLifecycleState.NEGOTIATING || s.state == UplinkLifecycleState.MICROPHONE_ACTIVE
        // The indicator must never be hidden while the microphone is in use, whatever else is going on.
        val show = s.localDirection == AudioDirection.MAIN_TO_SIM && (running || s.microphoneActive || s.callActive == true)

        val micLine = when {
            s.microphoneActive -> "● MICROPHONE ON - sending to the SIM Phone"
            s.state == UplinkLifecycleState.NEGOTIATING -> "Microphone off - waiting for the SIM Phone to accept"
            s.lastStopReason != null -> "Microphone off - stopped: ${s.lastStopReason}"
            else -> "Microphone off"
        }

        val capLine = "Uplink capability: " + s.capability.headline +
            (if (s.capability.isSupported) "" else " (${s.capability.detail})")

        val connLine = "Bluetooth: " + (if (s.linkUp) "connected" else "not connected") + " · Call: " +
            yesNo(s.callActive, "active", "no active call", "unknown")

        val explanation = when {
            s.state == UplinkLifecycleState.SHUT_DOWN -> UplinkBlock.SHUT_DOWN.text + ". The microphone is off."
            !s.flags.uplinkBridge ->
                "Uplink is switched off in this build (uplinkBridge = false). Your microphone is never used."
            s.state == UplinkLifecycleState.NOT_SUPPORTED ->
                "Not supported / not verified: the SIM Phone refused the stream, so the microphone was never opened. " +
                    "This build cannot put audio into the phone call."
            s.state == UplinkLifecycleState.FAILED -> "Uplink failed: ${s.detail ?: "unknown reason"}. The microphone is off."
            s.microphoneActive ->
                "The microphone is on and its audio is sent to the SIM Phone. That the audio reaches the call is NOT verified."
            !s.capability.isSupported ->
                "Not supported / not verified: this build has no verified way to put audio into the phone call. " +
                    "Starting asks the SIM Phone; if it refuses, the microphone stays off."
            else -> "The SIM Phone reports an uplink sink. Confirm by listening on the far end; nothing here proves it."
        }

        val echo = when (s.headsetOutputPresent) {
            true -> "A headset-type output is present, which lowers the risk of echo (routing not checked). $ECHO_UNVERIFIED"
            false -> "No headset detected: the Main Phone speaker may feed back into the microphone. Use a headset. $ECHO_UNVERIFIED"
            null -> "Headset state unknown: the speaker may feed back into the microphone. Use a headset. $ECHO_UNVERIFIED"
        }

        val stop = running || s.microphoneActive
        val actionable = s.startBlock == null || s.startBlock == UplinkBlock.PERMISSION || s.startBlock == UplinkBlock.ECHO_RISK
        return UplinkUi(
            showSection = show,
            microphoneActive = s.microphoneActive,
            microphoneLine = micLine,
            capabilityLine = capLine,
            connectionLine = connLine,
            explanation = explanation,
            echoNote = echo,
            buttonText = if (stop) "Stop microphone uplink" else "Start microphone uplink",
            buttonIsStop = stop,
            buttonEnabled = stop || actionable,
            buttonHint = when {
                stop || actionable -> ""
                else -> s.startBlock?.text ?: ""
            }
        )
    }

    /**
     * SIM Phone. The Main Phone microphone can only be "on for this phone" while this phone receives an uplink stream, which it
     * does only after its own sink accepted one. The only sink of this build refuses, so in this build the answer is always "not streaming".
     */
    fun sim(s: UplinkSnapshot, callActive: Boolean): UplinkSimUi {
        val status = when {
            !s.flags.uplinkBridge -> "Uplink experiment: off in this build (uplinkBridge = false). The Main Phone microphone is not used."
            else -> "Uplink sink: " + s.capability.headline + ". This phone " +
                (if (s.capability.isSupported) "reports a sink (not verified by a far-end listener)."
                 else "refuses microphone streams from the Main Phone.")
        }
        val indicator = if (s.receivingUplink) "● Main Phone microphone is streaming to this phone"
                        else "Main Phone microphone: not streaming to this phone"
        return UplinkSimUi(
            visible = callActive || s.receivingUplink,
            indicatorActive = s.receivingUplink,
            indicatorLine = indicator,
            statusLine = status
        )
    }
}
