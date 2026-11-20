package com.example.companion.setup

import com.example.companion.data.Role
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.localizedLabel

/** One line of the Setup Status section. [key] is the name the "already asked" flag is stored under (see AppPrefs.isSetupAsked). */
enum class SetupItem(val key: String) {
    ROLE("role"),
    BLUETOOTH("bluetooth"),
    CALL_PERMISSION("call_permission"),
    CALL_ROLE("call_role"),
    SMS("sms"),
    NOTIFICATIONS("notifications"),
    CONTACTS("contacts")
}

/** How much the app needs the item. Only REQUIRED items count as "setup incomplete"; RECOMMENDED ones are still offered in the guided setup. */
enum class Importance { REQUIRED, RECOMMENDED, OPTIONAL }

enum class RowStatus { OK, NEEDS_ACTION, BLOCKED, OPTIONAL_OFF, UNAVAILABLE }

enum class SetupAction(val isPermissionFix: Boolean) {
    NONE(false),
    /** Show Android's permission dialog (after our explanation). */
    REQUEST_PERMISSION(true),
    /** Android will not show the dialog again; only the app's system settings page can change it. */
    OPEN_APP_SETTINGS(true),
    /** The default phone app role (Android shows its own confirmation). */
    REQUEST_CALL_ROLE(true),
    /** Notifications are switched off for the app in system settings. */
    OPEN_NOTIFICATION_SETTINGS(true),
    ENABLE_BLUETOOTH(false)
}

/** Where a runtime permission stands, as far as the UI is concerned. Same meaning as the Contacts feature's own enum. */
enum class PermissionAccess { GRANTED, CAN_ASK, SETTINGS_ONLY }

/**
 * [askedBefore]: our dialog was already shown once (stored in AppPrefs). [showRationale]: Android's
 * shouldShowRequestPermissionRationale for a missing permission: true after one denial, false before the first request and
 * after "don't ask again".
 */
data class PermissionFacts(val granted: Boolean, val askedBefore: Boolean, val showRationale: Boolean) {
    companion object { val GRANTED = PermissionFacts(granted = true, askedBefore = false, showRationale = false) }
}

/** Everything the Android side has to read; the rest is decided here so it can be unit-tested on a plain JVM. */
data class SetupInput(
    val role: Role?,
    val sdk: Int,
    val bluetooth: PermissionFacts,
    val bluetoothSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val callPhone: PermissionFacts,
    val callRoleHeld: Boolean,
    val callRoleAvailable: Boolean,
    val callRoleAsked: Boolean,
    val sms: PermissionFacts,
    val notifications: PermissionFacts,
    /** NotificationManager.areNotificationsEnabled(): the app's notifications are not switched off in system settings. */
    val notificationsEnabled: Boolean,
    val contacts: PermissionFacts
)

data class SetupRow(
    val item: SetupItem,
    val title: String,
    val importance: Importance,
    val status: RowStatus,
    val statusText: String,
    /** Plain-language reason, shown on the row and again in the dialog before Android's own dialog. */
    val why: String,
    /** Extra help, shown only while BLOCKED. */
    val blockedHelp: String,
    val action: SetupAction,
    val actionLabel: String,
    /** Android's dialog (or the role dialog) was already shown once for this item. */
    val askedBefore: Boolean
) {
    val ok get() = status == RowStatus.OK
}

/**
 * Pure functions (no Android types). WRITTEN BUT NOT RUN: see STAGE22_NOTES.md.
 *
 * Stage 15: [rows] and [summaryLine] take an optional `language` (last parameter, default English). Every text comes from the central
 * catalog ([StringKey], prefix SETUP_); English output is word-for-word the text that was written here before. Which rows exist, their
 * importance / status / action and the guided order are unchanged.
 */
object SetupState {

    const val SETTINGS_HELP = "Android will not show this question again. Open app settings, tap Permissions and allow it."
    const val SMS_RESTRICTED_NOTE = " If it cannot be allowed there, open App info, tap the three-dot menu and choose \"Allow restricted settings\" first (needed for apps installed outside Google Play)."

    fun access(f: PermissionFacts): PermissionAccess = when {
        f.granted -> PermissionAccess.GRANTED
        !f.askedBefore || f.showRationale -> PermissionAccess.CAN_ASK
        else -> PermissionAccess.SETTINGS_ONLY
    }

    private fun t(key: StringKey, language: AppLanguage) = Strings.text(key, language)

    /** The rows to show for [input.role], in the order the guided setup walks through them. Without a role only the Role row exists. */
    fun rows(input: SetupInput, language: AppLanguage = AppLanguage.ENGLISH): List<SetupRow> {
        val role = input.role ?: return listOf(roleRow(null, language))
        return when (role) {
            Role.MAIN_PHONE -> listOf(roleRow(role, language), bluetoothRow(input, role, language), notificationsRow(input, role, language), contactsRow(input, language))
            Role.SIM_PHONE -> listOf(
                roleRow(role, language), bluetoothRow(input, role, language), callPermissionRow(input, language), callRoleRow(input, language),
                smsRow(input, language), notificationsRow(input, role, language)
            )
        }
    }

    // ---------------------------------------------------------------- rows

    private fun roleRow(role: Role?, language: AppLanguage) = if (role == null) SetupRow(
        SetupItem.ROLE, t(StringKey.SETUP_ROLE_TITLE, language), Importance.REQUIRED, RowStatus.NEEDS_ACTION, t(StringKey.ROLE_NOT_SELECTED, language),
        t(StringKey.SETUP_ROLE_CHOOSE, language), "", SetupAction.NONE, "", askedBefore = false
    ) else SetupRow(
        SetupItem.ROLE, t(StringKey.SETUP_ROLE_TITLE, language), Importance.REQUIRED, RowStatus.OK,
        Strings.text(StringKey.SETUP_ROLE_SAVED, language, role.localizedLabel(language)),
        when (role) {
            Role.SIM_PHONE -> t(StringKey.SETUP_ROLE_WHY_SIM, language)
            Role.MAIN_PHONE -> t(StringKey.SETUP_ROLE_WHY_MAIN, language)
        }, "", SetupAction.NONE, "", askedBefore = false
    )

    private fun bluetoothRow(i: SetupInput, role: Role, language: AppLanguage): SetupRow {
        val why = when {
            role == Role.MAIN_PHONE && i.sdk >= 31 -> t(StringKey.SETUP_BT_WHY_MAIN_NEW, language)
            role == Role.MAIN_PHONE -> t(StringKey.SETUP_BT_WHY_MAIN_OLD, language)
            i.sdk >= 31 -> t(StringKey.SETUP_BT_WHY_SIM_NEW, language)
            else -> t(StringKey.SETUP_BT_WHY_NONE, language)
        }
        val title = t(StringKey.SETUP_BT_TITLE, language)
        fun row(status: RowStatus, text: String, action: SetupAction, label: String, help: String = "") =
            SetupRow(SetupItem.BLUETOOTH, title, Importance.REQUIRED, status, text, why, help, action, label, i.bluetooth.askedBefore)

        if (!i.bluetoothSupported) return row(RowStatus.UNAVAILABLE, t(StringKey.SETUP_BT_UNSUPPORTED, language), SetupAction.NONE, "")
        return when (access(i.bluetooth)) {
            PermissionAccess.GRANTED ->
                if (i.bluetoothEnabled) row(RowStatus.OK, t(StringKey.SETUP_BT_OK, language), SetupAction.NONE, "")
                else row(RowStatus.NEEDS_ACTION, t(StringKey.SETUP_BT_OFF, language), SetupAction.ENABLE_BLUETOOTH, t(StringKey.SETUP_BT_TURN_ON, language))
            PermissionAccess.CAN_ASK ->
                row(RowStatus.NEEDS_ACTION,
                    if (i.bluetooth.askedBefore) t(StringKey.SETUP_BT_NOT_ALLOWED, language) else t(StringKey.SETUP_PERMISSION_NEEDED, language),
                    SetupAction.REQUEST_PERMISSION, t(StringKey.SETUP_BT_ALLOW, language))
            PermissionAccess.SETTINGS_ONLY ->
                row(RowStatus.BLOCKED, t(StringKey.SETUP_BT_BLOCKED, language), SetupAction.OPEN_APP_SETTINGS,
                    t(StringKey.SETUP_OPEN_APP_SETTINGS, language), t(StringKey.SETUP_SETTINGS_HELP, language))
        }
    }

    private fun callPermissionRow(i: SetupInput, language: AppLanguage): SetupRow {
        val why = t(StringKey.SETUP_CALL_WHY, language)
        val title = t(StringKey.SETUP_CALL_TITLE, language)
        fun row(status: RowStatus, text: String, action: SetupAction, label: String, help: String = "") =
            SetupRow(SetupItem.CALL_PERMISSION, title, Importance.REQUIRED, status, text, why, help, action, label, i.callPhone.askedBefore)
        return when (access(i.callPhone)) {
            PermissionAccess.GRANTED -> row(RowStatus.OK, t(StringKey.SETUP_ALLOWED, language), SetupAction.NONE, "")
            PermissionAccess.CAN_ASK ->
                row(RowStatus.NEEDS_ACTION, if (i.callPhone.askedBefore) t(StringKey.SETUP_NOT_ALLOWED, language) else t(StringKey.SETUP_PERMISSION_NEEDED, language),
                    SetupAction.REQUEST_PERMISSION, t(StringKey.SETUP_CALL_ALLOW, language))
            PermissionAccess.SETTINGS_ONLY -> row(RowStatus.BLOCKED, t(StringKey.SETUP_BLOCKED, language), SetupAction.OPEN_APP_SETTINGS,
                t(StringKey.SETUP_OPEN_APP_SETTINGS, language), t(StringKey.SETUP_SETTINGS_HELP, language))
        }
    }

    private fun callRoleRow(i: SetupInput, language: AppLanguage): SetupRow {
        val why = t(StringKey.SETUP_CALLROLE_WHY, language)
        val title = t(StringKey.SETUP_CALLROLE_TITLE, language)
        fun row(status: RowStatus, text: String, action: SetupAction, label: String) =
            SetupRow(SetupItem.CALL_ROLE, title, Importance.REQUIRED, status, text, why, "", action, label, i.callRoleAsked)
        return when {
            i.callRoleHeld -> row(RowStatus.OK, t(StringKey.SETUP_CALLROLE_HELD, language), SetupAction.NONE, "")
            !i.callRoleAvailable -> row(RowStatus.UNAVAILABLE, t(StringKey.SETUP_CALLROLE_UNAVAILABLE, language), SetupAction.NONE, "")
            else -> row(RowStatus.NEEDS_ACTION,
                if (i.callRoleAsked) t(StringKey.SETUP_CALLROLE_NOT_SET, language) else t(StringKey.SETUP_CALLROLE_NOT_SET_YET, language),
                SetupAction.REQUEST_CALL_ROLE, t(StringKey.SETUP_CALLROLE_BUTTON, language))
        }
    }

    private fun smsRow(i: SetupInput, language: AppLanguage): SetupRow {
        val why = t(StringKey.SETUP_SMS_WHY, language)
        val title = t(StringKey.SETUP_SMS_TITLE, language)
        fun row(status: RowStatus, text: String, action: SetupAction, label: String, help: String = "") =
            SetupRow(SetupItem.SMS, title, Importance.REQUIRED, status, text, why, help, action, label, i.sms.askedBefore)
        return when (access(i.sms)) {
            PermissionAccess.GRANTED -> row(RowStatus.OK, t(StringKey.SETUP_SMS_OK, language), SetupAction.NONE, "")
            PermissionAccess.CAN_ASK ->
                row(RowStatus.NEEDS_ACTION, if (i.sms.askedBefore) t(StringKey.SETUP_NOT_ALLOWED, language) else t(StringKey.SETUP_PERMISSION_NEEDED, language),
                    SetupAction.REQUEST_PERMISSION, t(StringKey.SETUP_SMS_ALLOW, language))
            PermissionAccess.SETTINGS_ONLY ->
                row(RowStatus.BLOCKED, t(StringKey.SETUP_BLOCKED, language), SetupAction.OPEN_APP_SETTINGS, t(StringKey.SETUP_OPEN_APP_SETTINGS, language),
                    t(StringKey.SETUP_SETTINGS_HELP, language) + t(StringKey.SETUP_SMS_RESTRICTED_NOTE, language))
        }
    }

    private fun notificationsRow(i: SetupInput, role: Role, language: AppLanguage): SetupRow {
        val why = when (role) {
            Role.MAIN_PHONE -> t(StringKey.SETUP_NOTIF_WHY_MAIN, language)
            Role.SIM_PHONE -> t(StringKey.SETUP_NOTIF_WHY_SIM, language)
        }
        val title = t(StringKey.SETUP_NOTIF_TITLE, language)
        fun row(status: RowStatus, text: String, action: SetupAction, label: String, help: String = "") =
            SetupRow(SetupItem.NOTIFICATIONS, title, Importance.RECOMMENDED, status, text, why, help, action, label, i.notifications.askedBefore)
        return when (access(i.notifications)) {
            PermissionAccess.GRANTED ->
                if (i.notificationsEnabled) row(RowStatus.OK, if (i.sdk >= 33) t(StringKey.SETUP_ALLOWED, language) else t(StringKey.SETUP_NOTIF_ON_OLD, language), SetupAction.NONE, "")
                else row(RowStatus.NEEDS_ACTION, t(StringKey.SETUP_NOTIF_OFF_IN_SETTINGS, language), SetupAction.OPEN_NOTIFICATION_SETTINGS, t(StringKey.SETUP_NOTIF_OPEN_SETTINGS, language))
            PermissionAccess.CAN_ASK ->
                row(RowStatus.NEEDS_ACTION, if (i.notifications.askedBefore) t(StringKey.SETUP_NOT_ALLOWED, language) else t(StringKey.SETUP_PERMISSION_NEEDED, language),
                    SetupAction.REQUEST_PERMISSION, t(StringKey.SETUP_NOTIF_ALLOW, language))
            PermissionAccess.SETTINGS_ONLY ->
                row(RowStatus.BLOCKED, t(StringKey.SETUP_BLOCKED, language), SetupAction.OPEN_NOTIFICATION_SETTINGS, t(StringKey.SETUP_NOTIF_OPEN_SETTINGS, language),
                    t(StringKey.SETUP_NOTIF_BLOCKED_HELP, language))
        }
    }

    /** Main Phone only, and optional: never part of the guided setup. The user turns it on here (or on the screens that show names). */
    private fun contactsRow(i: SetupInput, language: AppLanguage): SetupRow {
        val why = t(StringKey.SETUP_CONTACTS_WHY, language)
        val title = t(StringKey.SETUP_CONTACTS_TITLE, language)
        fun row(status: RowStatus, text: String, action: SetupAction, label: String, help: String = "") =
            SetupRow(SetupItem.CONTACTS, title, Importance.OPTIONAL, status, text, why, help, action, label, i.contacts.askedBefore)
        return when (access(i.contacts)) {
            PermissionAccess.GRANTED -> row(RowStatus.OK, t(StringKey.SETUP_CONTACTS_ON, language), SetupAction.NONE, "")
            PermissionAccess.CAN_ASK ->
                row(RowStatus.OPTIONAL_OFF, t(StringKey.SETUP_CONTACTS_OFF, language), SetupAction.REQUEST_PERMISSION, t(StringKey.SETUP_CONTACTS_SHOW, language))
            PermissionAccess.SETTINGS_ONLY ->
                row(RowStatus.OPTIONAL_OFF, t(StringKey.SETUP_CONTACTS_OFF_BLOCKED, language), SetupAction.OPEN_APP_SETTINGS,
                    t(StringKey.SETUP_OPEN_APP_SETTINGS, language), t(StringKey.SETUP_SETTINGS_HELP, language))
        }
    }

    // ---------------------------------------------------------------- decisions

    /** A required or recommended item that still needs the user, and that the user can actually fix. */
    fun actionable(row: SetupRow): Boolean =
        row.importance != Importance.OPTIONAL && row.status != RowStatus.OK && row.status != RowStatus.UNAVAILABLE

    /**
     * What "Set up this phone" walks through: required and recommended items whose dialog was NEVER shown yet, in row order.
     * An item the user already denied is not asked again on its own; its row keeps a button for doing it by hand.
     */
    fun guidedQueue(rows: List<SetupRow>): List<SetupItem> = rows
        .filter {
            it.importance != Importance.OPTIONAL && !it.askedBefore &&
                (it.action == SetupAction.REQUEST_PERMISSION || it.action == SetupAction.REQUEST_CALL_ROLE)
        }
        .map { it.item }

    /** Required items missing because of a permission or role (not a switched-off Bluetooth adapter, which the home screens already show). */
    fun blockingRows(rows: List<SetupRow>): List<SetupRow> =
        rows.filter { it.importance == Importance.REQUIRED && it.status != RowStatus.OK && it.status != RowStatus.UNAVAILABLE && it.action.isPermissionFix }

    /** One line for the top of the screen and Settings. Stage 15: in [language] (Persian digits); English output is unchanged. */
    fun summaryLine(rows: List<SetupRow>, language: AppLanguage = AppLanguage.ENGLISH): String {
        val required = rows.count { it.importance == Importance.REQUIRED && it.status != RowStatus.OK && it.status != RowStatus.UNAVAILABLE }
        val recommended = rows.count { it.importance == Importance.RECOMMENDED && it.status != RowStatus.OK && it.status != RowStatus.UNAVAILABLE }
        return when {
            required > 0 -> Strings.text(
                if (required == 1) StringKey.SETUP_SUMMARY_REQUIRED_ONE else StringKey.SETUP_SUMMARY_REQUIRED_MANY,
                language, TextDirection.number(required, language))
            recommended > 0 -> Strings.text(
                if (recommended == 1) StringKey.SETUP_SUMMARY_RECOMMENDED_ONE else StringKey.SETUP_SUMMARY_RECOMMENDED_MANY,
                language, TextDirection.number(recommended, language))
            else -> t(StringKey.SETUP_SUMMARY_ALL_DONE, language)
        }
    }

    /** Text of the banner on the Main Phone home screen; empty when nothing blocks. */
    fun bannerText(rows: List<SetupRow>): String {
        val blocking = blockingRows(rows)
        return if (blocking.isEmpty()) "" else "Setup incomplete: " + blocking.joinToString(", ") { it.title }
    }

    private fun plural(n: Int) = if (n == 1) "item" else "items"
}
