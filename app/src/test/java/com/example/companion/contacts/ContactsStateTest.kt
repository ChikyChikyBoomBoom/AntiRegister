package com.example.companion.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the Contacts decisions. WRITTEN BUT NOT RUN. */
class ContactsStateTest {

    @Test fun accessGrantedWinsOverEverything() {
        assertEquals(ContactsAccess.GRANTED, ContactsState.access(true, askedBefore = true, showRationale = false))
    }

    @Test fun firstTimeAndAfterOneDenialCanAsk() {
        assertEquals(ContactsAccess.CAN_ASK, ContactsState.access(false, askedBefore = false, showRationale = false))
        assertEquals(ContactsAccess.CAN_ASK, ContactsState.access(false, askedBefore = true, showRationale = true))
    }

    @Test fun askedAndNoRationaleMeansSettingsOnly() {
        assertEquals(ContactsAccess.SETTINGS_ONLY, ContactsState.access(false, askedBefore = true, showRationale = false))
    }

    @Test fun noticeAndButtonFollowAccess() {
        assertEquals("", ContactsState.notice(ContactsAccess.GRANTED))
        assertTrue(ContactsState.notice(ContactsAccess.CAN_ASK).contains("nothing is copied or uploaded"))
        assertEquals(ContactsState.BUTTON_CAN_ASK, ContactsState.buttonLabel(ContactsAccess.CAN_ASK))
        assertEquals(ContactsState.BUTTON_SETTINGS, ContactsState.buttonLabel(ContactsAccess.SETTINGS_ONLY))
    }

    @Test fun onlyPhoneNumberShapedTextIsLookedUp() {
        assertTrue(ContactsState.isLookupCandidate("+15551234"))
        assertTrue(ContactsState.isLookupCandidate(" (555) 123-4567 "))
        assertFalse(ContactsState.isLookupCandidate(null))
        assertFalse(ContactsState.isLookupCandidate("   "))
        assertFalse(ContactsState.isLookupCandidate("MYBANK"))     // alphanumeric SMS sender
        assertFalse(ContactsState.isLookupCandidate("*123#"))      // service code
        assertFalse(ContactsState.isLookupCandidate("12"))         // too short
        assertFalse(ContactsState.isLookupCandidate("1".repeat(70)))
    }

    @Test fun dialableRemovesFormattingOnly() {
        assertEquals("+15551234567", ContactsState.dialable("+1 (555) 123-4567"))
        assertEquals("5551234567", ContactsState.dialable("555.123.4567"))
        assertEquals("5551234", ContactsState.dialable("555\u00A01234"))
    }

    @Test fun dialableRefusesWhatItWouldHaveToChange() {
        assertNull(ContactsState.dialable("555-1234,5"))     // pause / extension
        assertNull(ContactsState.dialable("*67 555 1234"))
        assertNull(ContactsState.dialable("555 1234 ext 2"))
        assertNull(ContactsState.dialable(""))
        assertNull(ContactsState.dialable(null))
        assertNull(ContactsState.dialable("+"))
        assertNull(ContactsState.dialable("1".repeat(33)))
    }

    @Test fun initialIsFirstLetterOrDigitUpperCased() {
        assertEquals("A", ContactsState.initial("ann lee"))
        assertEquals("J", ContactsState.initial("  \"jo\""))
        assertEquals("7", ContactsState.initial("7-Eleven"))
        assertEquals("#", ContactsState.initial("---"))
        assertEquals("#", ContactsState.initial(null))
    }

    @Test fun secondaryNumberOnlyWhenANameIsTheMainLine() {
        assertEquals("+1555", ContactsState.secondaryNumber("+1555", "Ann"))
        assertEquals("", ContactsState.secondaryNumber("+1555", null))
        assertEquals("", ContactsState.secondaryNumber("+1555", "+1555"))
        assertEquals("", ContactsState.secondaryNumber(null, "Ann"))
    }
}
