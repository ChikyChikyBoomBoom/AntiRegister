package com.example.companion.contacts

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import com.example.companion.data.ReadDiagnostics
import java.util.concurrent.Executors

/**
 * Turns a phone number into a contact name / photo, using only Android's official Contacts provider
 * ([ContactsContract.PhoneLookup], one number per query). Nothing is listed, exported, copied, stored or uploaded: the
 * answers live in a small in-memory [ContactCache] for a few minutes and are lost when the process ends.
 *
 * Without the READ_CONTACTS permission every method answers "no contact" and does not touch the provider, so the app
 * behaves exactly as it did before this stage (numbers everywhere). Numbers that are not phone-number-shaped (see
 * [ContactsState.isLookupCandidate]) are never looked up. Never logs numbers or names.
 *
 * Three ways in, so a screen can pick what suits it:
 *  - [find]: answers now (may do one quick provider query on the calling thread, then the answer is cached). Used for a
 *    single number that is drawn once: the call screen and notifications.
 *  - [findCached]: never queries; answers only what is already known. Used while drawing lists.
 *  - [prefetch]: resolves several numbers on a background thread, then calls back on the main thread ONLY if it looked
 *    something up, so a redraw that calls it again does not loop.
 *
 * Stage 28: a provider FAILURE (as opposed to "not a contact") is told apart from a real "no". It is remembered only briefly
 * ([ContactCache.putFailure]) so a recovered provider is asked again soon, it sets [isUnavailable] (the optional notice on the
 * Contacts card), and it is recorded in [ReadDiagnostics] (class name and code locations only). Nothing here ever throws to a
 * caller or ends a lookup thread: a broken provider, a revoked permission, a record with a corrupt name or photo address all end as
 * "no contact", and the number is shown.
 */
class AndroidContactLookup(private val context: Context) {

    private val cache = ContactCache { SystemClock.elapsedRealtime() }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "contacts-lookup").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wasGranted = ContactsPermissions.granted(context)
    /** Stage 28: the last provider query failed (and none has succeeded since). Cleared on a success, on [invalidate] and when the permission changes. */
    @Volatile private var unavailable = false

    fun find(number: String?): ContactMatch? {
        val key = keyOf(number) ?: return null
        if (!granted()) return null
        cache.get(key)?.let { return it.match }
        return resolve(key)
    }

    fun findCached(number: String?): ContactMatch? {
        val key = keyOf(number) ?: return null
        if (!granted()) return null
        return cache.get(key)?.match
    }

    /** Resolves the numbers not known yet off the main thread. [onDone] runs on the main thread, and only if something was looked up. */
    fun prefetch(numbers: Collection<String?>, onDone: () -> Unit) {
        if (!granted()) return
        val todo = numbers.mapNotNull { keyOf(it) }.distinct().filter { cache.get(it) == null }
        if (todo.isEmpty()) return
        try {
            executor.execute {
                // Stage 28: nothing thrown here may reach the thread's uncaught-exception handler (that would end the app).
                try {
                    todo.forEach { if (cache.get(it) == null) resolve(it) }
                    main.post(onDone)
                } catch (e: Exception) {
                    ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_LOOKUP, "background lookup", e)
                }
            }
        } catch (e: Exception) {
            ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_LOOKUP, "start background lookup", e)    // executor refused the task: numbers stay
        }
    }

    /** Forget everything remembered (the permission changed, or the user asked to refresh). */
    fun invalidate() { cache.clear(); unavailable = false }

    /** Stage 28: true while permission is granted but the Contacts provider is failing. The screens then keep showing numbers. */
    fun isUnavailable(): Boolean = unavailable && granted()

    // ---------------------------------------------------------------- internals

    private fun keyOf(number: String?): String? = number?.trim()?.takeIf { ContactsState.isLookupCandidate(it) }

    /** The permission can change while the app runs (the system dialog, or settings); answers from the other state are dropped. */
    private fun granted(): Boolean {
        val g = ContactsPermissions.granted(context)
        if (g != wasGranted) { wasGranted = g; cache.clear(); unavailable = false }
        return g
    }

    /** Asks the provider and remembers the answer (a failure only briefly). Never throws. */
    private fun resolve(key: String): ContactMatch? = when (val r = query(key)) {
        is Lookup.Found -> { unavailable = false; cache.put(key, r.match); r.match }
        Lookup.NotFound -> { unavailable = false; cache.put(key, null); null }
        Lookup.Failed -> { unavailable = true; cache.putFailure(key); null }
    }

    private sealed interface Lookup {
        class Found(val match: ContactMatch) : Lookup
        object NotFound : Lookup
        object Failed : Lookup
    }

    private fun query(number: String): Lookup = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        if (cursor == null) {
            ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_LOOKUP, "provider returned no cursor")
            Lookup.Failed                       // no provider answered: not the same as "not a contact"
        } else cursor.use { c ->
            if (!c.moveToFirst()) Lookup.NotFound
            else {
                // Stage 28: a record with a blank / corrupt name is treated as "not a contact" (the number is shown); a bad photo address only loses the photo.
                val name = ContactsState.cleanName(c.getString(0))
                if (name == null) Lookup.NotFound else Lookup.Found(ContactMatch(name, ContactsState.cleanPhotoUri(c.getString(1))))
            }
        }
    } catch (e: SecurityException) {
        ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_LOOKUP, "permission withdrawn during query", e)
        Lookup.NotFound                         // permission withdrawn between the check and the query: granted() clears the cache on the next call
    } catch (e: Exception) {
        ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_LOOKUP, "query contacts provider", e)
        Lookup.Failed                           // provider unavailable / died / bad row: the number is still shown
    }
}
