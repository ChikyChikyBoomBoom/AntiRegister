package com.example.companion.sms

/**
 * Stage 26: developer-only record of SMS problems. NEVER shown to the user (screens show localized texts from [SmsErrorText]).
 *
 * What it keeps per problem: where it happened ([Area]), what kind of failure it was (a short stable code), the exception CLASS name
 * and the top few stack frames (code locations only). What it never keeps: the exception message (an exception message can quote
 * data), a phone number, a sender, a message text, an id. So it is safe to read from logcat or a bug report.
 *
 * Read it with `adb logcat -s CompanionSms` (one line per problem), or [recent] in code and tests. In memory only: an app restart
 * clears it. Pure Kotlin except the log sink, which is replaceable (JVM tests replace it; a missing android.util.Log is tolerated).
 * Recording can never throw, so reporting a problem can never become a second problem.
 */
object SmsDiagnostics {

    /** The place in the SMS code a problem was caught. */
    enum class Area {
        SIM_RECEIVE,        // SmsReceiver: an SMS arrived on the SIM Phone
        SIM_SENT_RESULT,    // SmsSentReceiver: Android's per-part send result
        SIM_SEND,           // HostSmsController: handling an SMS_SEND request
        SIM_GATEWAY,        // AndroidSmsGateway: calling Android's SMS API
        SIM_STORE,          // SimSmsStore: send records / inbound queue
        SIM_LINK,           // HostSmsController: Bluetooth callbacks and forwarding
        MAIN_SEND,          // ClientSmsController.send
        MAIN_RECEIVE,       // ClientSmsController: SMS_RECEIVED handling
        MAIN_RESULT,        // ClientSmsController: SMS_SEND_RESULT handling
        MAIN_STORE,         // SmsStore (Main Phone database)
        MAIN_NOTIFY,        // SmsNotifier / listeners of the controller
        UI                  // an SMS screen
    }

    /** One recorded problem. [detail] is a short fixed phrase written by the app, never data from a message. */
    data class Entry(val time: Long, val area: Area, val what: String, val exception: String?, val frames: List<String>, val detail: String?)

    const val TAG = "CompanionSms"
    const val MAX_ENTRIES = 50
    private const val MAX_FRAMES = 4

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** Where a formatted line goes. Default: Android's log (warning level). Replaced in tests; never throws out of [record]. */
    @Volatile var sink: (String) -> Unit = { line -> androidLog(line) }

    /** Injectable clock (tests). */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /** Records a caught problem. [what] is a short fixed phrase ("insert received sms"), never user data. */
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
