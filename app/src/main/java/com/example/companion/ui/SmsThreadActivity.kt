package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import android.widget.TextView
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.contacts.ContactPicker
import com.example.companion.contacts.PickFailure
import com.example.companion.contacts.ContactsState
import com.example.companion.data.Role
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.tr
import com.example.companion.protocol.SmsMessages
import com.example.companion.sms.ClientSmsController
import com.example.companion.sms.SmsDiagnostics
import com.example.companion.sms.SmsErrorText
import com.example.companion.sms.threadKey
import java.text.DateFormat
import java.util.Date

/**
 * Main Phone: one conversation, or a new one when no address is given (the user types a number first).
 * Everything it shows comes from the local store and the existing [ClientSmsController]; it adds no transport.
 * What each message shows ("Sending", "Sent", "Failed: <reason>") is what the SIM Phone reported, see [SmsThreadState].
 * Send is enabled only when the SIM Phone link is up, the number is valid and the text is not empty ([SmsThreadState.composer]);
 * while the link is down the screen says so next to the text box and nothing is queued for later.
 *
 * Stage 19: the title shows the contact's name when the number is a contact (optional Contacts permission), with the number
 * under it. For a new message, "Contacts" opens the system contact picker and fills in the chosen number; typing a number
 * still works, and nothing here needs the Contacts permission.
 */
class SmsThreadActivity : LocalizedActivity(), ClientSmsController.Listener, ConnectionManager.Listener {
    private lateinit var linkV: LinkBanner
    private lateinit var unavailableV: TextView
    private lateinit var errV: TextView
    private lateinit var loadErrV: TextView
    private lateinit var recipientErrV: TextView
    private lateinit var hintV: TextView
    private lateinit var recipient: EditText
    private lateinit var recipientRow: LinearLayout
    private lateinit var peerV: TextView
    private lateinit var message: EditText
    private lateinit var sendBtn: Button
    private lateinit var list: ListView
    private var address: String? = null
    private var fromNotification = false
    /** The contact chosen in the picker, so its name can be shown even without the Contacts permission. Valid only while the box still holds that number. */
    private var pickedName: String? = null
    private var pickedNumber: String? = null
    private var rows: List<SmsMessageRow> = emptyList()
    private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = bubble(this@SmsThreadActivity, rows[position])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The activity is recreated on rotation: restore a conversation started here (new number) and the unsent text.
        address = savedInstanceState?.getString(STATE_ADDRESS) ?: intent.getStringExtra(EXTRA_ADDRESS)
        fromNotification = intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)
        title = address?.let { TextDirection.isolate(it, AppLocale.current) } ?: tr(StringKey.SMS_NEW_MESSAGE)   // Stage 17: a number in the ActionBar keeps its order in Persian
        val root = vbox()
        linkV = LinkBanner(this)      // Stage 22: the same link strip as Messages and Recent Calls
        recipient = EditText(this).apply { hint = tr(StringKey.SMS_RECIPIENT_HINT); inputType = InputType.TYPE_CLASS_PHONE; styleAsPhoneField() }   // Stage 16: digits never reorder
        recipientRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(recipient, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button(StringKey.SMS_PICK_CONTACT) { pickContact() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).marginsRelative(start = rowGap()))
        }
        peerV = secondaryText()
        recipientErrV = errorText().apply { textSize = 13f }
        list = ListView(this).apply {
            adapter = this@SmsThreadActivity.adapter
            divider = null
            isStackFromBottom = true
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            // Tapping a failed message puts its text back in the box so the user can send it again (as a NEW message).
            setOnItemClickListener { _, _, pos, _ ->
                val r = rows.getOrNull(pos) ?: return@setOnItemClickListener      // Stage 26: the list can change under a tap
                if (r.reusable) { message.setText(r.body); message.setSelection(message.text.length) }
            }
        }
        message = EditText(this).apply { hint = tr(StringKey.SMS_MESSAGE_HINT); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4; styleAsTextField() }
        sendBtn = styledButton(StringKey.SMS_SEND, ButtonStyle.PRIMARY) { onSend() }      // Stage 22: the one main action of this screen is drawn as the primary button
        unavailableV = errorText()
        errV = errorText()
        loadErrV = errorText()       // Stage 26: "could not load the messages" (shown only while the local message store cannot be read)
        hintV = hintText()
        root.addView(linkV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        root.addView(recipientRow)
        root.addView(peerV)
        root.addView(recipientErrV)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(unavailableV)
        root.addView(loadErrV)
        root.addView(errV)
        root.addView(hintV)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL      // Stage 22: the box and the taller Send button share one centre line
            addView(message, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).marginsRelative(start = rowGap()))
        })
        setContentView(constrainWidth(root))      // Stage 22: same readable column on wide screens as the other screens

        pickedName = savedInstanceState?.getString(STATE_PICKED_NAME)
        pickedNumber = savedInstanceState?.getString(STATE_PICKED_NUMBER)
        savedInstanceState?.getString(STATE_RECIPIENT)?.let { recipient.setText(it) }
        savedInstanceState?.getString(STATE_DRAFT)?.let { message.setText(it) }
        // Registered after the restore above, so restoring does not clear a refusal message.
        recipient.addTextChangedListener(watcher { errV.show(false); updateComposer(); updatePeer(); if (address == null) loadMessages() })
        message.addTextChangedListener(watcher { errV.show(false); updateComposer() })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        address?.let { outState.putString(STATE_ADDRESS, it) }
        outState.putString(STATE_RECIPIENT, recipient.text.toString())
        outState.putString(STATE_DRAFT, message.text.toString())
        pickedName?.let { outState.putString(STATE_PICKED_NAME, it) }
        pickedNumber?.let { outState.putString(STATE_PICKED_NUMBER, it) }
    }

    // ---------------------------------------------------------------- contact picker (Stage 19)

    private fun pickContact() {
        if (!ContactPicker.start(this, REQ_PICK_CONTACT)) {
            Toast.makeText(this, tr(StringKey.HOME_NO_CONTACTS_APP), Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_CONTACT || resultCode != RESULT_OK) return
        val result = ContactPicker.readResult(this, data?.data)
        val picked = result.contact
        if (picked == null) {
            // Stage 28: contact-picker result only (no message is sent, stored or received here): say why nothing was filled in. Backing out stays silent.
            val reason = when (result.failure) {
                PickFailure.UNREADABLE -> ContactsState.pickUnreadable(AppLocale.current)
                PickFailure.NO_NUMBER -> ContactsState.pickNoNumber(AppLocale.current)
                else -> null
            }
            if (reason != null) Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            return
        }
        val number = ContactsState.dialable(picked.number)
        if (number == null) {
            errV.text = tr(StringKey.SMS_CONTACT_UNUSABLE, tr(StringKey.SMS_CONTACT_NOT_USABLE_REASON)); errV.show(true)   // nothing is changed
            return
        }
        pickedName = picked.name; pickedNumber = number
        recipient.setText(number)                  // the text watcher updates the composer, the name line and the messages shown
        recipient.setSelection(recipient.text.length)
    }

    /** Title and name line for the current address / recipient. Names come only from the picker or what the lookup already knows. */
    private fun updateTitle() {
        val a = address
        title = when {
            a != null -> TextDirection.isolate(SmsConversationsState.title(a) { app.contacts.findCached(it)?.name }, AppLocale.current)   // Stage 17: name or number, order kept in Persian
            else -> pickedName?.takeIf { pickedNumber == recipient.text.toString().trim() }?.let { TextDirection.isolate(it, AppLocale.current) } ?: tr(StringKey.SMS_NEW_MESSAGE)
        }
    }

    private fun updatePeer() {
        val a = address
        val shown = a ?: recipient.text.toString().trim()
        val name = if (a == null && pickedNumber != null && shown == pickedNumber) pickedName
                   else app.contacts.findCached(shown)?.name
        peerV.text = when {
            name == null -> ""
            a != null -> shown                    // the title already shows the name; show the number under it
            else -> tr(StringKey.SMS_TO_NAME, TextDirection.isolate(name, AppLocale.current))
        }
        peerV.show(peerV.text.isNotEmpty())
        // Unknown numbers are resolved in the background; the screen redraws once. (Does nothing without the permission.)
        if (name == null) app.contacts.prefetch(listOf(shown)) { if (!isDestroyed) { updateTitle(); updatePeer() } }
        updateTitle()
    }

    private fun onSend() {
        val to = address ?: recipient.text.toString().trim()
        val body = SmsThreadState.outgoingBody(message.text.toString())
        // The controller re-checks everything (empty, number, length, link) and is the real gate; the button state is only a first line.
        // Stage 26: it does not throw, but the screen does not rely on that: an unexpected exception becomes a plain refusal, not a crash.
        val reason = try { app.clientSms.send(to, body) } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.UI, "send call threw", e)
            SmsErrorText.REFUSE_INTERNAL
        }
        // Stage 13: the controller's English refusal is only re-worded for display; what it decided is unchanged.
        if (reason != null) { errV.text = SmsErrorText.refusal(reason, AppLocale.current); errV.show(true); updateComposer(); return }     // refused: the text stays in the box
        errV.show(false)
        message.setText("")
        if (address == null) { address = to; recipientRow.show(false) }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        app.clientSms.addListener(this); app.connection.addListener(this)
        // Stage 15: opened from a notification, possibly after the process was restarted. Same start rule as the Home screen
        // (the one existing ConnectionManager; nothing starts after an error or an explicit Disconnect).
        if (fromNotification && app.prefs.role == Role.MAIN_PHONE) ensureConnectionStarted(userIntent = true)
        refresh()
    }

    /**
     * Stage 15: if a notification opened this screen as the first screen of the app (nothing underneath), Back would leave the
     * app. Put Home and the Messages list underneath first, so Back goes to Messages, then Home. If the app was already open,
     * this screen sits on top of whatever was there and Back returns to it as usual.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isTaskRoot && app.prefs.role == Role.MAIN_PHONE) {
            startActivities(arrayOf(
                Intent(this, homeActivityFor(app.prefs.role)),
                Intent(this, SmsConversationsActivity::class.java)       // last = on top once this screen is finished
            ))
        }
        super.onBackPressed()
    }

    override fun onStop() {
        app.clientSms.removeListener(this); app.connection.removeListener(this)
        super.onStop()
    }

    override fun onSmsChanged() = refresh()
    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()

    private fun refresh() {
        val isMain = app.prefs.role == Role.MAIN_PHONE
        val up = app.clientSms.linkUp
        val linkLine = SmsThreadState.linkLine(isMain, up, AppLocale.current)
        if (isMain) linkV.update(linkStatusFor(up), linkLine) else linkV.note(linkLine)     // Stage 22: same dot + word strip as the other screens; the words are unchanged
        recipientRow.show(address == null)
        updatePeer()
        // This conversation is on screen, so what it holds (including a message that just arrived) is read. Only an address that
        // was opened or already used counts: a number still being typed must not mark an unrelated conversation read.
        if (isMain) address?.let { app.clientSms.markRead(it) }
        loadMessages()
        updateComposer()
    }

    /** Messages of the open conversation; while a new number is being typed, those of the conversation that number already has (if any). */
    private fun loadMessages() {
        // Stage 26: a database or formatting problem leaves the screen empty with a clear sentence instead of crashing it.
        try {
            val shown = address ?: recipient.text.toString().trim().takeIf { SmsMessages.isValidRecipient(it) }
            val entries = shown?.let { app.smsStore.thread(threadKey(it)) } ?: emptyList()
            // Stage 16: in Persian the formatted date/time is isolated left-to-right so it keeps its order inside the Persian status line.
            rows = SmsThreadState.rows(entries, app.clientSms.linkUp, AppLocale.current) { TextDirection.ltrData(timeFormat.format(Date(it)), AppLocale.current) }
            loadErrV.show(false)
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.UI, "load conversation failed", e)
            rows = emptyList()
            loadErrV.text = tr(StringKey.SMS_ERROR_LOAD); loadErrV.show(true)
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateComposer() {
        val c = SmsThreadState.composer(
            app.prefs.role == Role.MAIN_PHONE, app.clientSms.linkUp, address,
            recipient.text.toString(), message.text.toString(), AppLocale.current,
            linkIssue = app.connection.linkIssue        // Stage 27: names the Bluetooth cause in the notice; Send's enabled state is unchanged
        )
        sendBtn.isEnabled = c.sendEnabled
        unavailableV.text = c.unavailableText; unavailableV.show(c.unavailableText.isNotEmpty())
        recipientErrV.text = c.recipientError; recipientErrV.show(c.recipientError.isNotEmpty())
        hintV.text = c.hintText; hintV.show(c.hintText.isNotEmpty())
    }

    private fun watcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = action()
    }

    private fun bubble(c: Context, r: SmsMessageRow): View {
        val d = resources.displayMetrics.density
        val bubbleColor = when (r.kind) {
            BubbleKind.RECEIVED -> UiColors.BUBBLE_RECEIVED
            BubbleKind.SENDING -> UiColors.BUBBLE_SENDING
            BubbleKind.SENT -> UiColors.BUBBLE_SENT
            BubbleKind.FAILED -> UiColors.BUBBLE_FAILED
        }
        val statusColor = when (r.kind) {
            BubbleKind.FAILED -> UiColors.ERROR
            BubbleKind.SENDING -> UiColors.PRIMARY
            else -> UiColors.TEXT_HINT
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (r.outgoing) Gravity.END else Gravity.START
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            contentDescription = r.description
            addView(TextView(c).apply {
                text = r.body; textSize = 16f
                followContentDirection()        // Stage 16: each SMS aligns and flows by its own language, whatever the screen's direction
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO     // the row description reads body, time and status
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(bubbleColor)
                    // A message still being sent is drawn with a dashed outline, so it differs from "Sent" without relying on colour alone.
                    if (r.kind == BubbleKind.SENDING) setStroke((1 * d).toInt().coerceAtLeast(1), UiColors.PRIMARY, 6 * d, 4 * d)
                }
            })
            addView(TextView(c).apply {
                textSize = 12f      // Stage 22: 12sp like the time in the Messages list (was 11)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                text = listOf(r.timeText, r.statusText + if (r.reusable) " " + tr(StringKey.SMS_TAP_TO_REUSE) else "")
                    .filter { it.isNotBlank() }.joinToString(" · ")
                setTextColor(statusColor)
                followAppDirection()            // Stage 16: "time · status" is app text; the time inside it is isolated left-to-right
            })
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        /** Stage 15: set by the SMS notification's tap action. */
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
        private const val STATE_ADDRESS = "state_address"
        private const val STATE_RECIPIENT = "state_recipient"
        private const val STATE_DRAFT = "state_draft"
        private const val STATE_PICKED_NAME = "state_picked_name"
        private const val STATE_PICKED_NUMBER = "state_picked_number"
        private const val REQ_PICK_CONTACT = 1901
    }
}
