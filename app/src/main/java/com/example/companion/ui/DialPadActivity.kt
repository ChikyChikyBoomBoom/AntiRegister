package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import com.example.companion.i18n.AppLocale
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.ClientCallController
import com.example.companion.contacts.ContactPicker
import com.example.companion.contacts.PickFailure
import com.example.companion.contacts.ContactsState
import com.example.companion.data.Role
import com.example.companion.protocol.CallState

/**
 * Main Phone dial pad.
 *
 * It owns no call logic. Call sends the number through [ClientCallController.dial] (the existing DIAL command), then waits
 * for the SIM Phone to REPORT the result through the same controller. Only when the SIM Phone reports a call does this
 * screen open the Stage 10 call screen; a rejection is shown here, under the number, with the number kept for another try.
 *
 * Stage 19: "Contacts" opens the system contact picker and puts the chosen number in the number box (Call is still a separate
 * press). Under the number, the contact's name is shown when the number is a contact (the picked one, or one the optional
 * Contacts permission can resolve). The picker needs no permission, and typing a number works as before.
 */
class DialPadActivity : LocalizedActivity(), ClientCallController.Listener, ConnectionManager.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val attemptTimeout = Runnable { onAttemptTimeout() }

    private lateinit var linkV: LinkBanner
    private lateinit var numberV: TextView
    private lateinit var contactV: TextView
    private lateinit var pickB: Button
    private lateinit var messageV: TextView
    private lateinit var deleteB: Button
    private lateinit var clearB: Button
    private lateinit var callB: Button
    private lateinit var returnB: Button
    private val keyButtons = ArrayList<Button>()

    private var number = ""
    private var awaiting = false
    private var baseline: CallState? = null
    private var attemptStartedAt = 0L
    private var failure: String? = null
    /** Stage 19: the contact chosen in the picker; its name is shown only while the box still holds exactly that number. */
    private var pickedName: String? = null
    private var pickedNumber: String? = null
    /** Stage 12: opened from Recent Calls with a number to call. Consumed once, in onStart; never repeated after rotation. */
    private var autoDialPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Dial Pad"
        if (app.prefs.role != Role.MAIN_PHONE) { finish(); return }
        // Stage 7: calling is off. Closes before anything is built, so neither a typed number nor EXTRA_AUTO_DIAL_NUMBER can be dialled.
        if (!CallFeatures.isCallingEnabled()) { showCallingDisabled(); finish(); return }
        number = savedInstanceState?.getString(STATE_NUMBER).orEmpty().take(DialPadState.MAX_LENGTH)
        pickedName = savedInstanceState?.getString(STATE_PICKED_NAME)
        pickedNumber = savedInstanceState?.getString(STATE_PICKED_NUMBER)
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_AUTO_DIAL_NUMBER)?.takeIf { it.isNotEmpty() }?.let {
                number = it.take(DialPadState.MAX_LENGTH); autoDialPending = true
            }
        }

        val root = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }

        // Stage 24: the same link strip as the other screens (spinner while connecting, words always shown).
        linkV = LinkBanner(this)
        root.addView(linkV, matchWidth())

        // Newest digits stay visible: a long number is cut at the start, never at the end.
        numberV = TextView(this).apply {
            textSize = 34f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(numberV, matchWidth(top = 16))
        contactV = TextView(this).apply {
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(UiColors.TEXT_SECONDARY); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(contactV, matchWidth())
        messageV = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(UiColors.ERROR)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(messageV, matchWidth(top = 4))

        DialPadState.KEYS.forEach { keys ->
            root.addView(row(keys.map { k ->
                keyButton(k).also { keyButtons.add(it) }
            }, height = keyHeightDp()), matchWidth(top = 4))
        }

        clearB = actionButton("Clear", UiColors.NEUTRAL) { if (!awaiting) { failure = null; number = DialPadState.clear(); refresh() } }
        deleteB = actionButton("Delete", UiColors.NEUTRAL) {
            if (!awaiting) { failure = null; number = DialPadState.deleteLast(number); refresh() }
        }.apply {
            // Press and hold clears the whole number (same as Clear).
            setOnLongClickListener {
                if (awaiting) return@setOnLongClickListener false
                failure = null; number = DialPadState.clear(); refresh(); true
            }
            contentDescription = "Delete. Press and hold to clear the number."
        }
        root.addView(row(listOf(clearB, deleteB), height = 56), matchWidth(top = 12))
        root.addView(hintText("Press and hold Delete to clear the number.").apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // already in Delete's description
        }, matchWidth(top = 4))

        pickB = actionButton("Contacts", UiColors.NEUTRAL) { pickContact() }
        root.addView(pickB, matchWidth(top = 8).apply { height = dp(56) })

        callB = actionButton("Call", UiColors.SUCCESS) { dial() }
        root.addView(callB, matchWidth(top = 16).apply { height = dp(72) })
        returnB = actionButton("Return to call", UiColors.PRIMARY) { openCallScreen() }
        root.addView(returnB, matchWidth(top = 8).apply { height = dp(56) })

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(constrainWidth(root, maxDp = 480)) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NUMBER, number)
        pickedName?.let { outState.putString(STATE_PICKED_NAME, it) }
        pickedNumber?.let { outState.putString(STATE_PICKED_NUMBER, it) }
    }

    // ---------------------------------------------------------------- contact picker (Stage 19)

    private fun pickContact() {
        if (awaiting) return
        if (!ContactPicker.start(this, REQ_PICK_CONTACT)) {
            Toast.makeText(this, ContactsState.noContactsApp(AppLocale.current), Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_CONTACT || resultCode != RESULT_OK || awaiting) return
        val result = ContactPicker.readResult(this, data?.data)
        val picked = result.contact
        if (picked == null) {
            // Stage 28: a contact that could not be read / has no number is explained instead of silently ignored (backing out stays silent).
            val reason = when (result.failure) {
                PickFailure.UNREADABLE -> ContactsState.pickUnreadable(AppLocale.current)
                PickFailure.NO_NUMBER -> ContactsState.pickNoNumber(AppLocale.current)
                else -> null
            }
            if (reason != null) Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            return
        }
        val digits = ContactsState.dialable(picked.number)
        if (digits == null) {
            failure = ContactsState.notUsable(AppLocale.current)            // shown as "Could not dial: ..."; the number box is left as it was
        } else {
            failure = null
            number = digits.take(DialPadState.MAX_LENGTH)
            pickedName = picked.name; pickedNumber = number
        }
        if (::callB.isInitialized) refresh()
    }

    /** Name line under the number: the picked contact, or a contact the lookup knows (resolved in the background, no Contacts permission needed for the picked one). */
    private fun updateContactLine() {
        val n = number
        val name = if (n.isEmpty()) null
                   else if (n == pickedNumber) pickedName
                   else app.contacts.findCached(n)?.name
        contactV.text = name.orEmpty()
        contactV.show(!name.isNullOrEmpty())
        if (name == null && n.isNotEmpty()) app.contacts.prefetch(listOf(n)) { if (!isDestroyed) refresh() }
    }

    // ---------------------------------------------------------------- lifecycle / observing

    override fun onStart() {
        super.onStart()
        if (!::callB.isInitialized) return
        app.clientCalls.addListener(this); app.connection.addListener(this)
        if (awaiting) {
            // The call screen may have been opened (and closed) while this screen was stopped: settle the attempt first.
            evaluate(openScreen = false)
            if (awaiting) {
                val left = DialPadState.ATTEMPT_TIMEOUT_MS - (SystemClock.elapsedRealtime() - attemptStartedAt)
                if (left <= 0) onAttemptTimeout() else { main.removeCallbacks(attemptTimeout); main.postDelayed(attemptTimeout, left) }
            }
        }
        refresh()
        if (autoDialPending) { autoDialPending = false; dial() }   // same path as pressing Call: the existing DIAL command
    }

    override fun onStop() {
        main.removeCallbacks(attemptTimeout)
        if (::callB.isInitialized) { app.clientCalls.removeListener(this); app.connection.removeListener(this) }
        super.onStop()
    }

    // Both sources call back on the main thread.
    override fun onCallUiChanged() { evaluate(openScreen = true); refresh() }
    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) { evaluate(openScreen = true); refresh() }

    // ---------------------------------------------------------------- dialing

    private fun pressKey(k: String) {
        if (awaiting) return
        failure = null
        number = DialPadState.append(number, k)
        refresh()
    }

    private fun dial() {
        val cc = app.clientCalls
        if (number.isEmpty() || awaiting) return               // never dial an empty number
        if (!cc.linkUp || !DialPadState.simAccepts(number)) { refresh(); return }   // re-check: the link may have just dropped
        if (CallScreenState.isLive(cc.snapshot?.state)) { refresh(); return }
        failure = null
        baseline = cc.snapshot?.state
        awaiting = true
        attemptStartedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(attemptTimeout); main.postDelayed(attemptTimeout, DialPadState.ATTEMPT_TIMEOUT_MS)
        cc.dial(number)                                        // the existing DIAL command; the number is sent exactly as typed
        refresh()
    }

    /** Looks at what the SIM Phone has reported since Call was pressed. Success is never assumed. */
    private fun evaluate(openScreen: Boolean) {
        if (!awaiting) return
        val cc = app.clientCalls
        if (!cc.linkUp) {
            endAttempt()
            failure = "The link dropped while dialing. A call may have started on the SIM Phone."
            return
        }
        when (DialPadState.outcome(baseline, cc.snapshot?.state, cc.lastError, cc.lastErrorCommand)) {
            DialOutcome.WAITING -> Unit
            DialOutcome.FAILED -> { endAttempt(); failure = CallScreenState.describeError(cc.lastError ?: "") }
            DialOutcome.CALL_STARTED -> {
                endAttempt()
                number = ""                                    // the number is now on the call screen
                if (openScreen) openCallScreen()
            }
        }
    }

    private fun onAttemptTimeout() {
        if (!awaiting) return
        endAttempt()
        failure = "the SIM Phone did not report a call. Check the SIM Phone, then try again."
        refresh()
    }

    private fun endAttempt() { awaiting = false; main.removeCallbacks(attemptTimeout) }

    /** Same flags as [CallScreenLauncher]: one call screen, never stacked. */
    private fun openCallScreen() {
        startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    // ---------------------------------------------------------------- drawing

    private fun refresh() {
        if (!::callB.isInitialized) return
        val cc = app.clientCalls
        val ui = DialPadState.from(number, cc.linkUp, CallScreenState.isLive(cc.snapshot?.state), awaiting, failure)

        val linkLabel = HomeStatus.linkStatus(app.connection.state).label
        linkV.update(linkStatusFor(cc.linkUp), "Bluetooth: $linkLabel")

        updateContactLine()
        numberV.text = ui.display
        numberV.setTextColor(if (ui.isEmpty) UiColors.TEXT_HINT else UiColors.TEXT)
        numberV.contentDescription = ui.spoken
        messageV.text = ui.message; messageV.show(ui.message.isNotEmpty())

        keyButtons.forEach { it.setEnabledLook(ui.keysEnabled) }
        pickB.setEnabledLook(!awaiting)
        deleteB.setEnabledLook(ui.deleteEnabled)
        clearB.setEnabledLook(ui.clearEnabled)
        callB.setEnabledLook(ui.callEnabled); callB.text = ui.callLabel
        returnB.show(ui.showReturnToCall)
    }

    // ---------------------------------------------------------------- view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Key height follows the screen: tall phones keep the big keys, short screens (landscape, split-screen) shrink them so the pad still fits. */
    private fun keyHeightDp() = (resources.configuration.screenHeightDp * 0.09).toInt().coerceIn(52, 76)

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun row(buttons: List<Button>, height: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, dp(height), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
    }

    private fun roundBg(color: Int) = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(color) }

    private fun keyButton(k: String): Button = button(k) { pressKey(k) }.apply {
        textSize = 28f; setTypeface(null, Typeface.BOLD); isAllCaps = false
        setTextColor(UiColors.TEXT); background = roundBg(Color.rgb(238, 238, 238))
        contentDescription = when (k) { "*" -> "star"; "#" -> "pound"; else -> k }
    }

    private fun actionButton(text: String, color: Int, onClick: () -> Unit): Button = button(text, onClick).apply {
        textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE); background = roundBg(color)
    }

    companion object {
        /** Stage 12: number to call as soon as the screen opens (sent by Recent Calls). */
        const val EXTRA_AUTO_DIAL_NUMBER = "dialpad.autoDialNumber"
        private const val STATE_NUMBER = "dialpad.number"
        private const val STATE_PICKED_NAME = "dialpad.pickedName"
        private const val STATE_PICKED_NUMBER = "dialpad.pickedNumber"
        private const val REQ_PICK_CONTACT = 1902
    }
}
