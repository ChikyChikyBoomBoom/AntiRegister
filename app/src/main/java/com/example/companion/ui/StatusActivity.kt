package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.ConnectionErrorText
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.localizedLabel
import com.example.companion.i18n.tr
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.calls.CallPermissions
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.CallRole
import com.example.companion.data.Role
import com.example.companion.sms.SmsPermissions
import java.text.DateFormat
import java.util.Date

/** Connection diagnostics + Connect/Disconnect. Only observes ConnectionManager; keeps no Bluetooth state of its own. */
class StatusActivity : LocalizedActivity(), ConnectionManager.Listener, BluetoothManager.Listener {
    private lateinit var statusDot: StatusIndicator
    private lateinit var statusBigV: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var toolsHeader: TextView
    private lateinit var localV: TextView
    private lateinit var roleV: TextView
    private lateinit var btV: TextView
    private lateinit var remoteV: TextView
    private lateinit var stateV: TextView
    private lateinit var errV: TextView
    private lateinit var lastOkV: TextView
    private lateinit var pingV: TextView
    private lateinit var callV: TextView
    private lateinit var smsV: TextView
    private lateinit var smsSetup: View
    private lateinit var openSms: View
    private lateinit var callSetup: View
    private lateinit var openCalls: View
    private lateinit var openHistory: View
    private lateinit var audioDiag: View
    private lateinit var audioTest: View
    private lateinit var hfpDiag: View
    private lateinit var toggle: Button
    private lateinit var pingBtn: View
    private lateinit var pick: View
    private lateinit var discoverable: View
    private lateinit var enableBt: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = tr(StringKey.STATUS_TITLE)
        val root = vbox()
        // Stage 24: the connection status first (word + dot/spinner, tinted), then the detail lines in their own card.
        root.addView(screenTitle(tr(StringKey.STATUS_TITLE)))
        statusDot = StatusIndicator(this, 14)
        statusBigV = label().apply {
            textSize = 22f; setTypeface(null, Typeface.BOLD); setPaddingRelative(dp(8), 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusCard = card().apply {
            addView(LinearLayout(this@StatusActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(statusDot); addView(statusBigV)
            })
        }
        root.addView(statusCard)
        localV = label(); roleV = label(); btV = label(); remoteV = label()
        stateV = label(); errV = label(); lastOkV = label(); pingV = label(); callV = label(); smsV = label()
        val details = card().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        listOf(localV, roleV, btV, remoteV, stateV, errV, lastOkV, pingV, callV, smsV).forEach {
            it.textSize = 14f; details.addView(it)
        }
        root.addView(details, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        toggle = styledButton(tr(StringKey.SETTINGS_CONNECT), ButtonStyle.PRIMARY) { onToggle() }
        pingBtn = button(tr(StringKey.STATUS_PING)) { app.connection.ping() }
        enableBt = button(tr(StringKey.STATUS_ENABLE_BT)) {
            // Stage 32A-1: ACTION_REQUEST_ENABLE needs BLUETOOTH_CONNECT on Android 12+; ask on the Setup screen first (same as the buttons below).
            if (!BtPermissions.granted(this, app.prefs.role)) openSetup()
            else startSystemPage(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))   // Stage 30: guarded
        }
        pick = button(tr(StringKey.PICKER_TITLE)) { startActivity(Intent(this, DevicePickerActivity::class.java)) }
        discoverable = button(tr(StringKey.STATUS_DISCOVERABLE)) {
            if (!BtPermissions.granted(this, app.prefs.role)) openSetup()
            else startSystemPage(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        }
        callSetup = button(tr(StringKey.STATUS_CALL_SETUP)) { if (CallFeatures.isCallingEnabled()) openSetup() else showCallingDisabled() }   // Stage 7: no role / call-permission request   // Stage 22: asked on the Setup screen
        openCalls = button(tr(StringKey.STATUS_OPEN_CALLS)) { if (CallFeatures.isCallingEnabled()) startActivity(Intent(this, CallActivity::class.java)) else showCallingDisabled() }   // Stage 7
        // Stage 6: the SIM Phone's way to its call history (read from the call log; display only, see SimCallActivity)
        openHistory = button(tr(StringKey.STATUS_CALL_HISTORY)) { startActivity(Intent(this, SimCallActivity::class.java)) }
        audioDiag = button("Audio diagnostics (Stage 4)") { startActivity(Intent(this, AudioDiagActivity::class.java)) }
        audioTest = button("Bluetooth audio test (Stage 5)") { startActivity(Intent(this, AudioTransportTestActivity::class.java)) }
        hfpDiag = button("Bluetooth HFP feasibility (Stage 6)") { startActivity(Intent(this, HfpDiagActivity::class.java)) }
        root.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(pingBtn)
        openSms = button(tr(StringKey.STATUS_MESSAGES_SMS)) { startActivity(Intent(this, SmsConversationsActivity::class.java)) }
        smsSetup = button(tr(StringKey.STATUS_SMS_SETUP)) { openSetup() }   // Stage 22: asked on the Setup screen
        root.addView(openCalls); root.addView(callSetup); root.addView(openHistory)
        root.addView(openSms); root.addView(smsSetup)
        toolsHeader = sectionHeader("Test tools")
        root.addView(toolsHeader)
        root.addView(audioDiag); root.addView(audioTest); root.addView(hfpDiag)
        root.addView(sectionHeader(tr(StringKey.STATUS_HEADER_BLUETOOTH)))
        root.addView(enableBt); root.addView(pick); root.addView(discoverable)
        root.addView(CheckBox(this).apply {
            text = tr(StringKey.STATUS_AUTO_RECONNECT)
            isChecked = app.prefs.autoReconnect
            setOnCheckedChangeListener { _, c ->
                // Stage 29: same safe save as the Settings screen; if the value cannot be written it still applies until the app is closed, and the person is told.
                val saved = try { app.prefs.saveAutoReconnect(c) } catch (e: Exception) { false }
                if (!saved) Toast.makeText(this@StatusActivity, SettingsIssue.SAVE_FAILED.text(AppLocale.current), Toast.LENGTH_LONG).show()
            }
        })
        root.addView(sectionHeader(tr(StringKey.STATUS_HEADER_SETUP_DIAG)))
        root.addView(button(tr(StringKey.SETTINGS_SETUP_STATUS)) { openSetup() })
        if (DiagnosticScreens.isVisible()) root.addView(button(tr(StringKey.SETTINGS_DIAG_REPORT)) { startActivity(Intent(this, DiagnosticsActivity::class.java)) })   // Stage 23; hidden from normal users while DiagnosticScreens is off
        root.addView(button(tr(StringKey.HOME_TILE_SETTINGS)) {   // Stage 18: role, device, auto-reconnect and app info now live in SettingsActivity
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })
        setContentView(ScrollView(this).apply { addView(constrainWidth(root)) })
        // Stage 22: nothing is asked automatically here; permissions are explained and requested on the Setup screen.
    }

    /** Stage 22: the one place that asks for permissions (with the reason first, and Android settings if a permission was blocked). */
    private fun openSetup() = startActivity(Intent(this, SetupActivity::class.java))

    private fun onToggle() {
        val cm = app.connection
        if (cm.isActive || cm.state == ConnectionManager.State.DISCOVERING) cm.userStop()
        else if (!BtPermissions.granted(this, app.prefs.role)) openSetup()
        else cm.start()
    }

    /** Stage 30: opens a system page (Bluetooth on / discoverable). A phone without that page, or a refusal, shows a message instead of ending the app. */
    private fun startSystemPage(i: Intent) {
        try { startActivity(i) }
        catch (e: Exception) { Toast.makeText(this, tr(StringKey.SETUP_NO_SETTINGS_PAGE), Toast.LENGTH_LONG).show() }
    }

    override fun onStart() {
        super.onStart()
        app.connection.addListener(this); app.bluetooth.addListener(this)
        ensureStarted(); refresh()
    }

    override fun onStop() {
        app.connection.removeListener(this); app.bluetooth.removeListener(this)
        super.onStop()
    }

    /** Auto-start only from a clean idle state; after an ERROR the user presses Connect. */
    private fun ensureStarted() = ensureConnectionStarted()   // shared with HomeActivity (ConnectionAutoStart.kt)

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()
    override fun onInfoChanged() = refresh()
    override fun onAdapterStateChanged(enabled: Boolean) = refresh()

    private fun stateText(cm: ConnectionManager) = when (cm.state) {
        ConnectionManager.State.DISCONNECTED -> tr(StringKey.STATUS_ST_DISCONNECTED)
        ConnectionManager.State.DISCOVERING -> tr(StringKey.STATUS_ST_DISCOVERING)
        ConnectionManager.State.PAIRING -> tr(StringKey.STATUS_ST_PAIRING)
        ConnectionManager.State.LISTENING -> tr(StringKey.STATUS_ST_LISTENING)
        ConnectionManager.State.CONNECTING -> tr(StringKey.STATUS_ST_CONNECTING)
        ConnectionManager.State.CONNECTED -> tr(StringKey.STATUS_ST_CONNECTED)
        ConnectionManager.State.RECONNECTING -> tr(StringKey.STATUS_ST_RECONNECTING,
            TextDirection.number(cm.retryAttempt, AppLocale.current) +
                (if (cm.retryAttempt <= cm.maxAttempts) "/" + TextDirection.number(cm.maxAttempts, AppLocale.current) else ""))
        ConnectionManager.State.ERROR -> tr(StringKey.STATUS_ST_ERROR)
    }

    private fun refresh() {
        val role = app.prefs.role
        val bt = app.bluetooth
        val cm = app.connection
        val lang = AppLocale.current
        // Stage 19: every line below is in the selected language; the values (device names, times) are data and are isolated for RTL.
        localV.text = tr(StringKey.STATUS_LOCAL_DEVICE, TextDirection.ltrData(bt.localName ?: "-", lang))
        roleV.text = tr(StringKey.STATUS_LOCAL_ROLE, role?.localizedLabel(lang) ?: "-")
        btV.text = tr(StringKey.STATUS_BLUETOOTH, tr(when { !bt.isSupported -> StringKey.STATUS_BT_UNSUPPORTED; bt.isEnabled -> StringKey.STATUS_BT_ENABLED; else -> StringKey.STATUS_BT_DISABLED }))
        remoteV.text = tr(StringKey.STATUS_REMOTE, TextDirection.ltrData(cm.connectedName ?: app.prefs.deviceName ?: app.prefs.deviceAddress ?: "-", lang))
        stateV.text = tr(StringKey.STATUS_STATE, stateText(cm))
        errV.text = tr(StringKey.STATUS_LAST_ERROR, ConnectionErrorText.localizeOrNull(cm.lastError, lang) ?: tr(StringKey.STATUS_NONE))
        val t = app.prefs.lastConnectedAt
        lastOkV.text = tr(StringKey.STATUS_LAST_CONNECTED, if (t == 0L) tr(StringKey.STATUS_NEVER) else TextDirection.ltrData(DateFormat.getDateTimeInstance().format(Date(t)), lang))
        pingV.text = tr(StringKey.STATUS_LAST_PING, cm.lastPingMs?.let { TextDirection.ltrData("$it ms", lang) } ?: "-")
        val held = CallRole.isHeld(this)
        val missingCallPerms = CallPermissions.missing(this).isNotEmpty()
        callV.text = tr(StringKey.STATUS_CALL_CONTROL, when {
            !CallFeatures.isCallingEnabled() -> tr(StringKey.HOME_CALLING_DISABLED)   // Stage 7 (Stage 19: selected language)
            !held -> tr(StringKey.STATUS_CALL_NOT_ENABLED)
            missingCallPerms -> tr(StringKey.STATUS_CALL_PARTIAL)
            else -> tr(StringKey.STATUS_ENABLED)
        })
        callV.show(role == Role.SIM_PHONE)
        val smsMissing = SmsPermissions.missing(this).isNotEmpty()
        smsV.text = tr(StringKey.STATUS_SMS_FORWARDING, if (smsMissing) tr(StringKey.STATUS_SMS_NOT_ENABLED) else tr(StringKey.STATUS_ENABLED))
        smsV.show(role == Role.SIM_PHONE)
        smsSetup.show(role == Role.SIM_PHONE && smsMissing)
        openSms.show(role == Role.MAIN_PHONE)
        callSetup.show(role == Role.SIM_PHONE && (!CallFeatures.isCallingEnabled() || !held || missingCallPerms))
        openCalls.show(role == Role.MAIN_PHONE)
        openHistory.show(role == Role.SIM_PHONE)
        // Stage 3: developer screens hidden (flip DiagnosticScreens.VISIBLE to bring the "Test tools" section back)
        toolsHeader.show(DiagnosticScreens.isVisible() && role != null)
        audioDiag.show(DiagnosticScreens.isVisible() && role == Role.SIM_PHONE)
        audioTest.show(DiagnosticScreens.isVisible() && role != null)
        hfpDiag.show(DiagnosticScreens.isVisible() && role != null)

        val link = HomeStatus.linkStatus(cm.state)
        statusBigV.text = SettingsState.statusText(cm.state)
        statusDot.set(link.color(), UiPolish.isBusy(cm.state))      // spinner while scanning, pairing, connecting or waiting
        statusCard.setCardTint(link.tint())

        toggle.text = tr(if (cm.isActive || cm.state == ConnectionManager.State.DISCOVERING) StringKey.SETTINGS_DISCONNECT else StringKey.SETTINGS_CONNECT)
        pingBtn.show(cm.state == ConnectionManager.State.CONNECTED)
        pick.show(role == Role.MAIN_PHONE)
        discoverable.show(role == Role.SIM_PHONE)
        enableBt.show(bt.isSupported && !bt.isEnabled)
    }

    override fun onResume() { super.onResume(); ensureStarted(); refresh(); app.hostCalls.resync() }
}
