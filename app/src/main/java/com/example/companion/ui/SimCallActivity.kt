package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.audio.call.CallAudioManager
import com.example.companion.audio.call.CallAudioStatus
import com.example.companion.audio.transport.UplinkLifecycle
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.CallHistorySource
import com.example.companion.calls.CallLogEntry
import com.example.companion.calls.CallLogPermissions
import com.example.companion.calls.CallLogResult
import com.example.companion.calls.CallManager
import com.example.companion.calls.SimCallHistoryState
import com.example.companion.calls.SimCallLogReader
import com.example.companion.data.ReadDiagnostics
import com.example.companion.data.Role
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.tr
import java.text.DateFormat
import java.util.Date
import com.example.companion.calls.SimAudioControl
import com.example.companion.calls.CallRole
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState

/**
 * Minimal on-device call screen for the SIM phone. Required because the default phone app replaces the system
 * call UI. Also the target of ACTION_DIAL (needed for the dialer role); it does not dial anything by itself.
 *
 * Stage 5: also shows the SIM Phone's call history, read from Android's call log (READ_CALL_LOG, see [SimCallLogReader]).
 * That section is display only: no row is tappable, nothing is dialled, answered or controlled from it, and it does not
 * depend on the default-phone-app role, [com.example.companion.calls.CompanionInCallService] or CALL_PHONE.
 * The call buttons above it are the unchanged legacy call screen.
 */
class SimCallActivity : LocalizedActivity(), CallManager.Listener, CallAudioManager.Listener, SimAudioControl.Listener, UplinkLifecycle.Listener {
    private lateinit var audioV: TextView
    private lateinit var uplinkV: TextView
    private lateinit var mute: Button
    private lateinit var speaker: Button
    private lateinit var stateV: TextView
    private lateinit var numberV: TextView
    private lateinit var infoV: TextView
    private lateinit var answer: Button
    private lateinit var reject: Button
    private lateinit var hangup: Button

    // ---- Stage 5: call history (READ_CALL_LOG)
    private val callLog by lazy { SimCallLogReader(this) }
    private lateinit var historyHeader: TextView
    private lateinit var historyStatus: TextView
    private lateinit var historyGrant: Button
    private lateinit var historySettings: Button
    private lateinit var historyRefresh: Button
    private lateinit var historyList: LinearLayout
    private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    private var historyLoading = false
    private val shownLanguage: AppLanguage = AppLocale.current      // Stage 14: language the call-history section was built in
    private var permissionAsked = false
    private var skippedRows = 0          // Stage 28: damaged call-log rows left out of the list that is drawn
    private var shownEntries: List<CallLogEntry> = emptyList()   // Stage 6: last drawn list, redrawn when contact names arrive

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = tr(StringKey.SIMCALL_TITLE)
        val root = vbox()
        stateV = label().apply { textSize = 24f }
        numberV = label()
        infoV = label()
        // Stage 7: while calling is off these (hidden) buttons only show the message; CallManager refuses the commands anyway.
        answer = button(tr(StringKey.SIMCALL_ANSWER)) { if (CallFeatures.isCallingEnabled()) app.callManager.answer() else showCallingDisabled() }
        reject = button(tr(StringKey.SIMCALL_REJECT)) { if (CallFeatures.isCallingEnabled()) app.callManager.reject() else showCallingDisabled() }
        hangup = button(tr(StringKey.SIMCALL_HANGUP)) { if (CallFeatures.isCallingEnabled()) app.callManager.hangup() else showCallingDisabled() }
        audioV = label()
        uplinkV = label().apply { textSize = 14f }   // Stage 40: what this phone knows about the Main Phone microphone
        mute = button(tr(StringKey.SIMCALL_MUTE)) {
            if (!CallFeatures.isCallingEnabled()) { showCallingDisabled(); return@button }
            val on = app.simAudio.read()?.micMuted != true
            app.simAudio.setMuted(on)?.let { Toast.makeText(this, tr(StringKey.SIMCALL_MUTE_FAILED, it), Toast.LENGTH_LONG).show() }
        }
        speaker = button(tr(StringKey.SIMCALL_SPEAKER)) {
            if (!CallFeatures.isCallingEnabled()) { showCallingDisabled(); return@button }
            val on = app.simAudio.read()?.speakerOn != true
            app.simAudio.setSpeaker(on)?.let { Toast.makeText(this, tr(StringKey.SIMCALL_SPEAKER_FAILED, it), Toast.LENGTH_LONG).show() }
        }
        listOf(stateV, numberV, audioV, uplinkV, answer, reject, hangup, mute, speaker, infoV).forEach { root.addView(it) }
        root.addView(button(tr(StringKey.STATUS_TITLE)) { startActivity(Intent(this, StatusActivity::class.java)) })
        if (DiagnosticScreens.isVisible()) {   // Stage 3: hidden developer screen
            root.addView(button("Audio diagnostics (Stage 4)") { startActivity(Intent(this, AudioDiagActivity::class.java)) })
        }
        buildHistorySection(root)
        // Stage 7: a tel: / ACTION_DIAL deep link lands here (kept for the legacy dialer role). It never dials; say so once.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_DIAL && !CallFeatures.isCallingEnabled()) showCallingDisabled()
        // Stage 5: the call buttons plus a history list can be taller than the screen, so the page scrolls.
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // Stage 7: singleTop re-delivery of a tel: / ACTION_DIAL link. Nothing is dialled; only the message is shown.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == Intent.ACTION_DIAL && !CallFeatures.isCallingEnabled()) showCallingDisabled()
    }

    override fun onStart() {
        super.onStart()
        // Stage 14: only the call-history section is localized; if the language changed while this screen waited, rebuild it.
        if (shownLanguage != AppLocale.current) { recreate(); return }
        app.callManager.addListener(this); app.callAudio.addListener(this); app.simAudio.addListener(this)
        app.uplink.addListener(this)
        refresh()
        loadHistory()
    }
    override fun onStop() {
        app.callManager.removeListener(this); app.callAudio.removeListener(this); app.simAudio.removeListener(this)
        app.uplink.removeListener(this)
        super.onStop()
    }

    override fun onCallAudioChanged(status: CallAudioStatus) = refresh()
    override fun onSimAudioChanged() = refresh()
    override fun onUplinkChanged() = refresh()

    override fun onSnapshotChanged(snapshot: CallSnapshot) = refresh()
    override fun onCommandFailed(command: String, error: String, snapshot: CallSnapshot) {
        Toast.makeText(this, tr(StringKey.SIMCALL_COMMAND_FAILED, command, error), Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val s = app.callManager.snapshot
        stateV.text = if (s.state == CallState.IDLE) CallStateText.name(s.state, AppLocale.current) else CallStateText.name(s.state, AppLocale.current) + (s.disconnectCause?.let { " ($it)" } ?: "")
        numberV.text = if (s.state == CallState.IDLE) "" else tr(StringKey.SIMCALL_NUMBER, s.number?.let { TextDirection.ltrData(it, AppLocale.current) } ?: tr(StringKey.SIMCALL_NUMBER_NA))
        val rep = app.simAudio.read()
        val inCall = s.state == CallState.ACTIVE
        audioV.text = if (inCall) "Cellular call audio bridge: " + app.callAudio.status.label else ""
        // Stage 40: this phone cannot see the Main Phone's microphone. It can say only whether it is RECEIVING an uplink stream
        // (it has to accept one first; the only sink of this build refuses), so "not streaming" is the honest default.
        val up = UplinkUiState.sim(app.uplink.snapshot(), inCall)
        uplinkV.text = up.indicatorLine + "\n" + up.statusLine
        uplinkV.show(up.visible)
        val controls = CallFeatures.isCallingEnabled()   // Stage 7: no call-control button is shown while calling is off
        mute.show(controls && inCall); speaker.show(controls && inCall)
        mute.isEnabled = rep != null; speaker.isEnabled = rep != null
        mute.text = tr(StringKey.SIMCALL_MUTE_STATE, CallStateText.onOff(rep?.micMuted, AppLocale.current))
        speaker.text = tr(StringKey.SIMCALL_SPEAKER_STATE, CallStateText.onOff(rep?.speakerOn, AppLocale.current))
        answer.show(controls && s.state == CallState.RINGING)
        reject.show(controls && s.state == CallState.RINGING)
        hangup.show(controls && s.state in HANGUP_STATES)
        infoV.text = when {
            !controls -> tr(StringKey.HOME_CALLING_DISABLED)   // Stage 7 (Stage 19: in the selected language; CallFeatures.DISABLED_MESSAGE is unchanged): replaces every call hint on this screen; history below is unchanged
            !CallRole.isHeld(this) -> tr(StringKey.SIMCALL_INFO_NOT_ENABLED)
            intent?.action == Intent.ACTION_DIAL -> tr(StringKey.SIMCALL_INFO_DIAL)
            else -> tr(StringKey.SIMCALL_INFO_DEFAULT)
        }
    }

    // ------------------------------------------------------------------ Stage 5: call history from the call log

    private fun historyEnabled() = CallHistorySource.SIM_USES_CALL_LOG && app.prefs.role == Role.SIM_PHONE

    private fun buildHistorySection(root: LinearLayout) {
        historyHeader = sectionHeader(tr(StringKey.CALLS_SIM_HEADER))
        historyStatus = hintText()
        historyGrant = button(tr(StringKey.CALLS_SIM_GRANT)) { requestCallLogPermission() }
        historySettings = button(tr(StringKey.CALLS_SIM_SETTINGS)) { openAppSettings() }
        historyRefresh = button(tr(StringKey.CALLS_SIM_REFRESH)) { loadHistory() }
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(historyHeader, historyStatus, historyGrant, historySettings, historyRefresh, historyList).forEach { root.addView(it) }
        val on = historyEnabled()
        listOf(historyHeader, historyStatus, historyRefresh, historyList).forEach { it.show(on) }
        historyGrant.show(false); historySettings.show(false)
    }

    // ---- Stage 31A: no call history over a secure lock screen (this activity is exported and showWhenLocked)
    private var historyHiddenForLock = false

    private fun historyLockedOut(): Boolean = try {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km != null && km.isKeyguardLocked && km.isKeyguardSecure
    } catch (e: Exception) { false }

    private fun hideHistoryForLock() {
        historyHiddenForLock = true
        shownEntries = emptyList()
        historyList.removeAllViews()
        listOf(historyHeader, historyStatus, historyGrant, historySettings, historyRefresh, historyList).forEach { it.show(false) }
    }

    /** After the person unlocks while this screen stays open, bring the history back. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && historyHiddenForLock) loadHistory()
    }

    /** Reads the call log off the main thread and draws the result. Safe to call at any time; never throws. */
    private fun loadHistory() {
        if (!::historyList.isInitialized || !historyEnabled()) return
        // Stage 31A: this exported screen can be shown over a secure lock screen; the call log is not read or drawn then.
        if (historyLockedOut()) { hideHistoryForLock(); return }
        if (historyHiddenForLock) { historyHiddenForLock = false; listOf(historyHeader, historyRefresh, historyList).forEach { it.show(true) } }
        if (historyLoading) return
        if (!callLog.hasPermission()) { showHistory(CallLogResult.PermissionMissing); return }
        historyLoading = true
        historyStatus.text = SimCallHistoryState.loadingText(AppLocale.current); historyStatus.show(true)
        try {
            Thread {
                val result = try { callLog.read() } catch (e: Exception) {
                    ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_UI, "background call log read", e)
                    CallLogResult.Failed(tr(StringKey.CALLS_SIM_READ_FAILED))
                }
                runOnUiThread {
                    historyLoading = false
                    if (!isDestroyed && !isFinishing) showHistory(result)
                }
            }.start()
        } catch (e: Exception) {
            // Stage 28: the reading thread could not even start; do not stay stuck on "Loading…" with the Refresh button blocked.
            ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_UI, "start call log read", e)
            historyLoading = false
            showHistory(CallLogResult.Failed(tr(StringKey.CALLS_SIM_READ_FAILED)))
        }
    }

    private fun drawHistoryRows() {
        if (historyLockedOut()) { hideHistoryForLock(); return }   // Stage 31A
        historyList.removeAllViews()
        // Stage 28: a row that cannot be drawn (unexpected value) is left out; it must not end the app or hide the other rows.
        var failed = 0
        shownEntries.forEach {
            try { historyList.addView(historyRow(it)) }
            catch (e: Exception) { failed++; ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_UI, "draw call log row", e) }
        }
        if (failed > 0) {
            historyStatus.text = SimCallHistoryState.partialText(failed + skippedRows, AppLocale.current); historyStatus.show(true)
        }
    }

    private fun showHistory(result: CallLogResult) {
        if (historyLockedOut()) { hideHistoryForLock(); return }   // Stage 31A: a read that finished after the screen locked is dropped
        shownEntries = emptyList()
        skippedRows = 0
        historyList.removeAllViews()
        historyGrant.show(false); historySettings.show(false)
        when (result) {
            is CallLogResult.Loaded -> {
                shownEntries = SimCallHistoryState.normalize(result.entries)
                skippedRows = result.skipped
                // Stage 28: empty log -> the empty text; some damaged rows left out -> a short note above the rest; otherwise nothing.
                val note = SimCallHistoryState.partialText(result.skipped, AppLocale.current)
                historyStatus.text = if (shownEntries.isEmpty()) SimCallHistoryState.emptyText(AppLocale.current) else note
                historyStatus.show(shownEntries.isEmpty() || note.isNotEmpty())
                drawHistoryRows()
                // Stage 6: contact names use the existing lookup, which only answers when the optional Contacts permission is granted.
                // Unknown numbers are resolved in the background and the list is redrawn once; without permission the number is shown.
                app.contacts.prefetch(shownEntries.map { it.number }) { if (!isDestroyed && !isFinishing) drawHistoryRows() }
            }
            CallLogResult.PermissionMissing -> {
                historyStatus.text = SimCallHistoryState.permissionText(AppLocale.current)
                historyStatus.show(true)
                historyGrant.show(true)
                // Stage 28: "asked before" is remembered across restarts, so a permanent denial / revocation offers the settings page at once.
                historySettings.show(SimCallHistoryState.offerSettings(permissionAsked || app.prefs.callLogPermissionAsked, safeRationale()))
            }
            is CallLogResult.Failed -> { historyStatus.text = SimCallHistoryState.failureText(result.reason, AppLocale.current); historyStatus.show(true) }
        }
    }

    /** Same drawing as the Main Phone's Recent Calls row (title, optional number line, detail), but plain text: not tappable. */
    private fun historyRow(e: CallLogEntry): View {
        val r = RecentCallsState.row(e, { ms -> timeFormat.format(Date(ms)) }, AppLocale.current) { n -> app.contacts.findCached(n)?.name }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(context).apply {
                text = r.title; textSize = 17f; setTypeface(null, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                followLayoutStart()          // Stage 17: same as the Main Phone's Recent Calls row
                if (r.missed) setTextColor(UiColors.ERROR)
            })
            addView(TextView(context).apply {
                text = r.detail; textSize = 13f
                followAppDirection()         // Stage 17: "type · time · duration" is app text; time and duration are isolated left-to-right
                setTextColor(if (r.missed) UiColors.ERROR else UiColors.TEXT_HINT)
            })
        }
    }

    private fun requestCallLogPermission() {
        if (callLog.hasPermission()) { loadHistory(); return }
        permissionAsked = true
        app.prefs.callLogPermissionAsked = true
        try { requestPermissions(arrayOf(CallLogPermissions.READ), REQ_CALL_LOG) }
        catch (e: Exception) {
            ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_UI, "request call log permission", e)
            Toast.makeText(this, tr(StringKey.CALLS_SIM_ASK_FAILED), Toast.LENGTH_LONG).show()
        }
    }

    private fun safeRationale(): Boolean =
        try { shouldShowRequestPermissionRationale(CallLogPermissions.READ) } catch (e: Exception) { false }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code != REQ_CALL_LOG) return
        // Granted -> read now. Denied (or the dialog was dismissed) -> the section explains it; nothing else changes.
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) loadHistory() else showHistory(CallLogResult.PermissionMissing)
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        catch (e: Exception) {   // Stage 28: was ActivityNotFoundException only; a SecurityException must not end the app either
            ReadDiagnostics.record(ReadDiagnostics.Area.CALL_LOG_UI, "open app settings", e)
            Toast.makeText(this, tr(StringKey.CALLS_SIM_SETTINGS_PATH), Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQ_CALL_LOG = 5101
        val HANGUP_STATES = setOf(CallState.ACTIVE, CallState.DIALING, CallState.CONNECTING,
            CallState.SELECT_ACCOUNT, CallState.HOLDING)
    }
}
