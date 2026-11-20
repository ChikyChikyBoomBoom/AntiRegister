package com.example.companion.ui

import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.data.Role
import com.example.companion.protocol.CallState

/** Title, one sentence, and an optional action label for an empty state. */
data class EmptyText(val title: String, val message: String, val actionLabel: String? = null)

/**
 * Stage 24: the wording and the few yes/no decisions behind the polished screens. No Android types, so it is unit-tested
 * on a plain JVM. It reads nothing and changes nothing; the screens pass in what the existing state owners already know.
 */
object UiPolish {

    val NO_CALLS = EmptyText(
        "No recent calls",
        "Calls you make or receive through the SIM Phone will be listed here."
    )
    val NO_MESSAGES = EmptyText(
        "No messages",
        "Text messages sent and received through the SIM Phone will be listed here."
    )
    val NO_DEVICE = EmptyText(
        "No SIM Phone selected",
        "Select your SIM Phone to send and read text messages from this phone.",
        actionLabel = "Open Settings"
    )
    val NO_DEVICES_FOUND = EmptyText(
        "No devices found",
        "Turn on Bluetooth on the SIM Phone and make it discoverable, then tap Rescan."
    )
    val NO_ACTIVE_CALL = EmptyText(
        "No active call",
        "Incoming and outgoing calls on the SIM Phone appear here."
    )

    const val SCANNING = "Scanning for nearby devices…"

    /** True while the link is being opened or re-opened: the screens show a spinner instead of a still dot. */
    fun isBusy(status: LinkStatus): Boolean =
        status == LinkStatus.CONNECTING || status == LinkStatus.RECONNECTING

    /** Same idea at the connection-state level: also true while scanning, pairing or waiting for the other phone. */
    fun isBusy(state: State): Boolean = when (state) {
        State.CONNECTING, State.PAIRING, State.RECONNECTING, State.DISCOVERING, State.LISTENING -> true
        State.CONNECTED, State.DISCONNECTED, State.ERROR -> false
    }

    /** Main Phone with no saved SIM Phone: the screens explain what to do instead of showing blank lines. */
    fun needsDevice(role: Role?, savedName: String?, savedAddress: String?): Boolean =
        role == Role.MAIN_PHONE && savedName.isNullOrBlank() && savedAddress.isNullOrBlank()

    /** Same wording the call screen uses, so the same call is never called two different things. */
    fun callStateLabel(state: CallState): String = when (state) {
        CallState.IDLE -> "No call"
        CallState.RINGING -> "Incoming call"
        CallState.CONNECTING, CallState.SELECT_ACCOUNT, CallState.DIALING -> "Outgoing call"
        CallState.ACTIVE -> "Call in progress"
        CallState.HOLDING -> "On hold"
        CallState.DISCONNECTING -> "Ending call"
        CallState.DISCONNECTED -> "Call ended"
    }
}
