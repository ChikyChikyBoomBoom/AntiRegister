package com.example.companion.ui

import com.example.companion.audio.transport.AudioDirection
import com.example.companion.audio.transport.UplinkBlock
import com.example.companion.audio.transport.UplinkCapability
import com.example.companion.audio.transport.UplinkFlags
import com.example.companion.audio.transport.UplinkLifecycleState
import com.example.companion.audio.transport.UplinkMessages
import com.example.companion.audio.transport.UplinkSnapshot
import com.example.companion.audio.transport.UplinkUnsupportedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 40 JVM tests of the uplink card text and enable rules. WRITTEN BUT NOT RUN (no Kotlin compiler / Gradle available). */
class UplinkUiStateTest {

    private val cap = UplinkCapability.notSupported(UplinkUnsupportedReason.PEER_NOT_VERIFIED, "peer has not verified an uplink sink")

    private fun snap(
        flagOn: Boolean = true,
        state: UplinkLifecycleState = UplinkLifecycleState.IDLE,
        detail: String? = null,
        lastStop: String? = null,
        block: UplinkBlock? = null,
        micActive: Boolean = false,
        dir: AudioDirection? = AudioDirection.MAIN_TO_SIM,
        link: Boolean = true,
        call: Boolean? = true,
        headset: Boolean? = true,
        receiving: Boolean = false
    ) = UplinkSnapshot(
        flags = UplinkFlags(uplinkBridge = flagOn), state = state, capability = cap, detail = detail, lastStopReason = lastStop,
        startBlock = block, microphoneActive = micActive, localDirection = dir, linkUp = link, callActive = call,
        microphonePermission = true, headsetOutputPresent = headset, appVisible = true, receiverArmed = false, receivingUplink = receiving
    )

    private val forbidden = listOf("operational", "working", "echo cancellation works", "connected to the call")

    private fun assertHonest(ui: UplinkUi) {
        val all = listOf(ui.microphoneLine, ui.capabilityLine, ui.connectionLine, ui.explanation, ui.echoNote, ui.buttonText, ui.buttonHint)
            .joinToString("\n").lowercase()
        forbidden.forEach { assertFalse("must not say \"$it\"", all.contains(it)) }
    }

    @Test fun flagOffExplainsAndDisablesStart() {
        val ui = UplinkUiState.main(snap(flagOn = false, state = UplinkLifecycleState.DISABLED, block = UplinkBlock.FLAG_OFF))
        assertFalse(ui.microphoneActive)
        assertFalse(ui.buttonEnabled)
        assertFalse(ui.buttonIsStop)
        assertTrue(ui.explanation.contains("uplinkBridge = false"))
        assertTrue(ui.buttonHint.contains("uplinkBridge = false"))
        assertTrue(ui.microphoneLine.startsWith("Microphone off"))
        assertHonest(ui)
    }

    @Test fun capabilityLineAlwaysCarriesTheNotSupportedHeadline() {
        val ui = UplinkUiState.main(snap())
        assertTrue(ui.capabilityLine.contains(UplinkMessages.NOT_SUPPORTED))
        assertTrue(ui.explanation.contains(UplinkMessages.NOT_SUPPORTED))
        assertHonest(ui)
    }

    @Test fun idleWithAnActiveCallShowsAStartButtonAndTheMicrophoneOff() {
        val ui = UplinkUiState.main(snap())
        assertTrue(ui.showSection)
        assertEquals("Start microphone uplink", ui.buttonText)
        assertTrue(ui.buttonEnabled)
        assertFalse(ui.microphoneActive)
        assertEquals("Microphone off", ui.microphoneLine)
    }

    @Test fun missingPermissionAndEchoRiskKeepTheButtonUsableBecauseTheyPrompt() {
        assertTrue(UplinkUiState.main(snap(block = UplinkBlock.PERMISSION)).buttonEnabled)
        assertTrue(UplinkUiState.main(snap(block = UplinkBlock.ECHO_RISK)).buttonEnabled)
    }

    @Test fun otherBlocksDisableStartAndSayWhy() {
        listOf(UplinkBlock.NO_LINK, UplinkBlock.NO_ACTIVE_CALL, UplinkBlock.BACKGROUND, UplinkBlock.MICROPHONE_BUSY).forEach { b ->
            val ui = UplinkUiState.main(snap(block = b))
            assertFalse(b.name, ui.buttonEnabled)
            assertEquals(b.text, ui.buttonHint)
        }
    }

    @Test fun negotiatingIsNotAMicrophoneIndicator() {
        val ui = UplinkUiState.main(snap(state = UplinkLifecycleState.NEGOTIATING, block = UplinkBlock.ALREADY_RUNNING))
        assertFalse(ui.microphoneActive)
        assertTrue(ui.microphoneLine.contains("waiting"))
        assertTrue(ui.buttonIsStop)
        assertTrue(ui.buttonEnabled)
        assertEquals("Stop microphone uplink", ui.buttonText)
    }

    @Test fun microphoneActiveShowsTheIndicatorAndStopEvenWithoutACall() {
        val ui = UplinkUiState.main(snap(state = UplinkLifecycleState.MICROPHONE_ACTIVE, micActive = true, call = false, block = UplinkBlock.ALREADY_RUNNING))
        assertTrue(ui.showSection)                         // never hidden while the microphone is in use
        assertTrue(ui.microphoneActive)
        assertTrue(ui.microphoneLine.contains("MICROPHONE ON"))
        assertTrue(ui.buttonIsStop && ui.buttonEnabled)
        assertTrue(ui.explanation.contains("NOT verified"))
        assertHonest(ui)
    }

    @Test fun notSupportedStateExplainsThatTheMicrophoneWasNeverOpened() {
        val ui = UplinkUiState.main(snap(state = UplinkLifecycleState.NOT_SUPPORTED, detail = "Not supported / not verified (peer refused)"))
        assertFalse(ui.microphoneActive)
        assertTrue(ui.explanation.contains("never opened"))
        assertHonest(ui)
    }

    @Test fun failedStateShowsTheReasonAndTheMicrophoneOff() {
        val ui = UplinkUiState.main(snap(state = UplinkLifecycleState.FAILED, detail = "Bluetooth disconnected - uplink stopped"))
        assertFalse(ui.microphoneActive)
        assertTrue(ui.explanation.contains("Bluetooth disconnected"))
    }

    @Test fun aStopReasonIsShownAfterTheRunEnded() {
        val ui = UplinkUiState.main(snap(lastStop = "There is no active call", call = false))
        assertTrue(ui.microphoneLine.contains("stopped: There is no active call"))
        assertFalse(ui.microphoneActive)
    }

    @Test fun sectionIsHiddenWithoutACallOnAnIdleMainPhoneAndOnTheSimPhone() {
        assertFalse(UplinkUiState.main(snap(call = false)).showSection)
        assertFalse(UplinkUiState.main(snap(call = null)).showSection)
        assertFalse(UplinkUiState.main(snap(dir = AudioDirection.SIM_TO_MAIN)).showSection)
    }

    @Test fun connectionLineSaysWhatIsKnown() {
        assertEquals("Bluetooth: connected · Call: active", UplinkUiState.main(snap()).connectionLine)
        assertEquals("Bluetooth: not connected · Call: unknown", UplinkUiState.main(snap(link = false, call = null)).connectionLine)
    }

    @Test fun echoNoteNeverClaimsCancellationWorksAndAdvisesAHeadsetWhenNoneIsFound() {
        listOf(true, false, null).forEach { h ->
            val note = UplinkUiState.main(snap(headset = h)).echoNote
            assertTrue(note.contains(UplinkUiState.ECHO_UNVERIFIED))
        }
        assertTrue(UplinkUiState.main(snap(headset = false)).echoNote.contains("Use a headset"))
        assertTrue(UplinkUiState.main(snap(headset = null)).echoNote.contains("unknown"))
    }

    @Test fun shutDownSaysTheMicrophoneIsOff() {
        val ui = UplinkUiState.main(snap(state = UplinkLifecycleState.SHUT_DOWN, block = UplinkBlock.SHUT_DOWN))
        assertFalse(ui.buttonEnabled)
        assertTrue(ui.explanation.contains("microphone is off"))
    }

    // ---- SIM Phone

    @Test fun simPhoneSaysNotStreamingBecauseItsSinkRefuses() {
        val ui = UplinkUiState.sim(snap(dir = AudioDirection.SIM_TO_MAIN), callActive = true)
        assertTrue(ui.visible)
        assertFalse(ui.indicatorActive)
        assertEquals("Main Phone microphone: not streaming to this phone", ui.indicatorLine)
        assertTrue(ui.statusLine.contains(UplinkMessages.NOT_SUPPORTED))
        assertTrue(ui.statusLine.contains("refuses"))
    }

    @Test fun simPhoneIndicatorIsActiveOnlyWhileAnUplinkStreamIsReceived() {
        val ui = UplinkUiState.sim(snap(dir = AudioDirection.SIM_TO_MAIN, receiving = true), callActive = true)
        assertTrue(ui.indicatorActive)
        assertTrue(ui.indicatorLine.contains("streaming to this phone"))
    }

    @Test fun simPhoneFlagOffSaysTheMicrophoneIsNotUsedAndHidesOutsideCalls() {
        val off = UplinkUiState.sim(snap(flagOn = false, dir = AudioDirection.SIM_TO_MAIN), callActive = true)
        assertTrue(off.statusLine.contains("uplinkBridge = false"))
        assertFalse(UplinkUiState.sim(snap(dir = AudioDirection.SIM_TO_MAIN), callActive = false).visible)
    }
}
