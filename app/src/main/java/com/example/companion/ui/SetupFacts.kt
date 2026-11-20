package com.example.companion.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.CallRole
import com.example.companion.contacts.ContactsPermissions
import com.example.companion.data.Role
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.setup.PermissionFacts
import com.example.companion.setup.Importance
import com.example.companion.setup.RowStatus
import com.example.companion.setup.SetupAction
import com.example.companion.setup.SetupInput
import com.example.companion.setup.SetupItem
import com.example.companion.setup.SetupRow
import com.example.companion.setup.SetupState
import com.example.companion.sms.SmsPermissions

/** Stage 22: the permissions each setup item covers. One definition, used to read the state and to request it. */
fun Activity.setupPermissions(item: SetupItem): Array<String> = when (item) {
    SetupItem.BLUETOOTH -> BtPermissions.required(app.prefs.role ?: Role.MAIN_PHONE)
    SetupItem.CALL_PERMISSION -> arrayOf(Manifest.permission.CALL_PHONE)
    SetupItem.SMS -> SmsPermissions.required()
    SetupItem.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    SetupItem.CONTACTS -> arrayOf(ContactsPermissions.READ)
    SetupItem.ROLE, SetupItem.CALL_ROLE -> emptyArray()
}

/** Notifications and Contacts keep the flags older stages already used, so a dialog shown from there is not shown again here. */
fun Activity.setupAsked(item: SetupItem): Boolean = when (item) {
    SetupItem.NOTIFICATIONS -> app.prefs.notificationPermissionAsked
    SetupItem.CONTACTS -> app.prefs.contactsPermissionAsked
    else -> app.prefs.isSetupAsked(item.key)
}

fun Activity.markSetupAsked(item: SetupItem) {
    when (item) {
        SetupItem.NOTIFICATIONS -> app.prefs.notificationPermissionAsked = true
        SetupItem.CONTACTS -> app.prefs.contactsPermissionAsked = true
        else -> app.prefs.markSetupAsked(item.key)
    }
}

private fun Activity.facts(item: SetupItem): PermissionFacts {
    val missing = setupPermissions(item).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    return PermissionFacts(
        granted = missing.isEmpty(),
        askedBefore = setupAsked(item),
        showRationale = missing.any { shouldShowRequestPermissionRationale(it) }
    )
}

/** Reads the live state of every setup item from Android. Decisions are made by [SetupState]. */
fun Activity.readSetupInput(): SetupInput = SetupInput(
    role = app.prefs.role,
    sdk = Build.VERSION.SDK_INT,
    bluetooth = facts(SetupItem.BLUETOOTH),
    bluetoothSupported = app.bluetooth.isSupported,
    bluetoothEnabled = app.bluetooth.isEnabled,
    callPhone = facts(SetupItem.CALL_PERMISSION),
    callRoleHeld = CallRole.isHeld(this),
    callRoleAvailable = CallRole.requestIntent(this) != null,
    callRoleAsked = setupAsked(SetupItem.CALL_ROLE),
    sms = facts(SetupItem.SMS),
    notifications = facts(SetupItem.NOTIFICATIONS),
    notificationsEnabled = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true,
    contacts = facts(SetupItem.CONTACTS)
)

/**
 * Stage 15: [language] is the language of the row texts. It defaults to English, so the Home banner (which only uses the row titles)
 * is exactly as before; Settings and the Setup screen pass the selected language.
 */
fun Activity.readSetupRows(language: AppLanguage = AppLanguage.ENGLISH): List<SetupRow> =
    SetupState.rows(readSetupInput(), language).map { callingOffRow(it, language, app.prefs.role) }

/**
 * Stage 7: while calling is off, the SIM Phone's "Make calls" (CALL_PHONE) and "Default phone app" rows are replaced by the
 * "under development" message: no button, not required, never in the guided queue, never counted as missing. The pure [SetupState]
 * (and its tests) is untouched; every Setup / Settings / Home banner reads its rows through this one function.
 */
private fun callingOffRow(r: SetupRow, language: AppLanguage, role: Role?): SetupRow =
    if (CallFeatures.isCallingEnabled() || !isCallingOnlyItem(r.item, role)) r
    else r.copy(
        importance = Importance.OPTIONAL, status = RowStatus.UNAVAILABLE, statusText = Strings.text(StringKey.HOME_CALLING_DISABLED, language),   // Stage 15: same sentence as CallFeatures.DISABLED_MESSAGE, in the selected language
        blockedHelp = "", action = SetupAction.NONE, actionLabel = ""
    )

/**
 * Stage 25: the items that exist only for calling. Besides the two call rows of Stage 7, this is the SIM Phone's Notifications row: its text
 * says it is for call notifications, and the SIM Phone shows no other notification (SMS notifications are the Main Phone's, Stage 15), so
 * while calling is off it must not ask for POST_NOTIFICATIONS. The Main Phone's Notifications row is not affected.
 */
fun isCallingOnlyItem(item: SetupItem, role: Role?): Boolean =
    item == SetupItem.CALL_PERMISSION || item == SetupItem.CALL_ROLE || (item == SetupItem.NOTIFICATIONS && role == Role.SIM_PHONE)
