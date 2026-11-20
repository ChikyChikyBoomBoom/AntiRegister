package com.example.companion.data

/**
 * Stage 29: developer-only record of problems in the saved settings, Settings screen and first-run (welcome / Setup) code. NEVER shown to
 * the user (screens show localized texts from the catalog, see [com.example.companion.ui.SettingsIssue]). Same idea and same rules as
 * [ReadDiagnostics] (kept separate so that one, and the SMS and Bluetooth diagnostics, stay untouched).
 *
 * What it keeps per problem: where it happened ([Area]), a short fixed phrase written by the app, the exception CLASS name and the top few
 * stack frames (code locations only). What it never keeps: the exception message, the stored value itself, a device address or a device
 * name. So it is safe to read from logcat or a bug report: `adb logcat -s CompanionSettings`, or [recent] in code and tests.
 *
 * In memory only; an app restart clears it. Recording can never throw, so reporting a problem can never become a second problem.
 */
object SettingsDiagnostics {

    enum class Area {
        PREFS_READ,      // AppPrefs: a stored value could not be read or was not usable (replaced by the safe default)
        PREFS_WRITE,     // AppPrefs: a value could not be saved
        PREFS_OPEN,      // AppPrefs: the preferences file itself could not be opened
        SETTINGS_UI,     // SettingsActivity: drawing, a tap, opening another screen
        ROLE_CHANGE,     // SettingsActions: role / device change
        ONBOARDING_UI,   // RoleSelectActivity / SetupActivity
        LANGUAGE         // the language chooser
    }

    /** One recorded problem. [detail] is a short fixed phrase written by the app, never stored data. */
    data class Entry(val time: Long, val area: Area, val what: String, val exception: String?, val frames: List<String>, val detail: String?)

    const val TAG = "CompanionSettings"
    const val MAX_ENTRIES = 50
    private const val MAX_FRAMES = 4

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** Where a formatted line goes. Default: Android's log (warning level). Replaced in tests; never throws out of [record]. */
    @Volatile var sink: (String) -> Unit = { line -> androidLog(line) }

    /** Injectable clock (tests). */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /** Records a caught problem. [what] is a short fixed phrase ("save auto reconnect"), never user data. */
    fun record(area: Area, what: String, t: Throwable? = null, detail: String? = null) {
        try {
            val entry = Entry(
                time = clock(), area = area, what = what.take(80),
                exception = t?.javaClass?.name,
                frames = t?.stackTrace?.take(MAX_FRAMES)?.map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty(),
                detail = detail?.take(80)
            )
            synchronized(lock) {
                entries.addLast(entry)
                while (entries.size > MAX_ENTRIES) entries.removeFirst()
            }
            sink(format(entry))
        } catch (ignored: Throwable) {
            // Diagnostics must never throw into the code that is reporting a problem.
        }
    }

    /** Newest last. A copy. */
    fun recent(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    fun format(e: Entry): String = buildString {
        append(e.area.name).append(": ").append(e.what)
        e.detail?.let { append(" [").append(it).append(']') }
        e.exception?.let { append(" | ").append(it) }
        if (e.frames.isNotEmpty()) append(" at ").append(e.frames.joinToString(" < "))
    }

    private fun androidLog(line: String) {
        try { android.util.Log.w(TAG, line) } catch (ignored: Throwable) { /* plain JVM test, or no logger: nothing to do */ }
    }
}
