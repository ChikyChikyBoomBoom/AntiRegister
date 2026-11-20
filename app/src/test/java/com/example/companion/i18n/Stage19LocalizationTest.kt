package com.example.companion.i18n

import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.calls.CallFeatures
import com.example.companion.contacts.ContactsAccess
import com.example.companion.contacts.ContactsState
import com.example.companion.protocol.CallState
import com.example.companion.ui.CallNotificationAction
import com.example.companion.ui.CallNotificationState
import com.example.companion.ui.CallStateText
import com.example.companion.ui.HistoryAction
import com.example.companion.ui.HistoryState
import com.example.companion.ui.HistoryTab
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 19: JVM tests for the remaining Persian translations. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available).
 * Views, dialogs, toasts and notifications themselves need a device; see app/STAGE19_NOTES.md.
 */
class Stage19LocalizationTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN
    private val persianLetters = Regex("[\\u0600-\\u06FF]")
    private val stage19Keys = StringKey.values().dropWhile { it != StringKey.SIMCALL_TITLE }

    // ---------------------------------------------------------------- catalog

    @Test fun stage19KeysAreAllPresentAndTranslated() {
        assertTrue(stage19Keys.size >= 100)
        stage19Keys.forEach {
            assertTrue("No Persian: $it", Strings.isTranslated(it, fa))
            assertTrue("Persian text has no Persian letters: $it", persianLetters.containsMatchIn(Strings.text(it, fa)) || it.english.all { c -> !c.isLetter() })
        }
    }

    @Test fun placeholdersMatchBetweenEnglishAndPersian() {
        val re = Regex("%\\d\\\$[sd]")
        StringKey.values().forEach {
            assertEquals("Placeholders differ: $it", re.findAll(it.english).map { m -> m.value }.sorted().toList(),
                re.findAll(Strings.text(it, fa)).map { m -> m.value }.sorted().toList())
        }
    }

    @Test fun englishWordingOfMovedLiteralsIsUnchanged() {
        assertEquals("Phone call", Strings.text(StringKey.SIMCALL_TITLE, en))
        assertEquals("Hang up", Strings.text(StringKey.SIMCALL_HANGUP, en))
        assertEquals("Mute failed: x", Strings.text(StringKey.SIMCALL_MUTE_FAILED, en, "x"))
        assertEquals("ANSWER failed: busy", Strings.text(StringKey.SIMCALL_COMMAND_FAILED, en, "ANSWER", "busy"))
        assertEquals("Number: 123", Strings.text(StringKey.SIMCALL_NUMBER, en, "123"))
        assertEquals("Connection state: CONNECTED", Strings.text(StringKey.STATUS_STATE, en, Strings.text(StringKey.STATUS_ST_CONNECTED, en)))
        assertEquals("RECONNECTING (attempt 2/6)", Strings.text(StringKey.STATUS_ST_RECONNECTING, en, "2/6"))
        assertEquals("Updated 3:45 PM", Strings.text(StringKey.DIAG_UPDATED, en, "3:45 PM"))
        assertEquals("Reconnect during a call?", Strings.text(StringKey.DIAG_DIALOG_TITLE, en))
    }

    @Test fun missingPersianTextFallsBackToEnglish() {
        val withoutKey = emptyMap<StringKey, String>()
        assertEquals("Phone call", Strings.text(StringKey.SIMCALL_TITLE, fa, withoutKey))
        assertEquals(StringKey.ERR_TIMEOUT.english, Strings.text(StringKey.ERR_TIMEOUT, fa, withoutKey))
    }

    // ---------------------------------------------------------------- call state words

    @Test fun callStateEnglishEqualsEnumNameExceptIdle() {
        CallState.values().filter { it != CallState.IDLE }.forEach { assertEquals(it.name, CallStateText.name(it, en)) }
        assertEquals("No call", CallStateText.name(CallState.IDLE, en))
    }

    @Test fun callStatePersianIsTranslatedForEveryState() {
        CallState.values().forEach {
            val text = CallStateText.name(it, fa)
            assertNotEquals(CallStateText.name(it, en), text)
            assertTrue(persianLetters.containsMatchIn(text))
        }
    }

    @Test fun onOffWords() {
        assertEquals("ON", CallStateText.onOff(true, en)); assertEquals("OFF", CallStateText.onOff(false, en)); assertEquals("unknown", CallStateText.onOff(null, en))
        assertEquals("روشن", CallStateText.onOff(true, fa)); assertEquals("خاموش", CallStateText.onOff(false, fa)); assertEquals("نامشخص", CallStateText.onOff(null, fa))
    }

    // ---------------------------------------------------------------- connection errors

    private val englishErrors = listOf(
        "Only the Main Phone scans for devices", "Bluetooth permission denied. Allow it in system settings.", "Bluetooth is turned off",
        "Could not start Bluetooth discovery", "No role selected", "Bluetooth is not supported on this device",
        "No SIM phone selected. Use \"Select SIM phone\" first.", "Saved device address is invalid. Select the SIM phone again.",
        "Could not start pairing", "Device is no longer paired. Select the SIM phone again.", "Pairing timed out", "Bluetooth was turned off",
        "Pairing failed or was cancelled", "Connection timed out", "SIM Phone unavailable (off, out of range, or app not listening)",
        "Connection lost (remote disconnected or out of range)", "Connection lost (remote stopped responding)", "Connection lost (Bluetooth link dropped)"
    )

    @Test fun connectionErrorsAreUnchangedInEnglish() {
        englishErrors.forEach { assertEquals(it, ConnectionErrorText.localize(it, en)) }
        assertEquals("Could not open Bluetooth socket: boom", ConnectionErrorText.localize("Could not open Bluetooth socket: boom", en))
    }

    @Test fun connectionErrorsAreTranslatedInPersian() {
        englishErrors.forEach {
            val text = ConnectionErrorText.localize(it, fa)
            assertNotEquals(it, text)
            assertTrue(persianLetters.containsMatchIn(text))
        }
    }

    @Test fun errorDetailFromTheSystemIsKept() {
        val text = ConnectionErrorText.localize("Could not open Bluetooth socket: read failed, socket might closed", fa)
        assertTrue(text.contains("read failed, socket might closed"))
        assertTrue(persianLetters.containsMatchIn(text))
        assertTrue(ConnectionErrorText.localize("Unexpected Bluetooth error: X", fa).contains("X"))
    }

    @Test fun unknownErrorIsReturnedUnchanged() {
        assertEquals("Something new", ConnectionErrorText.localize("Something new", fa))
        assertEquals(null, ConnectionErrorText.localizeOrNull(null, fa))
    }

    // ---------------------------------------------------------------- notifications

    @Test fun callNotificationEnglishEqualsOriginalTexts() {
        assertEquals(CallNotificationState.PUBLIC_TEXT, CallNotificationState.publicText(en))
        assertEquals(CallNotificationState.NO_RESPONSE, CallNotificationState.noResponse(en))
        assertEquals(CallNotificationState.UNKNOWN_NUMBER, CallNotificationState.caller(null, en) { null })
        assertEquals(CallNotificationState.UNKNOWN_NUMBER, CallNotificationState.caller(null) { null })
        val ringing = CallNotificationState.content("123", { null }, null, "")
        assertEquals("Incoming call", CallNotificationState.statusText(ringing))
        assertEquals("Answering…", CallNotificationState.statusText(CallNotificationState.content("123", { null }, CallNotificationAction.ANSWER, "")))
        assertEquals("Rejecting…", CallNotificationState.statusText(CallNotificationState.content("123", { null }, CallNotificationAction.REJECT, "")))
    }

    @Test fun callNotificationPersian() {
        val ringing = CallNotificationState.content("123", { null }, null, "", null, fa)
        assertEquals("تماس ورودی", CallNotificationState.statusText(ringing, fa))
        assertEquals("در حال پاسخ دادن…", CallNotificationState.statusText(CallNotificationState.content("1", { null }, CallNotificationAction.ANSWER, "", null, fa), fa))
        assertEquals("شماره‌ی ناشناس", CallNotificationState.caller(null, fa) { null })
        assertEquals("123", CallNotificationState.caller("123", fa) { null })      // a number is data: never translated
        assertEquals("Ali", CallNotificationState.caller("123", fa) { "Ali" })      // a contact name is data too
    }

    // ---------------------------------------------------------------- Contacts and Call History

    @Test fun contactsNoticesStayEnglishByDefaultAndTranslateInPersian() {
        assertEquals(ContactsState.NOTICE_CAN_ASK, ContactsState.notice(ContactsAccess.CAN_ASK))
        assertEquals(ContactsState.NO_CONTACTS_APP, ContactsState.noContactsApp(en))
        assertEquals(ContactsState.NOT_USABLE, ContactsState.notUsable(en))
        assertNotEquals(ContactsState.NO_CONTACTS_APP, ContactsState.noContactsApp(fa))
        assertNotEquals(ContactsState.NOT_USABLE, ContactsState.notUsable(fa))
        assertTrue(persianLetters.containsMatchIn(ContactsState.notice(ContactsAccess.SETTINGS_ONLY, fa)))
    }

    @Test fun callHistoryMenuHasNoEditingActionsAndEveryLabelIsTranslated() {
        // Read-only: the Calls menu offers Call / Send message / Delete exactly as before (Delete is the existing local-history delete).
        assertEquals(listOf(HistoryAction.CALL, HistoryAction.SMS, HistoryAction.DELETE), HistoryState.menu(HistoryTab.CALLS))
        HistoryAction.values().filter { it != HistoryAction.SMS }.forEach {
            assertTrue(persianLetters.containsMatchIn(HistoryState.callsActionLabel(it, fa)))
        }
    }

    // ---------------------------------------------------------------- what must NOT have changed

    @Test fun callingAndAudioStayOff() {
        assertFalse(CallFeatures.isCallingEnabled())
        assertFalse(CallFeatures.isHistoryEditingEnabled())
        assertFalse(AudioTransferFeatures.isTransferEnabled())
        assertEquals("این بخش در حال توسعه است", CallFeatures.DISABLED_MESSAGE)   // the constant itself is unchanged
        assertEquals("This section is under development", Strings.text(StringKey.HOME_CALLING_DISABLED, en))   // the toast now uses this key
    }

    // ---------------------------------------------------------------- launcher name (resources)

    private fun res(path: String) = File("src/main/res/$path").takeIf { it.exists() } ?: File("app/src/main/res/$path")

    @Test fun launcherNameResources() {
        assertTrue(res("values/strings.xml").readText().contains(">AntiRegister<"))
        assertTrue(res("values-fa/strings.xml").readText().contains(">آنتی رجیستر<"))
        val manifest = (File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")).readText()
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertEquals("آنتی رجیستر", Strings.text(StringKey.APP_NAME, fa))
    }
}
