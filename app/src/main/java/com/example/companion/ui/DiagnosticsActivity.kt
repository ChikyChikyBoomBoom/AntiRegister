package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.tr
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.audio.call.CallAudioManager
import com.example.companion.audio.call.CallAudioStatus
import com.example.companion.audio.hfp.Tri
import com.example.companion.audio.transport.AndroidAudioFacts
import com.example.companion.audio.transport.UplinkFlags
import com.example.companion.audio.transport.UplinkLifecycle
import com.example.companion.app.CompanionApp
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.calls.CallManager
import com.example.companion.calls.ClientCallController
import com.example.companion.data.Role
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import com.example.companion.sms.SmsPermissions
import java.text.DateFormat
import java.util.Date

/**
 * Stage 23: Connection & error diagnostics for both phones.
 *
 * Read-only view of the EXISTING state owners (ConnectionManager, BluetoothManager, CallManager / ClientCallController,
 * SmsPermissions, CallAudioManager). It keeps no connection, call, SMS or audio state of its own and opens no socket.
 * The three buttons that act (Reconnect, Disconnect) call the same ConnectionManager methods Settings already uses.
 * "Copy" puts the text from [DiagnosticsState.report] on this phone's clipboard; nothing is written to a file or sent anywhere.
 */
class DiagnosticsActivity : LocalizedActivity(),
    ConnectionManager.Listener, BluetoothManager.Listener,
    ClientCallController.Listener, CallManager.Listener, CallAudioManager.Listener, UplinkLifecycle.Listener {

    private lateinit var rowsBox: LinearLayout
    private lateinit var updatedV: TextView
    private lateinit var reconnectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var actionHintV: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stage 23 (structure review): diagnostics are not a normal-user screen. Like the three developer screens it finishes itself before
        // any listener or view is created if something still launches it while DiagnosticScreens is off (the entry buttons are hidden too).
        if (!DiagnosticScreens.isVisible()) { finish(); return }
        title = tr(StringKey.DIAG_TITLE)
        val root = vbox()
        root.addView(screenTitle(tr(StringKey.DIAG_TITLE)))
        root.addView(label(tr(StringKey.DIAG_NOTE)).apply {
            textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY)
        })
        updatedV = hintText()
        root.addView(updatedV)

        // Stage 24: the rows sit in one card; the buttons below follow the shared order (main action first, disabled ones greyed).
        rowsBox = card()
        root.addView(rowsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        reconnectBtn = styledButton(tr(StringKey.DIAG_RECONNECT), ButtonStyle.PRIMARY) { onReconnect() }
        disconnectBtn = styledButton(tr(StringKey.SETTINGS_DISCONNECT), ButtonStyle.SECONDARY) { onDisconnect() }
        root.addView(reconnectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(disconnectBtn)
        actionHintV = hintText()      // says why a button is greyed out
        root.addView(actionHintV)
        root.addView(button(tr(StringKey.DIAG_REFRESH)) { refresh() })
        root.addView(button(tr(StringKey.DIAG_COPY)) { onCopy() })

        setContentView(ScrollView(this).apply { addView(constrainWidth(root)) })
    }

    // ---------------------------------------------------------------- actions

    private fun onReconnect() {
        val role = app.prefs.role
        val bt = app.bluetooth
        when {
            role == null -> { toast(tr(StringKey.DIAG_CHOOSE_ROLE)); return }
            !bt.isSupported -> { toast(tr(StringKey.HOME_DETAIL_BT_UNSUPPORTED)); return }
            !bt.isEnabled -> { toast(tr(StringKey.DIAG_BT_ON_FIRST)); return }
            !BtPermissions.granted(this, role) -> { startActivity(Intent(this, SetupActivity::class.java)); return }   // Stage 22: permissions are asked there
            role == Role.MAIN_PHONE && app.prefs.deviceAddress == null -> { toast(tr(StringKey.DIAG_SELECT_SIM_FIRST)); return }
        }
        if (SettingsState.callInProgress(currentCallState())) {
            AlertDialog.Builder(this)
                .setTitle(tr(StringKey.DIAG_DIALOG_TITLE))
                .setMessage(tr(StringKey.DIAG_DIALOG_BODY))
                .setPositiveButton(tr(StringKey.DIAG_RECONNECT)) { _, _ -> doReconnect() }
                .setNegativeButton(tr(StringKey.ACTION_CANCEL), null)
                .show()
        } else doReconnect()
    }

    /** Same two ConnectionManager calls the app already uses elsewhere: stop() tears everything down, start() begins a fresh attempt. */
    private fun doReconnect() {
        val cm = app.connection
        cm.stop()
        cm.start()
        refresh()
    }

    private fun onDisconnect() {
        val cm = app.connection
        if (SettingsState.isActive(cm.state)) cm.userStop()    // persists "user stopped", like every other Disconnect button
        refresh()
    }

    private fun onCopy() {
        val text = DiagnosticsState.report(buildInput(), System.currentTimeMillis(), ::formatTime)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AntiRegister diagnostics", text))
        if (Build.VERSION.SDK_INT < 33) toast(tr(StringKey.DIAG_COPIED))   // Android 13+ shows its own confirmation
    }

    // ---------------------------------------------------------------- reading existing state

    private fun currentCallState(): CallState? = when (app.prefs.role) {
        Role.SIM_PHONE -> app.callManager.snapshot.state
        Role.MAIN_PHONE -> app.clientCalls.snapshot?.state
        null -> null
    }

    private fun buildInput(): DiagnosticsInput {
        val role = app.prefs.role
        val cm = app.connection
        val bt = app.bluetooth
        val cap = app.callAudio.getCapability()
        val audio = app.callAudio.status
        val (versionName, versionCode) = versionInfo()
        return DiagnosticsInput(
            appVersion = "$versionName ($versionCode)",
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            role = role,
            bluetoothSupported = bt.isSupported,
            bluetoothEnabled = bt.isEnabled,
            bluetoothPermission = BtPermissions.granted(this, role),
            connectionState = cm.state,
            retryAttempt = cm.retryAttempt,
            deviceName = cm.connectedName ?: app.prefs.deviceName.takeIf { role == Role.MAIN_PHONE },
            deviceAddress = app.prefs.deviceAddress.takeIf { role == Role.MAIN_PHONE },
            lastConnectedAt = app.prefs.lastConnectedAt,
            lastDisconnectedAt = cm.lastDisconnectedAt,
            lastError = cm.lastError,
            lastErrorAt = cm.lastErrorAt,
            callState = currentCallState(),
            telephonyAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            smsPermissionsGranted = SmsPermissions.missing(this).isEmpty(),
            audioMode = cap.mode,
            audioTwoWayCellular = cap.twoWayCellularAudio,
            audioReason = (audio.reason ?: cap.reason)?.text,
            audioSession = audio.state,
            audioIndicator = audio.indicator.text,
            uplink = buildUplink(cap.twoWayCellularAudio)
        )
    }

    /** Stage 40: read-only. Reads the lifecycle and the phone's audio facts; opens no microphone and changes no audio state. */
    private fun buildUplink(twoWay: Tri): UplinkDiagnostics {
        val up = app.uplink
        val snap = up.snapshot()
        return UplinkDiagnostics(
            facts = AndroidAudioFacts(this).read(),
            microphonePermission = snap.microphonePermission,
            sinkCapability = snap.capability,
            flags = UplinkFlags(
                uplinkBridge = up.flags.uplinkBridge,
                downlinkBridge = up.flags.downlinkBridge,   // Stage 24: effective value (master switch AND constant), same basis as uplinkBridge above
                twoWayCellularAudio = when (twoWay) { Tri.YES -> UplinkFlags.TWO_WAY_YES; Tri.NO -> UplinkFlags.TWO_WAY_NO; Tri.UNKNOWN -> UplinkFlags.TWO_WAY_UNKNOWN }
            ),
            lifecycle = snap.state,
            lifecycleDetail = snap.detail ?: snap.lastStopReason
        )
    }

    @Suppress("DEPRECATION")   // the non-deprecated overloads need API 33 / 28; minSdk is 26
    private fun versionInfo(): Pair<String, Long> = try {
        val pi = if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                 else packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        (pi.versionName ?: "?") to code
    } catch (e: Exception) { "?" to 0L }

    private fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance().format(Date(ms))

    // ---------------------------------------------------------------- lifecycle / observing

    override fun onStart() {
        super.onStart()
        app.connection.addListener(this); app.bluetooth.addListener(this)
        app.clientCalls.addListener(this); app.callManager.addListener(this); app.callAudio.addListener(this)
        app.uplink.addListener(this)
        refresh()
    }

    override fun onStop() {
        app.connection.removeListener(this); app.bluetooth.removeListener(this)
        app.clientCalls.removeListener(this); app.callManager.removeListener(this); app.callAudio.removeListener(this)
        app.uplink.removeListener(this)
        super.onStop()
    }

    override fun onResume() { super.onResume(); refresh() }   // e.g. back from Setup or system Bluetooth settings

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()
    override fun onInfoChanged() = refresh()
    override fun onAdapterStateChanged(enabled: Boolean) = refresh()
    override fun onCallUiChanged() = refresh()
    override fun onSnapshotChanged(snapshot: CallSnapshot) = refresh()
    override fun onCallAudioChanged(status: CallAudioStatus) = refresh()
    override fun onUplinkChanged() = refresh()

    // ---------------------------------------------------------------- drawing

    private fun refresh() {
        if (!::rowsBox.isInitialized) return
        rowsBox.removeAllViews()
        DiagnosticsState.rows(buildInput(), ::formatTime).forEachIndexed { i, r ->
            rowsBox.addView(label(r.label).apply {
                textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(UiColors.TEXT_HINT)
                setPadding(0, if (i == 0) 0 else dp(12), 0, 0)
            })
            rowsBox.addView(label(r.value).apply { textSize = 16f; setPadding(0, dp(2), 0, dp(2)); setTextIsSelectable(true) })
        }
        val cm = app.connection
        disconnectBtn.isEnabled = SettingsState.isActive(cm.state)
        reconnectBtn.isEnabled = app.prefs.role != null
        actionHintV.setTextOrHide(when {
            app.prefs.role == null -> tr(StringKey.DIAG_HINT_ROLE)
            !SettingsState.isActive(cm.state) -> tr(StringKey.DIAG_HINT_DISCONNECT)
            else -> ""
        })
        updatedV.text = tr(StringKey.DIAG_UPDATED, TextDirection.ltrData(DateFormat.getTimeInstance().format(Date()), AppLocale.current))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
