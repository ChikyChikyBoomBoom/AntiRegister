package com.example.companion.ui

import com.example.companion.audio.call.AudioMode
import com.example.companion.audio.call.AudioSessionState
import com.example.companion.audio.hfp.Tri
import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.data.Role
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests of the Stage 23 diagnostics text. WRITTEN BUT NOT RUN. */
class DiagnosticsStateTest {

    private val fmt: (Long) -> String = { "T$it" }

    private fun input(
        role: Role? = Role.MAIN_PHONE,
        state: State = State.DISCONNECTED,
        retry: Int = 0,
        name: String? = "Pixel 7",
        address: String? = "AA:BB:CC:DD:EE:FF",
        lastConnected: Long = 0,
        lastDisconnected: Long = 0,
        lastError: String? = null,
        lastErrorAt: Long = 0,
        call: CallState? = null,
        telephony: Boolean = true,
        smsPerm: Boolean = true
    ) = DiagnosticsInput(
        appVersion = "0.23 (23)", androidRelease = "14", sdk = 34, deviceModel = "Google Pixel 7", role = role,
        bluetoothSupported = true, bluetoothEnabled = true, bluetoothPermission = true,
        connectionState = state, retryAttempt = retry, deviceName = name, deviceAddress = address,
        lastConnectedAt = lastConnected, lastDisconnectedAt = lastDisconnected, lastError = lastError, lastErrorAt = lastErrorAt,
        callState = call, telephonyAvailable = telephony, smsPermissionsGranted = smsPerm,
        audioMode = AudioMode.NONE, audioTwoWayCellular = Tri.NO, audioReason = "HFP unavailable",
        audioSession = AudioSessionState.UNAVAILABLE, audioIndicator = "Audio Unavailable"
    )

    private fun value(rows: List<DiagRow>, label: String) = rows.first { it.label == label }.value

    @Test fun hasEveryRequestedRow() {
        val labels = DiagnosticsState.rows(input(), fmt).map { it.label }
        listOf("App version", "Android", "Device model", "Role", "Bluetooth", "SIM Phone", "Connection", "Last connected",
            "Last disconnected", "Last communication error", "Call state", "SMS", "Audio").forEach { assertTrue(it, it in labels) }
    }

    @Test fun simPhoneLabelsTheConnectedDevice() {
        val labels = DiagnosticsState.rows(input(role = Role.SIM_PHONE, state = State.CONNECTED), fmt).map { it.label }
        assertTrue("Connected device" in labels)
    }

    @Test fun addressIsMaskedEverywhere() {
        assertEquals("XX:XX:XX:XX:EE:FF", DiagnosticsState.maskAddress("aa:bb:cc:dd:ee:ff"))
        val report = DiagnosticsState.report(input(lastError = "failed for AA:BB:CC:DD:EE:FF"), 1, fmt)
        assertFalse(report.contains("AA:BB:CC:DD"))
        assertTrue(report.contains("XX:XX:XX:XX:EE:FF"))
    }

    @Test fun deviceNameThatIsAnAddressIsMasked() {
        // ConnectionManager falls back to the address when a device has no name.
        val text = DiagnosticsState.deviceText(Role.SIM_PHONE, State.CONNECTED, "AA:BB:CC:DD:EE:FF", null)
        assertEquals("XX:XX:XX:XX:EE:FF", text)
    }

    @Test fun errorIsOneLineAndCapped() {
        assertNull(DiagnosticsState.cleanError("  "))
        assertNull(DiagnosticsState.cleanError(null))
        assertEquals("a b", DiagnosticsState.cleanError("a\n  b"))
        val long = DiagnosticsState.cleanError("x".repeat(500))!!
        assertEquals(200, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun timesUseNeverOrNoneWhenUnknown() {
        val rows = DiagnosticsState.rows(input(), fmt)
        assertEquals("never", value(rows, "Last connected"))
        assertEquals("none since the app started", value(rows, "Last disconnected"))
        val rows2 = DiagnosticsState.rows(input(lastConnected = 5, lastDisconnected = 9), fmt)
        assertEquals("T5", value(rows2, "Last connected"))
        assertEquals("T9", value(rows2, "Last disconnected"))
    }

    @Test fun errorShowsTimeWhenKnown() {
        val rows = DiagnosticsState.rows(input(lastError = "Connection timed out", lastErrorAt = 7), fmt)
        assertEquals("Connection timed out\n(T7)", value(rows, "Last communication error"))
        assertEquals("none", value(DiagnosticsState.rows(input(), fmt), "Last communication error"))
    }

    @Test fun reconnectingShowsAttempt() {
        assertEquals("Reconnecting (Attempt 2 of 6)", DiagnosticsState.connectionText(State.RECONNECTING, 2))
        assertEquals("Reconnecting", DiagnosticsState.connectionText(State.RECONNECTING, 0))
        State.values().forEach { assertTrue(it.name, DiagnosticsState.connectionText(it, 0).isNotBlank()) }
    }

    @Test fun callStateIsUnknownWithoutAReport() {
        assertTrue(DiagnosticsState.callText(Role.MAIN_PHONE, null).startsWith("Unknown"))
        assertEquals("Ringing", DiagnosticsState.callText(Role.SIM_PHONE, CallState.RINGING))
        CallState.values().forEach { assertTrue(it.name, DiagnosticsState.callText(Role.SIM_PHONE, it).isNotBlank()) }
    }

    @Test fun smsStateFollowsRoleAndLink() {
        assertTrue(DiagnosticsState.smsText(Role.MAIN_PHONE, State.CONNECTED, true, true).startsWith("Link up"))
        assertTrue(DiagnosticsState.smsText(Role.MAIN_PHONE, State.RECONNECTING, true, true).startsWith("Unavailable"))
        assertTrue(DiagnosticsState.smsText(Role.SIM_PHONE, State.CONNECTED, false, true).startsWith("Not available"))
        assertTrue(DiagnosticsState.smsText(Role.SIM_PHONE, State.CONNECTED, true, false).startsWith("Not enabled"))
        assertTrue(DiagnosticsState.smsText(Role.SIM_PHONE, State.LISTENING, true, true).contains("waiting"))
        assertTrue(DiagnosticsState.smsText(Role.SIM_PHONE, State.CONNECTED, true, true).contains("forwarding"))
    }

    @Test fun bluetoothText() {
        assertEquals("Not supported", DiagnosticsState.bluetoothText(false, false, false))
        assertEquals("Off", DiagnosticsState.bluetoothText(true, false, true))
        assertEquals("On, Bluetooth permission missing", DiagnosticsState.bluetoothText(true, true, false))
        assertEquals("On", DiagnosticsState.bluetoothText(true, true, true))
    }

    @Test fun savedDeviceIsLabelledNotConnected() {
        val t = DiagnosticsState.deviceText(Role.MAIN_PHONE, State.DISCONNECTED, "Pixel 7", "AA:BB:CC:DD:EE:FF")
        assertEquals("Saved: Pixel 7 (XX:XX:XX:XX:EE:FF) (not connected)", t)
        assertEquals("None selected", DiagnosticsState.deviceText(Role.MAIN_PHONE, State.DISCONNECTED, null, null))
        assertEquals("None connected", DiagnosticsState.deviceText(Role.SIM_PHONE, State.LISTENING, null, null))
    }

    @Test fun audioTextShowsModeCapabilityAndSession() {
        val t = DiagnosticsState.audioText(AudioMode.NONE, Tri.NO, "HFP unavailable", AudioSessionState.UNAVAILABLE, "Audio Unavailable")
        assertTrue(t.contains("no audio path — HFP unavailable"))
        assertTrue(t.contains("Two-way call audio: no"))
        assertTrue(t.contains("Unavailable (Audio Unavailable)"))
    }

    @Test fun reportContainsRowsAndPrivacyLineOnly() {
        val report = DiagnosticsState.report(input(call = CallState.ACTIVE, state = State.CONNECTED), 3, fmt)
        assertTrue(report.startsWith("AntiRegister diagnostics\nGenerated: T3"))
        assertTrue(report.contains("App version: 0.23 (23)"))
        assertTrue(report.contains("Call state: Active"))
        assertTrue(report.contains("No SMS text, phone numbers, contacts or call audio are included."))
        // multi-line values are indented under their label
        assertTrue(report.contains("Audio: Mode: no audio path — HFP unavailable\n    Two-way call audio: no"))
    }
}
