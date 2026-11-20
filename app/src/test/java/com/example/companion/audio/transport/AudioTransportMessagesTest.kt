package com.example.companion.audio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 31B JVM tests. WRITTEN BUT NOT RUN: no Gradle / Android SDK / device was available when this stage was authored.
 * They cover only the pure parts ([AudioTransportMessages]); AudioTransport itself needs Handler/SystemClock and has no JVM test.
 */
class AudioTransportMessagesTest {

    @Test fun existingTextsAreUnchanged() {
        assertEquals("Not listening: open the audio test screen on this phone", AudioTransportMessages.NOT_LISTENING_TEST_SCREEN)
        assertEquals("The other phone did not answer. Open the audio test screen on it first.", AudioTransportMessages.NEGOTIATE_TIMEOUT)
    }

    @Test fun notListeningConstant() {
        assertEquals("Not listening", AudioTransportMessages.NOT_LISTENING)
        assertTrue(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN.startsWith(AudioTransportMessages.NOT_LISTENING))
    }

    @Test fun isNotListeningRecognisesRefusals() {
        assertTrue(AudioTransportMessages.isNotListening(AudioTransportMessages.NOT_LISTENING))
        assertTrue(AudioTransportMessages.isNotListening(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN))
        assertFalse(AudioTransportMessages.isNotListening(null))
        assertFalse(AudioTransportMessages.isNotListening(""))
        assertFalse(AudioTransportMessages.isNotListening("No role selected"))
        assertFalse(AudioTransportMessages.isNotListening(AudioTransportMessages.NEGOTIATE_TIMEOUT))
    }

    @Test fun defaultsRefuseExactlyAsBefore() {
        // rxEnabled = false, callRxEnabled = false (the defaults) -> same refusal text as before Stage 31B
        assertFalse(AudioTransportMessages.acceptsIncoming(testScreenRx = false, callRx = false))
        assertEquals(AudioTransportMessages.NOT_LISTENING_TEST_SCREEN, AudioTransportMessages.refusalReason(false, false))
    }

    @Test fun testScreenAloneStillAccepts() {
        assertTrue(AudioTransportMessages.acceptsIncoming(testScreenRx = true, callRx = false))
        assertNull(AudioTransportMessages.refusalReason(true, false))
    }

    @Test fun callRxAloneAcceptsWithoutTestScreen() {
        assertTrue(AudioTransportMessages.acceptsIncoming(testScreenRx = false, callRx = true))
        assertNull(AudioTransportMessages.refusalReason(false, true))
    }

    @Test fun bothAcceptAndClosingOneKeepsTheOther() {
        assertNull(AudioTransportMessages.refusalReason(true, true))
        assertNull(AudioTransportMessages.refusalReason(false, true))   // test screen closed, call rx still armed
        assertNull(AudioTransportMessages.refusalReason(true, false))   // call rx off, test screen still open
    }
}
