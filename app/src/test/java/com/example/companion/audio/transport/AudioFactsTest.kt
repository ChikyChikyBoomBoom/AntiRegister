package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 40 JVM tests of the audio-facts text helpers. The numeric constants mirror the Android SDK from memory (not checked here). WRITTEN BUT NOT RUN. */
class AudioFactsTest {
    @Test fun modeText() {
        assertEquals("unknown", AudioFactsText.modeText(null))
        assertEquals("NORMAL", AudioFactsText.modeText(0))
        assertEquals("IN_CALL", AudioFactsText.modeText(2))
        assertEquals("IN_COMMUNICATION", AudioFactsText.modeText(3))
        assertEquals("unrecognised (99)", AudioFactsText.modeText(99))
    }

    @Test fun devicesTextIsHonestAboutUnknownAndEmpty() {
        assertTrue(AudioFactsText.devicesText(null).startsWith("unknown"))
        assertEquals("none reported", AudioFactsText.devicesText(emptyList()))
        assertEquals("speaker, type 999", AudioFactsText.devicesText(listOf(2, 2, 999)))
    }

    @Test fun headsetDetectionIsPresenceOnly() {
        assertTrue(AudioFactsText.hasHeadsetOutput(listOf(2, 3)))
        assertTrue(AudioFactsText.hasHeadsetOutput(listOf(7)))
        assertFalse(AudioFactsText.hasHeadsetOutput(listOf(1, 2)))
        assertFalse(AudioFactsText.hasHeadsetOutput(emptyList()))
    }

    @Test fun permissionText() {
        assertEquals("granted", AudioFactsText.permissionText(true))
        assertEquals("denied", AudioFactsText.permissionText(false))
        assertEquals("unknown", AudioFactsText.permissionText(null))
    }

    @Test fun defaultsAreUnknown() {
        val f = AudioFacts()
        assertEquals(null, f.audioMode); assertEquals(null, f.communicationDeviceTypes); assertEquals(null, f.headsetOutputPresent)
        assertTrue(f.micRates.isEmpty())
    }
}
