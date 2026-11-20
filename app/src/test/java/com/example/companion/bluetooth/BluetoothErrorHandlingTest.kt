package com.example.companion.bluetooth

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.ConnectionErrorText
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.sms.SmsErrorText
import com.example.companion.ui.SmsThreadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 27: JVM tests of the Bluetooth error-handling pieces that are pure Kotlin: the developer diagnostics, the link-issue classifier,
 * the flap limit, and the new Persian / English texts. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). ConnectionManager,
 * MessageTransport, BluetoothManager and the screens need Android and are not covered here (see STAGE27_NOTES.md).
 */
class BluetoothErrorHandlingTest {

    private val en = AppLanguage.ENGLISH
    private val fa = AppLanguage.PERSIAN
    private val lines = ArrayList<String>()
    private val oldSink = BluetoothDiagnostics.sink
    private val oldClock = BluetoothDiagnostics.clock

    @Before fun setUp() { BluetoothDiagnostics.clear(); lines.clear(); BluetoothDiagnostics.sink = { lines.add(it) } }
    @After fun tearDown() { BluetoothDiagnostics.sink = oldSink; BluetoothDiagnostics.clock = oldClock; BluetoothDiagnostics.clear() }

    private fun hasLatin(s: String) = Regex("[A-Za-z]").containsMatchIn(s.replace(Regex("%\\d+\\$[sd]"), ""))

    // ------------------------------------------------------------ diagnostics

    @Test fun recordKeepsAreaPhraseClassMessageAndFrames() {
        BluetoothDiagnostics.record(BluetoothDiagnostics.Area.CONNECT, "SIM Phone unavailable", java.io.IOException("read failed, socket might closed"), "state=CONNECTING attempt=1")
        val e = BluetoothDiagnostics.recent().single()
        assertEquals(BluetoothDiagnostics.Area.CONNECT, e.area)
        assertEquals("java.io.IOException", e.exception)
        assertEquals("read failed, socket might closed", e.exceptionMessage)
        assertTrue(e.frames.isNotEmpty() && e.frames.size <= 4)
        assertEquals("state=CONNECTING attempt=1", e.detail)
        val line = lines.single()
        assertTrue(line.startsWith("CONNECT: SIM Phone unavailable [state=CONNECTING attempt=1] | java.io.IOException: read failed"))
    }

    @Test fun recordWithoutAnExceptionIsStillKept() {
        BluetoothDiagnostics.record(BluetoothDiagnostics.Area.RETRY, "giving up: link keeps dropping")
        val e = BluetoothDiagnostics.recent().single()
        assertNull(e.exception); assertNull(e.exceptionMessage); assertTrue(e.frames.isEmpty())
    }

    @Test fun bluetoothAddressesAreMaskedEverywhere() {
        BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LINK, "x", java.io.IOException("Connection to AA:BB:CC:DD:EE:FF refused, also 01:23:45:67:89:ab"))
        val e = BluetoothDiagnostics.recent().single()
        assertFalse(e.exceptionMessage!!.contains("AA:BB", ignoreCase = true))
        assertFalse(e.exceptionMessage!!.contains("01:23", ignoreCase = true))
        assertTrue(e.exceptionMessage!!.contains("xx:xx:xx:xx:xx:xx"))
        assertFalse(lines.single().contains("AA:BB", ignoreCase = true))
        assertEquals("a xx:xx:xx:xx:xx:xx b", BluetoothDiagnostics.clean("a AA:BB:CC:DD:EE:FF b"))
    }

    @Test fun messagesAreOneLineAndShort() {
        val c = BluetoothDiagnostics.clean("line one\nline two\r\n" + "x".repeat(500))
        assertFalse(c.contains('\n')); assertFalse(c.contains('\r'))
        assertTrue(c.length <= 120)
    }

    @Test fun keepsOnlyTheNewestEntries() {
        repeat(BluetoothDiagnostics.MAX_ENTRIES + 10) { BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LINK, "p$it") }
        val all = BluetoothDiagnostics.recent()
        assertEquals(BluetoothDiagnostics.MAX_ENTRIES, all.size)
        assertEquals("p${BluetoothDiagnostics.MAX_ENTRIES + 9}", all.last().what)
        assertEquals("p10", all.first().what)
    }

    @Test fun recordNeverThrowsEvenIfTheSinkOrTheClockDoes() {
        BluetoothDiagnostics.sink = { throw IllegalStateException("sink broken") }
        BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LINK, "a")           // must not throw
        BluetoothDiagnostics.clock = { throw IllegalStateException("clock broken") }
        BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LINK, "b")           // must not throw
    }

    @Test fun recordUsesTheInjectedClock() {
        BluetoothDiagnostics.clock = { 12345L }
        BluetoothDiagnostics.record(BluetoothDiagnostics.Area.ADAPTER, "off")
        assertEquals(12345L, BluetoothDiagnostics.recent().single().time)
    }

    // ------------------------------------------------------------ link issue

    @Test fun linkIssueIsNullWhenTheLinkIsUp() {
        assertNull(LinkIssues.of(linkUp = true, supported = false, permissionGranted = false, enabled = false, reconnecting = true))
    }

    @Test fun linkIssueOrderIsUnsupportedThenPermissionThenOffThenReconnecting() {
        assertEquals(LinkIssue.UNSUPPORTED, LinkIssues.of(false, supported = false, permissionGranted = false, enabled = false, reconnecting = true))
        assertEquals(LinkIssue.PERMISSION_MISSING, LinkIssues.of(false, supported = true, permissionGranted = false, enabled = false, reconnecting = true))
        assertEquals(LinkIssue.BLUETOOTH_OFF, LinkIssues.of(false, supported = true, permissionGranted = true, enabled = false, reconnecting = true))
        assertEquals(LinkIssue.RECONNECTING, LinkIssues.of(false, supported = true, permissionGranted = true, enabled = true, reconnecting = true))
    }

    @Test fun linkIssueIsNullWithoutAKnownCause() {
        assertNull(LinkIssues.of(false, supported = true, permissionGranted = true, enabled = true, reconnecting = false))
    }

    // ------------------------------------------------------------ flap limit

    @Test fun flapLimitIsReachedOnlyAfterTenQuickDropsInARow() {
        var drops = 0
        repeat(9) { drops = ReconnectPolicy.nextQuickDrops(drops, 1_000) }
        assertFalse(ReconnectPolicy.flapLimitReached(drops))
        drops = ReconnectPolicy.nextQuickDrops(drops, 1_000)
        assertTrue(ReconnectPolicy.flapLimitReached(drops))
        assertTrue(ReconnectPolicy.flapLimitReached(ReconnectPolicy.nextQuickDrops(drops, 1_000)))     // saturated count stays over the limit
    }

    @Test fun aStableLinkResetsTheFlapCount() {
        var drops = 9
        drops = ReconnectPolicy.nextQuickDrops(drops, ReconnectPolicy.STABLE_LINK_MS)
        assertEquals(0, drops)
        assertFalse(ReconnectPolicy.flapLimitReached(drops))
    }

    @Test fun existingRetryRulesAreUnchanged() {
        assertEquals(3_000L, ReconnectPolicy.delayMs(1, isHost = false))
        assertEquals(30_000L, ReconnectPolicy.delayMs(50, isHost = false))
        assertTrue(ReconnectPolicy.canRetry(ReconnectPolicy.FAST_ATTEMPTS - 1, linkWasUp = false, sinceLossMs = 0))
        assertFalse(ReconnectPolicy.canRetry(ReconnectPolicy.FAST_ATTEMPTS, linkWasUp = false, sinceLossMs = 0))
        assertFalse(ReconnectPolicy.canRetry(0, linkWasUp = true, sinceLossMs = ReconnectPolicy.LOST_LINK_WINDOW_MS))
    }

    // ------------------------------------------------------------ connection error texts

    @Test fun connectionMessagesEqualTheCatalogEnglishCharacterForCharacter() {
        assertEquals(StringKey.ERR_BT_PERMISSION.english, ConnectionMessages.PERMISSION_DENIED)
        assertEquals(StringKey.ERR_BT_OFF.english, ConnectionMessages.BT_OFF)
        assertEquals(StringKey.ERR_BT_UNSUPPORTED.english, ConnectionMessages.BT_UNSUPPORTED)
        assertEquals(StringKey.ERR_LINK_UNSTABLE.english, ConnectionMessages.LINK_UNSTABLE)
    }

    @Test fun everyConnectionMessageIsTranslatedForDisplay() {
        listOf(ConnectionMessages.PERMISSION_DENIED, ConnectionMessages.BT_OFF, ConnectionMessages.BT_UNSUPPORTED, ConnectionMessages.LINK_UNSTABLE).forEach {
            assertEquals(it, ConnectionErrorText.localize(it, en))                       // English: unchanged
            val text = ConnectionErrorText.localize(it, fa)
            assertNotEquals(it, text)                                                    // Persian: mapped
            assertFalse(text, hasLatin(text))
        }
    }

    @Test fun theNewConnectionTextIsInPersianAndNamesTheConnectButtonAsTheAppDoes() {
        assertTrue(Strings.isTranslated(StringKey.ERR_LINK_UNSTABLE, fa))
        assertTrue(Strings.text(StringKey.ERR_LINK_UNSTABLE, fa).contains(Strings.text(StringKey.SETTINGS_CONNECT, fa)))
        assertTrue(StringKey.ERR_LINK_UNSTABLE.english.contains(StringKey.SETTINGS_CONNECT.english))
    }

    @Test fun anUnknownConnectionTextIsStillNeverLost() {
        assertEquals("Something new", ConnectionErrorText.localize("Something new", fa))
        assertEquals("Could not open Bluetooth socket: boom", ConnectionErrorText.localize("Could not open Bluetooth socket: boom", en))
    }

    // ------------------------------------------------------------ SMS: Bluetooth reasons

    @Test fun linkDownReasonNamesTheCause() {
        assertEquals(SmsErrorText.REFUSE_BT_UNSUPPORTED, SmsErrorText.linkDownReason(LinkIssue.UNSUPPORTED))
        assertEquals(SmsErrorText.REFUSE_BT_PERMISSION, SmsErrorText.linkDownReason(LinkIssue.PERMISSION_MISSING))
        assertEquals(SmsErrorText.REFUSE_BT_OFF, SmsErrorText.linkDownReason(LinkIssue.BLUETOOTH_OFF))
        assertEquals(SmsErrorText.REFUSE_RECONNECTING, SmsErrorText.linkDownReason(LinkIssue.RECONNECTING))
    }

    @Test fun linkDownReasonWithoutACauseIsTheOriginalSentence() {
        assertEquals("SIM Phone unavailable (Bluetooth is not connected). Nothing was sent.", SmsErrorText.linkDownReason(null))
        assertEquals(SmsErrorText.REFUSE_LINK_DOWN, SmsErrorText.linkDownReason(null))
    }

    @Test fun everyLinkDownReasonSaysNothingWasSent() {
        (LinkIssue.values().toList() + listOf<LinkIssue?>(null)).forEach {
            assertTrue(SmsErrorText.linkDownReason(it).endsWith("Nothing was sent."))
        }
    }

    @Test fun everyLinkDownReasonIsTranslatedAndEnglishIsUnchanged() {
        (LinkIssue.values().toList() + listOf<LinkIssue?>(null)).forEach { issue ->
            val reason = SmsErrorText.linkDownReason(issue)
            assertEquals(reason, SmsErrorText.refusal(reason, en))
            val persian = SmsErrorText.refusal(reason, fa)
            assertNotEquals(reason, persian)
            assertFalse(persian, hasLatin(persian))
        }
    }

    @Test fun smsBluetoothRefusalEnglishMatchesTheCatalog() {
        assertEquals(StringKey.SMS_REFUSE_BT_OFF.english, SmsErrorText.REFUSE_BT_OFF)
        assertEquals(StringKey.SMS_REFUSE_BT_PERMISSION.english, SmsErrorText.REFUSE_BT_PERMISSION)
        assertEquals(StringKey.SMS_REFUSE_BT_UNSUPPORTED.english, SmsErrorText.REFUSE_BT_UNSUPPORTED)
        assertEquals(StringKey.SMS_REFUSE_RECONNECTING.english, SmsErrorText.REFUSE_RECONNECTING)
        assertEquals(StringKey.SMS_REFUSE_LINK_DOWN.english, SmsErrorText.REFUSE_LINK_DOWN)
    }

    // ------------------------------------------------------------ SMS composer

    private fun composer(up: Boolean, issue: LinkIssue?, language: AppLanguage = en) =
        SmsThreadState.composer(true, up, "+15551234", "", "hello", language, issue)

    @Test fun composerNoticeNamesTheBluetoothCauseAndSendStaysDisabled() {
        LinkIssue.values().forEach {
            val c = composer(false, it)
            assertFalse(c.sendEnabled)
            assertEquals(Strings.text(SmsThreadState.sendBlockedKey(it), en), c.unavailableText)
        }
        assertTrue(composer(false, LinkIssue.BLUETOOTH_OFF).unavailableText.contains("Bluetooth is turned off"))
        assertTrue(composer(false, LinkIssue.PERMISSION_MISSING).unavailableText.contains("permission"))
    }

    @Test fun composerWithoutACauseKeepsTheOriginalNotice() {
        val c = composer(false, null)
        assertFalse(c.sendEnabled)
        assertEquals(Strings.text(StringKey.SMS_SEND_BLOCKED, en), c.unavailableText)
        assertEquals(SmsThreadState.SEND_BLOCKED_TEXT, c.unavailableText)
    }

    @Test fun aLinkThatIsUpIgnoresAnyIssueAndIsUnchanged() {
        val c = composer(true, LinkIssue.BLUETOOTH_OFF)
        assertTrue(c.sendEnabled)
        assertEquals("", c.unavailableText)
    }

    @Test fun composerNoticesAreTranslated() {
        LinkIssue.values().forEach {
            val text = composer(false, it, fa).unavailableText
            assertNotEquals(composer(false, it, en).unavailableText, text)
            assertFalse(text, hasLatin(text))
        }
    }

    @Test fun everyNewKeyHasPersianTextWithMatchingPlaceholders() {
        val keys = listOf(
            StringKey.SMS_SEND_BLOCKED_BT_OFF, StringKey.SMS_SEND_BLOCKED_BT_PERMISSION, StringKey.SMS_SEND_BLOCKED_BT_UNSUPPORTED,
            StringKey.SMS_SEND_BLOCKED_RECONNECTING, StringKey.SMS_REFUSE_BT_OFF, StringKey.SMS_REFUSE_BT_PERMISSION,
            StringKey.SMS_REFUSE_BT_UNSUPPORTED, StringKey.SMS_REFUSE_RECONNECTING, StringKey.ERR_LINK_UNSTABLE
        )
        val re = Regex("%\\d+\\$[sd]")
        keys.forEach {
            assertTrue(it.name, Strings.isTranslated(it, fa))
            assertFalse(it.name, hasLatin(Strings.text(it, fa)))
            assertEquals(it.name, re.findAll(it.english).map { m -> m.value }.toSet(), re.findAll(Strings.text(it, fa)).map { m -> m.value }.toSet())
        }
    }
}
