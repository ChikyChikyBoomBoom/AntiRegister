package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.audio.diag.AudioDiagnostics
import com.example.companion.audio.diag.Heard
import com.example.companion.audio.diag.Phase
import com.example.companion.audio.diag.Report
import com.example.companion.audio.diag.RouteControl
import com.example.companion.calls.CallManager
import com.example.companion.data.Role
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import android.telecom.CallAudioState

/**
 * Stage 4 diagnostic screen (SIM Phone). Experiment only: nothing is saved, recorded to disk, or sent anywhere.
 * Must stay in the foreground while an experiment runs, otherwise Android may silence microphone capture.
 */
class AudioDiagActivity : LocalizedActivity(), AudioDiagnostics.Listener, CallManager.Listener {
    private lateinit var callV: TextView
    private lateinit var statusV: TextView
    private lateinit var reportV: TextView
    private lateinit var permBtn: Button
    private lateinit var modeBtn: Button
    private val actionButtons = ArrayList<Button>()
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DiagnosticScreens.isVisible()) { finish(); return }   // Stage 3: hidden developer screen; finishing in onCreate skips onStart (no listeners, probe or audio)
        title = "Audio diagnostics"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = vbox()
        root.addView(label("Stage 4 - cellular call audio experiment").apply { setTypeface(null, Typeface.BOLD) })
        root.addView(label(INSTRUCTIONS).apply { textSize = 13f })
        callV = label(); statusV = label()
        root.addView(callV); root.addView(statusV)
        permBtn = button("Allow microphone (RECORD_AUDIO)") {
            // Stage 4: this screen is hidden while DiagnosticScreens.VISIBLE is false; no permission request from it in that case
            if (DiagnosticScreens.isVisible()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        root.addView(permBtn)

        fun action(text: String, onClick: () -> Unit): Button {
            val b = button(text, onClick); actionButtons.add(b); root.addView(b); return b
        }
        action("1. Quiet phase (nobody speaks)") { app.audioDiag.runPhase(Phase.QUIET) }
        action("2. Remote phase (remote talks, you are silent)") { app.audioDiag.runPhase(Phase.REMOTE_ONLY) }
        action("3. Local phase (you talk, remote is silent)") { app.audioDiag.runPhase(Phase.LOCAL_ONLY) }
        action("Switch audio route: earpiece <-> speaker") { toggleRoute() }
        action("4. Tone test: voice-communication usage") { app.audioDiag.runToneTest(true) }
        action("5. Tone test: media usage") { app.audioDiag.runToneTest(false) }
        modeBtn = action("Audio mode write test (only when NO call)") {
            if (!app.audioDiag.runModeWriteTest()) Toast.makeText(this, "Only possible when there is no call", Toast.LENGTH_LONG).show()
        }
        root.addView(button("Stop running experiment") { app.audioDiag.cancel() })
        root.addView(button("Copy report") { copyReport() })
        root.addView(button("Share report as text") { shareReport() })
        actionButtons.add(button("Clear results") { app.audioDiag.clear() }.also { root.addView(it) })

        reportV = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(reportV)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        app.callManager.addListener(this); app.audioDiag.addListener(this)
        refresh()
    }

    override fun onStop() {
        app.callManager.removeListener(this); app.audioDiag.removeListener(this)
        dialog?.dismiss(); dialog = null
        super.onStop()
    }

    override fun onChanged() = refresh()
    override fun onSnapshotChanged(snapshot: CallSnapshot) = refresh()

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        refresh()
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val d = app.audioDiag
        val s = app.callManager.snapshot
        callV.text = when {
            app.prefs.role != Role.SIM_PHONE -> "WARNING: this phone is not set up as SIM Phone. Run the experiment on the SIM Phone."
            s.state == CallState.ACTIVE -> "Call state: ACTIVE - results count as call evidence"
            else -> "Call state: ${s.state.name} - runs are CONTROL ONLY (not call evidence)"
        }
        statusV.text = "Status: ${d.status}"
        permBtn.show(!micGranted())
        actionButtons.forEach { it.isEnabled = !d.running && micGranted() }
        modeBtn.isEnabled = !d.running && s.state == CallState.IDLE
        reportV.text = Report.build(this, d.snapshot(), s.state.name)
        if (d.pendingAnswer >= 0 && dialog == null && !d.running) askRemote(d.pendingAnswer)
    }

    private fun askRemote(index: Int) {
        dialog = AlertDialog.Builder(this)
            .setTitle("Tone test #${index + 1}")
            .setMessage("Ask the remote person: did they hear a steady 1 kHz beep during the last 4 seconds?")
            .setCancelable(false)
            .setPositiveButton("Yes, heard it") { _, _ -> answer(index, Heard.YES) }
            .setNegativeButton("No, nothing") { _, _ -> answer(index, Heard.NO) }
            .setNeutralButton("Not sure") { _, _ -> answer(index, Heard.UNSURE) }
            .show()
    }

    private fun answer(index: Int, h: Heard) { dialog = null; app.audioDiag.answerRemoteHeard(index, h) }

    @Suppress("DEPRECATION")
    private fun toggleRoute() {
        val cur = RouteControl.currentRoute()
        if (cur == null) {
            Toast.makeText(this, "Route control needs an active call and the default phone app role", Toast.LENGTH_LONG).show()
            return
        }
        val target = if ((cur and CallAudioState.ROUTE_SPEAKER) != 0) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
        RouteControl.setRoute(target)
        Toast.makeText(this, "Requested: ${RouteControl.routeName(target)}", Toast.LENGTH_SHORT).show()
    }

    private fun reportText() = Report.build(this, app.audioDiag.snapshot(), app.callManager.snapshot.state.name)

    private fun copyReport() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Audio diagnostics", reportText()))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, reportText())
        startActivity(Intent.createChooser(i, "Share report"))
    }

    private companion object {
        const val REQ_MIC = 20
        const val INSTRUCTIONS =
            "1) Start a call on the SIM Phone (dial from the Main Phone) and wait until it is ACTIVE.\n" +
            "2) Hold the phone to your ear, speaker OFF, keep this screen open.\n" +
            "3) Run phases 1-3 (about 21 s each). Whoever should speak must talk continuously for the whole phase; the other side stays silent.\n" +
            "4) Run both tone tests with the earpiece route, ask the remote person, and answer the dialog.\n" +
            "5) Copy the report. Repeat on other phones to compare."
    }
}
