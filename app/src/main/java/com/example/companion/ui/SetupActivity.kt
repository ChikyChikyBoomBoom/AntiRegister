package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.CallRole
import com.example.companion.data.SettingsDiagnostics
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.localizedLabel
import com.example.companion.i18n.tr
import com.example.companion.setup.RowStatus
import com.example.companion.setup.SetupAction
import com.example.companion.setup.SetupItem
import com.example.companion.setup.SetupRow
import com.example.companion.setup.SetupState

/**
 * Stage 22: first-run setup AND the Setup Status screen (the same screen, reached from Settings, the diagnostics screen and the
 * Main Phone home banner later on).
 *
 * Every permission is asked from here, always in the same order: the row explains it, the user taps, a short dialog repeats the
 * reason, and only then does Android's dialog appear. A permission the user already declined is never asked again on its own
 * ("Set up this phone" skips it); its row keeps a button, and when Android will no longer show the dialog the button opens the app's
 * system settings page instead. No connection, call, SMS or audio code is touched; this screen only reads permission state.
 *
 * Stage 15: every text (screen text, row texts built by [SetupState], dialogs, toasts) is in the selected language with an English
 * fallback. The order of the steps, what is asked and when it is asked are unchanged.
 */
class SetupActivity : LocalizedActivity(), BluetoothManager.Listener {
    private lateinit var introV: TextView
    private lateinit var summaryV: TextView
    private lateinit var rowsBox: LinearLayout
    private lateinit var guidedBtn: android.widget.Button
    private lateinit var doneBtn: android.widget.Button
    private lateinit var noticeV: TextView          // Stage 29: "permission list could not be read" / "settings cannot be saved" (hidden when all is well)

    private val shownLanguage = AppLocale.current   // Stage 15: the language this instance was built in
    private val lang: AppLanguage get() = AppLocale.current
    private var firstRun = false
    private var guided = mutableListOf<SetupItem>()   // items still to walk through while "Set up this phone" is running
    private var inGuided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val role = app.prefs.role
        if (role == null) { startActivity(Intent(this, RoleSelectActivity::class.java)); finish(); return }
        title = tr(StringKey.SETUP_TITLE)
        firstRun = !app.prefs.setupCompleted
        // Stage 29: if the permission state cannot be read ([safeRows] = null) the screen is still shown (with a notice and the Continue button),
        // so a first-time user is never stuck and never crashes here.
        val firstRows = safeRows()
        if (firstRun && firstRows != null && firstRows.none { SetupState.actionable(it) }) { finishSetup(); return }   // nothing to ask: straight to the app

        val root = vbox()
        noticeV = errorText().apply { show(false); accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE }
        introV = label().apply { textSize = 15f }
        summaryV = label().apply { textSize = 16f; setTypeface(null, Typeface.BOLD); accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE }
        guidedBtn = button(tr(StringKey.SETUP_GUIDED)) { startGuided() }
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        doneBtn = button(if (firstRun) tr(StringKey.SETUP_CONTINUE) else tr(StringKey.SETUP_DONE)) { if (firstRun) finishSetup() else finish() }
        root.addView(noticeV); root.addView(introV); root.addView(summaryV); root.addView(guidedBtn)
        root.addView(header(tr(StringKey.SETTINGS_SETUP_STATUS))); root.addView(rowsBox)
        root.addView(doneBtn)
        setContentView(ScrollView(this).apply { addView(root) })
        introV.text = if (firstRun) tr(StringKey.SETUP_INTRO_FIRST_RUN, role.localizedLabel(lang))
        else tr(StringKey.SETUP_INTRO_REVIEW)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        if (::rowsBox.isInitialized) {
            // Stage 15: if the language was changed while this screen was waiting in the back stack, redraw it in the new language.
            if (shownLanguage != AppLocale.current) { recreate(); return }
            app.bluetooth.addListener(this); refresh()
        }
    }
    override fun onStop() { app.bluetooth.removeListener(this); super.onStop() }
    override fun onResume() { super.onResume(); if (::rowsBox.isInitialized) refresh() }   // e.g. back from Android's settings page
    override fun onAdapterStateChanged(enabled: Boolean) = refresh()

    // ---------------------------------------------------------------- drawing

    private fun refresh() {
        if (!::rowsBox.isInitialized) return
        val rows = safeRows(lang)
        if (rows == null) {          // Stage 29: nothing to list; say so and keep Continue / Done working
            summaryV.text = ""; guidedBtn.show(false); rowsBox.removeAllViews()
            noticeV.setTextOrHide(SettingsIssue.SETUP_LOAD_FAILED.text(lang))
            return
        }
        noticeV.setTextOrHide(if (app.prefs.storageProblem) SettingsIssue.STORAGE_UNAVAILABLE.text(lang) else "")
        try {
            summaryV.text = SetupState.summaryLine(rows, lang)
            guidedBtn.show(SetupState.guidedQueue(rows).isNotEmpty())
            rowsBox.removeAllViews()
            rows.forEach { rowsBox.addView(rowView(it)) }
        } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.ONBOARDING_UI, "draw setup rows", e)
            noticeV.setTextOrHide(SettingsIssue.SETUP_LOAD_FAILED.text(lang))
        }
    }

    /**
     * Stage 29: the setup rows, or null when Android (or a system service) fails while they are read. The failure is recorded for developers
     * only. Callers treat null as "nothing known": no step is asked, nothing is marked as asked, and the person can still continue.
     */
    private fun safeRows(language: AppLanguage = AppLanguage.ENGLISH): List<SetupRow>? = try {
        readSetupRows(language)
    } catch (e: Exception) {
        SettingsDiagnostics.record(SettingsDiagnostics.Area.ONBOARDING_UI, "read setup rows", e)
        null
    }

    private fun rowView(r: SetupRow) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(label(r.title).apply { textSize = 17f; setTypeface(null, Typeface.BOLD) })
        // The status word is always shown; colour is only a second cue.
        addView(label(statusWord(r) + ": " + r.statusText).apply { textSize = 15f; setTextColor(colorFor(r.status)) })
        addView(label(r.why).apply { textSize = 13f; setTextColor(UiColors.TEXT_SECONDARY) })
        if (r.status == RowStatus.BLOCKED && r.blockedHelp.isNotEmpty())
            addView(label(r.blockedHelp).apply { textSize = 13f; setTextColor(UiColors.ERROR) })
        if (r.action != SetupAction.NONE) addView(button(r.actionLabel) { onRowAction(r) }.apply { isAllCaps = false })
    }

    private fun statusWord(r: SetupRow) = tr(when (r.status) {
        RowStatus.OK -> StringKey.SETUP_WORD_READY
        RowStatus.NEEDS_ACTION -> StringKey.SETUP_WORD_NEEDS
        RowStatus.BLOCKED -> StringKey.SETUP_WORD_BLOCKED
        RowStatus.OPTIONAL_OFF -> StringKey.SETUP_WORD_OFF
        RowStatus.UNAVAILABLE -> StringKey.SETUP_WORD_UNAVAILABLE
    })

    private fun colorFor(s: RowStatus) = when (s) {
        RowStatus.OK -> UiColors.SUCCESS
        RowStatus.NEEDS_ACTION -> UiColors.WARNING
        RowStatus.BLOCKED -> UiColors.ERROR
        RowStatus.OPTIONAL_OFF, RowStatus.UNAVAILABLE -> UiColors.TEXT_HINT
    }

    private fun header(t: String) = label(t.uppercase()).apply {
        textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(UiColors.TEXT_HINT); setPadding(0, dp(24), 0, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- actions

    private fun onRowAction(r: SetupRow) {
        inGuided = false; guided.clear()                      // a manual tap ends any guided run
        when (r.action) {
            SetupAction.REQUEST_PERMISSION, SetupAction.REQUEST_CALL_ROLE -> explainThenRequest(r, onSkip = null)
            SetupAction.OPEN_APP_SETTINGS -> openAppSettings()
            SetupAction.OPEN_NOTIFICATION_SETTINGS -> openSafely(NotificationAccess.settingsIntent(this))
            SetupAction.ENABLE_BLUETOOTH -> openSafely(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            SetupAction.NONE -> Unit
        }
    }

    private fun startGuided() {
        val rows = safeRows()
        if (rows == null) { refresh(); return }      // Stage 29: state unreadable: ask nothing (refresh shows the notice)
        guided = SetupState.guidedQueue(rows).toMutableList()
        inGuided = true
        nextGuided()
    }

    /** Re-reads the state before each step: an earlier answer (or a change made meanwhile) can make a later item unnecessary. */
    private fun nextGuided() {
        while (guided.isNotEmpty()) {
            val item = guided.removeAt(0)
            val row = (safeRows(lang) ?: break).firstOrNull { it.item == item } ?: continue   // Stage 29: unreadable state ends the guided run
            if (row.askedBefore || (row.action != SetupAction.REQUEST_PERMISSION && row.action != SetupAction.REQUEST_CALL_ROLE)) continue
            explainThenRequest(row, onSkip = { nextGuided() })
            return
        }
        inGuided = false
        refresh()
    }

    /** The reason first, then Android's own dialog. "Not now" asks nothing and records nothing, so the row stays available. */
    private fun explainThenRequest(r: SetupRow, onSkip: (() -> Unit)?) {
        AlertDialog.Builder(this)
            .setTitle(r.title)
            .setMessage(r.why)
            .setPositiveButton(tr(StringKey.SETUP_CONTINUE)) { _, _ -> request(r.item) }
            .setNegativeButton(tr(StringKey.SETUP_NOT_NOW)) { _, _ -> onSkip?.invoke() ?: refresh() }
            .setOnCancelListener { onSkip?.invoke() ?: refresh() }
            .show()
    }

    private fun request(item: SetupItem) {
        // Stage 7: second line of defence (the rows already have no button): never ask for CALL_PHONE or the default phone app role.
        // Stage 25: same for the SIM Phone's Notifications row, which only serves call notifications (see isCallingOnlyItem).
        if (!CallFeatures.isCallingEnabled() && isCallingOnlyItem(item, app.prefs.role)) { showCallingDisabled(); afterAnswer(); return }
        if (item == SetupItem.CALL_ROLE) { requestCallRole(); return }
        val missing = setupPermissions(item).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { afterAnswer(); return }
        markSetupAsked(item)                                  // before the dialog: even if this screen is recreated, it is not asked twice
        requestPermissions(missing.toTypedArray(), REQ_BASE + item.ordinal)
    }

    private fun requestCallRole() {
        if (CallRole.isHeld(this)) { afterAnswer(); return }
        val i = CallRole.requestIntent(this)
        if (i == null) { toast(tr(StringKey.SETUP_NO_ROLE_DIALOG)); afterAnswer(); return }
        markSetupAsked(SetupItem.CALL_ROLE)
        startActivityForResult(i, REQ_ROLE)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        val item = SetupItem.values().firstOrNull { REQ_BASE + it.ordinal == code } ?: return
        if (item == SetupItem.CONTACTS) app.contacts.invalidate()     // same as the Contacts prompt: forget any cached "no contact" answers
        afterAnswer()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE) { app.hostCalls.resync(); afterAnswer() }
    }

    private fun afterAnswer() { refresh(); if (inGuided) nextGuided() }

    private fun finishSetup() {
        // Stage 29: the person is never kept on this screen because "finished" could not be saved. In that case the app still goes on (the value
        // is kept in memory for this run) and the person is told that Setup may appear again; it closes by itself when nothing is missing.
        val saved = try { app.prefs.saveSetupCompleted(true) } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.ONBOARDING_UI, "save setup completed", e); false
        }
        if (!saved) toast(SettingsIssue.SETUP_NOT_REMEMBERED.text(lang))
        try {
            startActivity(Intent(this, homeActivityFor(app.prefs.role)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.ONBOARDING_UI, "open home after setup", e)
            toast(SettingsIssue.OPEN_FAILED.text(lang))
        }
        finish()
    }

    private fun openAppSettings() = openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun openSafely(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toast(tr(StringKey.SETUP_NO_SETTINGS_PAGE)) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_BASE = 2200      // + SetupItem ordinal
        const val REQ_ROLE = 2300
    }
}
