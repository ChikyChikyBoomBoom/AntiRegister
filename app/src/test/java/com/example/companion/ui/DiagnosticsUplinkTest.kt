package com.example.companion.ui

import com.example.companion.audio.call.AudioMode
import com.example.companion.audio.call.AudioSessionState
import com.example.companion.audio.hfp.Tri
import com.example.companion.audio.transport.AudioFacts
import com.example.companion.audio.transport.UplinkCapability
import com.example.companion.audio.transport.UplinkFlags
import com.example.companion.audio.transport.UplinkLifecycleState
import com.example.companion.audio.transport.UplinkUnsupportedReason
import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 40 JVM tests of the uplink rows of the Diagnostics screen. WRITTEN BUT NOT RUN (no Kotlin compiler / Gradle available). */
class DiagnosticsUplinkTest {

    private val fmt: (Long) -> String = { "T$it" }
    private val cap = UplinkCapability.notSupported(UplinkUnsupportedReason.NO_VERIFIED_SINK, "no verified cellular uplink sink in this build")

    private fun diag(
        facts: AudioFacts = AudioFacts(),
        perm: Boolean? = null,
        flags: UplinkFlags = UplinkFlags(),
        life: UplinkLifecycleState = UplinkLifecycleState.DISABLED,
        detail: String? = null
    ) = UplinkDiagnostics(facts, perm, cap, flags, life, detail)

    private fun input(uplink: UplinkDiagnostics?, state: State = State.CONNECTED) = DiagnosticsInput(
        appVersion = "0.35 (35)", androidRelease = "14", sdk = 34, deviceModel = "Test Phone", role = Role.MAIN_PHONE,
        bluetoothSupported = true, bluetoothEnabled = true, bluetoothPermission = true,
        connectionState = state, retryAttempt = 0, deviceName = "SIM", deviceAddress = "AA:BB:CC:DD:EE:FF",
        lastConnectedAt = 0, lastDisconnectedAt = 0, lastError = null, lastErrorAt = 0,
        callState = null, telephonyAvailable = true, smsPermissionsGranted = true,
        audioMode = AudioMode.NONE, audioTwoWayCellular = Tri.NO, audioReason = null,
        audioSession = AudioSessionState.UNAVAILABLE, audioIndicator = "Audio Unavailable",
        uplink = uplink
    )

    private fun value(rows: List<DiagRow>, label: String) = rows.first { it.label == label }.value

    @Test fun withoutUplinkDataThereAreNoUplinkRows() {
        val labels = DiagnosticsState.rows(input(null), fmt).map { it.label }
        assertFalse("Feature flags" in labels)
        assertFalse("Capture lifecycle" in labels)
    }

    @Test fun everyRequestedUplinkRowIsPresent() {
        val labels = DiagnosticsState.rows(input(diag()), fmt).map { it.label }
        listOf("Audio mode (Android)", "Communication devices", "Supported sample rates", "Microphone permission",
            "Uplink sink capability", "Feature flags", "Capture lifecycle", "Connection").forEach { assertTrue(it, it in labels) }
    }

    @Test fun unknownFactsAreReportedAsUnknownNotGuessed() {
        val rows = DiagnosticsState.rows(input(diag()), fmt)
        assertEquals("unknown", value(rows, "Audio mode (Android)"))
        assertTrue(value(rows, "Communication devices").startsWith("unknown"))
        assertEquals("unknown", value(rows, "Microphone permission"))
        assertTrue(value(rows, "Supported sample rates").contains("Microphone rates: unknown"))
        assertTrue(value(rows, "Supported sample rates").contains("Output rate of this phone: unknown"))
    }

    @Test fun knownFactsAreShown() {
        val facts = AudioFacts(audioMode = 2, communicationDeviceTypes = listOf(1, 2, 7),
            micRates = listOf(8000 to true, 16000 to true, 44100 to false, 48000 to null), nativeOutputRate = 48000, headsetOutputPresent = true)
        val rows = DiagnosticsState.rows(input(diag(facts = facts, perm = true)), fmt)
        assertEquals("IN_CALL", value(rows, "Audio mode (Android)"))
        assertEquals("earpiece, speaker, Bluetooth SCO", value(rows, "Communication devices"))
        assertEquals("granted", value(rows, "Microphone permission"))
        val rates = value(rows, "Supported sample rates")
        assertTrue(rates.contains("8000 Hz: buffer reported (used by this app)"))
        assertTrue(rates.contains("44100 Hz: not supported"))
        assertTrue(rates.contains("48000 Hz: unknown"))
        assertTrue(rates.contains("Output rate of this phone: 48000 Hz"))
        assertTrue(rates.contains("does not prove that capture works"))
    }

    @Test fun deniedPermissionIsShownAsDenied() {
        assertEquals("denied", value(DiagnosticsState.rows(input(diag(perm = false)), fmt), "Microphone permission"))
    }

    @Test fun flagsShowTheDefaultsAndTwoWayStaysNo() {
        val v = value(DiagnosticsState.rows(input(diag()), fmt), "Feature flags")
        assertEquals("uplinkBridge = false\ndownlinkBridge = false\ntwoWayCellularAudio = NO", v)
    }

    @Test fun capabilityRowStartsWithTheNotSupportedHeadlineAndNeverSaysSupported() {
        val v = value(DiagnosticsState.rows(input(diag()), fmt), "Uplink sink capability")
        assertTrue(v.startsWith("Not supported / not verified"))
        assertTrue(v.contains("no verified cellular uplink sink"))
        assertFalse(v.lowercase().contains("operational"))
    }

    @Test fun lifecycleRowShowsTheStateAndTheReason() {
        val rows = DiagnosticsState.rows(input(diag(life = UplinkLifecycleState.IDLE, detail = "There is no active call")), fmt)
        val v = value(rows, "Capture lifecycle")
        assertTrue(v.startsWith("Idle - microphone off"))
        assertTrue(v.contains("There is no active call"))
        assertTrue(value(DiagnosticsState.rows(input(diag(life = UplinkLifecycleState.MICROPHONE_ACTIVE)), fmt), "Capture lifecycle").contains("MICROPHONE ACTIVE"))
    }

    @Test fun bluetoothConnectionStateStaysInTheExistingConnectionRow() {
        assertEquals("Connected", value(DiagnosticsState.rows(input(diag(), State.CONNECTED), fmt), "Connection"))
        assertEquals("Disconnected", value(DiagnosticsState.rows(input(diag(), State.DISCONNECTED), fmt), "Connection"))
    }

    @Test fun theCopiedReportHasTheUplinkRowsAndNoAddressesOrAudio() {
        val report = DiagnosticsState.report(input(diag(facts = AudioFacts(audioMode = 0, communicationDeviceTypes = listOf(2)))), 1, fmt)
        assertTrue(report.contains("Feature flags: uplinkBridge = false"))
        assertTrue(report.contains("Capture lifecycle: "))
        assertFalse(report.contains("AA:BB:CC:DD"))
    }
}
