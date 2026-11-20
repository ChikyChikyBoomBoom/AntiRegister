package com.example.companion.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.companion.contacts.ContactsAccess
import com.example.companion.contacts.ContactsPermissions
import com.example.companion.contacts.ContactsState
import com.example.companion.data.ReadDiagnostics
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.tr

/**
 * The one place the optional Contacts permission is asked for. It is a short notice with a button that is hidden once the
 * permission is granted. The permission is requested only when the user taps the button (never on its own, never at app
 * start); declining changes nothing else: every screen keeps showing phone numbers.
 *
 * The host activity forwards its onRequestPermissionsResult to [onPermissionResult] and calls [update] in onStart.
 */
class ContactsPrompt(private val activity: Activity, private val onChanged: () -> Unit) {

    private val notice = TextView(activity).apply { textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY); setPadding(0, activity.dp(8), 0, activity.dp(4)); followLayoutStart() }   // Stage 17: starts at the start edge like the other notes
    private val button = activity.button("") { onTap() }

    /** Add this to the screen's layout. It hides itself when there is nothing to ask. */
    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(notice)
        addView(button)
    }

    fun access(): ContactsAccess = ContactsState.access(
        granted = ContactsPermissions.granted(activity),
        askedBefore = activity.app.prefs.contactsPermissionAsked,
        showRationale = activity.shouldShowRequestPermissionRationale(ContactsPermissions.READ)
    )

    fun update(visible: Boolean = true) {
        val a = access()
        // Stage 28: permission granted but the Contacts provider is failing -> a short notice (no button: there is nothing to tap), numbers stay.
        val unavailable = a == ContactsAccess.GRANTED && safeUnavailable()
        val show = visible && (a != ContactsAccess.GRANTED || unavailable)
        // Stage 14: read in the selected language every time the card is refreshed (the host screen calls update() in onStart).
        notice.text = if (unavailable) ContactsState.unavailableNotice(AppLocale.current) else ContactsState.notice(a, AppLocale.current)
        button.text = ContactsState.buttonLabel(a, AppLocale.current)
        button.show(!unavailable)
        view.show(show)
    }

    private fun safeUnavailable(): Boolean = try { activity.app.contacts.isUnavailable() } catch (e: Exception) { false }

    private fun onTap() {
        when (access()) {
            ContactsAccess.GRANTED -> update()
            ContactsAccess.CAN_ASK -> {
                activity.app.prefs.contactsPermissionAsked = true
                // Stage 28: a refused request (no permission UI on this phone, activity state) says so instead of ending the app.
                try { activity.requestPermissions(arrayOf(ContactsPermissions.READ), REQUEST_CODE) }
                catch (e: Exception) {
                    ReadDiagnostics.record(ReadDiagnostics.Area.CONTACTS_UI, "request contacts permission", e)
                    Toast.makeText(activity, tr(StringKey.CALLS_SIM_ASK_FAILED), Toast.LENGTH_LONG).show()
                }
            }
            ContactsAccess.SETTINGS_ONLY ->
                // Stage 28: a phone without the app-settings page: tell the user the path by hand.
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                    )
                } catch (e: Exception) {
                    ReadDiagnostics.record(ReadDiagnostics.Area.CONTACTS_UI, "open app settings", e)
                    Toast.makeText(activity, tr(StringKey.CALLS_SIM_SETTINGS_PATH), Toast.LENGTH_LONG).show()
                }
        }
    }

    /** Returns true when the result was ours. The answer (granted or not) is simply re-read; nothing else depends on it. */
    fun onPermissionResult(requestCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        activity.app.contacts.invalidate()
        update()
        onChanged()
        return true
    }

    companion object { const val REQUEST_CODE = 1900 }
}
