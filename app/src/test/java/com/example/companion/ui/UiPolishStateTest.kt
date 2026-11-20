package com.example.companion.ui

import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.data.Role
import com.example.companion.protocol.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Stage 24 wording and yes/no decisions. WRITTEN BUT NOT RUN. */
class UiPolishStateTest {

    @Test fun linkStatusIsBusyOnlyWhileOpeningTheLink() {
        assertTrue(UiPolish.isBusy(LinkStatus.CONNECTING))
        assertTrue(UiPolish.isBusy(LinkStatus.RECONNECTING))
        assertFalse(UiPolish.isBusy(LinkStatus.CONNECTED))
        assertFalse(UiPolish.isBusy(LinkStatus.DISCONNECTED))
    }

    @Test fun connectionStateIsBusyWhileScanningPairingConnectingOrWaiting() {
        val busy = setOf(State.CONNECTING, State.PAIRING, State.RECONNECTING, State.DISCOVERING, State.LISTENING)
        State.values().forEach { assertEquals(it.name, it in busy, UiPolish.isBusy(it)) }
    }

    @Test fun onlyAMainPhoneWithNoSavedDeviceNeedsOne() {
        assertTrue(UiPolish.needsDevice(Role.MAIN_PHONE, null, null))
        assertTrue(UiPolish.needsDevice(Role.MAIN_PHONE, "", "  "))
        assertFalse(UiPolish.needsDevice(Role.MAIN_PHONE, "Pixel 7", null))
        assertFalse(UiPolish.needsDevice(Role.MAIN_PHONE, null, "AA:BB:CC:DD:EE:FF"))
        assertFalse(UiPolish.needsDevice(Role.SIM_PHONE, null, null))
        assertFalse(UiPolish.needsDevice(null, null, null))
    }

    @Test fun homeShowsTheNoDeviceEmptyStateOnlyForTheSelectDeviceDetail() {
        val noDevice = HomeStatus.from(State.DISCONNECTED, null, null, null, 0, 6, null, true, true, true)
        assertEquals(HomeStatus.SELECT_DEVICE_DETAIL, noDevice.detail)
        // Bluetooth off outranks "no device": the more urgent line is kept and no empty state is implied.
        val btOff = HomeStatus.from(State.DISCONNECTED, null, null, null, 0, 6, null, true, false, true)
        assertFalse(btOff.detail == HomeStatus.SELECT_DEVICE_DETAIL)
    }

    @Test fun everyCallStateHasALabelAndLabelsMatchTheCallScreenTitles() {
        CallState.values().forEach { assertTrue(it.name, UiPolish.callStateLabel(it).isNotBlank()) }
        assertEquals("No call", UiPolish.callStateLabel(CallState.IDLE))
        assertEquals("Incoming call", UiPolish.callStateLabel(CallState.RINGING))
        assertEquals("Outgoing call", UiPolish.callStateLabel(CallState.DIALING))
        assertEquals("Call in progress", UiPolish.callStateLabel(CallState.ACTIVE))
        assertEquals("On hold", UiPolish.callStateLabel(CallState.HOLDING))
        assertEquals("Call ended", UiPolish.callStateLabel(CallState.DISCONNECTED))
    }

    @Test fun emptyStatesHaveTitleAndMessageAndOnlyNoDeviceHasAnAction() {
        listOf(UiPolish.NO_CALLS, UiPolish.NO_MESSAGES, UiPolish.NO_DEVICE, UiPolish.NO_DEVICES_FOUND, UiPolish.NO_ACTIVE_CALL).forEach {
            assertTrue(it.title.isNotBlank())
            assertTrue(it.message.isNotBlank())
        }
        assertEquals("Open Settings", UiPolish.NO_DEVICE.actionLabel)
        assertNull(UiPolish.NO_CALLS.actionLabel)
        assertNull(UiPolish.NO_MESSAGES.actionLabel)
        assertNull(UiPolish.NO_DEVICES_FOUND.actionLabel)
        assertNull(UiPolish.NO_ACTIVE_CALL.actionLabel)
    }
}
