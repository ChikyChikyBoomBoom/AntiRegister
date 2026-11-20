package com.example.companion.ui

import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection

/** The four link states the Main Phone home screen shows. Always derived from [State]; never stored anywhere. */
enum class LinkStatus(val label: String) {
    CONNECTED("Connected"),
    CONNECTING("Connecting"),
    DISCONNECTED("Disconnected"),
    RECONNECTING("Reconnecting");

    /** Stage 13: the label in [language]. [label] stays the English text. */
    fun label(language: AppLanguage): String = Strings.text(
        when (this) {
            CONNECTED -> StringKey.LINK_CONNECTED
            CONNECTING -> StringKey.LINK_CONNECTING
            DISCONNECTED -> StringKey.LINK_DISCONNECTED
            RECONNECTING -> StringKey.LINK_RECONNECTING
        }, language
    )
}

/** Everything the home screen draws, computed in one place so the screen itself has no decisions to make. */
data class HomeUiState(
    val status: LinkStatus,
    val deviceLine: String,
    val detail: String,
    /** Calls, Messages and Dial Pad. True only while the Bluetooth link to the SIM Phone is up. */
    val actionsEnabled: Boolean
)

/** Pure function (no Android types) so it can be unit-tested on a plain JVM. */
object HomeStatus {

    /** Exhaustive on purpose: adding a [State] value will not compile until it is mapped here. */
    fun linkStatus(state: State): LinkStatus = when (state) {
        State.CONNECTED -> LinkStatus.CONNECTED
        State.CONNECTING, State.PAIRING -> LinkStatus.CONNECTING          // pairing is the first step of connecting
        State.RECONNECTING -> LinkStatus.RECONNECTING
        State.DISCONNECTED, State.DISCOVERING, State.LISTENING, State.ERROR -> LinkStatus.DISCONNECTED
    }

    fun from(
        state: State,
        connectedName: String?,
        savedName: String?,
        savedAddress: String?,
        retryAttempt: Int,
        maxAttempts: Int,
        lastError: String?,
        bluetoothSupported: Boolean,
        bluetoothEnabled: Boolean,
        permissionGranted: Boolean,
        /** Stage 13: language of the returned texts. English by default, so callers that do not pass it get the original wording. */
        language: AppLanguage = AppLanguage.ENGLISH
    ): HomeUiState {
        fun t(key: StringKey, vararg args: Any?) = Strings.text(key, language, *args)
        val status = linkStatus(state)
        val saved = clean(savedName) ?: clean(savedAddress)
        // While connected the socket's own device name wins; otherwise show the device the user selected.
        val name = if (state == State.CONNECTED) clean(connectedName) ?: saved else saved

        val deviceLine = when {
            name == null -> t(StringKey.HOME_DEVICE_NONE)
            status == LinkStatus.DISCONNECTED -> t(StringKey.HOME_DEVICE_SELECTED, TextDirection.isolate(name, language))
            else -> t(StringKey.HOME_DEVICE_CONNECTED, TextDirection.isolate(name, language))
        }

        val detail = when (status) {
            LinkStatus.CONNECTED -> ""
            LinkStatus.CONNECTING ->
                if (state == State.PAIRING) t(StringKey.HOME_DETAIL_PAIRING) else t(StringKey.HOME_DETAIL_OPENING)
            LinkStatus.RECONNECTING -> {
                // "of N" only while the count is bounded; a lost link keeps retrying after that (Stage 17), so later attempts have no total.
                val attempt = when {
                    retryAttempt in 1..maxAttempts -> t(
                        StringKey.HOME_DETAIL_ATTEMPT_OF,
                        TextDirection.number(retryAttempt, language), TextDirection.number(maxAttempts, language)
                    )
                    retryAttempt > maxAttempts -> t(StringKey.HOME_DETAIL_ATTEMPT, TextDirection.number(retryAttempt, language))
                    else -> t(StringKey.HOME_DETAIL_REACHING)
                }
                attempt + (clean(lastError)?.let { "\n$it" } ?: "")
            }
            LinkStatus.DISCONNECTED -> when {
                !permissionGranted -> t(StringKey.HOME_DETAIL_BT_PERMISSION)
                !bluetoothSupported -> t(StringKey.HOME_DETAIL_BT_UNSUPPORTED)
                !bluetoothEnabled -> t(StringKey.HOME_DETAIL_BT_OFF)
                name == null -> selectDeviceDetail(language)
                state == State.DISCOVERING -> t(StringKey.HOME_DETAIL_SCANNING)
                // lastError is only meaningful in ERROR; in DISCONNECTED it can be left over from an earlier failure.
                state == State.ERROR && clean(lastError) != null -> clean(lastError)!!
                else -> t(StringKey.HOME_DETAIL_OPEN_SETTINGS)
            }
        }

        return HomeUiState(status, deviceLine, detail, actionsEnabled = state == State.CONNECTED)
    }

    /** The detail shown when nothing is wrong except that no SIM Phone was selected yet (the Home screen swaps it for an empty state). */
    const val SELECT_DEVICE_DETAIL = "Open Settings to select your SIM Phone."

    /** Stage 13: the "select your SIM Phone" detail in [language]; the Home screen compares against it to show its empty state. */
    fun selectDeviceDetail(language: AppLanguage): String = Strings.text(StringKey.HOME_DETAIL_SELECT_DEVICE, language)

    /**
     * Stage 13: the banner above the menu. [titles] are the titles of the blocking setup rows (the Setup screen's own wording, which
     * is not translated yet, so they stay as they are). Empty list = no banner. English output equals SetupState.bannerText.
     */
    fun setupBanner(titles: List<String>, language: AppLanguage): String =
        if (titles.isEmpty()) ""
        else Strings.text(StringKey.HOME_SETUP_INCOMPLETE, language, titles.joinToString(", ").let { TextDirection.isolate(it, language) })

    private fun clean(s: String?): String? = s?.takeIf { it.isNotBlank() }
}
