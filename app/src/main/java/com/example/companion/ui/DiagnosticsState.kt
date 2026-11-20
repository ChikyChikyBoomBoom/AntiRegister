package com.example.companion.ui

import com.example.companion.audio.call.AudioMode
import com.example.companion.audio.call.AudioSessionState
import com.example.companion.audio.transport.AudioFacts
import com.example.companion.audio.transport.AudioFactsText
import com.example.companion.audio.transport.UplinkCapability
import com.example.companion.audio.transport.UplinkFlags
import com.example.companion.audio.transport.UplinkLifecycleState
import com.example.companion.audio.hfp.Tri
import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.bluetooth.ReconnectPolicy
import com.example.companion.data.Role
import com.example.companion.protocol.CallState

/**
 * Stage 23: everything the Diagnostics screen shows, as plain values read from the EXISTING state owners
 * (ConnectionManager, BluetoothManager, call controllers, SMS permissions, CallAudioManager). The screen reads them,
 * this file only turns them into text. No Android types, so it can be unit-tested on a plain JVM.
 *
 * Privacy by construction: there is no field for an SMS text, a phone number, a contact or audio. The call state is the
 * state name only (never the number). Device addresses are masked; error text is cleaned by [cleanError].
 */
data class DiagnosticsInput(
    val appVersion: String,                 // e.g. "0.23 (23)"
    val androidRelease: String,
    val sdk: Int,
    val deviceModel: String,
    val role: Role?,

    val bluetoothSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val bluetoothPermission: Boolean,

    val connectionState: State,
    val retryAttempt: Int,
    /** Name of the connected device, or of the saved SIM Phone on the Main Phone; null = none. */
    val deviceName: String?,
    /** Saved device address (Main Phone only; the SIM Phone has none to show). Masked before display. */
    val deviceAddress: String?,
    val lastConnectedAt: Long,              // epoch millis, 0 = never (persisted)
    val lastDisconnectedAt: Long,           // epoch millis, 0 = none since the app started
    val lastError: String?,
    val lastErrorAt: Long,                  // epoch millis, 0 = unknown

    /** SIM Phone: Android's call state. Main Phone: the state the SIM Phone reported. null = unknown (not synced). */
    val callState: CallState?,

    /** SIM Phone only. */
    val telephonyAvailable: Boolean,
    val smsPermissionsGranted: Boolean,

    val audioMode: AudioMode,
    val audioTwoWayCellular: Tri,
    val audioReason: String?,               // AudioUnavailableReason.text or null
    val audioSession: AudioSessionState,
    val audioIndicator: String,             // AudioIndicator.text
    /** Stage 40 (trailing, defaulted: older call sites keep compiling). null = the uplink rows are not shown. */
    val uplink: UplinkDiagnostics? = null
)

/**
 * Stage 40: the uplink / microphone facts for the Diagnostics screen. Facts the phone could not give are null and are shown as
 * "unknown"; nothing is guessed. No audio, no device names, no addresses.
 */
data class UplinkDiagnostics(
    val facts: AudioFacts,
    /** RECORD_AUDIO: true = granted, false = denied, null = unknown. */
    val microphonePermission: Boolean?,
    val sinkCapability: UplinkCapability,
    val flags: UplinkFlags,
    val lifecycle: UplinkLifecycleState,
    /** Lifecycle detail (why it stopped / failed), already free of audio. */
    val lifecycleDetail: String?
)

data class DiagRow(val label: String, val value: String)

object DiagnosticsState {

    private const val MAX_ERROR_CHARS = 200
    private val MAC = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")

    /** "AA:BB:CC:DD:EE:FF" -> "XX:XX:XX:XX:EE:FF". Anything that is not an address is returned unchanged. */
    fun maskAddress(s: String): String =
        MAC.replace(s) { m -> val parts = m.value.split(':', '-'); "XX:XX:XX:XX:${parts[4].uppercase()}:${parts[5].uppercase()}" }

    /** One short line, addresses masked, length capped. The connection layer only produces fixed texts plus system exception messages. */
    fun cleanError(s: String?): String? {
        val t = s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val masked = maskAddress(t)
        return if (masked.length <= MAX_ERROR_CHARS) masked else masked.take(MAX_ERROR_CHARS - 1) + "…"
    }

    fun bluetoothText(supported: Boolean, enabled: Boolean, permission: Boolean): String = when {
        !supported -> "Not supported"
        !enabled -> "Off"
        !permission -> "On, Bluetooth permission missing"
        else -> "On"
    }

    fun connectionText(state: State, retryAttempt: Int): String = when (state) {
        State.DISCONNECTED -> "Disconnected"
        State.DISCOVERING -> "Scanning for devices"
        State.PAIRING -> "Pairing"
        State.LISTENING -> "Listening (waiting for the Main Phone)"
        State.CONNECTING -> "Connecting"
        State.CONNECTED -> "Connected"
        State.RECONNECTING -> "Reconnecting" + if (retryAttempt > 0) " (${ReconnectPolicy.attemptText(retryAttempt)})" else ""
        State.ERROR -> "Error (see last error)"
    }

    fun deviceText(role: Role?, state: State, name: String?, address: String?): String {
        val n = name?.takeIf { it.isNotBlank() }?.let { maskAddress(it) }
        val a = address?.takeIf { it.isNotBlank() }?.let { maskAddress(it) }
        return when {
            role == null -> "-"
            state == State.CONNECTED -> n ?: "Connected (name unknown)"
            role == Role.SIM_PHONE -> "None connected"
            n == null && a == null -> "None selected"
            else -> "Saved: " + listOfNotNull(n, a?.takeIf { it != n }?.let { "($it)" }).joinToString(" ") + " (not connected)"
        }
    }

    fun callText(role: Role?, state: CallState?): String = when {
        role == null -> "-"
        state == null -> "Unknown (no report from the SIM Phone)"
        else -> when (state) {
            CallState.IDLE -> "No call"
            CallState.RINGING -> "Ringing"
            CallState.CONNECTING -> "Connecting"
            CallState.SELECT_ACCOUNT -> "Waiting for SIM choice"
            CallState.DIALING -> "Dialing"
            CallState.ACTIVE -> "Active"
            CallState.HOLDING -> "On hold"
            CallState.DISCONNECTING -> "Ending"
            CallState.DISCONNECTED -> "Ended"
        }
    }

    /**
     * Derived only from facts that already exist. The Main Phone has no SMS link of its own: SMS rides on the one Bluetooth
     * connection, so its state is that connection's state. The SIM Phone's own readiness (telephony + SMS permissions) is read locally.
     */
    fun smsText(role: Role?, state: State, telephonyAvailable: Boolean, smsPermissionsGranted: Boolean): String = when (role) {
        null -> "-"
        Role.MAIN_PHONE ->
            if (state == State.CONNECTED) "Link up (SMS goes through the SIM Phone)" else "Unavailable (not connected to the SIM Phone)"
        Role.SIM_PHONE -> when {
            !telephonyAvailable -> "Not available (this device has no telephony)"
            !smsPermissionsGranted -> "Not enabled (SMS permissions missing)"
            state == State.CONNECTED -> "Enabled, forwarding to the Main Phone"
            else -> "Enabled, waiting for the Main Phone"
        }
    }

    private fun triText(t: Tri) = when (t) { Tri.YES -> "yes"; Tri.NO -> "no"; Tri.UNKNOWN -> "unknown" }

    fun audioText(mode: AudioMode, twoWay: Tri, reason: String?, session: AudioSessionState, indicator: String): String {
        val modeText = when (mode) {
            AudioMode.NONE -> "no audio path"
            AudioMode.HFP -> "HFP"
            AudioMode.CUSTOM_BLUETOOTH_TRANSPORT -> "Bluetooth transport"
        }
        val why = reason?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        return "Mode: $modeText$why\nTwo-way call audio: ${triText(twoWay)}\nSession: ${session.name.lowercase().replaceFirstChar { it.uppercase() }} ($indicator)"
    }

    fun capabilityText(c: UplinkCapability): String = c.headline + if (c.isSupported) "" else "\n${cleanError(c.detail) ?: ""}"

    fun lifecycleText(state: UplinkLifecycleState, detail: String?): String =
        state.text + (cleanError(detail)?.let { "\n$it" } ?: "")

    /** Stage 40 rows, in the order the task lists them. The Bluetooth connection state is the existing "Connection" row above. */
    fun uplinkRows(u: UplinkDiagnostics): List<DiagRow> = listOf(
        DiagRow("Audio mode (Android)", AudioFactsText.modeText(u.facts.audioMode)),
        DiagRow("Communication devices", AudioFactsText.devicesText(u.facts.communicationDeviceTypes)),
        DiagRow("Supported sample rates", AudioFactsText.ratesText(u.facts.micRates, u.facts.nativeOutputRate)),
        DiagRow("Microphone permission", AudioFactsText.permissionText(u.microphonePermission)),
        DiagRow("Uplink sink capability", capabilityText(u.sinkCapability)),
        DiagRow("Feature flags", u.flags.lines().joinToString("\n")),
        DiagRow("Capture lifecycle", lifecycleText(u.lifecycle, u.lifecycleDetail))
    )

    /** "never" / "none since the app started" / formatted time. [fmt] formats epoch millis in the user's locale. */
    fun timeText(epochMs: Long, never: String, fmt: (Long) -> String): String = if (epochMs <= 0L) never else fmt(epochMs)

    fun rows(i: DiagnosticsInput, fmt: (Long) -> String): List<DiagRow> {
        val err = cleanError(i.lastError)
        return listOf(
            DiagRow("App version", i.appVersion),
            DiagRow("Android", "${i.androidRelease} (API ${i.sdk})"),
            DiagRow("Device model", i.deviceModel),
            DiagRow("Role", i.role?.label ?: "Not selected"),
            DiagRow("Bluetooth", bluetoothText(i.bluetoothSupported, i.bluetoothEnabled, i.bluetoothPermission)),
            DiagRow(if (i.role == Role.SIM_PHONE) "Connected device" else "SIM Phone", deviceText(i.role, i.connectionState, i.deviceName, i.deviceAddress)),
            DiagRow("Connection", connectionText(i.connectionState, i.retryAttempt)),
            DiagRow("Last connected", timeText(i.lastConnectedAt, "never", fmt)),
            DiagRow("Last disconnected", timeText(i.lastDisconnectedAt, "none since the app started", fmt)),
            DiagRow("Last communication error",
                if (err == null) "none" else err + if (i.lastErrorAt > 0) "\n(${fmt(i.lastErrorAt)})" else ""),
            DiagRow("Call state", callText(i.role, i.callState)),
            DiagRow("SMS", smsText(i.role, i.connectionState, i.telephonyAvailable, i.smsPermissionsGranted)),
            DiagRow("Audio", audioText(i.audioMode, i.audioTwoWayCellular, i.audioReason, i.audioSession, i.audioIndicator))
        ) + (i.uplink?.let { uplinkRows(it) } ?: emptyList())
    }

    /** The text put on the clipboard. Contains exactly the rows above plus a header and a privacy line, nothing else. */
    fun report(i: DiagnosticsInput, generatedAt: Long, fmt: (Long) -> String): String = buildString {
        appendLine("AntiRegister diagnostics")
        appendLine("Generated: ${fmt(generatedAt)}")
        appendLine()
        rows(i, fmt).forEach { r ->
            val lines = r.value.split('\n')
            appendLine("${r.label}: ${lines.first()}")
            lines.drop(1).forEach { appendLine("    $it") }
        }
        appendLine()
        append("No SMS text, phone numbers, contacts or call audio are included. Device addresses are masked.")
    }
}
