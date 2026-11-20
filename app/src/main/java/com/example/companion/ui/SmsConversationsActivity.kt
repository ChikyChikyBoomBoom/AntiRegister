package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.calls.CallFeatures
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.Role
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.tr
import com.example.companion.sms.ClientSmsController
import com.example.companion.sms.SmsDiagnostics
import java.text.DateFormat
import java.util.Date

/**
 * Main Phone: Messages list. Reads the local SMS store (newest conversation first) and observes the existing controllers;
 * it adds no transport and sends nothing. A row shows the contact name or number, the latest message, its time and an
 * unread indicator. Opening a row shows the conversation; that screen marks it read (see [SmsThreadActivity]).
 * Long-press deletes a conversation from this phone only; "New message" opens the message screen without an address.
 *
 * Stage 19: with the optional Contacts permission, a conversation whose number is a contact shows the contact's name and
 * photo instead of the number. Without it (or for numbers that are not contacts) the number is shown as before.
 */
class SmsConversationsActivity : LocalizedActivity(), ClientSmsController.Listener, ConnectionManager.Listener {
    private lateinit var linkV: LinkBanner
    private lateinit var noticeV: TextView
    private lateinit var noticeBtn: Button
    private lateinit var problemV: TextView
    private lateinit var emptyV: View
    private lateinit var contactsPrompt: ContactsPrompt
    private lateinit var list: ListView
    private lateinit var tabs: View
    private lateinit var hintV: TextView
    private lateinit var clearBtn: Button
    private var items: List<SmsConversationRow> = emptyList()
    private val clockFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val dateFormat = DateFormat.getDateInstance(DateFormat.SHORT)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(this@SmsConversationsActivity, items[position])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = tr(StringKey.SMS_TITLE)
        val root = vbox()
        tabs = historyTabBar(HistoryTab.MESSAGES) { SmsConversationsState.tabLabel(it, AppLocale.current) }
        root.addView(tabs)
        linkV = LinkBanner(this)
        root.addView(linkV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // Stage 15: shown only while new messages cannot notify the user (permission denied / switched off in system settings).
        noticeV = errorText()    // Stage 16: label-based, so it follows the layout start
        noticeBtn = button(StringKey.SMS_NOTIFICATION_SETTINGS) {
            // Stage 30: some phones have no such settings page; say so instead of ending the app.
            try { startActivity(NotificationAccess.settingsIntent(this)) }
            catch (e: Exception) { android.widget.Toast.makeText(this, tr(StringKey.SETUP_NO_SETTINGS_PAGE), android.widget.Toast.LENGTH_LONG).show() }
        }
        root.addView(noticeV)
        root.addView(noticeBtn)
        problemV = errorText()      // Stage 26: shown while a received SMS could not be stored, or the list could not be read
        root.addView(problemV)
        contactsPrompt = ContactsPrompt(this) { refresh() }
        root.addView(contactsPrompt.view)
        root.addView(styledButton(StringKey.SMS_NEW_MESSAGE, ButtonStyle.PRIMARY) { startActivity(Intent(this, SmsThreadActivity::class.java)) })
        // Stage 24: an empty inbox is an explained state, not a blank screen (it takes the list's space).
        // Stage 13: same two texts as UiPolish.NO_MESSAGES, from the central catalog.
        emptyV = emptyState(UiIcon.MESSAGES, EmptyText(tr(StringKey.SMS_EMPTY_TITLE), tr(StringKey.SMS_EMPTY_BODY)))
        root.addView(emptyV, fillRemaining())
        list = ListView(this).apply {
            adapter = this@SmsConversationsActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val c = items.getOrNull(pos) ?: return@setOnItemClickListener      // Stage 26: the list can change under a tap
                startActivity(Intent(this@SmsConversationsActivity, SmsThreadActivity::class.java)
                    .putExtra(SmsThreadActivity.EXTRA_ADDRESS, c.address))
            }
            setOnItemLongClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { onRowLongPressed(it) }; true }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        hintV = hintText(StringKey.SMS_LIST_HINT)
        root.addView(hintV)
        clearBtn = button(StringKey.SMS_CLEAR_ALL) { onClearAll() }
        root.addView(clearBtn)
        setContentView(constrainWidth(root))
    }

    private fun open(c: SmsConversationRow) = startActivity(Intent(this, SmsThreadActivity::class.java)
        .putExtra(SmsThreadActivity.EXTRA_ADDRESS, c.address))

    /**
     * Stage 20: call the number of a conversation. Uses exactly the Recent Calls rules (same refusal messages) and hands
     * the number to the existing dial pad, which sends the existing DIAL command. No dialing code lives here.
     */
    private fun call(c: SmsConversationRow) {
        if (!CallFeatures.isCallingEnabled()) { showCallingDisabledLocalized(); return }   // Stage 7: never hands a number to the dial pad
        val cc = app.clientCalls
        val number = HistoryState.dialNumber(c.address)
        val tap = RecentCallsState.onTap(number, cc.linkUp, CallScreenState.isLive(cc.snapshot?.state))
        if (tap != RecentCallTap.DIAL) { Toast.makeText(this, RecentCallsState.tapMessage(tap, AppLocale.current), Toast.LENGTH_LONG).show(); return }   // Stage 18: localized like Recent Calls
        startActivity(Intent(this, DialPadActivity::class.java).putExtra(DialPadActivity.EXTRA_AUTO_DIAL_NUMBER, number))
    }

    // Stage 13: the same menu as showHistoryMenu (shared with Recent Calls, which stays English), with localized entries.
    private fun onRowLongPressed(c: SmsConversationRow) {
        val actions = HistoryState.menu(HistoryTab.MESSAGES)
        AlertDialog.Builder(this)
            .setTitle(TextDirection.isolate(c.title, AppLocale.current))   // Stage 17: dialog title keeps a number's order in Persian
            .setItems(actions.map { SmsConversationsState.actionLabel(it, AppLocale.current) }.toTypedArray()) { _, i ->
                when (actions[i]) {
                    HistoryAction.OPEN -> open(c)
                    HistoryAction.CALL -> call(c)
                    HistoryAction.DELETE -> confirmDelete(c)
                    HistoryAction.SMS -> Unit
                }
            }
            .setNegativeButton(tr(StringKey.ACTION_CANCEL), null)
            .show()
    }

    private fun onClearAll() {
        val all = try { app.smsStore.conversations() } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.UI, "read conversations before clear failed", e)
            Toast.makeText(this, tr(StringKey.SMS_ERROR_LOAD), Toast.LENGTH_LONG).show()
            return
        }
        if (all.isEmpty()) return
        // Stage 13: same dialog as confirmClearAll (shared with Recent Calls, which stays English), localized for Messages.
        AlertDialog.Builder(this)
            .setTitle(tr(StringKey.SMS_CLEAR_ALL_TITLE))
            .setMessage(SmsConversationsState.clearAllBody(all.size, all.sumOf { it.count }, AppLocale.current))
            .setPositiveButton(tr(StringKey.SMS_CLEAR_ALL_CONFIRM)) { _, _ ->
                try {
                    app.smsStore.clearAll()
                    all.forEach { app.smsNotifier.cancel(it.key) }       // no notification may outlive its conversation
                } catch (e: Exception) {                                 // Stage 26: was SQLiteException only
                    SmsDiagnostics.record(SmsDiagnostics.Area.UI, "clear all messages failed", e)
                    Toast.makeText(this, tr(StringKey.SMS_ERROR_CLEAR), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton(tr(StringKey.ACTION_CANCEL), null)
            .show()
    }

    private fun confirmDelete(c: SmsConversationRow) {
        AlertDialog.Builder(this)
            .setTitle(tr(StringKey.SMS_DELETE_TITLE))
            .setMessage(tr(StringKey.SMS_DELETE_BODY, TextDirection.isolate(c.title, AppLocale.current)))
            .setPositiveButton(tr(StringKey.SMS_ACTION_DELETE)) { _, _ ->
                try { app.smsStore.deleteThread(c.key); app.smsNotifier.cancel(c.key) } catch (e: Exception) {      // Stage 26: was SQLiteException only
                    SmsDiagnostics.record(SmsDiagnostics.Area.UI, "delete conversation failed", e)
                    Toast.makeText(this, tr(StringKey.SMS_ERROR_DELETE), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton(tr(StringKey.ACTION_CANCEL), null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        app.clientSms.addListener(this); app.connection.addListener(this)
        contactsPrompt.update(visible = app.prefs.role == Role.MAIN_PHONE)
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (::contactsPrompt.isInitialized) contactsPrompt.onPermissionResult(code)
    }

    override fun onStop() {
        app.clientSms.removeListener(this); app.connection.removeListener(this)
        super.onStop()
    }

    // Coming back from the system settings page: re-read what Android allows.
    override fun onResume() {
        super.onResume()
        updateNotice()
    }

    private fun updateNotice() {
        // Stage 26: asking Android about notification access can fail on some devices; then no notice is shown (never a crash).
        val notice = try {
            if (app.prefs.role == Role.MAIN_PHONE) SmsNotificationState.accessNotice(app.smsNotifier.access(), AppLocale.current) else ""
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.UI, "notification access check failed", e); ""
        }
        noticeV.text = notice; noticeV.show(notice.isNotEmpty()); noticeBtn.show(notice.isNotEmpty())
    }

    override fun onSmsChanged() = refresh()
    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()

    private fun refresh() {
        if (app.prefs.role != Role.MAIN_PHONE) {
            linkV.note(tr(StringKey.SMS_NOT_MAIN_NOTE))
            emptyV.show(false); list.show(false); tabs.show(false); hintV.show(false); clearBtn.show(false); problemV.show(false)
            return
        }
        if (isDestroyed) return
        linkV.update(linkStatusFor(app.clientSms.linkUp), SmsConversationsState.linkLine(app.clientSms.linkUp, AppLocale.current))
        // Stage 26: a database or formatting problem shows a clear sentence and an empty list instead of crashing the screen.
        // A received SMS that could not be stored on this phone gets its own sentence (it is kept on the SIM Phone and retried).
        var loadFailed = false
        val conversations = try { app.smsStore.conversations() } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.UI, "load conversations failed", e); loadFailed = true; emptyList()
        }
        items = try {
            // Stage 19: names come only from what is already known; unknown numbers are resolved in the background and this redraws once.
            SmsConversationsState.rows(
                conversations, System.currentTimeMillis(), nameFor = { app.contacts.findCached(it)?.name },
                formatClock = { clockFormat.format(Date(it)) }, formatDate = { dateFormat.format(Date(it)) },
                language = AppLocale.current
            )
        } catch (e: Exception) {
            SmsDiagnostics.record(SmsDiagnostics.Area.UI, "build conversation rows failed", e); loadFailed = true; emptyList()
        }
        val problem = when {
            loadFailed -> tr(StringKey.SMS_ERROR_LOAD)
            app.clientSms.incomingNotSaved -> tr(StringKey.SMS_ERROR_INCOMING_NOT_SAVED)
            else -> ""
        }
        problemV.text = problem; problemV.show(problem.isNotEmpty())
        if (!loadFailed) app.contacts.prefetch(conversations.map { it.address }) { refresh() }
        adapter.notifyDataSetChanged()
        emptyV.show(items.isEmpty()); list.show(items.isNotEmpty())
        hintV.show(items.isNotEmpty()); clearBtn.show(HistoryState.clearAllVisible(items.size))
    }

    private fun row(c: Context, r: SmsConversationRow): View {
        val d = resources.displayMetrics.density
        val unread = r.hasUnread
        val dot = View(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(UiColors.PRIMARY) }
            visibility = if (unread) View.VISIBLE else View.INVISIBLE        // INVISIBLE keeps the rows aligned
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // the row description already says "unread"
        }
        val titleLine = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(c).apply {
                text = r.title; textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                followLayoutStart()          // Stage 16: a number stays left-to-right but starts at the start edge; a Persian name is right-to-left
                setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(c).apply {
                text = r.timeText; textSize = 12f; setPaddingRelative((8 * d).toInt(), 0, 0, 0)
                firstStrongOrLtr()                                  // Stage 17: a clock/date (no strong letter) stays left-to-right (plain first-strong would make it right-to-left in an RTL layout); a Persian word like "yesterday" flows right-to-left
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                setTextColor(if (unread) UiColors.PRIMARY else UiColors.TEXT_HINT)
            })
        }
        val avatarSize = (40 * d).toInt()
        val avatar = ImageView(c).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO      // the row description already names the contact
            app.avatars.bind(this, r.address, avatarSize)
        }
        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleLine)
            addView(TextView(c).apply {
                text = r.preview; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                followLayoutStart()          // Stage 16: the preview's own first letter decides its direction (SMS content)
                setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (unread) UiColors.TEXT else UiColors.TEXT_SECONDARY)
            })
            if (r.failedText.isNotEmpty()) addView(TextView(c).apply {
                text = r.failedText; textSize = 13f; setTextColor(UiColors.ERROR); followLayoutStart()
            })
        }
        val p = (8 * d).toInt()
        val callBtn = c.button(StringKey.SMS_ROW_CALL) { call(r) }.apply {
            makeCompact()
            isFocusable = false                                   // keep the row itself tappable
            contentDescription = tr(StringKey.SMS_ROW_CALL_DESC, TextDirection.isolate(r.title, AppLocale.current))
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p, p, p)
            contentDescription = r.description
            addView(dot, LinearLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt()).apply { marginEnd = p })
            addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = p })
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (unread) addView(TextView(c).apply {
                this.text = r.badgeText; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = (22 * d).toInt()
                setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(UiColors.PRIMARY) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = p })
            addView(callBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = p })
        }
    }
}
