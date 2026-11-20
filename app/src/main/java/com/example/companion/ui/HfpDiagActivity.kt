package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.audio.diag.Evaluator
import com.example.companion.audio.hfp.CallAudioPolicy
import com.example.companion.audio.hfp.HfpReport
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.data.Role
import com.example.companion.protocol.CallState

/** Stage 6 diagnostic: what Android allows for Bluetooth HFP. Read-only; opens no socket and no audio. */
class HfpDiagActivity : LocalizedActivity() {
    private lateinit var out: TextView
    private var lastText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DiagnosticScreens.isVisible()) { finish(); return }   // Stage 3: hidden developer screen; finishing in onCreate skips onStart (no listeners, probe or audio)
        title = "Bluetooth HFP"
        val root = vbox()
        root.addView(label("Stage 6 - Bluetooth HFP / Hands-Free feasibility").apply { setTypeface(null, Typeface.BOLD) })
        root.addView(label(INFO).apply { textSize = 13f })
        root.addView(button("Run probe again") { run() })
        root.addView(button("Copy report") { copy() })
        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true) }
        root.addView(out)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        run()   // Stage 22: no automatic permission request; the probe reports a missing permission itself (Bluetooth is asked on the Setup screen)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) { run() }

    private fun run() {
        out.text = "Probing..."
        app.hfp.probe { facts, cap ->
            if (isDestroyed || isFinishing) return@probe
            val call = when (app.prefs.role) {
                Role.SIM_PHONE -> app.callManager.snapshot.state
                Role.MAIN_PHONE -> app.clientCalls.snapshot?.state ?: CallState.IDLE
                null -> CallState.IDLE
            }
            val stage4 = runCatching { Evaluator.evaluate(app.audioDiag.snapshot()) }.getOrNull()
            lastText = HfpReport.render(facts, cap, CallAudioPolicy.decide(cap, stage4, call))
            out.text = lastText
        }
    }

    private fun copy() {
        if (lastText.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("HFP report", lastText))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val INFO = "Reads what the public Android SDK exposes about HFP. Nothing is connected, routed or recorded. " +
            "A listed API is not treated as a capability."
    }
}
