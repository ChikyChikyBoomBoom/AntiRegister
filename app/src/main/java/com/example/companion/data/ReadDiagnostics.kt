package com.example.companion.data

/**
 * Stage 28: developer-only record of problems in the Contacts and read-only Call History code. NEVER shown to the user (screens show
 * localized texts from the catalog). Same idea as the SMS and Bluetooth diagnostics, kept separate so those stay untouched.
 *
 * What it keeps per problem: where it happened ([Area]), a short fixed phrase written by the app, the exception CLASS name and the top
 * few stack frames (code locations only). What it never keeps: the exception message (it can quote data), a phone number, a contact
 * name, a photo address, a call-log row. So it is safe to read from logcat or a bug report.
 *
 * Read it with `adb logcat -s CompanionRead`, or [recent] in code and tests. In memory only; an app restart clears it. Recording can
 * never throw, so reporting a problem can never become a second problem. Pure Kotlin except the log sink (replaceable; a missing
 * android.util.Log, as on a plain JVM, is tolerated).
 */
object ReadDiagnostics {

    /** The place a problem was caught. */
    enum class Area {
        CONTACT_LOOKUP,     // AndroidContactLookup: one number -> name / photo query, or the background lookup
        CONTACT_PHOTO,      // Avatars: reading or drawing a contact photo
        CONTACT_PICKER,     // ContactPicker: opening the system picker or reading the chosen row
        CONTACTS_UI,        // ContactsPrompt: permission request / settings page
        CALL_LOG_QUERY,     // SimCallLogReader: the query itself
        CALL_LOG_ROW,       // SimCallLogReader: one malformed row
        CALL_LOG_UI         // SimCallActivity: background read, drawing, permission request
    }

    /** One recorded problem. [detail] is a short fixed phrase written by the app, never data from a contact or a call. */
    data class Entry(val time: Long, val area: Area, val what: String, val exception: String?, val frames: List<String>, val detail: String?)

    const val TAG = "CompanionRead"
    const val MAX_ENTRIES = 50
    private const val MAX_FRAMES = 4

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** Where a formatted line goes. Default: Android's log (warning level). Replaced in tests; never throws out of [record]. */
    @Volatile var sink: (String) -> Unit = { line -> androidLog(line) }

    /** Injectable clock (tests). */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /** Records a caught problem. [what] is a short fixed phrase ("query call log"), never user data. */
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
