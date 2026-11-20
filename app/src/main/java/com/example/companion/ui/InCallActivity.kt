package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.audio.call.AudioIndicator
import com.example.companion.audio.call.CallAudioManager
import com.example.companion.audio.call.CallAudioStatus
import com.example.companion.audio.transport.UplinkBlock
import com.example.companion.audio.transport.UplinkLifecycle
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.ClientCallController
import com.example.companion.data.Role
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState

/**
 * Main Phone call screen: incoming, outgoing and active calls.
 *
 * It owns no call logic. It observes [ClientCallController] (what the SIM Phone reported), [ConnectionManager] (the link)
 * and [CallAudioManager] (audio status), redraws through the pure [CallScreenState], and sends commands only through
 * [ClientCallController]. Buttons never assume success: the screen changes when the SIM Phone reports the change.
 * Closing this screen (Back) never ends the call.
 *
 * Stage 19: when the caller's number is a contact (optional Contacts permission), the contact's name is the main line with
 * the number under it, and a round photo / initial is shown. Otherwise the number is shown as before.
 *
 * Stage 40: one extra card for the microphone uplink experiment (explicit Start/Stop, microphone indicator, capability, connection,
 * explanation). It is a view of [UplinkLifecycle] (text and enable rules in [UplinkUiState]); it owns no audio. Leaving this screen
 * stops the uplink, so the microphone is never on while its indicator is not on screen.
 */
class InCallActivity : LocalizedActivity(), ClientCallController.Listener, ConnectionManager.Listener, CallAudioManager.Listener, UplinkLifecycle.Listener {

    private enum class Pending(val text: String) { ANSWER("Answering…"), REJECT("Rejecting…"), HANGUP("Hanging up…") }

    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 1_000) } }
    private val finishNow = Runnable { finish() }
    private val pendingTimeout = Runnable { pending = null; refresh() }

    private lateinit var dot: View
    private lateinit var linkV: TextView
    private lateinit var bannerV: TextView
    private lateinit var titleV: TextView
    private lateinit var avatarV: ImageView
    private lateinit var nameV: TextView
    private lateinit var numberV: TextView
    private lateinit var statusV: TextView
    private lateinit var durationV: TextView
    private lateinit var audioNoteV: TextView
    private lateinit var errV: TextView
    private lateinit var audioStateV: TextView
    private lateinit var phoneAudioRow: LinearLayout
    private lateinit var phoneMute: Button
    private lateinit var phoneSpeaker: Button
    private lateinit var audioCaptionV: TextView
    private lateinit var audioRow: LinearLayout
    private lateinit var mute: Button
    private lateinit var speaker: Button
    private lateinit var answer: Button
    private lateinit var reject: Button
    private lateinit var hangup: Button
    private lateinit var closeV: Button
    // Stage 40: uplink card
    private lateinit var uplinkCard: LinearLayout
    private lateinit var uplinkMicV: TextView
    private lateinit var uplinkCapV: TextView
    private lateinit var uplinkConnV: TextView
    private lateinit var uplinkWhyV: TextView
    private lateinit var uplinkEchoV: TextView
    private lateinit var uplinkBtn: Button
    private lateinit var uplinkHintV: TextView

    // Remembered only to label the screen after the call changed or the link dropped. Never used to decide call state.
    private var lastLive: CallSnapshot? = null
    private var ended: EndedCall? = null
    private var pending: Pending? = null
    private var pendingFrom: CallState? = null
    /** Stage 27: the call session the pressed button belonged to; another session means the press no longer applies. */
    private var pendingId: String? = null
    private var finishScheduled = false
    private var fromNotification = false
    private var avatarKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Call"
        fromNotification = intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)
        if (app.prefs.role != Role.MAIN_PHONE) { finish(); return }
        // Stage 7: calling is off. No Answer / Reject / Hang up / Mute / Speaker / microphone button is ever built.
        if (!CallFeatures.isCallingEnabled()) { showCallingDisabled(); finish(); return }

        val root = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }

        dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // the text next to it says the same thing
        }
        linkV = TextView(this).apply { textSize = 14f; setPaddingRelative(dp(8), 0, 0, 0) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(linkV)
        })

        bannerV = centered(14f).apply { setTextColor(Color.rgb(183, 28, 28)) }
        titleV = centered(22f).apply {
            setTypeface(null, Typeface.BOLD)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE   // screen readers announce state changes
        }
        avatarV = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }   // the name next to it says the same
        nameV = centered(32f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        numberV = centered(32f).apply { setPadding(0, dp(12), 0, dp(4)) }
        statusV = centered(16f).apply {
            setTextColor(Color.DKGRAY)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        durationV = centered(40f).apply { setPadding(0, dp(12), 0, 0) }
        audioNoteV = centered(14f).apply { setTextColor(Color.rgb(183, 28, 28)) }
        errV = centered(14f).apply { setTextColor(Color.rgb(183, 28, 28)) }
        root.addView(bannerV, matchWidth(top = 16))
        root.addView(titleV, matchWidth(top = 32))
        root.addView(avatarV, LinearLayout.LayoutParams(dp(AVATAR_DP), dp(AVATAR_DP)).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(nameV, matchWidth())
        root.addView(numberV, matchWidth())
        root.addView(statusV, matchWidth())
        root.addView(durationV, matchWidth())
        audioStateV = centered(16f).apply {
            setTypeface(null, Typeface.BOLD)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE   // announce Connected / Connecting / Unavailable
        }
        root.addView(audioStateV, matchWidth(top = 12))
        root.addView(audioNoteV, matchWidth(top = 12))
        root.addView(errV, matchWidth(top = 12))

        // These act on the SIM Phone's own call audio through Telecom (this phone's mic/speaker are not part of the call).
        mute = toggleButton("Mute") { app.clientCalls.setMute(app.clientCalls.simAudio?.micMuted != true) }
        speaker = toggleButton("Speaker") { app.clientCalls.setSpeaker(app.clientCalls.simAudio?.speakerOn != true) }
        audioCaptionV = centered(12f).apply {
            setTextColor(Color.GRAY)
            text = "Mute and Speaker control the SIM Phone's microphone and speaker."
        }
        audioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(mute, speaker).forEach { addView(it, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        }
        // Stage 21: THIS phone's microphone / speaker. Shown only if the audio path supports them (the Stage 7 architecture has
        // no path today, so these stay hidden). Requests go to CallAudioManager; the labels follow its REPORTED state.
        phoneMute = toggleButton("Mute this phone") { app.callAudio.setMicrophoneMuted(app.callAudio.status.micMuted != true) }
        phoneSpeaker = toggleButton("Speaker") { app.callAudio.setSpeakerOn(app.callAudio.status.speakerOn != true) }
        phoneAudioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(phoneMute, phoneSpeaker).forEach { addView(it, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        }
        root.addView(phoneAudioRow, matchWidth(top = 32))
        root.addView(audioRow, matchWidth(top = 16))
        root.addView(audioCaptionV, matchWidth(top = 4))

        // Stage 40: microphone uplink experiment. Hidden unless a call is active or the microphone is in use.
        uplinkMicV = centered(16f).apply { setTypeface(null, Typeface.BOLD); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        uplinkCapV = centered(14f)
        uplinkConnV = centered(14f).apply { setTextColor(Color.DKGRAY) }
        uplinkWhyV = centered(14f)
        uplinkEchoV = centered(12f).apply { setTextColor(Color.GRAY) }
        uplinkBtn = toggleButton("Start microphone uplink") { onUplinkButton() }
        uplinkHintV = centered(12f).apply { setTextColor(Color.GRAY) }
        uplinkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(uplinkMicV, uplinkCapV, uplinkConnV, uplinkWhyV, uplinkEchoV).forEach { addView(it, matchWidth()) }
            addView(uplinkBtn, matchWidth(top = 8).apply { height = dp(56) })
            addView(uplinkHintV, matchWidth(top = 4))
        }
        root.addView(uplinkCard, matchWidth(top = 24))

        answer = callButton("Answer", Color.rgb(46, 125, 50)) { press(Pending.ANSWER) { app.clientCalls.answer() } }
        reject = callButton("Reject", Color.rgb(198, 40, 40)) { press(Pending.REJECT) { app.clientCalls.reject() } }
        hangup = callButton("Hang up", Color.rgb(198, 40, 40)) { press(Pending.HANGUP) { app.clientCalls.hangup() } }
        val closeBtn = callButton("Close", Color.rgb(97, 97, 97)) { finish() }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(reject, answer).forEach { addView(it, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        }, matchWidth(top = 24))
        root.addView(hangup, matchWidth(top = 24).apply { height = dp(72) })
        root.addView(closeBtn, matchWidth(top = 24).apply { height = dp(56) })
        closeV = closeBtn

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    // ---------------------------------------------------------------- lifecycle / observing

    override fun onStart() {
        super.onStart()
        if (!::closeV.isInitialized) return
        app.clientCalls.addListener(this); app.connection.addListener(this); app.callAudio.addListener(this)
        app.uplink.addListener(this)
        // Stage 16: opened from the incoming-call notification, possibly after the process was restarted. Same start rule as the
        // Home screen (the one existing ConnectionManager; nothing starts after an error or an explicit Disconnect).
        if (fromNotification) ensureConnectionStarted(userIntent = true)
        refresh()
        main.postDelayed(tick, 1_000)   // call timer
    }

    /**
     * Stage 16: if a notification opened this screen as the first screen of the app (nothing underneath), Back would leave the
     * app. Put Home underneath first. If the app was already open, this screen sits on top of whatever was there as usual.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isTaskRoot && app.prefs.role == Role.MAIN_PHONE) startActivity(Intent(this, homeActivityFor(app.prefs.role)))
        super.onBackPressed()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        if (::closeV.isInitialized) {
            // Stage 40: the microphone indicator lives on this screen, so the uplink ends when the screen is no longer visible.
            app.uplink.stop("Call screen closed")
            app.uplink.removeListener(this)
            app.clientCalls.removeListener(this); app.connection.removeListener(this); app.callAudio.removeListener(this)
        }
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(finishNow); main.removeCallbacks(pendingTimeout)
        super.onDestroy()
    }

    // All three sources call back on the main thread.
    override fun onCallUiChanged() = refresh()
    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()
    override fun onCallAudioChanged(status: CallAudioStatus) = refresh()
    override fun onUplinkChanged() = refresh()

    // ---------------------------------------------------------------- Stage 40: microphone uplink

    /** The only place a user action starts or stops the uplink. Never starts after a permission dialog by itself. */
    private fun onUplinkButton() {
        val up = app.uplink
        if (up.isRunning) { up.stop(); refresh(); return }
        val r = up.start(echoAcknowledged = false)
        when (r.block) {
            null -> Unit                                       // negotiation started; the microphone opens only if the SIM Phone accepts
            // Stage 4: no microphone permission request while audio transfer is off (already unreachable: preflight returns FLAG_OFF first)
            UplinkBlock.PERMISSION ->
                if (AudioTransferFeatures.isTransferEnabled()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MICROPHONE)
                else toastMsg(AudioTransferFeatures.DISABLED_MESSAGE)
            UplinkBlock.ECHO_RISK -> AlertDialog.Builder(this)
                .setTitle("Possible echo")
                .setMessage("No headset was detected. The Main Phone speaker may feed back into the microphone. Echo cancellation has not been tested. Use a headset, or start anyway?")
                .setPositiveButton("Start anyway") { _, _ -> app.uplink.start(echoAcknowledged = true).let { if (!it.started) toastMsg(it.message) }; refresh() }
                .setNegativeButton("Cancel", null)
                .show()
            else -> toastMsg(r.message)
        }
        refresh()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MICROPHONE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        // Deliberately no automatic start: the user presses Start again.
        toastMsg(if (granted) "Microphone permission granted. Press Start again to begin." else "Microphone permission denied. The microphone stays off.")
        app.uplink.onEnvironmentChanged()
        refresh()
    }

    private fun toastMsg(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    private fun drawUplink() {
        val ui = UplinkUiState.main(app.uplink.snapshot())
        uplinkCard.show(ui.showSection)
        uplinkMicV.text = ui.microphoneLine
        // Red + bold text while the microphone captures; the words say the same, colour is secondary.
        uplinkMicV.setTextColor(if (ui.microphoneActive) Color.WHITE else Color.DKGRAY)
        uplinkMicV.background = if (ui.microphoneActive) roundBg(Color.rgb(198, 40, 40)) else null
        uplinkCapV.text = ui.capabilityLine
        uplinkConnV.text = ui.connectionLine
        uplinkWhyV.text = ui.explanation
        uplinkEchoV.text = ui.echoNote
        uplinkBtn.text = ui.buttonText
        uplinkBtn.setEnabledLook(ui.buttonEnabled)
        uplinkBtn.setToggleColor(ui.buttonIsStop)
        uplinkHintV.setTextAndShow(ui.buttonHint)
    }

    // ---------------------------------------------------------------- commands

    /** Sends one command and blocks a second tap until the SIM Phone reports a change, an error, or 5 s pass. */
    private fun press(p: Pending, send: () -> Unit) {
        if (pending != null) return
        pending = p
        pendingFrom = app.clientCalls.snapshot?.state
        pendingId = app.clientCalls.snapshot?.callId
        main.removeCallbacks(pendingTimeout); main.postDelayed(pendingTimeout, 5_000)
        send()
        refresh()
    }

    private fun clearPending() { pending = null; pendingId = null; main.removeCallbacks(pendingTimeout) }

    // ---------------------------------------------------------------- drawing

    private fun refresh() {
        if (!::closeV.isInitialized) return
        val cc = app.clientCalls
        val s = cc.snapshot
        remember(s)
        if (pending != null && (s?.state != pendingFrom || CallNotificationState.isOtherSession(pendingId, s?.callId) ||
                cc.lastError != null || !cc.linkUp)) clearPending()

        val audio = app.callAudio.status
        val seconds = cc.activeSince?.takeIf { s?.state == CallState.ACTIVE }
            ?.let { (SystemClock.elapsedRealtime() - it) / 1000 }
        val ui = CallScreenState.from(
            CallScreenInput(
                linkUp = cc.linkUp,
                linkText = HomeStatus.linkStatus(app.connection.state).label.lowercase(),
                snapshot = s, lastLive = lastLive, ended = ended,
                controlAvailable = cc.controlAvailable, simAudio = cc.simAudio, activeSeconds = seconds,
                audioState = audio.state, audioDetail = audio.detail,
                phoneMuteSupported = audio.muteSupported, phoneMicMuted = audio.micMuted,
                phoneSpeakerSupported = audio.speakerSupported, phoneSpeakerOn = audio.speakerOn,
                // One quick provider query for this number, then cached: the screen redraws every second.
                nameFor = { app.contacts.find(it)?.name }
            )
        )

        linkV.text = "Bluetooth: " + HomeStatus.linkStatus(app.connection.state).label
        (dot.background as GradientDrawable).setColor(if (cc.linkUp) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40))
        bannerV.setTextAndShow(ui.banner)
        titleV.text = ui.title
        showCaller(ui)
        statusV.setTextAndShow(ui.statusLine)
        durationV.setTextAndShow(ui.durationLine)
        audioStateV.setTextAndShow(ui.audioStateLine)
        audioStateV.setTextColor(when (ui.audioIndicator) {
            AudioIndicator.CONNECTED -> Color.rgb(46, 125, 50)
            AudioIndicator.CONNECTING -> Color.rgb(239, 108, 0)
            else -> Color.rgb(183, 28, 28)
        })   // colour is secondary; the words say the same
        audioNoteV.setTextAndShow(ui.audioNote)
        errV.setTextAndShow(cc.lastError?.let { "${cc.lastErrorCommand ?: "Command"} failed: ${CallScreenState.describeError(it)}" } ?: "")

        val busy = pending != null
        reject.show(ui.showReject); answer.show(ui.showAnswer); hangup.show(ui.showHangup)
        answer.text = if (pending == Pending.ANSWER) Pending.ANSWER.text else "Answer"
        reject.text = if (pending == Pending.REJECT) Pending.REJECT.text else "Reject"
        hangup.text = if (pending == Pending.HANGUP) Pending.HANGUP.text else "Hang up"
        listOf(answer, reject, hangup).forEach { it.setEnabledLook(!busy) }
        closeV.show(ui.mode == CallScreenMode.SIM_UNAVAILABLE)

        phoneAudioRow.show(ui.showPhoneAudioControls)
        phoneMute.text = when (ui.phoneMuteOn) { true -> "Unmute this phone"; false -> "Mute this phone"; null -> "Mute (unavailable)" }
        phoneSpeaker.text = when (ui.phoneSpeakerOn) { true -> "Speaker: on"; false -> "Speaker: off"; null -> "Speaker (unavailable)" }
        phoneMute.setEnabledLook(ui.phoneMuteEnabled); phoneSpeaker.setEnabledLook(ui.phoneSpeakerEnabled)
        phoneMute.setToggleColor(ui.phoneMuteOn == true); phoneSpeaker.setToggleColor(ui.phoneSpeakerOn == true)

        audioRow.show(ui.showAudioControls); audioCaptionV.show(ui.showAudioControls)
        mute.text = when (ui.muteOn) { true -> "Unmute"; false -> "Mute"; null -> "Mute (unknown)" }
        speaker.text = when (ui.speakerOn) { true -> "Speaker: on"; false -> "Speaker: off"; null -> "Speaker (unknown)" }
        mute.setEnabledLook(ui.muteEnabled); speaker.setEnabledLook(ui.speakerEnabled)
        mute.setToggleColor(ui.muteOn == true); speaker.setToggleColor(ui.speakerOn == true)

        drawUplink()

        when (ui.mode) {
            CallScreenMode.ENDED -> scheduleFinish(2_500)   // brief "Call ended", then back to the previous screen
            CallScreenMode.IDLE -> scheduleFinish(300)      // nothing to show
            else -> cancelFinish()
        }
    }

    /** Contact name + photo when the caller is a contact; otherwise only the number, large, as before. */
    private fun showCaller(ui: CallScreenUi) {
        val known = ui.nameLine.isNotEmpty()
        nameV.setTextAndShow(ui.nameLine)
        numberV.setTextAndShow(ui.numberLine)
        numberV.textSize = if (known) 18f else 32f
        avatarV.show(known)
        val key = if (known) "${ui.callerNumber}|${ui.nameLine}" else null
        if (key != avatarKey) {                                  // bind once per caller, not on every tick
            avatarKey = key
            if (known) app.avatars.bind(avatarV, ui.callerNumber, dp(AVATAR_DP))
        }
    }

    /** Keeps just enough to label "ended" / "unavailable" views. The SIM Phone's report stays the only source of call state. */
    private fun remember(s: CallSnapshot?) {
        when {
            s == null -> Unit
            CallScreenState.isLive(s.state) -> { lastLive = s; ended = null }
            s.state == CallState.DISCONNECTED -> {
                ended = EndedCall(s.number ?: lastLive?.number, s.disconnectCause); lastLive = null
            }
            s.state == CallState.IDLE && ended == null && lastLive != null -> {
                ended = EndedCall(lastLive?.number, null); lastLive = null   // SIM Phone went straight to IDLE
            }
        }
    }

    private fun scheduleFinish(delayMs: Long) {
        if (finishScheduled) return
        finishScheduled = true
        main.postDelayed(finishNow, delayMs)
    }

    private fun cancelFinish() { finishScheduled = false; main.removeCallbacks(finishNow) }

    // ---------------------------------------------------------------- view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun centered(size: Float) = TextView(this).apply { textSize = size; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(2)) }

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun TextView.setTextAndShow(t: String) { text = t; show(t.isNotEmpty()) }

    private fun Button.setEnabledLook(enabled: Boolean) { isEnabled = enabled; alpha = if (enabled) 1f else 0.4f }

    private fun roundBg(color: Int) = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(color) }

    private fun callButton(text: String, color: Int, onClick: () -> Unit): Button = button(text, onClick).apply {
        textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE); background = roundBg(color)
    }

    private fun toggleButton(text: String, onClick: () -> Unit): Button = button(text, onClick).apply {
        textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE); background = roundBg(NEUTRAL)
    }

    // Colour is a secondary cue only: the label text also changes (Mute/Unmute, Speaker: on/off).
    private fun Button.setToggleColor(on: Boolean) { background = roundBg(if (on) Color.rgb(21, 101, 192) else NEUTRAL) }

    companion object {
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
        private const val REQ_MICROPHONE = 4001
        private const val AVATAR_DP = 96
        private val NEUTRAL = Color.rgb(97, 97, 97)
    }
}
