package com.example.companion.i18n

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.view.View
import com.example.companion.data.AppPrefs

/**
 * Stage 12: the one place that knows which language is selected.
 *
 * - [current] is read by [tr] and by anything that builds text (screens, notifications, state objects).
 * - It is loaded once from [AppPrefs] in CompanionApp.onCreate and changed only through [set].
 * - Default is English. Nothing in the app changes until a screen calls [tr] (Stage 12 moves no screen over).
 *
 * Main-thread use; [current] is volatile so a notification built on another thread still reads a valid value.
 */
object AppLocale {
    @Volatile var current: AppLanguage = AppLanguage.DEFAULT
        private set

    /** Called once at startup. */
    fun load(prefs: AppPrefs) { current = AppLanguage.fromCode(prefs.languageCode) }

    /** Saves and applies [language]. Screens already open must be redrawn (see [showLanguageDialog], which recreates the activity). */
    fun set(prefs: AppPrefs, language: AppLanguage): Boolean {
        // Stage 29: the language applies for this run even if saving fails; the result tells the caller to say so (it returns false then).
        val saved = prefs.saveLanguage(language.code)
        current = language
        return saved
    }

    /** For tests and previews: sets the language without touching preferences. */
    internal fun setForTest(language: AppLanguage) { current = language }

    /** The selected language's direction. */
    val isRtl: Boolean get() = current.isRtl
}

/** Text for [key] in the selected language (English fallback). Use this for every new or migrated user-facing text. */
fun tr(key: StringKey): String = Strings.text(key, AppLocale.current)

/** Same, with [java.util.Formatter] arguments ("%1$s"). */
fun tr(key: StringKey, vararg args: Any?): String = Strings.text(key, AppLocale.current, *args)

/**
 * Stage 16: sets the layout direction of a screen's root from the selected app language (Persian -> RTL, English -> LTR), whatever the
 * phone's system language is. Called by [LocalizedActivity.setContentView] for every screen. The manifest declares
 * `android:supportsRtl="true"` since Stage 16, so Android honours it.
 */
fun View.applyAppLayoutDirection() {
    layoutDirection = if (AppLocale.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
}

/**
 * Stage 16: gives a screen the chosen app language as its resource configuration (used by [LocalizedActivity.attachBaseContext]), so
 * the ActionBar, dialogs and system widgets take their layout direction from the app language, not the phone's.
 *
 * Uses only platform APIs (createConfigurationContext). The base context is returned unchanged when it already has the right
 * language and direction (the usual English-on-English-phone case), so nothing changes there.
 */
object LocalizedContext {
    fun wrap(base: Context, language: AppLanguage = AppLocale.current): Context {
        val current = base.resources.configuration
        val wantedDirection = if (language.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        if (current.layoutDirection == wantedDirection && current.locales[0].language == language.toLocale().language) return base
        val config = Configuration(current)
        val locale = language.toLocale()
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

/**
 * Language chooser (used on Home and the welcome screen). Lists each language by its own
 * name, saves the choice and recreates [this] so the screen redraws (direction included); screens in the back stack recreate themselves in
 * [LocalizedActivity.onStart] when they come back.
 */
fun Activity.showLanguageDialog(prefs: AppPrefs, onChanged: () -> Unit = {}) {
    val languages = AppLanguage.values()
    val names = languages.map { it.nativeName }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle(tr(StringKey.LANGUAGE_TITLE))
        .setSingleChoiceItems(names, languages.indexOf(AppLocale.current)) { dialog, which ->
            val chosen = languages[which]
            dialog.dismiss()
            if (chosen != AppLocale.current) {
                val saved = AppLocale.set(prefs, chosen)
                if (!saved) {   // Stage 29: say so in the NEW language (AppLocale.current is already the chosen one); the toast outlives the recreate
                    try { android.widget.Toast.makeText(applicationContext, tr(StringKey.LANGUAGE_ERR_SAVE_FAILED), android.widget.Toast.LENGTH_LONG).show() }
                    catch (e: Exception) { com.example.companion.data.SettingsDiagnostics.record(com.example.companion.data.SettingsDiagnostics.Area.LANGUAGE, "show language save notice", e) }
                }
                onChanged()
                recreate()
            }
        }
        .setNegativeButton(tr(StringKey.ACTION_CANCEL), null)
        .show()
}
