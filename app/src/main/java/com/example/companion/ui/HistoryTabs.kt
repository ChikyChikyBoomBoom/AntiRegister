package com.example.companion.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings

/**
 * Stage 20: the "Recent Calls | Messages" tab bar shared by the two history screens. Each tab is its own existing
 * activity; switching replaces the current screen (back still goes to Home) and moves no data. The selected tab is
 * shown in bold and is not tappable.
 */
fun Activity.historyTabBar(selected: HistoryTab, labelOf: (HistoryTab) -> String = { it.label }): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    HistoryTab.values().forEach { tab ->
        val isSelected = tab == selected
        val tabLabel = labelOf(tab)      // Stage 13: the Messages screen passes localized labels; Recent Calls keeps the English default
        // Stage 24: the selected tab is drawn filled (not greyed out like a disabled button); tapping it still does nothing.
        addView(styledButton(tabLabel, if (isSelected) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY) {
            if (!isSelected) switchHistoryTab(tab)
        }.apply {
            isClickable = !isSelected
            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            // Stage 17: "selected" came from English text only; it now uses the existing catalog word (English output is unchanged: "Recent Calls, selected").
            contentDescription = if (isSelected) HistoryState.tabSelectedDescription(tabLabel, AppLocale.current) else tabLabel
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
    }
}

private fun Activity.switchHistoryTab(tab: HistoryTab) {
    val target = when (tab) {
        HistoryTab.CALLS -> RecentCallsActivity::class.java
        HistoryTab.MESSAGES -> SmsConversationsActivity::class.java
    }
    startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
    finish()
}

/** A simple list menu of [actions]; [onPick] is called with the chosen one. */
fun Activity.showHistoryMenu(
    title: String,
    actions: List<HistoryAction>,
    language: AppLanguage = AppLanguage.ENGLISH,     // Stage 14: default English = the old menu; Recent Calls passes the selected language
    onPick: (HistoryAction) -> Unit
) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(actions.map { if (language == AppLanguage.ENGLISH) it.label else HistoryState.callsActionLabel(it, language) }.toTypedArray()) { _, i -> onPick(actions[i]) }
        .setNegativeButton(Strings.text(StringKey.ACTION_CANCEL, language), null)
        .show()
}

/** Confirmation for "Clear all": nothing is removed unless the user taps the confirm button. */
fun Activity.confirmClearAll(
    tab: HistoryTab,
    count: Int,
    messageCount: Int = 0,
    language: AppLanguage = AppLanguage.ENGLISH,     // Stage 14: default English = the old dialog
    onConfirm: () -> Unit
) {
    AlertDialog.Builder(this)
        .setTitle(HistoryState.clearAllTitle(tab, language))
        .setMessage(HistoryState.clearAllMessage(tab, count, messageCount, language))
        .setPositiveButton(Strings.text(StringKey.CALLS_CLEAR_ALL_CONFIRM, language)) { _, _ -> onConfirm() }
        .setNegativeButton(Strings.text(StringKey.ACTION_CANCEL, language), null)
        .show()
}
