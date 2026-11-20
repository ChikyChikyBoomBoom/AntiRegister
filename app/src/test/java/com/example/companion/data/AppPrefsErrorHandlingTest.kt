package com.example.companion.data

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 29: AppPrefs over a fake SharedPreferences that can hold damaged values and fail to write. Plain JVM.
 * WRITTEN BUT NOT RUN (no Gradle / Android SDK was available).
 */
class AppPrefsErrorHandlingTest {

    /** In-memory SharedPreferences. Like Android's it throws ClassCastException when a key holds another type. */
    private class FakePrefs : SharedPreferences {
        val data = HashMap<String, Any?>()
        var failCommit = false          // commit() returns false and stores nothing
        var throwOnEdit = false         // edit() throws
        var commits = 0
        var applies = 0

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = if (data.containsKey(key!!)) data[key!!] as String? else defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = if (data.containsKey(key!!)) data[key!!] as Int else defValue
        override fun getLong(key: String?, defValue: Long): Long = if (data.containsKey(key!!)) data[key!!] as Long else defValue
        override fun getFloat(key: String?, defValue: Float): Float = if (data.containsKey(key!!)) data[key!!] as Float else defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = if (data.containsKey(key!!)) data[key!!] as Boolean else defValue
        override fun contains(key: String?): Boolean = data.containsKey(key!!)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor {
            if (throwOnEdit) throw IllegalStateException("edit failed")
            return object : SharedPreferences.Editor {
                private val pending = LinkedHashMap<String, Any?>()
                private val removed = HashSet<String>()
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
                override fun remove(key: String?): SharedPreferences.Editor { removed.add(key!!); pending.remove(key); return this }
                override fun clear(): SharedPreferences.Editor { data.clear(); return this }
                override fun commit(): Boolean { commits++; return if (failCommit) false else { flush(); true } }
                override fun apply() { applies++; flush() }
                private fun flush() { removed.forEach { data.remove(it) }; data.putAll(pending) }
            }
        }
    }

    private lateinit var fake: FakePrefs
    private lateinit var prefs: AppPrefs
    private val log = ArrayList<String>()

    @Before fun setUp() {
        fake = FakePrefs(); prefs = AppPrefs.forTest(fake)
        log.clear(); SettingsDiagnostics.clear(); SettingsDiagnostics.sink = { log.add(it) }
    }
    @After fun tearDown() { SettingsDiagnostics.sink = { }; SettingsDiagnostics.clear() }

    // ---------------------------------------------------------------- defaults

    @Test fun emptyFileGivesTheDocumentedDefaults() {
        assertNull(prefs.role); assertNull(prefs.deviceAddress); assertNull(prefs.deviceName); assertNull(prefs.languageCode)
        assertTrue(prefs.autoReconnect)
        assertFalse(prefs.userStopped); assertFalse(prefs.setupCompleted); assertFalse(prefs.notificationPermissionAsked)
        assertFalse(prefs.contactsPermissionAsked); assertFalse(prefs.callLogPermissionAsked); assertFalse(prefs.isSetupAsked("bluetooth"))
        assertEquals(0L, prefs.lastConnectedAt)
        assertFalse(prefs.storageProblem)
    }

    @Test fun roundTripOfEveryValueStillWorks() {
        prefs.role = Role.MAIN_PHONE
        prefs.selectDevice("AA:BB:CC:DD:EE:FF", "Pixel 7")
        prefs.autoReconnect = false; prefs.userStopped = true; prefs.lastConnectedAt = 123L; prefs.setupCompleted = true
        prefs.languageCode = "fa"; prefs.markSetupAsked("sms")
        assertEquals(Role.MAIN_PHONE, prefs.role)
        assertEquals("AA:BB:CC:DD:EE:FF", prefs.deviceAddress); assertEquals("Pixel 7", prefs.deviceName)
        assertFalse(prefs.autoReconnect); assertTrue(prefs.userStopped); assertEquals(123L, prefs.lastConnectedAt)
        assertTrue(prefs.setupCompleted); assertEquals("fa", prefs.languageCode); assertTrue(prefs.isSetupAsked("sms"))
        assertFalse(prefs.storageProblem)
    }

    // ---------------------------------------------------------------- damaged values

    @Test fun unknownRoleReadsAsNoRoleAndIsRecordedOnce() {
        fake.data["role"] = "ADMIN"
        repeat(5) { assertNull(prefs.role) }
        assertEquals(1, SettingsDiagnostics.recent().count { it.area == SettingsDiagnostics.Area.PREFS_READ })
    }

    @Test fun diagnosticsNeverContainTheStoredValue() {
        fake.data["device_address"] = "secret-not-a-mac"
        assertNull(prefs.deviceAddress)
        assertTrue(log.isNotEmpty())
        assertTrue(log.none { it.contains("secret-not-a-mac") })
    }

    @Test fun wrongTypesDoNotThrowAndGiveDefaults() {
        fake.data["role"] = 7                         // Int where a String is expected
        fake.data["auto_reconnect"] = "yes"           // String where a Boolean is expected
        fake.data["setup_completed"] = 1L
        fake.data["last_connected_at"] = "never"
        fake.data["language_code"] = true
        assertNull(prefs.role)
        assertTrue(prefs.autoReconnect)               // default of auto-reconnect is ON
        assertFalse(prefs.setupCompleted)
        assertEquals(0L, prefs.lastConnectedAt)
        assertNull(prefs.languageCode)
    }

    @Test fun badDeviceAddressMeansNoDeviceSelected() {
        fake.data["device_address"] = "not an address"; fake.data["device_name"] = "Pixel 7"
        assertNull(prefs.deviceAddress)
    }

    @Test fun negativeLastConnectedTimeMeansNever() {
        fake.data["last_connected_at"] = -5L
        assertEquals(0L, prefs.lastConnectedAt)
    }

    // ---------------------------------------------------------------- failed writes

    @Test fun failedCommitIsReportedAndTheValueStillAppliesForThisRun() {
        fake.failCommit = true
        assertFalse(prefs.saveAutoReconnect(false))
        assertFalse(prefs.autoReconnect)              // the screen and the Bluetooth code agree on what the person chose
        assertTrue(prefs.storageProblem)
        assertTrue(fake.data.isEmpty())               // and nothing reached the file
    }

    @Test fun laterSuccessfulSaveClearsTheProblem() {
        fake.failCommit = true
        prefs.saveAutoReconnect(false)
        fake.failCommit = false
        assertTrue(prefs.saveAutoReconnect(true))
        assertTrue(prefs.autoReconnect)
        assertFalse(prefs.storageProblem)
        assertEquals(true, fake.data["auto_reconnect"])
    }

    @Test fun editorThatThrowsIsCaughtAndReportedAsNotSaved() {
        fake.throwOnEdit = true
        assertFalse(prefs.saveLanguage("fa"))
        assertEquals("fa", prefs.languageCode)
        prefs.role = Role.SIM_PHONE                   // the fire-and-forget setter must not throw either
        assertEquals(Role.SIM_PHONE, prefs.role)
        assertTrue(SettingsDiagnostics.recent().any { it.area == SettingsDiagnostics.Area.PREFS_WRITE })
    }

    @Test fun roleChangeIsOneCommitAndNeverHalfDone() {
        prefs.role = Role.SIM_PHONE; prefs.setupCompleted = true; prefs.userStopped = true
        fake.failCommit = true
        assertFalse(prefs.saveRoleChange(Role.MAIN_PHONE))
        // failed: nothing reached the file, so the OLD role keeps its setup state in the file
        assertEquals("SIM_PHONE", fake.data["role"]); assertEquals(true, fake.data["setup_completed"])
        fake.failCommit = false
        val before = fake.commits
        assertTrue(prefs.saveRoleChange(Role.MAIN_PHONE))
        assertEquals(1, fake.commits - before)
        assertEquals(Role.MAIN_PHONE, prefs.role); assertFalse(prefs.setupCompleted); assertFalse(prefs.userStopped)
    }

    @Test fun clearDeviceDurablyIsOneCommitAndKeepsTheDeviceWhenItFails() {
        prefs.selectDevice("AA:BB:CC:DD:EE:FF", "Pixel 7"); prefs.lastConnectedAt = 99L
        fake.failCommit = true
        assertFalse(prefs.clearDeviceDurably())
        assertEquals("AA:BB:CC:DD:EE:FF", fake.data["device_address"])
        fake.failCommit = false
        assertTrue(prefs.clearDeviceDurably())
        assertNull(prefs.deviceAddress); assertNull(prefs.deviceName); assertEquals(0L, prefs.lastConnectedAt)
    }

    @Test fun setupCompletedSaveReportsFailure() {
        fake.failCommit = true
        assertFalse(prefs.saveSetupCompleted(true))
        assertTrue(prefs.setupCompleted)              // not trapped on Setup for the rest of this run
    }

    // ---------------------------------------------------------------- the file cannot be opened at all

    @Test fun unavailableStorageRunsOnDefaultsAndInMemoryChoices() {
        val p = AppPrefs.forTest(null)
        assertTrue(p.storageProblem)
        assertNull(p.role); assertTrue(p.autoReconnect); assertFalse(p.setupCompleted)
        assertFalse(p.saveRoleChange(Role.SIM_PHONE))      // reported as not saved...
        assertEquals(Role.SIM_PHONE, p.role)               // ...but the choice works for this run
        assertFalse(p.setupCompleted)
        p.selectDevice("AA:BB:CC:DD:EE:FF", "Pixel 7")
        assertEquals("AA:BB:CC:DD:EE:FF", p.deviceAddress)
        p.clearDevice()
        assertNull(p.deviceAddress); assertNull(p.deviceName)
    }

    @Test fun removedKeyStaysRemovedInMemoryWhenSavingFails() {
        prefs.selectDevice("AA:BB:CC:DD:EE:FF", "Pixel 7")
        fake.failCommit = true
        assertFalse(prefs.clearDeviceDurably())
        assertNull(prefs.deviceAddress)                    // the session overlay says "cleared"
    }
}
