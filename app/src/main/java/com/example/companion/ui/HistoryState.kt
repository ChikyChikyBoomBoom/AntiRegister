package com.example.companion.ui

import com.example.companion.contacts.ContactsState
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection
import com.example.companion.protocol.SmsMessages

/** The two local-history sections of the Main Phone. */
enum class HistoryTab(val label: String) { CALLS("Recent Calls"), MESSAGES("Messages") }

/** Entries of the long-press menu. */
enum class HistoryAction(val label: String) { CALL("Call"), SMS("Send message"), OPEN("Open conversation"), DELETE("Delete") }

/** Pure functions (no Android types) so the history screens' decisions can be unit-tested on a plain JVM. */
object HistoryState {

    const val NO_SMS_NUMBER = "This entry has no number that can receive a message."
    const val CALLS_HINT = "Tap a call to call back. Long-press for more options."
    const val MESSAGES_HINT = "Tap a conversation to open it. Long-press for more options."

    /**
     * The address to open a new/existing conversation with for a number from the call history, or null when the number
     * cannot be messaged (missing, or not a phone number the SMS code accepts). The number is never edited, only trimmed.
     */
    fun smsAddress(number: String?): String? = number?.trim()?.takeIf { SmsMessages.isValidRecipient(it) }

    /**
     * The number to hand to the existing dial path for an address from the Messages list: formatting characters
     * ("+1 (555) 123-4567") are removed, nothing else is changed. An address that cannot be made dialable (an alphanumeric
     * sender, a code) is returned unchanged, so [RecentCallsState.onTap] refuses it with its usual message.
     */
    fun dialNumber(address: String?): String? = ContactsState.dialable(address) ?: address

    fun menu(tab: HistoryTab): List<HistoryAction> = when (tab) {
        HistoryTab.CALLS -> listOf(HistoryAction.CALL, HistoryAction.SMS, HistoryAction.DELETE)
        HistoryTab.MESSAGES -> listOf(HistoryAction.OPEN, HistoryAction.CALL, HistoryAction.DELETE)
    }

    // Stage 14: every function below got an optional trailing `language` (default English = the old text). The Messages branches
    // reuse the Stage 13 keys; the Messages screen itself still builds its own dialogs (Stage 13) and does not call these.
    fun clearAllLabel(tab: HistoryTab, language: AppLanguage = AppLanguage.ENGLISH) = Strings.text(
        when (tab) {
            HistoryTab.CALLS -> StringKey.CALLS_CLEAR_ALL
            HistoryTab.MESSAGES -> StringKey.SMS_CLEAR_ALL
        }, language
    )

    fun clearAllTitle(tab: HistoryTab, language: AppLanguage = AppLanguage.ENGLISH) = Strings.text(
        when (tab) {
            HistoryTab.CALLS -> StringKey.CALLS_CLEAR_ALL_TITLE
            HistoryTab.MESSAGES -> StringKey.SMS_CLEAR_ALL_TITLE
        }, language
    )

    /** [count] = entries (calls) or conversations (messages); [messageCount] = stored messages, used for the Messages tab only. */
    fun clearAllMessage(tab: HistoryTab, count: Int, messageCount: Int = 0, language: AppLanguage = AppLanguage.ENGLISH): String = when (tab) {
        HistoryTab.CALLS ->
            if (count == 1) Strings.text(StringKey.CALLS_CLEAR_ALL_BODY_ONE, language)
            else Strings.text(StringKey.CALLS_CLEAR_ALL_BODY_MANY, language, TextDirection.number(count, language))
        HistoryTab.MESSAGES ->
            Strings.text(
                StringKey.SMS_CLEAR_ALL_BODY, language,
                if (count == 1) Strings.text(StringKey.SMS_COUNT_CONVERSATION_ONE, language)
                else Strings.text(StringKey.SMS_COUNT_CONVERSATION_MANY, language, TextDirection.number(count, language)),
                if (messageCount == 1) Strings.text(StringKey.SMS_COUNT_MESSAGE_ONE, language)
                else Strings.text(StringKey.SMS_COUNT_MESSAGE_MANY, language, TextDirection.number(messageCount, language))
            )
    }

    /** Stage 14: long-press menu entry of the Recent Calls screen. (The Messages screen has its own, SmsConversationsState.actionLabel.) */
    fun callsActionLabel(action: HistoryAction, language: AppLanguage = AppLanguage.ENGLISH): String = when (action) {
        HistoryAction.CALL -> Strings.text(StringKey.CALLS_ACTION_CALL, language)
        HistoryAction.SMS -> Strings.text(StringKey.CALLS_ACTION_SMS, language)
        HistoryAction.DELETE -> Strings.text(StringKey.CALLS_ACTION_DELETE, language)
        HistoryAction.OPEN -> Strings.text(StringKey.SMS_ACTION_OPEN, language)
    }

    fun noSmsNumber(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CALLS_NO_SMS_NUMBER, language)
    fun callsHint(language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(StringKey.CALLS_LIST_HINT, language)

    fun clearAllVisible(count: Int) = count > 0

    /**
     * Stage 17: what a screen reader says for the selected history tab. English: "Recent Calls, selected" (exactly the old text).
     * Persian: the tab label, a Persian comma and the existing catalog word for "selected".
     */
    fun tabSelectedDescription(tabLabel: String, language: AppLanguage = AppLanguage.ENGLISH): String =
        tabLabel + (if (language.isRtl) "، " else ", ") + Strings.text(StringKey.PICKER_SELECTED, language)
}
