package com.example.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1: the audio-transfer kill switches ship OFF. Pure constants / functions; nothing Android is loaded.
 * WRITTEN BUT NOT EXECUTED in this environment (no Kotlin compiler / Gradle available).
 */
class AudioTransferFeaturesTest {
    @Test fun audioTransferShipsDisabled() {
        assertFalse(AudioTransferFeatures.AUDIO_TRANSFER_ENABLED)
        assertFalse(AudioTransferFeatures.isTransferEnabled())
    }

    @Test fun hfpShipsDisabled() {
        assertFalse(AudioTransferFeatures.HFP_ENABLED)
        assertFalse(AudioTransferFeatures.isHfpEnabled())
    }

    @Test fun magiskShipsDisabled() {
        assertFalse(AudioTransferFeatures.MAGISK_ENABLED)
        assertFalse(AudioTransferFeatures.isMagiskEnabled())
        assertTrue(AudioTransferFeatures.MAGISK_DISABLED_MESSAGE.isNotBlank())
    }

    @Test fun disabledMessagesAreNotEmpty() {
        assertTrue(AudioTransferFeatures.DISABLED_MESSAGE.isNotBlank())
        assertTrue(AudioTransferFeatures.HFP_DISABLED_MESSAGE.isNotBlank())
        assertEquals("Audio transfer is disabled in this build", AudioTransferFeatures.DISABLED_MESSAGE)
    }
}
