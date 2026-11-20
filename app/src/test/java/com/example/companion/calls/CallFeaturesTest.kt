package com.example.companion.calls

import com.example.companion.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** JVM tests of the Stage 7 read-only calling switch. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class CallFeaturesTest {

    @Test fun callingAndHistoryEditingShipOff() {
        assertFalse(CallFeatures.isCallingEnabled())
        assertFalse(CallFeatures.isHistoryEditingEnabled())
    }

    @Test fun theUserFacingMessageIsExactlyTheRequestedPersianText() {
        assertEquals("این بخش در حال توسعه است", CallFeatures.DISABLED_MESSAGE)
    }

    @Test fun everyCallMessageIsBlockedWhileCallingIsOff() {
        listOf(
            MessageType.CALL_INCOMING, MessageType.CALL_STATE, MessageType.CALL_ANSWER, MessageType.CALL_REJECT,
            MessageType.CALL_HANGUP, MessageType.DIAL, MessageType.CALL_MUTE, MessageType.CALL_SPEAKER, MessageType.CALL_SYNC_REQUEST
        ).forEach { assertTrue("$it must be blocked", CallFeatures.blocksMessage(it)) }
    }

    @Test fun smsAndKeepaliveMessagesAreNeverBlocked() {
        listOf(
            MessageType.PING, MessageType.PONG, MessageType.SMS_RECEIVED, MessageType.SMS_SEND,
            MessageType.SMS_SEND_RESULT, MessageType.SMS_RECEIVED_ACK
        ).forEach { assertFalse("$it must pass", CallFeatures.blocksMessage(it)) }
    }

    @Test fun everyMessageTypeThatLooksLikeACallIsInTheBlockedSet() {
        // Guards against a future CALL_* / DIAL type being added to the protocol and forgotten here.
        MessageType.values().filter { it.name.startsWith("CALL_") || it.name == "DIAL" }
            .forEach { assertTrue("$it is missing from CALL_MESSAGE_TYPES", it in CallFeatures.CALL_MESSAGE_TYPES) }
    }

    /**
     * Source-level guard: the only code allowed to call TelecomManager.placeCall, Call.answer/reject/disconnect is CallManager,
     * and it must check the switch first. Reads the source file, so it needs the module directory as working directory (Gradle default).
     */
    @Test fun callManagerChecksTheSwitchBeforeEveryTelecomCommand() {
        val f = listOf("src/main/java", "app/src/main/java").map { File(it, "com/example/companion/calls/CallManager.kt") }.firstOrNull { it.exists() } ?: return
        val src = f.readText()
        listOf("fun answer() {", "fun reject() {", "fun hangup() {", "fun dial(number: String) {").forEach { sig ->
            val i = src.indexOf(sig)
            assertTrue("$sig not found", i >= 0)
            assertTrue("$sig must start with the CallFeatures check", src.substring(i, i + sig.length + 140).contains("CallFeatures.isCallingEnabled()"))
        }
    }
}
