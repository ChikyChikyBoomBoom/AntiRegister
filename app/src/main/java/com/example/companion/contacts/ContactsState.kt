package com.example.companion.contacts

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings

/** Where the optional Contacts permission stands, as far as the UI is concerned. */
enum class ContactsAccess {
    GRANTED,
    /** Not granted; the system dialog can still be shown. */
    CAN_ASK,
    /** Not granted and the system dialog will not appear again: only the app's system settings page can change it. */
    SETTINGS_ONLY
}

/** Pure functions (no Android types) so the Contacts decisions can be unit-tested on a plain JVM. */
object ContactsState {

    const val NOTICE_CAN_ASK =
        "Contact names and photos are off, so numbers are shown. Allow Contacts to see who is calling or texting. " +
            "Contacts are only read on this phone to look up a number; nothing is copied or uploaded."
    const val NOTICE_SETTINGS_ONLY =
        "Contact names and photos are off because Contacts access is turned off for this app. " +
            "Numbers are shown instead. You can allow it in the app's system settings."
    const val BUTTON_CAN_ASK = "Show contact names"
    const val BUTTON_SETTINGS = "Open app settings"
    const val NOT_USABLE = "that contact's number has characters the SIM Phone cannot use (only digits with an optional leading +)"
    const val NO_CONTACTS_APP = "No contacts app was found on this phone. Type the number instead."

    /**
     * [askedBefore]: the permission dialog was already shown once (stored in AppPrefs). [showRationale] is Android's
     * shouldShowRequestPermissionRationale: true after one denial, false before the first request and after "don't ask again".
     */
    fun access(granted: Boolean, askedBefore: Boolean, showRationale: Boolean): ContactsAccess = when {
        granted -> ContactsAccess.GRANTED
        !askedBefore || showRationale -> ContactsAccess.CAN_ASK
        else -> ContactsAccess.SETTINGS_ONLY
    }

    /** Stage 14: [language] is optional and defaults to English, so every existing call keeps its old text. Missing Persian -> English. */
    fun notice(a: ContactsAccess, language: AppLanguage = AppLanguage.ENGLISH): String = when (a) {
        ContactsAccess.GRANTED -> ""
        ContactsAccess.CAN_ASK -> Strings.text(StringKey.CONTACTS_NOTICE_CAN_ASK, language)
        ContactsAccess.SETTINGS_ONLY -> Strings.text(StringKey.CONTACTS_NOTICE_SETTINGS_ONLY, language)
    }

    fun buttonLabel(a: ContactsAccess, language: AppLanguage = AppLanguage.ENGLISH): String =
        Strings.text(if (a == ContactsAccess.SETTINGS_ONLY) StringKey.CONTACTS_BUTTON_OPEN_SETTINGS else StringKey.CONTACTS_BUTTON_SHOW_NAMES, language)

    /** Stage 28: shown (instead of a button) when permission is granted but the Contacts provider keeps failing. Numbers stay on screen. */
    fun unavailableNotice(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CONTACTS_NOTICE_UNAVAILABLE, language)

    /** Stage 28: the picker could not read the chosen contact / the chosen contact has no number. */
    fun pickUnreadable(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CONTACTS_PICK_UNREADABLE, language)
    fun pickNoNumber(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CONTACTS_PICK_NO_NUMBER, language)

    /** Stage 14: localized forms of [NO_CONTACTS_APP] / [NOT_USABLE] (they reuse the Stage 13 keys with the identical English). */
    fun noContactsApp(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.HOME_NO_CONTACTS_APP, language)
    fun notUsable(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.SMS_CONTACT_NOT_USABLE_REASON, language)

    /**
     * Should this string be looked up in Contacts at all? Phone-number-looking only: digits with an optional '+' and the usual
     * separators, at least 3 digits. Alphanumeric SMS senders ("MYBANK"), service codes and blanks are never sent to the provider.
     */
    fun isLookupCandidate(number: String?): Boolean {
        val t = number?.trim().orEmpty()
        if (t.isEmpty() || t.length > 64) return false
        if (!t.all { it.isDigit() || it in "+-.() \u00A0" }) return false
        return t.count { it.isDigit() } >= 3
    }

    /**
     * A contact's number as the SIM Phone accepts it: formatting characters removed, digits with an optional leading '+'.
     * Returns null when something else is in it (an extension, a pause, '*' or '#'): the number is never silently changed
     * into a different one.
     */
    fun dialable(raw: String?): String? {
        val cleaned = raw.orEmpty().filter { it !in " -.()\u00A0" }
        return cleaned.takeIf { DIALABLE.matches(it) }
    }

    private val DIALABLE = Regex("^\\+?[0-9]{1,32}$")

    /** Longest contact name drawn; a longer (corrupt) record is cut, never rejected. */
    const val MAX_NAME_LENGTH = 100

    /**
     * Stage 28: a contact name from the provider made safe to draw: control characters removed, runs of white space collapsed, cut at
     * [MAX_NAME_LENGTH]. null when nothing readable is left (blank, only control characters), so the number is shown instead.
     */
    fun cleanName(raw: String?): String? {
        if (raw == null) return null
        val sb = StringBuilder(minOf(raw.length, MAX_NAME_LENGTH + 8))
        var lastSpace = true                                         // also trims leading white space
        for (ch in raw) {
            if (Character.isISOControl(ch) || Character.isWhitespace(ch) || ch == '\u00A0') {
                if (!lastSpace) { sb.append(' '); lastSpace = true }
            } else { sb.append(ch); lastSpace = false }
            if (sb.length >= MAX_NAME_LENGTH + 1) break
        }
        val t = sb.toString().trim().take(MAX_NAME_LENGTH).trim()
        return t.takeIf { it.isNotEmpty() }
    }

    /** Stage 28: a photo address from the provider; only a non-blank content:// address is used, anything else means "no photo". */
    fun cleanPhotoUri(raw: String?): String? =
        raw?.trim()?.takeIf { it.length <= 2048 && it.startsWith("content://", ignoreCase = true) }

    /** Letter or digit for the avatar of a contact without a photo; "#" when the name has none. */
    fun initial(name: String?): String =
        name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    /** The number as a second line, only when a name is the main line. */
    fun secondaryNumber(number: String?, name: String?): String =
        if (!number.isNullOrBlank() && !name.isNullOrBlank() && number != name) number else ""
}
