package com.example.companion.contacts

import com.example.companion.data.ReadDiagnostics
import com.example.companion.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stage 28: Contacts error handling, pure parts. Plain JVM. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class ContactsErrorHandlingTest {

    private var clock = 0L
    private val lines = ArrayList<String>()

    @Before fun setUp() { ReadDiagnostics.clear(); lines.clear(); ReadDiagnostics.sink = { lines.add(it) }; ReadDiagnostics.clock = { 42L } }
    @After fun tearDown() { ReadDiagnostics.sink = { }; ReadDiagnostics.clock = { System.currentTimeMillis() }; ReadDiagnostics.clear() }

    // ---------------------------------------------------------------- cache: a provider failure is not a real "no"

    @Test fun failureIsRememberedBrieflyAsNoContact() {
        val c = ContactCache(ttlMs = 300_000, now = { clock }, failureTtlMs = 30_000)
        c.putFailure("+15551234")
        assertNotNull(c.get("+15551234")); assertNull(c.get("+15551234")!!.match)     // no query storm on redraw
        clock = 29_999; assertNotNull(c.get("+15551234"))
        clock = 30_001; assertNull(c.get("+15551234"))                                // asked again soon: a recovered provider is used
    }

    @Test fun realAnswersKeepTheLongTimeLimit() {
        val c = ContactCache(ttlMs = 300_000, now = { clock }, failureTtlMs = 30_000)
        c.put("a", ContactMatch("Ann", null)); c.put("b", null)
        clock = 200_000
        assertEquals("Ann", c.get("a")!!.match!!.name); assertNotNull(c.get("b")); assertNull(c.get("b")!!.match)
        clock = 300_001
        assertNull(c.get("a")); assertNull(c.get("b"))
    }

    @Test fun clearDropsFailuresToo() {
        val c = ContactCache(now = { clock }); c.putFailure("x"); c.clear(); assertNull(c.get("x"))
    }

    // ---------------------------------------------------------------- invalid records

    @Test fun cleanNameKeepsNormalNames() {
        assertEquals("Ann Lee", ContactsState.cleanName("Ann Lee"))
        assertEquals("علی رضایی", ContactsState.cleanName("  علی   رضایی "))
    }

    @Test fun cleanNameRemovesControlCharactersAndCollapsesSpace() {
        assertEquals("Ann Lee", ContactsState.cleanName("Ann\u0000\n\tLee"))
        assertEquals("Ann Lee", ContactsState.cleanName("Ann\u00A0\u00A0Lee"))
    }

    @Test fun cleanNameOfNothingReadableIsNull() {
        assertNull(ContactsState.cleanName(null)); assertNull(ContactsState.cleanName("")); assertNull(ContactsState.cleanName("   "))
        assertNull(ContactsState.cleanName("\u0000\u0001\n"))
    }

    @Test fun cleanNameCutsAnAbsurdlyLongName() {
        val n = ContactsState.cleanName("x".repeat(5000))!!
        assertEquals(ContactsState.MAX_NAME_LENGTH, n.length)
    }

    @Test fun cleanPhotoUriAcceptsOnlyContentAddresses() {
        assertEquals("content://com.android.contacts/contacts/1/photo", ContactsState.cleanPhotoUri(" content://com.android.contacts/contacts/1/photo "))
        assertNull(ContactsState.cleanPhotoUri(null)); assertNull(ContactsState.cleanPhotoUri("")); assertNull(ContactsState.cleanPhotoUri("   "))
        assertNull(ContactsState.cleanPhotoUri("file:///sdcard/x.jpg")); assertNull(ContactsState.cleanPhotoUri("http://example.com/a.jpg"))
        assertNull(ContactsState.cleanPhotoUri("content://" + "a".repeat(3000)))
    }

    // ---------------------------------------------------------------- messages

    @Test fun newMessagesExistInBothLanguages() {
        val en = AppLanguage.ENGLISH; val fa = AppLanguage.PERSIAN
        listOf<(AppLanguage) -> String>(
            { ContactsState.unavailableNotice(it) }, { ContactsState.pickUnreadable(it) }, { ContactsState.pickNoNumber(it) }
        ).forEach { f ->
            assertTrue(f(en).isNotEmpty()); assertTrue(f(fa).isNotEmpty()); assertNotEquals(f(en), f(fa))
            assertTrue(Regex("[\\u0600-\\u06FF]").containsMatchIn(f(fa)))
        }
    }

    @Test fun existingContactTextsAreUnchanged() {
        assertEquals(ContactsState.NOTICE_CAN_ASK, ContactsState.notice(ContactsAccess.CAN_ASK))
        assertEquals(ContactsState.NOTICE_SETTINGS_ONLY, ContactsState.notice(ContactsAccess.SETTINGS_ONLY))
        assertEquals(ContactsAccess.SETTINGS_ONLY, ContactsState.access(granted = false, askedBefore = true, showRationale = false))
        assertEquals(ContactsAccess.GRANTED, ContactsState.access(granted = true, askedBefore = false, showRationale = false))
    }

    // ---------------------------------------------------------------- developer log: technical detail kept, nothing private, never a crash

    @Test fun diagnosticsKeepClassAndFramesButNotTheMessage() {
        val secret = "+15551234567 Ann Lee"
        ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_LOOKUP, "query contacts provider", IllegalStateException(secret))
        val e = ReadDiagnostics.recent().single()
        assertEquals("java.lang.IllegalStateException", e.exception)
        assertTrue(e.frames.isNotEmpty() && e.frames.size <= 4)
        assertFalse(ReadDiagnostics.format(e).contains("5551234")); assertFalse(ReadDiagnostics.format(e).contains("Ann"))
        assertEquals(1, lines.size)
    }

    @Test fun diagnosticsAreBoundedAndNeverThrow() {
        repeat(ReadDiagnostics.MAX_ENTRIES + 20) { ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_ROW, "row $it") }
        assertEquals(ReadDiagnostics.MAX_ENTRIES, ReadDiagnostics.recent().size)
        ReadDiagnostics.sink = { throw IllegalStateException("broken sink") }
        ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_UI, "still fine")        // must not throw
    }
}
