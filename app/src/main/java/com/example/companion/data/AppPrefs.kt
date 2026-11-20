package com.example.companion.data

import android.content.Context
import android.content.SharedPreferences
import com.example.companion.data.SettingsDiagnostics.Area
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's saved settings.
 *
 * Stage 29 (error handling, no change to what is stored or to any key name):
 * - Every READ is safe. A value of the wrong type, a damaged or unknown value, or an unreadable file gives the safe default (the same as a
 *   phone that never saved it): no role -> the welcome screen, no device -> pick one, auto-reconnect on, everything else off / 0.
 *   [StoredValues] decides what is usable. Each damaged key is recorded once in [SettingsDiagnostics] (never shown to the user).
 * - Every WRITE is safe. The property setters keep the original fire-and-forget `apply()` (they are used from the Bluetooth code, which must
 *   not block on disk), but can no longer throw. The `save...` functions are for the Settings and first-run screens: they write with
 *   `commit()`, so the caller learns whether the value reached the file, and changes that belong together are written in ONE commit.
 * - A value that could not be written is kept in memory for this run of the app (the screen the person just used then shows what they
 *   chose), and [storageProblem] tells the screens to say that settings cannot be saved right now. If the preferences file cannot be opened
 *   at all, the app runs on defaults plus the in-memory values until it is closed.
 */
class AppPrefs private constructor(private val sp: SharedPreferences?) {

    constructor(context: Context) : this(openOrNull(context))

    /** Values that could not be written (or all values, when [sp] is null), newest wins. [REMOVED] marks a key that was meant to be deleted. */
    private val memory = ConcurrentHashMap<String, Any>()
    /** Keys whose damaged stored value was already recorded, so a screen that redraws often does not flood the log. */
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** True while some value could not be saved (or the file cannot be used): choices only last until the app is closed. */
    val storageProblem: Boolean get() = sp == null || memory.isNotEmpty()

    var role: Role?
        get() {
            val raw = readString("role")
            val value = StoredValues.role(raw)
            if (raw != null && value == null) reportOnce("role", "unknown stored value")
            return value
        }
        set(v) { write(false, "role" to v?.name) }

    var deviceAddress: String?
        get() {
            val raw = readString("device_address")
            val value = StoredValues.deviceAddress(raw)
            if (raw != null && value == null) reportOnce("device_address", "unusable stored value")
            return value
        }
        private set(v) { write(false, "device_address" to v) }

    var deviceName: String?
        get() = StoredValues.deviceName(readString("device_name"))
        private set(v) { write(false, "device_name" to v) }

    var autoReconnect: Boolean
        get() = readBoolean("auto_reconnect", true)
        set(v) { write(false, "auto_reconnect" to v) }

    /** Persisted so a user's explicit Disconnect survives an app restart. */
    var userStopped: Boolean
        get() = readBoolean("user_stopped", false)
        set(v) { write(false, "user_stopped" to v) }

    /** Epoch millis of the last successful connection, 0 = never. */
    var lastConnectedAt: Long
        get() = StoredValues.timestamp(readLong("last_connected_at", 0L))
        set(v) { write(false, "last_connected_at" to v) }

    /** Stage 15 (Main Phone): the notification permission dialog was already shown once, so it is not shown again on its own. */
    var notificationPermissionAsked: Boolean
        get() = readBoolean("notification_permission_asked", false)
        set(v) { write(false, "notification_permission_asked" to v) }

    /** Stage 19 (Main Phone): the optional Contacts permission dialog was already shown once (used to tell "can ask" from "settings only"). */
    var contactsPermissionAsked: Boolean
        get() = readBoolean("contacts_permission_asked", false)
        set(v) { write(false, "contacts_permission_asked" to v) }

    /**
     * Stage 28 (SIM Phone): the READ_CALL_LOG permission dialog was already shown once. Remembered across restarts so that, after a permanent
     * denial or a revocation in system settings, the call-history section offers "Open app settings" straight away instead of a button that
     * silently does nothing. Read-only call history only; unrelated to any call-control permission.
     */
    var callLogPermissionAsked: Boolean
        get() = readBoolean("call_log_permission_asked", false)
        set(v) { write(false, "call_log_permission_asked" to v) }

    /**
     * Stage 22: the first-run setup was finished (or skipped) for the CURRENT role. Switching role clears it, so the new role's
     * permissions are explained once. Reset by [com.example.companion.ui.SettingsActions.changeRole].
     * Stage 29: a damaged or missing value reads as false, which only shows the Setup screen again (it closes by itself when nothing is missing).
     */
    var setupCompleted: Boolean
        get() = readBoolean("setup_completed", false)
        set(v) { write(false, "setup_completed" to v) }

    /**
     * Stage 22: this permission group's system dialog was already shown once (so it is never shown on its own again).
     * [key] is a SetupItem key. Notifications and Contacts keep using their older flags above.
     */
    fun isSetupAsked(key: String): Boolean = readBoolean("setup_asked_$key", false)
    fun markSetupAsked(key: String) { write(false, "setup_asked_$key" to true) }

    /**
     * Stage 12: the selected UI language code ("en" / "fa"). null = nothing chosen yet = English. Read through
     * [com.example.companion.i18n.AppLanguage.fromCode]; an unknown value also means English.
     */
    var languageCode: String?
        get() = readString("language_code")
        set(v) { write(false, "language_code" to v) }

    fun selectDevice(address: String, name: String?) { write(false, "device_address" to address, "device_name" to name) }

    /** Stage 18: forget the selected device (and the "last connected" time that belonged to it). Does not unpair it in Android. */
    fun clearDevice() {
        write(false, "device_address" to null, "device_name" to null, "last_connected_at" to null)
    }

    // ---------------------------------------------------------------- Stage 29: saves whose result the caller needs

    /** The Settings switch. False = not written to the file (the new value still applies until the app is closed). */
    fun saveAutoReconnect(v: Boolean): Boolean = write(true, "auto_reconnect" to v)

    /** The language chooser. False = not written to the file (the new language still applies until the app is closed). */
    fun saveLanguage(code: String?): Boolean = write(true, "language_code" to code)

    /** "Setup finished" / "show Setup again". False = not written to the file. */
    fun saveSetupCompleted(v: Boolean): Boolean = write(true, "setup_completed" to v)

    /**
     * Role change and first-time role choice: the new role, "no earlier Disconnect" and "setup not finished yet" are written in ONE commit,
     * so an interruption (app killed, storage full) leaves either the old role with its old setup state or the new role with a fresh setup,
     * never a new role that inherits the old role's "setup finished". False = nothing was changed.
     */
    fun saveRoleChange(newRole: Role): Boolean =
        write(true, "role" to newRole.name, "user_stopped" to false, "setup_completed" to false)

    /** Same as [clearDevice] but tells the caller whether the change reached the file (one commit for all three keys). */
    fun clearDeviceDurably(): Boolean =
        write(true, "device_address" to null, "device_name" to null, "last_connected_at" to null)

    // ---------------------------------------------------------------- Stage 29: safe reading and writing

    private fun readString(key: String): String? {
        val m = memory[key]
        if (m != null) return if (m === REMOVED) null else m as? String
        val s = sp ?: return null
        return try { s.getString(key, null) } catch (e: Exception) { readFailed(key, e); null }
    }

    private fun readBoolean(key: String, default: Boolean): Boolean {
        val m = memory[key]
        if (m != null) return if (m === REMOVED) default else (m as? Boolean ?: default)
        val s = sp ?: return default
        return try { s.getBoolean(key, default) } catch (e: Exception) { readFailed(key, e); default }
    }

    private fun readLong(key: String, default: Long): Long {
        val m = memory[key]
        if (m != null) return if (m === REMOVED) default else (m as? Long ?: default)
        val s = sp ?: return default
        return try { s.getLong(key, default) } catch (e: Exception) { readFailed(key, e); default }
    }

    private fun readFailed(key: String, e: Exception) = reportOnce(key, "stored value has the wrong type", e)

    private fun reportOnce(key: String, what: String, e: Exception? = null) {
        if (reported.add(key)) SettingsDiagnostics.record(Area.PREFS_READ, what, e, detail = key)
    }

    /**
     * Writes all [changes] in one editor ([null] value = remove the key). With [durable] it waits for the file (`commit`) and returns whether it
     * was written; otherwise it hands the write to Android (`apply`) and returns true unless the write could not even be started.
     * On failure the values are kept in [memory] so this run of the app stays consistent, and nothing is thrown.
     */
    private fun write(durable: Boolean, vararg changes: Pair<String, Any?>): Boolean {
        var ok = false
        val s = sp
        if (s != null) {
            try {
                val editor = s.edit()
                for ((key, value) in changes) {
                    when (value) {
                        null -> editor.remove(key)
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Long -> editor.putLong(key, value)
                        else -> throw IllegalArgumentException("unsupported value type")
                    }
                }
                if (durable) ok = editor.commit() else { editor.apply(); ok = true }
            } catch (e: Exception) {
                SettingsDiagnostics.record(Area.PREFS_WRITE, "save settings", e, detail = changes.joinToString(",") { it.first }.take(80))
            }
            if (!ok && durable) SettingsDiagnostics.record(Area.PREFS_WRITE, "commit returned false", null, detail = changes.joinToString(",") { it.first }.take(80))
        }
        for ((key, value) in changes) {
            if (ok) { memory.remove(key); reported.remove(key) }      // the file is the truth again for this key
            else memory[key] = value ?: REMOVED                        // keep the choice for this run of the app
        }
        return ok
    }

    companion object {
        private const val FILE = "companion_prefs"
        private val REMOVED = Any()

        internal fun openOrNull(context: Context): SharedPreferences? = try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            SettingsDiagnostics.record(Area.PREFS_OPEN, "open preferences", e)
            null
        }

        /** Tests only: an [AppPrefs] over a given (fake) preferences object, or over none (null = the file cannot be opened). */
        internal fun forTest(sp: SharedPreferences?): AppPrefs = AppPrefs(sp)
    }
}
