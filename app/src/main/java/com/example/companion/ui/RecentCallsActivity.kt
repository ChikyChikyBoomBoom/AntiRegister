package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.calls.CallFeatures
import com.example.companion.calls.CallHistoryRecorder
import com.example.companion.calls.ClientCallController
import com.example.companion.data.Role
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.tr
import java.text.DateFormat
import java.util.Date

/**
 * Main Phone: Recent Calls. Reads the local [com.example.companion.calls.CallHistoryStore] (newest first) and draws it.
 *
 * It records nothing itself (that is [CallHistoryRecorder], fed by the existing call-state system) and owns no call
 * logic. Tapping an entry hands the number to [DialPadActivity], which sends the existing DIAL command and reports the
 * SIM Phone's answer exactly as for a typed number. Delete removes one entry from this phone only.
 *
 * Stage 19: when the optional Contacts permission is granted, a number that is a contact shows the contact's name and
 * photo (the number moves to a second line); otherwise, and for numbers that are not contacts, the number is shown as before.
 * The stored history is never changed: only what is drawn.
 *
 * Stage 14: every text on this screen comes from the central catalog ([tr] / the `*State` helpers) in the selected language
 * (English fallback). Only wording changed: the retrieval, the contact lookup, the read-only rules ([CallFeatures]) and what a tap
 * does are exactly as before.
 */
class RecentCallsActivity : LocalizedActivity(), CallHistoryRecorder.Listener, ConnectionManager.Listener, ClientCallController.Listener {

    private lateinit var linkV: LinkBanner
    private lateinit var emptyV: View
    private lateinit var contactsPrompt: ContactsPrompt
    private lateinit var list: ListView
    private lateinit var hintV: TextView
    private lateinit var clearBtn: Button
    private var rows: List<RecentCallRow> = emptyList()
    private val shownLanguage: AppLanguage = AppLocale.current      // Stage 14: the language this instance was built in
    private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = rowView(this@RecentCallsActivity, rows[position])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = tr(StringKey.CALLS_TITLE)
        if (app.prefs.role != Role.MAIN_PHONE) { finish(); return }

        val root = vbox()
        root.addView(historyTabBar(HistoryTab.CALLS) { SmsConversationsState.tabLabel(it, AppLocale.current) })
        linkV = LinkBanner(this)
        root.addView(linkV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        contactsPrompt = ContactsPrompt(this) { refresh() }
        root.addView(contactsPrompt.view)
        // Stage 24: an empty history is an explained state, not a blank screen (it takes the list's space).
        emptyV = emptyState(UiIcon.RECENT, EmptyText(tr(StringKey.CALLS_EMPTY_TITLE), tr(StringKey.CALLS_EMPTY_BODY)))   // same two texts as UiPolish.NO_CALLS
        root.addView(emptyV, fillRemaining())
        list = ListView(this).apply {
            adapter = this@RecentCallsActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onRowTapped(rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> onRowLongPressed(rows[pos]); true }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        hintV = hintText(HistoryState.callsHint(AppLocale.current))
        root.addView(hintV)
        clearBtn = button(HistoryState.clearAllLabel(HistoryTab.CALLS, AppLocale.current)) { onClearAll() }
        root.addView(clearBtn)
        setContentView(constrainWidth(root))
    }

    // ---------------------------------------------------------------- lifecycle / observing

    override fun onStart() {
        super.onStart()
        if (!::list.isInitialized) return
        // Stage 14: if the language was changed while this screen was waiting in the back stack, redraw it in the new language.
        if (shownLanguage != AppLocale.current) { recreate(); return }
        app.callHistoryRecorder.addListener(this); app.connection.addListener(this); app.clientCalls.addListener(this)
        contactsPrompt.update()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (::contactsPrompt.isInitialized) contactsPrompt.onPermissionResult(code)
    }

    override fun onStop() {
        if (::list.isInitialized) {
            app.callHistoryRecorder.removeListener(this); app.connection.removeListener(this); app.clientCalls.removeListener(this)
        }
        super.onStop()
    }

    // All three call back on the main thread.
    override fun onCallHistoryChanged() = refresh()
    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()
    override fun onCallUiChanged() = refresh()

    // ---------------------------------------------------------------- actions

    private fun onRowTapped(row: RecentCallRow) {
        // Stage 7: history is read-only. A tap (or the menu's "Call") shows the message and never reaches the dial pad.
        if (!CallFeatures.isCallingEnabled()) { showCallingDisabledLocalized(); return }
        val cc = app.clientCalls
        val tap = RecentCallsState.onTap(row.number, cc.linkUp, CallScreenState.isLive(cc.snapshot?.state))
        if (tap != RecentCallTap.DIAL) { Toast.makeText(this, RecentCallsState.tapMessage(tap, AppLocale.current), Toast.LENGTH_LONG).show(); return }
        // The dial pad sends the existing DIAL command and shows the SIM Phone's reply; this screen has no dialing code of its own.
        startActivity(Intent(this, DialPadActivity::class.java).putExtra(DialPadActivity.EXTRA_AUTO_DIAL_NUMBER, row.number))
    }

    /** Stage 20: opens the existing conversation screen for this number (it has its own composer and link checks). */
    private fun sendSms(row: RecentCallRow) {
        val address = HistoryState.smsAddress(row.number)
        if (address == null) { Toast.makeText(this, HistoryState.noSmsNumber(AppLocale.current), Toast.LENGTH_LONG).show(); return }
        startActivity(Intent(this, SmsThreadActivity::class.java).putExtra(SmsThreadActivity.EXTRA_ADDRESS, address))
    }

    private fun onRowLongPressed(row: RecentCallRow) =
        showHistoryMenu(TextDirection.isolate(row.title, AppLocale.current), HistoryState.menu(HistoryTab.CALLS), AppLocale.current) {   // Stage 17: dialog title keeps a number's order
            when (it) {
                HistoryAction.CALL -> onRowTapped(row)
                HistoryAction.SMS -> sendSms(row)
                HistoryAction.DELETE -> confirmDelete(row)
                HistoryAction.OPEN -> Unit
            }
        }

    private fun onClearAll() {
        val count = rows.size
        if (count == 0) return
        if (!CallFeatures.isHistoryEditingEnabled()) { showCallingDisabledLocalized(); return }   // Stage 7: read-only history
        confirmClearAll(HistoryTab.CALLS, count, language = AppLocale.current) {
            try { app.callHistory.clearAll() } catch (e: SQLiteException) {
                Toast.makeText(this, tr(StringKey.CALLS_ERROR_CLEAR), Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun confirmDelete(row: RecentCallRow) {
        if (!CallFeatures.isHistoryEditingEnabled()) { showCallingDisabledLocalized(); return }   // Stage 7: read-only history
        AlertDialog.Builder(this)
            .setTitle(tr(StringKey.CALLS_DELETE_TITLE))
            .setMessage(tr(StringKey.CALLS_DELETE_BODY, TextDirection.isolate(row.title, AppLocale.current), row.detail))
            .setPositiveButton(tr(StringKey.CALLS_ACTION_DELETE)) { _, _ ->
                try { app.callHistory.delete(row.id) } catch (e: SQLiteException) {
                    Toast.makeText(this, tr(StringKey.CALLS_ERROR_DELETE), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton(tr(StringKey.ACTION_CANCEL), null)
            .show()
    }

    // ---------------------------------------------------------------- drawing

    private fun refresh() {
        if (!::list.isInitialized || isDestroyed) return
        linkV.update(linkStatusFor(app.clientCalls.linkUp), RecentCallsState.linkLine(app.clientCalls.linkUp, AppLocale.current))
        val entries = app.callHistory.all()
        // Contact names come only from what is already known; unknown numbers are resolved in the background and this redraws once.
        rows = entries.map { RecentCallsState.row(it, { ms -> timeFormat.format(Date(ms)) }, AppLocale.current) { n -> app.contacts.findCached(n)?.name } }
        app.contacts.prefetch(entries.map { it.number }) { refresh() }
        if (::contactsPrompt.isInitialized) contactsPrompt.update()   // Stage 28: shows / clears the "contact names unavailable" notice after a lookup
        adapter.notifyDataSetChanged()
        emptyV.show(rows.isEmpty()); list.show(rows.isNotEmpty())
        hintV.show(rows.isNotEmpty()); clearBtn.show(HistoryState.clearAllVisible(rows.size))
    }

    private fun rowView(c: Context, r: RecentCallRow): View {
        val d = resources.displayMetrics.density
        val p = (8 * d).toInt()
        val avatarSize = (40 * d).toInt()
        val avatar = ImageView(c).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO      // the title already names the caller
            app.avatars.bind(this, r.number, avatarSize)
        }
        val text = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(c).apply {
                text = r.title; textSize = 17f; setTypeface(null, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                followLayoutStart()          // Stage 17: a number stays left-to-right but starts at the start edge; a Persian name is right-to-left
                if (r.missed) setTextColor(UiColors.ERROR)
            })
            if (r.numberLine.isNotEmpty()) addView(TextView(c).apply {
                text = r.numberLine; textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                followLayoutStart()          // Stage 17: the number under a contact name
                setTextColor(if (r.missed) UiColors.ERROR else UiColors.TEXT_SECONDARY)
            })
            addView(TextView(c).apply {
                text = r.detail; textSize = 13f
                followAppDirection()         // Stage 17: "type · time · duration" is app text; the time and duration inside it are already isolated left-to-right
                setTextColor(if (r.missed) UiColors.ERROR else UiColors.TEXT_HINT)
            })
        }
        val sms = c.button(tr(StringKey.CALLS_BUTTON_SMS)) { sendSms(r) }.apply {
            makeCompact()
            isFocusable = false                                   // keep the row itself tappable
            contentDescription = tr(StringKey.CALLS_DESC_SMS, TextDirection.isolate(r.title, AppLocale.current))
            isEnabled = HistoryState.smsAddress(r.number) != null
        }
        val delete = c.button(tr(StringKey.CALLS_ACTION_DELETE)) { confirmDelete(r) }.apply {
            makeCompact()
            isFocusable = false                                   // keep the row itself tappable
            contentDescription = tr(StringKey.CALLS_DESC_DELETE, TextDirection.isolate(r.title, AppLocale.current))
        }
        val actions = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(sms, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(delete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p, p, p)
            addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = p })
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
