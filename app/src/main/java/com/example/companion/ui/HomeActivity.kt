package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.calls.CallFeatures
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.contacts.ContactsState
import com.example.companion.data.Role
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.showLanguageDialog
import com.example.companion.i18n.tr
import com.example.companion.setup.SetupState

/**
 * Main Phone home screen. Shows the link to the SIM Phone and the entry points (Recent Calls is local history, so it works while disconnected).
 *
 * Stage 9: only the active features are reachable from here: Messages, Recent Calls (read-only history), Contacts and Settings.
 * The Calls and Dial Pad tiles are gone (one "Calls" placeholder shows the under-development message instead). The diagnostic,
 * audio, HFP and call screens are NOT deleted: their classes, manifest entries and the DEST_* targets below stay in the project.
 *
 * It owns no Bluetooth or call/SMS state: it reads [ConnectionManager] (the single owner of connection state) and
 * redraws from it whenever that changes. The status-to-text rules live in [HomeStatus] so they can be unit-tested.
 */
class HomeActivity : LocalizedActivity(), ConnectionManager.Listener, BluetoothManager.Listener {
    private lateinit var dot: StatusIndicator
    private lateinit var statusCard: LinearLayout
    private lateinit var noDeviceV: View
    private lateinit var statusV: TextView
    private lateinit var deviceV: TextView
    private lateinit var detailV: TextView
    private lateinit var hintV: TextView
    private lateinit var messages: Button
    private lateinit var setupBanner: LinearLayout
    private lateinit var setupBannerV: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = tr(StringKey.HOME_TITLE)
        if (app.prefs.role != Role.MAIN_PHONE) {
            // Stale launch (role changed): go to whatever is the right first screen for the current role.
            startActivity(Intent(this, homeActivityFor(app.prefs.role)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish(); return
        }

        val root = vbox()
        root.addView(buildStatusCard())
        root.addView(buildSetupBanner())

        // Stage 9: a 2x2 grid of the active features, then one placeholder row for calling. Same tile look as before (Ui.kt helpers).
        messages = navButton(tr(StringKey.HOME_TILE_MESSAGES), UiIcon.MESSAGES) { openGated(DEST_MESSAGES) }
        // Stage 12: local history only, so it is never gated on the Bluetooth link. Read-only: a tap on an entry shows the dev message.
        val recent = navButton(tr(StringKey.HOME_TILE_RECENT_CALLS), UiIcon.RECENT) { startActivity(Intent(this, DEST_RECENT_CALLS)) }
        val contacts = navButton(tr(StringKey.HOME_TILE_CONTACTS), UiIcon.CONTACTS) { openContacts() }                    // opens the phone's own contacts app
        val settings = navButton(tr(StringKey.HOME_TILE_SETTINGS), UiIcon.SETTINGS) { startActivity(Intent(this, DEST_SETTINGS)) }   // never gated
        root.addView(sectionHeader(StringKey.HOME_MENU_HEADER))
        root.addView(navRow(messages, recent), rowParams(top = 0))
        root.addView(navRow(contacts, settings), rowParams(top = 0))

        // Calling is off (CallFeatures): the one remaining calling entry only shows the under-development message and starts nothing.
        val callsSoon = navButton(tr(StringKey.HOME_TILE_CALLS), UiIcon.CALLS) { showCallingDisabledLocalized() }
        root.addView(callsSoon, rowParams(top = 0).apply { height = dp(tileHeightDp() * 3 / 4); setMargins(dp(4), dp(4), dp(4), dp(4)) })

        // Stage 24: with no SIM Phone saved the Messages tile (disabled) gets an explanation and one way forward (the existing Settings screen).
        // Stage 13: same three texts as UiPolish.NO_DEVICE, taken from the central catalog.
        val noDeviceText = EmptyText(tr(StringKey.EMPTY_NO_DEVICE_TITLE), tr(StringKey.EMPTY_NO_DEVICE_BODY), tr(StringKey.EMPTY_NO_DEVICE_ACTION))
        noDeviceV = emptyState(UiIcon.BLUETOOTH, noDeviceText) { startActivity(Intent(this, DEST_SETTINGS)) }
        noDeviceV.show(false)
        root.addView(noDeviceV)
        hintV = hintText(StringKey.HOME_HINT_MESSAGES_NEED_LINK).apply { textSize = 14f; setPadding(dp(4), dp(12), dp(4), dp(4)) }
        root.addView(hintV)
        // Stage 13: the language switch. It lives on Home (not Settings, which is not translated yet): it saves the choice and recreates
        // this screen; Messages and the conversation screen are built from the saved language every time they open.
        root.addView(button(tr(StringKey.LANGUAGE_CURRENT, AppLocale.current.nativeName)) { showLanguageDialog(app.prefs) })
        root.addView(hintText(StringKey.LANGUAGE_HINT))
        // Wide screens keep a readable column; a small screen scrolls.
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(constrainWidth(root)) })
        // Stage 22: no permission is asked from here any more. The Setup screen explains and asks; this screen only points to it.
    }

    // ---------------------------------------------------------------- views

    private fun buildStatusCard(): View {
        statusCard = card()
        dot = StatusIndicator(this, 14)
        statusV = TextView(this).apply {
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setPaddingRelative(dp(8), 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE     // screen readers announce changes
            followLayoutStart()                                                  // Stage 16: status word sits next to the dot on the start side
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot)
            addView(statusV)
        }
        deviceV = label().apply { textSize = 17f; setPadding(0, dp(12), 0, 0) }
        detailV = label().apply { textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY) }
        statusCard.addView(statusRow)
        statusCard.addView(deviceV)
        statusCard.addView(detailV)
        return statusCard
    }

    /** Stage 22: shown only while a required permission or role is missing; it opens the Setup screen. */
    private fun buildSetupBanner(): View {
        setupBannerV = TextView(this).apply { textSize = 15f; setTextColor(UiColors.ERROR); setPadding(0, 0, 0, dp(4)); followLayoutStart() }
        setupBanner = card(UiColors.TINT_ERROR).apply {
            addView(setupBannerV)
            addView(styledButton(StringKey.HOME_REVIEW_SETUP, ButtonStyle.PRIMARY) { startActivity(Intent(this@HomeActivity, SetupActivity::class.java)) })
            visibility = View.GONE
        }
        // A little space above the card; the card itself is hidden when nothing is missing.
        return setupBanner.also { it.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) } }
    }

    /** A big tile: icon above the label. Disabled tiles go flat grey, icon included. */
    private fun navButton(text: String, icon: UiIcon, onClick: () -> Unit) = styledButton(text, ButtonStyle.SECONDARY, onClick).apply {
        textSize = 18f
        compoundDrawablePadding = dp(6)
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, IconDrawable(icon, UiColors.PRIMARY, dp(28)), null, null)
    }

    /** Smaller tiles on short screens (landscape, split-screen) so every tile stays visible. */
    private fun tileHeightDp() = if (resources.configuration.screenHeightDp < 480) 80 else 96

    private fun navRow(left: Button, right: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(left, right).forEach {
            addView(it, LinearLayout.LayoutParams(0, dp(tileHeightDp()), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
    }

    private fun rowParams(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Stage 9: Contacts hands over to the phone's own contacts app (read-only here; no permission, no list of our own). */
    private fun openContacts() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, tr(StringKey.HOME_NO_CONTACTS_APP), Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- navigation

    /** Buttons are disabled while not connected; this re-checks at click time in case the link dropped a moment ago. */
    private fun openGated(target: Class<out Activity>) {
        // Stage 7: the Calls and Dial Pad tiles show the "under development" message instead of opening a call screen.
        if (!CallFeatures.isCallingEnabled() && (target == DEST_CALLS || target == DEST_DIAL_PAD)) { showCallingDisabledLocalized(); return }
        if (app.connection.state != ConnectionManager.State.CONNECTED) { refresh(); return }
        startActivity(Intent(this, target))
    }

    // ---------------------------------------------------------------- lifecycle / observing

    override fun onStart() {
        super.onStart()
        if (app.prefs.role != Role.MAIN_PHONE) return
        app.connection.addListener(this); app.bluetooth.addListener(this)
        ensureConnectionStarted(); refresh()
    }

    override fun onResume() {
        super.onResume()
        if (app.prefs.role != Role.MAIN_PHONE) return
        ensureConnectionStarted(); refresh()
    }

    override fun onStop() {
        app.connection.removeListener(this); app.bluetooth.removeListener(this)
        super.onStop()
    }

    // ConnectionManager and BluetoothManager both call back on the main thread, so these can touch views directly.
    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = refresh()
    override fun onAdapterStateChanged(enabled: Boolean) = refresh()

    // ---------------------------------------------------------------- drawing

    private fun refresh() {
        if (!::messages.isInitialized) return    // stale-role launch finished before the views were built
        val cm = app.connection
        val bt = app.bluetooth
        val ui = HomeStatus.from(
            state = cm.state,
            connectedName = cm.connectedName,
            savedName = app.prefs.deviceName,
            savedAddress = app.prefs.deviceAddress,
            retryAttempt = cm.retryAttempt,
            maxAttempts = cm.maxAttempts,
            lastError = com.example.companion.i18n.ConnectionErrorText.localizeOrNull(cm.lastError, AppLocale.current),   // Stage 20: display only
            bluetoothSupported = bt.isSupported,
            bluetoothEnabled = bt.isEnabled,
            permissionGranted = BtPermissions.granted(this, Role.MAIN_PHONE),
            language = AppLocale.current
        )
        statusV.text = ui.status.label(AppLocale.current)
        dot.set(ui.status.color(), UiPolish.isBusy(cm.state))        // spinner while connecting / reconnecting
        statusCard.setCardTint(ui.status.tint())
        deviceV.text = ui.deviceLine

        // Nothing is wrong except that no SIM Phone was chosen yet: one clear empty state instead of two repeated lines.
        val noDevice = UiPolish.needsDevice(Role.MAIN_PHONE, app.prefs.deviceName, app.prefs.deviceAddress) &&
            ui.detail == HomeStatus.selectDeviceDetail(AppLocale.current)
        detailV.text = ui.detail
        detailV.show(ui.detail.isNotEmpty() && !noDevice)
        noDeviceV.show(noDevice)

        messages.isEnabled = ui.actionsEnabled      // Recent Calls, Contacts, Settings and the Calls note stay tappable
        hintV.show(!ui.actionsEnabled && !noDevice)

        // Stage 13: the "Setup incomplete:" wording is localized. Stage 18: the row titles are too (Stage 15 translated them, but this call
        // still used the English default, so a Persian banner listed English titles). Which rows block is unchanged: only the title text differs.
        val banner = HomeStatus.setupBanner(SetupState.blockingRows(readSetupRows(AppLocale.current)).map { it.title }, AppLocale.current)
        setupBannerV.text = banner
        setupBanner.show(banner.isNotEmpty())
    }

    private companion object {
        // Where each button goes. DEST_CALLS and DEST_DIAL_PAD have no tile since Stage 9 but are kept (and still guarded in openGated).
        val DEST_CALLS: Class<out Activity> = CallActivity::class.java                 // existing call controls
        val DEST_MESSAGES: Class<out Activity> = SmsConversationsActivity::class.java   // existing SMS list
        val DEST_DIAL_PAD: Class<out Activity> = DialPadActivity::class.java            // Stage 11: dedicated dial pad
        val DEST_SETTINGS: Class<out Activity> = SettingsActivity::class.java          // Stage 18: Settings (diagnostics stay reachable from there)
        val DEST_RECENT_CALLS: Class<out Activity> = RecentCallsActivity::class.java   // Stage 12: local call history
    }
}
