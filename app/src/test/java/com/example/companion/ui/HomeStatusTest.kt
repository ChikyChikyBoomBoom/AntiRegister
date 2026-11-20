package com.example.companion.ui

import com.example.companion.bluetooth.ConnectionManager.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the home screen's status mapping. WRITTEN BUT NOT RUN. */
class HomeStatusTest {

    private fun ui(
        state: State,
        connectedName: String? = null,
        savedName: String? = "Pixel 7",
        savedAddress: String? = "AA:BB:CC:DD:EE:FF",
        retry: Int = 0,
        lastError: String? = null,
        supported: Boolean = true,
        enabled: Boolean = true,
        permission: Boolean = true
    ) = HomeStatus.from(state, connectedName, savedName, savedAddress, retry, 6, lastError, supported, enabled, permission)

    @Test fun everyStateMapsToOneOfTheFourStatuses() {
        val expected = mapOf(
            State.CONNECTED to LinkStatus.CONNECTED,
            State.CONNECTING to LinkStatus.CONNECTING,
            State.PAIRING to LinkStatus.CONNECTING,
            State.RECONNECTING to LinkStatus.RECONNECTING,
            State.DISCONNECTED to LinkStatus.DISCONNECTED,
            State.DISCOVERING to LinkStatus.DISCONNECTED,
            State.LISTENING to LinkStatus.DISCONNECTED,
            State.ERROR to LinkStatus.DISCONNECTED
        )
        State.values().forEach { assertEquals(it.name, expected[it], HomeStatus.linkStatus(it)) }
    }

    @Test fun actionsAreEnabledOnlyWhenConnected() {
        State.values().forEach { assertEquals(it.name, it == State.CONNECTED, ui(it).actionsEnabled) }
    }

    @Test fun connectedShowsTheSocketsDeviceName() {
        val r = ui(State.CONNECTED, connectedName = "Galaxy A14")
        assertEquals("SIM Phone: Galaxy A14", r.deviceLine)
        assertEquals("", r.detail)
    }

    @Test fun connectedFallsBackToTheSavedNameThenAddress() {
        assertEquals("SIM Phone: Pixel 7", ui(State.CONNECTED).deviceLine)
        assertEquals("SIM Phone: AA:BB:CC:DD:EE:FF", ui(State.CONNECTED, savedName = null).deviceLine)
        assertEquals("SIM Phone: AA:BB:CC:DD:EE:FF", ui(State.CONNECTED, savedName = "  ").deviceLine)
    }

    @Test fun disconnectedShowsTheSelectedDeviceNotAConnectedOne() {
        // connectedName is cleared by ConnectionManager on teardown, but even a stale value must not be used.
        assertEquals("Selected SIM Phone: Pixel 7", ui(State.DISCONNECTED, connectedName = "stale").deviceLine)
    }

    @Test fun noDeviceSelected() {
        val r = ui(State.DISCONNECTED, savedName = null, savedAddress = null)
        assertEquals("No SIM Phone selected", r.deviceLine)
        assertEquals("Open Settings to select your SIM Phone.", r.detail)
    }

    @Test fun reconnectingShowsAttemptAndReason() {
        val r = ui(State.RECONNECTING, retry = 2, lastError = "Connection lost")
        assertEquals(LinkStatus.RECONNECTING, r.status)
        assertEquals("Attempt 2 of 6\nConnection lost", r.detail)
        assertEquals("SIM Phone: Pixel 7", r.deviceLine)
    }

    @Test fun reconnectingWithoutAttemptNumberStillReadable() {
        assertEquals("Trying to reach the SIM Phone…", ui(State.RECONNECTING, retry = 0).detail)
    }

    @Test fun pairingIsShownAsConnectingWithItsOwnHint() {
        val r = ui(State.PAIRING)
        assertEquals(LinkStatus.CONNECTING, r.status)
        assertEquals("Pairing: confirm on both phones", r.detail)
        assertFalse(r.actionsEnabled)
    }

    @Test fun errorShowsItsMessage() {
        assertEquals("Pairing timed out", ui(State.ERROR, lastError = "Pairing timed out").detail)
    }

    @Test fun staleErrorIsIgnoredWhenJustDisconnected() {
        assertEquals("Open Settings to connect.", ui(State.DISCONNECTED, lastError = "Connection timed out").detail)
    }

    @Test fun environmentProblemsBeatOtherDisconnectedDetails() {
        assertTrue(ui(State.ERROR, lastError = "x", permission = false).detail.startsWith("Bluetooth permission"))
        assertEquals("Bluetooth is not supported on this device.", ui(State.DISCONNECTED, supported = false).detail)
        assertEquals("Bluetooth is turned off.", ui(State.DISCONNECTED, enabled = false).detail)
    }

    @Test fun discoveringIsDisconnectedWithScanHint() {
        val r = ui(State.DISCOVERING)
        assertEquals(LinkStatus.DISCONNECTED, r.status)
        assertEquals("Scanning for devices…", r.detail)
    }
}
