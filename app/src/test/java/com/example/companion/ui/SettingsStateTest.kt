package com.example.companion.ui

import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.data.Role
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Settings screen's decisions. WRITTEN BUT NOT RUN. */
class SettingsStateTest {

    private fun ui(
        role: Role? = Role.MAIN_PHONE,
        state: State = State.DISCONNECTED,
        connectedName: String? = null,
        savedName: String? = "Pixel 7",
        savedAddress: String? = "AA:BB:CC:DD:EE:FF",
        retry: Int = 0,
        lastError: String? = null
    ) = SettingsState.from(role, state, connectedName, savedName, savedAddress, retry, lastError)

    @Test fun roleLineShowsTheRoleLabel() {
        assertEquals("Main Phone", ui(Role.MAIN_PHONE).roleLine)
        assertEquals("SIM Phone", ui(Role.SIM_PHONE).roleLine)
        assertEquals("Not selected", ui(null).roleLine)
    }

    @Test fun otherRoleSwaps() {
        assertEquals(Role.SIM_PHONE, SettingsState.otherRole(Role.MAIN_PHONE))
        assertEquals(Role.MAIN_PHONE, SettingsState.otherRole(Role.SIM_PHONE))
    }

    @Test fun everyStateHasAStatusText() {
        State.values().forEach { assertTrue(it.name, SettingsState.statusText(it).isNotBlank()) }
        assertEquals("Connected", ui(state = State.CONNECTED).statusLine)
        assertEquals("Disconnected", ui(state = State.ERROR).statusLine)
    }

    @Test fun connectButtonFollowsActivity() {
        State.values().forEach {
            val expectActive = it !in setOf(State.DISCONNECTED, State.ERROR)
            assertEquals(it.name, if (expectActive) "Disconnect" else "Connect", ui(state = it).connectLabel)
        }
    }

    @Test fun mainPhoneCannotConnectWithoutADeviceButCanAlwaysDisconnect() {
        val none = ui(savedName = null, savedAddress = null)
        assertFalse(none.connectEnabled)
        assertEquals("Select a SIM Phone first.", none.connectHint)
        assertEquals("No SIM Phone selected", none.deviceLine)
        assertEquals("Select SIM Phone", none.changeDeviceLabel)
        assertTrue(ui(state = State.CONNECTING, savedName = null, savedAddress = null).connectEnabled)
    }

    @Test fun simPhoneNeedsNoSelectedDevice() {
        val r = ui(Role.SIM_PHONE, savedName = null, savedAddress = null)
        assertTrue(r.connectEnabled)
        assertEquals("", r.connectHint)
        assertFalse(r.canChangeDevice)
    }

    @Test fun mainPhoneDeviceLineShowsNameAndAddress() {
        assertEquals("Pixel 7\nAA:BB:CC:DD:EE:FF", ui().deviceLine)
        assertEquals("AA:BB:CC:DD:EE:FF", ui(savedName = "  ").deviceLine)
        assertEquals("Change device", ui().changeDeviceLabel)
    }

    @Test fun connectedSocketNameWinsOnlyWhileConnected() {
        assertEquals("Galaxy A14\nAA:BB:CC:DD:EE:FF", ui(state = State.CONNECTED, connectedName = "Galaxy A14").deviceLine)
        assertEquals("Pixel 7\nAA:BB:CC:DD:EE:FF", ui(state = State.DISCONNECTED, connectedName = "stale").deviceLine)
    }

    @Test fun simPhoneShowsTheConnectedMainPhone() {
        assertEquals("Galaxy A14", ui(Role.SIM_PHONE, State.CONNECTED, connectedName = "Galaxy A14").deviceLine)
        assertEquals("Main Phone", ui(Role.SIM_PHONE, State.CONNECTED, connectedName = null).deviceLine)
        assertEquals("No Main Phone connected", ui(Role.SIM_PHONE, State.LISTENING).deviceLine)
    }

    @Test fun autoReconnectOnlyAppliesToTheMainPhone() {
        assertTrue(ui(Role.MAIN_PHONE).autoReconnectApplies)
        assertFalse(ui(Role.SIM_PHONE).autoReconnectApplies)
        assertTrue(ui(Role.SIM_PHONE).autoReconnectNote.contains("always"))
    }

    @Test fun detailLines() {
        assertEquals("Pairing timed out", ui(state = State.ERROR, lastError = "Pairing timed out").detail)
        assertEquals("", ui(state = State.DISCONNECTED, lastError = "stale").detail)
        assertEquals("Attempt 2 of 6\nConnection lost", ui(state = State.RECONNECTING, retry = 2, lastError = "Connection lost").detail)
        assertEquals("Attempt 9", ui(state = State.RECONNECTING, retry = 9).detail)
        assertEquals("", ui(state = State.RECONNECTING, retry = 0).detail)
        assertTrue(ui(state = State.PAIRING).detail.contains("pairing"))
    }

    @Test fun callInProgressRule() {
        assertFalse(SettingsState.callInProgress(null))
        assertFalse(SettingsState.callInProgress(CallState.IDLE))
        assertFalse(SettingsState.callInProgress(CallState.DISCONNECTED))
        listOf(CallState.RINGING, CallState.DIALING, CallState.ACTIVE, CallState.HOLDING, CallState.DISCONNECTING)
            .forEach { assertTrue(it.name, SettingsState.callInProgress(it)) }
    }

    @Test fun roleChangeIsBlockedDuringACall() {
        assertNull(SettingsState.roleChangeBlock(false))
        assertEquals("Finish the current call before changing the role.", SettingsState.roleChangeBlock(true))
    }

    @Test fun deviceChangeBlocks() {
        fun b(role: Role? = Role.MAIN_PHONE, call: Boolean = false, sup: Boolean = true, on: Boolean = true, perm: Boolean = true) =
            SettingsState.deviceChangeBlock(role, call, sup, on, perm)
        assertEquals(DeviceChangeBlock.NONE, b())
        assertEquals(DeviceChangeBlock.NOT_MAIN_PHONE, b(Role.SIM_PHONE))
        assertEquals(DeviceChangeBlock.NOT_MAIN_PHONE, b(null))
        assertEquals(DeviceChangeBlock.CALL_IN_PROGRESS, b(call = true))
        assertEquals(DeviceChangeBlock.BLUETOOTH_UNSUPPORTED, b(sup = false))
        assertEquals(DeviceChangeBlock.BLUETOOTH_OFF, b(on = false))
        assertEquals(DeviceChangeBlock.NEEDS_PERMISSION, b(perm = false))
    }

    @Test fun appInfoRows() {
        val rows = SettingsState.appInfo("0.18", 18, "com.example.companion", "14", 34, "Google Pixel 7", null).toMap()
        assertEquals("0.18 (18)", rows["Version"])
        assertEquals("14 (API 34)", rows["Android"])
        assertEquals("-", rows["Bluetooth name"])
        assertTrue(rows["Data"]!!.contains("this phone only"))
    }
}
