package com.example.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Stage 20 history decisions. WRITTEN BUT NOT RUN. */
class HistoryStateTest {

    @Test fun smsAddressAcceptsPhoneNumbersAndTrims() {
        assertEquals("+15551234", HistoryState.smsAddress(" +15551234 "))
        assertEquals("+1 (555) 123-4567", HistoryState.smsAddress("+1 (555) 123-4567"))
    }

    @Test fun smsAddressRefusesMissingOrUnusableNumbers() {
        assertNull(HistoryState.smsAddress(null))
        assertNull(HistoryState.smsAddress(""))
        assertNull(HistoryState.smsAddress("*123#"))
        assertNull(HistoryState.smsAddress("12"))
        assertNull(HistoryState.smsAddress("MYBANK"))
    }

    @Test fun dialNumberStripsFormattingOnly() {
        assertEquals("+15551234567", HistoryState.dialNumber("+1 (555) 123-4567"))
        assertEquals("5551234", HistoryState.dialNumber("555-1234"))
    }

    @Test fun dialNumberLeavesUndialableAddressesUnchangedSoTheyAreRefused() {
        assertEquals("MYBANK", HistoryState.dialNumber("MYBANK"))
        assertEquals(RecentCallTap.CANNOT_DIAL, RecentCallsState.onTap(HistoryState.dialNumber("MYBANK"), linkUp = true, callLive = false))
        assertEquals("555 1234 ext 5", HistoryState.dialNumber("555 1234 ext 5"))
        assertEquals(RecentCallTap.CANNOT_DIAL, RecentCallsState.onTap(HistoryState.dialNumber("555 1234 ext 5"), true, false))
        assertNull(HistoryState.dialNumber(null))
    }

    @Test fun formattedMessageAddressCanBeCalledThroughTheSameRules() {
        val n = HistoryState.dialNumber("+1 555 123 4567")
        assertEquals(RecentCallTap.DIAL, RecentCallsState.onTap(n, linkUp = true, callLive = false))
        assertEquals(RecentCallTap.LINK_DOWN, RecentCallsState.onTap(n, linkUp = false, callLive = false))
        assertEquals(RecentCallTap.CALL_IN_PROGRESS, RecentCallsState.onTap(n, linkUp = true, callLive = true))
    }

    @Test fun menusOfferTheRightActionsAndAlwaysDelete() {
        assertEquals(listOf(HistoryAction.CALL, HistoryAction.SMS, HistoryAction.DELETE), HistoryState.menu(HistoryTab.CALLS))
        assertEquals(listOf(HistoryAction.OPEN, HistoryAction.CALL, HistoryAction.DELETE), HistoryState.menu(HistoryTab.MESSAGES))
    }

    @Test fun clearAllTextsNameTheTabAndCounts() {
        assertEquals("Clear all calls?", HistoryState.clearAllTitle(HistoryTab.CALLS))
        assertEquals("Clear all messages?", HistoryState.clearAllTitle(HistoryTab.MESSAGES))
        assertTrue(HistoryState.clearAllMessage(HistoryTab.CALLS, 1).contains("1 call "))
        assertTrue(HistoryState.clearAllMessage(HistoryTab.CALLS, 3).contains("3 calls"))
        val m = HistoryState.clearAllMessage(HistoryTab.MESSAGES, 2, 7)
        assertTrue(m.contains("2 conversations")); assertTrue(m.contains("7 messages"))
        assertTrue(HistoryState.clearAllMessage(HistoryTab.MESSAGES, 1, 1).contains("1 conversation (1 message)"))
        assertTrue(HistoryState.clearAllMessage(HistoryTab.CALLS, 3).contains("cannot be undone"))
    }

    @Test fun clearAllIsOnlyOfferedWhenThereIsSomethingToClear() {
        assertFalse(HistoryState.clearAllVisible(0))
        assertTrue(HistoryState.clearAllVisible(1))
    }

    @Test fun tabLabelsMatchTheScreenTitles() {
        assertEquals("Recent Calls", HistoryTab.CALLS.label)
        assertEquals("Messages", HistoryTab.MESSAGES.label)
    }
}
