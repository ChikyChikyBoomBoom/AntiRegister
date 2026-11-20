package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.Role
import com.example.companion.data.SettingsDiagnostics
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.localizedLabel
import com.example.companion.i18n.showLanguageDialog
import com.example.companion.i18n.tr
import com.example.companion.protocol.CallState
import com.example.companion.setup.SetupState

/**
 * Stage 18: Settings for both phones (role, Bluetooth device, connection, auto-reconnect, app info).
 *
 * Like the other screens it owns no Bluetooth state: it reads [ConnectionManager] and the local [com.example.companion.data.AppPrefs]
 * and redraws when they change. Decisions live in [SettingsState]; the two configuration changes live in [SettingsActions].
 *
 * Stage 15: every text comes from the central catalog in the selected language (English fallback). Nothing about what the buttons do,
 * when they are enabled, or the order of the checks was changed. The language is switched on Home (Stage 13); this screen reads it
 * when it is created and redraws itself if it differs when the screen comes back (see onStart).
 */
@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity : LocalizedActivity(), ConnectionManager.Listener, BluetoothManager.Listener {
    private lateinit var roleV: TextView
    private lateinit var dot: StatusIndicator
    private lateinit var connCard: LinearLayout
    private lateinit var statusV: TextView
    private lateinit var detailV: TextView
    private lateinit var deviceHeaderV: TextView
    private lateinit var deviceV: TextView
    private lateinit var hintV: TextView
    private lateinit var connectBtn: Button
    private lateinit var changeDeviceBtn: Button
    private lateinit var discoverableBtn: Button
    private lateinit var changeRoleBtn: Button
    private lateinit var autoSwitch: Switch
    private lateinit var autoNoteV: TextView
    private lateinit var setupV: TextView
    private lateinit var noticeV: TextView      // Stage 29: one place for "settings could not be saved / read" (hidden when all is well)

    private val shownLanguage = AppLocale.current   // Stage 15: the language this instance was built in
    private val lang get() = AppLocale.current
    private var updating = false          // true while refresh() sets the switch, so that is not mistaken for a tap
    private val actions by lazy { SettingsActions(app.prefs, app.connection) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = tr(StringKey.HOME_TILE_SETTINGS)
        if (app.prefs.role == null) {      // not set up yet: the role picker is the right screen
            startActivity(Intent(this, RoleSelectActivity::class.java)); finish(); return
        }

        val root = vbox()
        noticeV = errorText().apply { show(false); accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(noticeV)

        // ---- Role
        root.addView(header(tr(StringKey.SETTINGS_HEADER_ROLE)))
        roleV = label().apply { textSize = 18f }
        changeRoleBtn = button(tr(StringKey.SETTINGS_CHANGE_ROLE)) { onChangeRole() }
        root.addView(roleV); root.addView(changeRoleBtn)

        // ---- Connection
        root.addView(header(tr(StringKey.SETTINGS_HEADER_CONNECTION)))
        dot = StatusIndicator(this, 14)
        statusV = label().apply {
            textSize = 22f; setTypeface(null, Typeface.BOLD); setPaddingRelative(dp(8), 0, 0, 0)
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        detailV = label().apply { textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY) }
        deviceHeaderV = label().apply { textSize = 13f; setTextColor(UiColors.TEXT_HINT); setPadding(0, dp(8), 0, 0) }
        deviceV = label().apply { textSize = 17f }
        connCard = card().apply {
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(dot); addView(statusV)
            })
            addView(detailV); addView(deviceHeaderV); addView(deviceV)
        }
        connectBtn = styledButton(tr(StringKey.SETTINGS_CONNECT), ButtonStyle.PRIMARY) { onConnect() }
        hintV = hintText()
        root.addView(connCard)
        root.addView(connectBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(hintV)

        // ---- Device
        changeDeviceBtn = button(tr(StringKey.SETTINGS_CHANGE_DEVICE)) { onChangeDevice() }
        discoverableBtn = button(tr(StringKey.SETTINGS_PAIR_ANOTHER)) { onDiscoverable() }
        root.addView(changeDeviceBtn); root.addView(discoverableBtn)

        // ---- Setup (Stage 22): permission status for this role; every permission is asked from the Setup screen
        root.addView(header(tr(StringKey.SETTINGS_HEADER_SETUP)))
        setupV = label().apply { textSize = 15f }
        root.addView(setupV)
        root.addView(button(tr(StringKey.SETTINGS_SETUP_STATUS)) { openSetup() })

        // ---- Auto reconnect
        root.addView(header(tr(StringKey.SETTINGS_HEADER_RECONNECT)))
        autoSwitch = Switch(this).apply {
            text = tr(StringKey.SETTINGS_AUTO_RECONNECT)
            textSize = 16f
            setOnCheckedChangeListener { _, checked -> if (!updating) onAutoReconnectChanged(checked) }
        }
        autoNoteV = hintText().apply { textSize = 14f }
        root.addView(autoSwitch); root.addView(autoNoteV)

        // ---- Language (Stage 20): the same chooser as Home and the welcome screen. It saves the choice and recreates this screen;
        // Home and the other screens in the back stack redraw themselves when they come back (LocalizedActivity.onStart).
        root.addView(header(tr(StringKey.LANGUAGE_TITLE)))
        root.addView(button(tr(StringKey.LANGUAGE_CURRENT, AppLocale.current.nativeName)) { showLanguageDialog(app.prefs) })
        root.addView(hintText(StringKey.LANGUAGE_HINT))

        // ---- Diagnostics (the previous Settings screen, still the way to the audio / HFP / ping tools)
        root.addView(header(tr(StringKey.SETTINGS_HEADER_ADVANCED)))
        if (DiagnosticScreens.isVisible()) root.addView(button(tr(StringKey.SETTINGS_DIAG_REPORT)) { startActivity(Intent(this, DiagnosticsActivity::class.java)) })   // Stage 23; hidden from normal users while DiagnosticScreens is off
        root.addView(button(tr(StringKey.SETTINGS_CONN_DIAG)) {
            startActivity(Intent(this, StatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })

        // ---- About
        root.addView(header(tr(StringKey.SETTINGS_HEADER_ABOUT)))
        val (versionName, versionCode) = versionInfo()
        val about = card()
        SettingsState.appInfo(versionName, versionCode, packageName, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            "${Build.MANUFACTURER} ${Build.MODEL}", app.bluetooth.localName, lang).forEach { (k, v) ->
            about.addView(label("$k: $v").apply { textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY) })
        }
        root.addView(about)

        setContentView(ScrollView(this).apply { addView(constrainWidth(root)) })
    }

    // ---------------------------------------------------------------- actions

    private fun onConnect() {
        val cm = app.connection
        val role = app.prefs.role ?: return
        // Stage 29: nothing a tap triggers may crash the screen; a failure is recorded for developers and the person gets a clear text.
        try {
            if (SettingsState.isActive(cm.state)) { cm.userStop(); return }       // Disconnect (also stops a scan); persists "user stopped"
            if (!BtPermissions.granted(this, role)) { openSetup(); return }
            cm.start()
        } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.SETTINGS_UI, "connect / disconnect tap", e)
            toast(SettingsIssue.ACTION_FAILED.text(lang))
        }
    }

    /** Stage 29: the auto-reconnect switch. If the value could not be saved it still applies until the app is closed, and the person is told. */
    private fun onAutoReconnectChanged(checked: Boolean) {
        val saved = try { app.prefs.saveAutoReconnect(checked) } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.SETTINGS_UI, "save auto reconnect", e); false
        }
        if (!saved) toast(SettingsIssue.SAVE_FAILED.text(lang))
        refresh()      // redraw from what the app will really use now (stored value, or the in-memory one when saving failed)
    }

    private fun onChangeRole() {
        val current = app.prefs.role ?: return
        SettingsState.roleChangeBlock(callInProgress(), lang)?.let { toast(it); return }
        val target = SettingsState.otherRole(current)
        AlertDialog.Builder(this)
            .setTitle(tr(StringKey.SETTINGS_SWITCH_TITLE, target.localizedLabel(lang)))
            .setMessage(tr(StringKey.SETTINGS_SWITCH_BODY, target.localizedLabel(lang)))
            .setPositiveButton(tr(StringKey.SETTINGS_SWITCH_CONFIRM)) { _, _ ->
                // A call could have started while the dialog was open.
                SettingsState.roleChangeBlock(callInProgress(), lang)?.let { toast(it); return@setPositiveButton }
                // Stage 29: the new role is saved in one step; if that fails the old role stays, this screen stays, and the person is told.
                val result = try { actions.switchRole(target) } catch (e: Exception) {
                    SettingsDiagnostics.record(SettingsDiagnostics.Area.ROLE_CHANGE, "switch role", e); SettingsActions.RoleChangeResult.SAVE_FAILED
                }
                if (result == SettingsActions.RoleChangeResult.SAVE_FAILED) { toast(SettingsIssue.ROLE_SAVE_FAILED.text(lang)); refresh(); return@setPositiveButton }
                if (result == SettingsActions.RoleChangeResult.CHANGED) {
                    try {
                        startActivity(Intent(this, startActivityFor(target, setupCompleted = false)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    } catch (e: Exception) {
                        // The role IS saved; the next start of the app opens the right screen (MainActivity reads it).
                        SettingsDiagnostics.record(SettingsDiagnostics.Area.ROLE_CHANGE, "open screen after role change", e)
                        toast(SettingsIssue.OPEN_FAILED.text(lang))
                    }
                }
                finish()
            }
            .setNegativeButton(tr(StringKey.ACTION_CANCEL), null)
            .show()
    }

    private fun onChangeDevice() {
        val role = app.prefs.role
        val bt = app.bluetooth
        // Check everything the picker needs BEFORE clearing the selection, so a refusal never loses the saved device.
        when (SettingsState.deviceChangeBlock(role, callInProgress(), bt.isSupported, bt.isEnabled, BtPermissions.granted(this, role))) {
            DeviceChangeBlock.NONE -> Unit
            DeviceChangeBlock.NEEDS_PERMISSION -> { openSetup(); return }
            DeviceChangeBlock.NOT_MAIN_PHONE -> { toast(tr(StringKey.SETTINGS_BLOCK_NOT_MAIN)); return }
            DeviceChangeBlock.CALL_IN_PROGRESS -> { toast(tr(StringKey.SETTINGS_BLOCK_DEVICE_CALL)); return }
            DeviceChangeBlock.BLUETOOTH_UNSUPPORTED -> { toast(tr(StringKey.HOME_DETAIL_BT_UNSUPPORTED)); return }
            DeviceChangeBlock.BLUETOOTH_OFF -> { toast(tr(StringKey.SETTINGS_BLOCK_BT_OFF)); return }
        }
        val name = app.prefs.deviceName?.takeIf { it.isNotBlank() } ?: app.prefs.deviceAddress
        if (name == null) { openPicker(); return }     // nothing selected, nothing to clear
        AlertDialog.Builder(this)
            .setTitle(tr(StringKey.SETTINGS_CHANGE_DEVICE_TITLE))
            .setMessage(tr(StringKey.SETTINGS_CHANGE_DEVICE_BODY, TextDirection.isolate(name, lang)))
            .setPositiveButton(tr(StringKey.SETTINGS_CHANGE_DEVICE_CONFIRM)) { _, _ -> openPicker() }
            .setNegativeButton(tr(StringKey.ACTION_CANCEL), null)
            .show()
    }

    private fun openPicker() {
        // Stage 29: disconnect + clear the previous selection in one commit. If clearing fails the list is not opened (the old selection is
        // still saved, so the screen and the saved data agree) and the person is told.
        val cleared = try { actions.beginDeviceChangeSafely() } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.ROLE_CHANGE, "clear selected device", e); false
        }
        if (!cleared) { toast(SettingsIssue.DEVICE_SAVE_FAILED.text(lang)); refresh(); return }
        openScreen(Intent(this, DevicePickerActivity::class.java))
    }

    /** SIM Phone: the way to let a different Main Phone pair. Same system dialog as on the diagnostics screen. */
    private fun onDiscoverable() {
        val role = app.prefs.role ?: return
        if (!BtPermissions.granted(this, role)) { openSetup(); return }
        openScreen(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
    }

    /** Stage 22: Bluetooth permission is explained and asked in one place, the Setup screen (which also opens Android settings if it was blocked). */
    private fun openSetup() = openScreen(Intent(this, SetupActivity::class.java))

    /** Stage 29: opens another screen; a missing or refused screen gives a clear text instead of a crash. */
    private fun openScreen(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.SETTINGS_UI, "open screen", e)
            toast(SettingsIssue.OPEN_FAILED.text(lang))
        }
    }

    private fun callInProgress(): Boolean {
        val state: CallState? = when (app.prefs.role) {
            Role.SIM_PHONE -> app.callManager.snapshot.state
            Role.MAIN_PHONE -> app.clientCalls.snapshot?.state
            null -> null
        }
        return SettingsState.callInProgress(state)
    }

    // ---------------------------------------------------------------- lifecycle / observing

    override fun onStart() {
        super.onStart()
        // Stage 15: if the language was changed while this screen was waiting in the back stack, redraw it in the new language.
        if (::roleV.isInitialized && shownLanguage != AppLocale.current) { recreate(); return }
        app.connection.addListener(this); app.bluetooth.addListener(this)
        refresh()
    }

    override fun onStop() {
        app.connection.removeListener(this); app.bluetooth.removeListener(this)
        super.onStop()
    }

    override fun onResume() { super.onResume(); if (::roleV.isInitialized) refresh() }   // e.g. back from the device picker

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()
    override fun onInfoChanged() = refresh()
    override fun onAdapterStateChanged(enabled: Boolean) = refresh()

    // ---------------------------------------------------------------- drawing

    private fun refresh() {
        if (!::roleV.isInitialized) return    // role was missing: onCreate finished before the views were built
        // Stage 29: an unexpected state (damaged saved data, a system call that fails) must not crash the screen. The failure is recorded for
        // developers and the person sees a short notice; the parts that were already drawn stay.
        val issue = try {
            draw()
            if (app.prefs.storageProblem) SettingsIssue.STORAGE_UNAVAILABLE else null
        } catch (e: Exception) {
            SettingsDiagnostics.record(SettingsDiagnostics.Area.SETTINGS_UI, "draw settings", e)
            SettingsIssue.LOAD_FAILED
        }
        noticeV.setTextOrHide(issue?.text(lang) ?: "")
    }

    private fun draw() {
        val role = app.prefs.role
        val cm = app.connection
        val ui = SettingsState.from(
            role = role,
            state = cm.state,
            connectedName = cm.connectedName,
            savedName = app.prefs.deviceName,
            savedAddress = app.prefs.deviceAddress,
            retryAttempt = cm.retryAttempt,
            lastError = cm.lastError,
            language = lang
        )
        roleV.text = ui.roleLine
        changeRoleBtn.text = role?.let { tr(StringKey.SETTINGS_SWITCH_TO, SettingsState.otherRole(it).localizedLabel(lang)) } ?: tr(StringKey.SETTINGS_CHANGE_ROLE)

        statusV.text = ui.statusLine
        val link = HomeStatus.linkStatus(cm.state)
        dot.set(link.color(), UiPolish.isBusy(cm.state))      // spinner while connecting, scanning or waiting
        connCard.setCardTint(link.tint())
        detailV.text = ui.detail; detailV.show(ui.detail.isNotEmpty())
        deviceHeaderV.text = if (role == Role.SIM_PHONE) tr(StringKey.SETTINGS_DEVICE_HEADER_SIM) else tr(StringKey.SETTINGS_DEVICE_HEADER_MAIN)
        deviceV.text = ui.deviceLine
        connectBtn.text = ui.connectLabel
        connectBtn.isEnabled = ui.connectEnabled
        hintV.text = ui.connectHint; hintV.show(ui.connectHint.isNotEmpty())

        changeDeviceBtn.text = ui.changeDeviceLabel
        changeDeviceBtn.show(ui.canChangeDevice)
        discoverableBtn.show(role == Role.SIM_PHONE)

        setupV.text = SetupState.summaryLine(readSetupRows(lang), lang)

        updating = true
        autoSwitch.isChecked = app.prefs.autoReconnect
        updating = false
        autoSwitch.isEnabled = ui.autoReconnectApplies
        autoNoteV.text = ui.autoReconnectNote
    }

    private fun header(t: String) = sectionHeader(t)

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    @Suppress("DEPRECATION")   // the non-deprecated overloads need API 33 / 28; minSdk is 26
    private fun versionInfo(): Pair<String, Long> = try {
        val pi = if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                 else packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        (pi.versionName ?: "?") to code
    } catch (e: Exception) { "?" to 0L }

}
