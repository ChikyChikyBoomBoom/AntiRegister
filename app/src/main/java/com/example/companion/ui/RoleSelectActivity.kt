package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Toast
import com.example.companion.data.SettingsDiagnostics
import com.example.companion.data.Role
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.showLanguageDialog
import com.example.companion.i18n.tr

/**
 * First-run welcome: explains the two roles and lets the user choose one (Stage 18: changing the role later is done in Settings).
 * Choosing saves the role at once (it survives restarts) and continues to the Setup screen, where only the permissions of that
 * role are explained and asked for. Clears the back stack so a role change never leaves the other role's screens behind.
 *
 * Stage 15: the texts are in the selected language (English fallback), and the screen has the language button (the Stage 12 dialog Home
 * uses): this is the first screen of a new install, before Home exists, so a Persian reader could not otherwise switch before choosing
 * a role. The role choice itself is unchanged.
 */
class RoleSelectActivity : LocalizedActivity() {
    private var choosing = false      // Stage 29: a second tap while the first choice is being handled does nothing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = tr(StringKey.ONBOARD_TITLE)
        val root = vbox()
        // Stage 29: saved settings cannot be used on this phone right now (damaged or unavailable file): say so before the person chooses.
        // The choice still works for this run of the app (AppPrefs keeps it in memory).
        if (app.prefs.storageProblem) root.addView(errorText(SettingsIssue.STORAGE_UNAVAILABLE.text(AppLocale.current)))
        // Stage 15: the first screen of a new install, before Home exists, so it carries the language button (same Stage 12 dialog as
        // Home). It saves the choice and recreates this screen; nothing else on the screen depends on it.
        root.addView(button(tr(StringKey.LANGUAGE_CURRENT, AppLocale.current.nativeName)) { showLanguageDialog(app.prefs) })
        root.addView(label(tr(StringKey.ONBOARD_HEADING)).apply { textSize = 22f; setTypeface(null, Typeface.BOLD) })
        root.addView(label(tr(StringKey.ONBOARD_INTRO)))
        root.addView(label(tr(StringKey.ROLE_SIM_PHONE)).apply { textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(24), 0, dp(4)) })
        root.addView(label(tr(StringKey.ONBOARD_SIM_DESC)))
        root.addView(button(tr(StringKey.ONBOARD_SIM_BUTTON)) { choose(Role.SIM_PHONE) })
        root.addView(label(tr(StringKey.ROLE_MAIN_PHONE)).apply { textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(24), 0, dp(4)) })
        root.addView(label(tr(StringKey.ONBOARD_MAIN_DESC)))
        root.addView(button(tr(StringKey.ONBOARD_MAIN_BUTTON)) { choose(Role.MAIN_PHONE) })
        root.addView(label(tr(StringKey.ONBOARD_NEXT))
            .apply { textSize = 14f; setTextColor(UiColors.TEXT_HINT); setPadding(0, dp(24), 0, dp(8)) })
        setContentView(ScrollView(this).apply { addView(constrainWidth(root)) })      // Stage 22: same readable column as Home / Settings
    }

    private fun choose(r: Role) {
        if (choosing) return
        // Stage 29: the role and "setup not finished" are saved in ONE step (SettingsActions.chooseRole), so an interruption never leaves a role
        // that inherits an old "setup finished". If it cannot be saved, nothing changes and the person can tap again.
        val result = try { SettingsActions(app.prefs, app.connection).chooseRole(r) } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.ONBOARDING_UI, "choose role", e); SettingsActions.RoleChangeResult.SAVE_FAILED
        }
        if (result == SettingsActions.RoleChangeResult.SAVE_FAILED) {
            Toast.makeText(this, SettingsIssue.ONBOARD_SAVE_FAILED.text(AppLocale.current), Toast.LENGTH_LONG).show()
            return
        }
        choosing = true
        try {
            startActivity(Intent(this, startActivityFor(r, setupCompleted = false)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (e: Exception) {
            // The role IS saved: the next start of the app (MainActivity) opens the Setup screen. Stay here so the person is not left on a blank screen.
            SettingsDiagnostics.record(SettingsDiagnostics.Area.ONBOARDING_UI, "open setup after role choice", e)
            choosing = false
            Toast.makeText(this, SettingsIssue.OPEN_FAILED.text(AppLocale.current), Toast.LENGTH_LONG).show()
            return
        }
        finish()
    }
}
