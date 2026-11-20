package com.example.companion.contacts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.example.companion.data.ReadDiagnostics

/** One phone number chosen in the system contact picker. [name] is null if the contact has none. */
data class PickedContact(val name: String?, val number: String)

/** Stage 28: why [ContactPicker.readResult] gave no contact (the screens word each one differently). */
enum class PickFailure {
    /** Nothing came back (the user backed out, or the picker returned no row). Silent: the user chose nothing. */
    NOTHING_PICKED,
    /** The row could not be read (provider error, access refused, corrupt record). */
    UNREADABLE,
    /** The contact was read but has no phone number. */
    NO_NUMBER
}

/** Stage 28: result of reading the picker's row: exactly one of [contact] / [failure] is set. */
class PickResult private constructor(val contact: PickedContact?, val failure: PickFailure?) {
    companion object {
        fun picked(c: PickedContact) = PickResult(c, null)
        fun failed(f: PickFailure) = PickResult(null, f)
    }
}

/**
 * The "choose a contact" step for a new SMS or call. It opens the phone's own contact picker
 * ([Intent.ACTION_PICK] on the phone-number list) and reads back ONLY the one number the user tapped. Android gives this
 * app temporary read access to that single row, so this needs no Contacts permission, builds no list of contacts of its own,
 * and stores nothing. If no contacts app exists, [start] returns false and the screen says so (typing a number still works).
 */
object ContactPicker {

    @Suppress("DEPRECATION")
    fun start(activity: Activity, requestCode: Int): Boolean = try {
        activity.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), requestCode)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: RuntimeException) {
        // Stage 28: a picker that refuses to start (SecurityException, bad intent state) must not end the app; the screen says typing still works.
        ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_PICKER, "start contact picker", e)
        false
    }

    /** Reads the picked row (the data URI of the picker's result). null when nothing usable came back. Stage 28: same answer as before; see [readResult] for the reason. */
    fun read(context: Context, data: Uri?): PickedContact? = readResult(context, data).contact

    /**
     * Stage 28: like [read] but says WHY nothing came back, so a screen can tell the user instead of silently doing nothing.
     * Never throws. The name is cleaned ([ContactsState.cleanName]); a blank name just means "no name".
     */
    fun readResult(context: Context, data: Uri?): PickResult {
        if (data == null) return PickResult.failed(PickFailure.NOTHING_PICKED)
        return try {
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val cursor = context.contentResolver.query(data, projection, null, null, null)
            if (cursor == null) {
                ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_PICKER, "picker row returned no cursor")
                PickResult.failed(PickFailure.UNREADABLE)
            } else cursor.use { c ->
                if (!c.moveToFirst()) PickResult.failed(PickFailure.NOTHING_PICKED)
                else {
                    val number = c.getString(0)?.trim().orEmpty()
                    if (number.isEmpty()) PickResult.failed(PickFailure.NO_NUMBER)
                    else PickResult.picked(PickedContact(ContactsState.cleanName(c.getString(1)), number))
                }
            }
        } catch (e: Exception) {
            ReadDiagnostics.record(ReadDiagnostics.Area.CONTACT_PICKER, "read picked contact", e)      // includes SecurityException
            PickResult.failed(PickFailure.UNREADABLE)
        }
    }
}
