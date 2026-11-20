package com.example.companion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 25: pins the permissions declared by AndroidManifest.xml. Reads the file only (pure JVM, no Android classes).
 * Written, not run. If a permission is added or restored on purpose, update the lists here and STAGE25_NOTES.md together.
 */
class Stage25PermissionsTest {

    private fun manifestText(): String =
        (File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")).readText()

    /** The permission names of the real <uses-permission> elements; text inside XML comments is ignored. */
    private fun declared(): Set<String> {
        val noComments = manifestText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(noComments).map { it.groupValues[1] }.toSet()
    }

    @Test fun retainedPermissionsAreDeclared() {
        val retained = setOf(
            "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS",
            "android.permission.READ_CALL_LOG", "android.permission.READ_CONTACTS"
        )
        assertEquals(retained, declared())
    }

    @Test fun permissionsOfDisabledFeaturesAreNotDeclared() {
        val d = declared()
        listOf(
            "android.permission.CALL_PHONE", "android.permission.USE_FULL_SCREEN_INTENT",                       // calling is off
            "android.permission.RECORD_AUDIO", "android.permission.MODIFY_AUDIO_SETTINGS",                      // audio transfer is off
            "android.permission.CAPTURE_AUDIO_OUTPUT", "android.permission.BLUETOOTH_PRIVILEGED"               // downlink bridge / Magisk are off
        ).forEach { assertFalse("$it must not be declared", it in d) }
    }

    @Test fun callHistoryIsReadOnlyAndSmsDoesNotReadTheInbox() {
        val d = declared()
        assertTrue("android.permission.READ_CALL_LOG" in d)
        listOf("android.permission.WRITE_CALL_LOG", "android.permission.READ_SMS", "android.permission.WRITE_CONTACTS",
            "android.permission.GET_ACCOUNTS", "android.permission.READ_PHONE_STATE").forEach { assertFalse(it in d) }
    }
}
