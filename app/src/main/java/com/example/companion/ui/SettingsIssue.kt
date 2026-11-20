package com.example.companion.ui

import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings

/**
 * Stage 29: the problems the Settings screen and the first-run screens can tell the person about, each tied to ONE catalog text
 * (English + Persian, English fallback through [Strings]). Pure Kotlin. The texts say what happened and what to do; they never contain
 * an exception name, a stack trace or a stored value (the technical detail goes to [com.example.companion.data.SettingsDiagnostics]).
 */
enum class SettingsIssue(val key: StringKey) {
    /** One setting could not be written (it still applies until the app is closed). */
    SAVE_FAILED(StringKey.SETTINGS_ERR_SAVE_FAILED),
    /** The preferences file cannot be used at all; every choice only lasts for this run of the app. */
    STORAGE_UNAVAILABLE(StringKey.SETTINGS_ERR_STORAGE_UNAVAILABLE),
    /** A screen could not be drawn from the saved data; it shows what it can. */
    LOAD_FAILED(StringKey.SETTINGS_ERR_LOAD_FAILED),
    /** The role was not switched because the new role could not be saved. */
    ROLE_SAVE_FAILED(StringKey.SETTINGS_ERR_ROLE_SAVE_FAILED),
    /** The selected device could not be cleared, so the device list was not opened. */
    DEVICE_SAVE_FAILED(StringKey.SETTINGS_ERR_DEVICE_SAVE_FAILED),
    /** Another screen could not be opened. */
    OPEN_FAILED(StringKey.SETTINGS_ERR_OPEN_FAILED),
    /** A button's action failed unexpectedly. */
    ACTION_FAILED(StringKey.SETTINGS_ERR_ACTION_FAILED),
    /** The language was applied but could not be saved. */
    LANGUAGE_SAVE_FAILED(StringKey.LANGUAGE_ERR_SAVE_FAILED),
    /** Welcome screen: the chosen role could not be saved. */
    ONBOARD_SAVE_FAILED(StringKey.ONBOARD_ERR_SAVE_FAILED),
    /** Setup screen: the permission list could not be read. */
    SETUP_LOAD_FAILED(StringKey.SETUP_ERR_LOAD_FAILED),
    /** Setup screen: "setup finished" could not be saved (it may be shown again next time). */
    SETUP_NOT_REMEMBERED(StringKey.SETUP_ERR_NOT_REMEMBERED);

    fun text(language: AppLanguage): String = Strings.text(key, language)
}
