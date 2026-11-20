package com.example.companion.bluetooth

/**
 * Stage 27: developer-only record of Bluetooth connection problems. NEVER shown to the user (screens show localized texts from
 * [com.example.companion.i18n.ConnectionErrorText]). Same idea as the SMS side's `SmsDiagnostics`.
 *
 * What it keeps per problem: where it happened ([Area]), a short fixed phrase written by the app, the exception CLASS name, the
 * exception MESSAGE (shortened, with anything that looks like a Bluetooth address masked), the top few stack frames and a short
 * context string such as "state=CONNECTING attempt=2". The exception message is kept here (unlike the SMS side) because the platform's
 * Bluetooth messages ("read failed, socket might closed or timeout, read ret: -1") are exactly what is needed to tell failures apart,
 * and they carry no message text or phone number. What it never keeps: a device address, a device name, SMS data, call data.
 *
 * Read it with `adb logcat -s CompanionBt` (one line per problem), or [recent] in code and tests. In memory only: an app restart
 * clears it. Pure Kotlin except the log sink, which is replaceable (JVM tests replace it; a missing android.util.Log is tolerated).
 * Recording can never throw, so reporting a problem can never become a second problem. Thread-safe: worker threads call it directly.
 */
object BluetoothDiagnostics {

    /** The place in the Bluetooth code a problem was caught. */
    enum class Area {
        CONNECT,        // ConnectionManager: client socket connect / pairing / starting an attempt
        LISTEN,         // ConnectionManager: SIM Phone listener / accept
        LINK,           // ConnectionManager: an established link ended or could not be set up
        RETRY,          // ConnectionManager: retry decisions (gave up, backoff, link unstable)
        PERMISSION,     // a missing or revoked Bluetooth permission
        ADAPTER,        // Bluetooth turned off, unsupported, or the adapter unavailable
        TRANSPORT,      // MessageTransport: reading / writing frames on the socket
        LISTENER,       // a ConnectionManager listener (another layer) threw
        RECEIVER        // BluetoothManager: system broadcast handling
    }

    /** One recorded problem. [detail] is a short context phrase written by the app. */
    data class Entry(
        val time: Long, val area: Area, val what: String,
        val exception: String?, val exceptionMessage: String?, val frames: List<String>, val detail: String?
    )

    const val TAG = "CompanionBt"
    const val MAX_ENTRIES = 100
    private const val MAX_FRAMES = 4
    private const val MAX_MESSAGE = 120

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** Where a formatted line goes. Default: Android's log (warning level). Replaced in tests; never throws out of [record]. */
    @Volatile var sink: (String) -> Unit = { line -> androidLog(line) }

    /** Injectable clock (tests). */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /** Records a caught problem. [what] and [detail] are short fixed phrases written by the app, never user data. */
    fun record(area: Area, what: String, t: Throwable? = null, detail: String? = null) {
        try {
            val entry = Entry(
                time = clock(), area = area, what = what.take(80),
                exception = t?.javaClass?.name,
                exceptionMessage = t?.message?.let { clean(it) }?.takeIf { it.isNotEmpty() },
                frames = t?.stackTrace?.take(MAX_FRAMES)?.map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty(),
                detail = detail?.take(100)
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

    private val ADDRESS = Regex("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b")

    /** Shortens an exception message to one line and masks anything shaped like a Bluetooth address (aa:bb:cc:dd:ee:ff). */
    fun clean(message: String): String =
        message.replace('\n', ' ').replace('\r', ' ').replace(ADDRESS, "xx:xx:xx:xx:xx:xx").trim().take(MAX_MESSAGE)

    fun format(e: Entry): String = buildString {
        append(e.area.name).append(": ").append(e.what)
        e.detail?.let { append(" [").append(it).append(']') }
        e.exception?.let { append(" | ").append(it) }
        e.exceptionMessage?.let { append(": ").append(it) }
        if (e.frames.isNotEmpty()) append(" at ").append(e.frames.joinToString(" < "))
    }

    private fun androidLog(line: String) {
        try { android.util.Log.w(TAG, line) } catch (ignored: Throwable) { /* plain JVM test, or no logger: nothing to do */ }
    }
}
