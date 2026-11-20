package com.example.companion.contacts

/**
 * Small in-memory memo of recent lookups, so a list of 50 rows (or a call screen that redraws every second) does not ask
 * the Contacts provider 50 times. It is NOT a contacts database: it lives only in this process, holds at most
 * [maxEntries] numbers, forgets each answer after [ttlMs], and is emptied when the Contacts permission changes.
 * "Not found" is remembered too, so numbers that are not contacts are not queried again on every redraw.
 *
 * Pure Kotlin (the clock is injected) so it can be unit-tested on a plain JVM. Thread-safe: the main thread and the lookup
 * thread both use it.
 */
class ContactCache(
    private val maxEntries: Int = 256,
    private val ttlMs: Long = 5 * 60 * 1000L,
    /** Stage 28: how long a FAILED lookup (provider error) is remembered. Short, so a recovered provider is asked again soon. Placed before [now] so `ContactCache(max, ttl) { clock }` still compiles. */
    private val failureTtlMs: Long = 30 * 1000L,
    private val now: () -> Long
) {
    /** A remembered answer; [match] is null for "this number is not a contact". */
    class Cached(val match: ContactMatch?)

    private class Entry(val match: ContactMatch?, val at: Long, val ttl: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    /** null = nothing usable remembered (never asked, or the answer is older than the time limit). */
    @Synchronized
    fun get(key: String): Cached? {
        val e = map[key] ?: return null
        if (now() - e.at !in 0..e.ttl) { map.remove(key); return null }
        return Cached(e.match)
    }

    @Synchronized
    fun put(key: String, match: ContactMatch?) { map[key] = Entry(match, now(), ttlMs) }

    /**
     * Stage 28: the provider FAILED for [key] (it is not known whether this is a contact). Remembered as "no contact" for only
     * [failureTtlMs], so a redraw does not ask the broken provider again at once (no query / redraw loop), and a recovered provider is
     * asked again within seconds instead of after five minutes.
     */
    @Synchronized
    fun putFailure(key: String) { map[key] = Entry(null, now(), failureTtlMs) }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size() = map.size
}
