package com.example.companion.sms

import com.example.companion.calls.CallFeatures
import com.example.companion.protocol.MessageType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 8: pins that disabling calling and audio (Stages 1-7) cannot reach SMS or the Bluetooth link.
 * Pure JVM (reads source files like [com.example.companion.calls.CallFeaturesTest]); needs the module directory as working directory (Gradle default).
 */
class SmsBluetoothIsolationTest {

    private val base = listOf("src/main/java", "app/src/main/java").map { File(it, "com/example/companion") }.firstOrNull { it.exists() }

    /** Every message type that is not a call command / call report must pass the Stage 7 gate (SMS, keepalive and audio-control alike). */
    @Test fun noNonCallMessageTypeIsBlocked() {
        MessageType.values().filter { it !in CallFeatures.CALL_MESSAGE_TYPES }
            .forEach { assertFalse("$it must not be blocked by the calling switch", CallFeatures.blocksMessage(it)) }
    }

    @Test fun onlyTheNineCallTypesAreBlocked() {
        val blocked = MessageType.values().filter { CallFeatures.blocksMessage(it) }.toSet()
        assertTrue(blocked == CallFeatures.CALL_MESSAGE_TYPES && blocked.size == 9)
    }

    /**
     * The SMS controllers, the SMS receivers/store, the SMS wire format and the whole Bluetooth connection layer must not depend on the
     * calling / audio switches or on any calling / audio class, so switching those features off (or on) cannot change SMS or Bluetooth behaviour.
     */
    @Test fun smsAndBluetoothSourcesDoNotReferenceCallingOrAudio() {
        val root = base ?: return
        val files = listOf("sms", "bluetooth").flatMap { d -> File(root, d).listFiles { f -> f.extension == "kt" }?.toList().orEmpty() } +
            File(root, "protocol/SmsMessages.kt")
        assertTrue("no SMS / Bluetooth source files found", files.size > 10)
        val forbidden = listOf("CallFeatures", "AudioTransferFeatures", "DiagnosticScreens", "import com.example.companion.audio", "import com.example.companion.calls")
        files.forEach { f ->
            val src = f.readText()
            forbidden.forEach { assertFalse("${f.name} must not reference '$it'", src.contains(it)) }
        }
    }
}
