package com.example.companion.ui

import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.bluetooth.ReconnectPolicy
import com.example.companion.data.Role
import com.example.companion.i18n.AppLanguage
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.Strings
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.localizedLabel
import com.example.companion.protocol.CallState

/** Everything the Settings screen draws from connection/role data, computed in one place so the screen has no decisions to make. */
data class SettingsUiState(
    val roleLine: String,
    val deviceLine: String,
    val statusLine: String,
    val detail: String,
    val connectLabel: String,
    val connectEnabled: Boolean,
    val connectHint: String,
    /** Main Phone only: the SIM Phone is chosen from a list. The SIM Phone just listens. */
    val canChangeDevice: Boolean,
    val changeDeviceLabel: String,
    /** The switch only does something on the Main Phone (the SIM Phone always listens again after a drop). */
    val autoReconnectApplies: Boolean,
    val autoReconnectNote: String
)

enum class DeviceChangeBlock { NONE, NOT_MAIN_PHONE, CALL_IN_PROGRESS, BLUETOOTH_UNSUPPORTED, BLUETOOTH_OFF, NEEDS_PERMISSION }

/**
 * Pure functions (no Android types) so they can be unit-tested on a plain JVM.
 *
 * Stage 15: the functions that return text take an optional `language` (last parameter, default English), so every existing call and
 * test keeps the original English wording. Decisions (what is blocked, what is enabled) are unchanged.
 */
object SettingsState {

    fun otherRole(role: Role): Role = if (role == Role.MAIN_PHONE) Role.SIM_PHONE else Role.MAIN_PHONE

    /** A call that must not be cut off by a role or device change. */
    fun callInProgress(state: CallState?): Boolean = state != null && state != CallState.IDLE && state != CallState.DISCONNECTED

    fun roleChangeBlock(callInProgress: Boolean, language: AppLanguage = AppLanguage.ENGLISH): String? =
        if (callInProgress) Strings.text(StringKey.SETTINGS_BLOCK_ROLE_CALL, language) else null

    fun deviceChangeBlock(role: Role?, callInProgress: Boolean, bluetoothSupported: Boolean, bluetoothEnabled: Boolean, permissionGranted: Boolean) = when {
        role != Role.MAIN_PHONE -> DeviceChangeBlock.NOT_MAIN_PHONE
        callInProgress -> DeviceChangeBlock.CALL_IN_PROGRESS
        !bluetoothSupported -> DeviceChangeBlock.BLUETOOTH_UNSUPPORTED
        !bluetoothEnabled -> DeviceChangeBlock.BLUETOOTH_OFF
        !permissionGranted -> DeviceChangeBlock.NEEDS_PERMISSION
        else -> DeviceChangeBlock.NONE
    }

    /** True while there is something to Disconnect (including a scan). */
    fun isActive(state: State) = when (state) {
        State.PAIRING, State.LISTENING, State.CONNECTING, State.CONNECTED, State.RECONNECTING, State.DISCOVERING -> true
        State.DISCONNECTED, State.ERROR -> false
    }

    fun statusText(state: State, language: AppLanguage = AppLanguage.ENGLISH): String = Strings.text(
        when (state) {
            State.CONNECTED -> StringKey.LINK_CONNECTED
            State.CONNECTING -> StringKey.SETTINGS_STATUS_CONNECTING
            State.PAIRING -> StringKey.SETTINGS_STATUS_PAIRING
            State.RECONNECTING -> StringKey.SETTINGS_STATUS_RECONNECTING
            State.LISTENING -> StringKey.SETTINGS_STATUS_LISTENING
            State.DISCOVERING -> StringKey.SETTINGS_STATUS_DISCOVERING
            State.DISCONNECTED -> StringKey.LINK_DISCONNECTED
            State.ERROR -> StringKey.LINK_DISCONNECTED
        }, language
    )

    /** Stage 15: "Attempt 2 of 6" / "Attempt 9" in [language] (Persian digits). English output equals [ReconnectPolicy.attemptText]. */
    fun attemptText(attempt: Int, language: AppLanguage = AppLanguage.ENGLISH): String =
        if (attempt in 1..ReconnectPolicy.FAST_ATTEMPTS)
            Strings.text(StringKey.HOME_DETAIL_ATTEMPT_OF, language,
                TextDirection.number(attempt, language), TextDirection.number(ReconnectPolicy.FAST_ATTEMPTS, language))
        else Strings.text(StringKey.HOME_DETAIL_ATTEMPT, language, TextDirection.number(attempt, language))

    fun from(
        role: Role?,
        state: State,
        connectedName: String?,
        savedName: String?,
        savedAddress: String?,
        retryAttempt: Int,
        lastError: String?,
        language: AppLanguage = AppLanguage.ENGLISH
    ): SettingsUiState {
        fun t(key: StringKey) = Strings.text(key, language)
        val isMain = role == Role.MAIN_PHONE
        val active = isActive(state)
        val name = if (state == State.CONNECTED) clean(connectedName) ?: clean(savedName) ?: clean(savedAddress)
                   else clean(savedName) ?: clean(savedAddress)
        val address = clean(savedAddress)
        val hasDevice = name != null

        val deviceLine = when {
            role == null -> "-"
            isMain -> when {
                !hasDevice -> t(StringKey.HOME_DEVICE_NONE)
                address != null && address != name -> "${TextDirection.isolate(name!!, language)}\n${TextDirection.isolate(address, language)}"
                else -> TextDirection.isolate(name!!, language)
            }
            state == State.CONNECTED -> clean(connectedName)?.let { TextDirection.isolate(it, language) } ?: t(StringKey.ROLE_MAIN_PHONE)
            else -> t(StringKey.SETTINGS_NO_MAIN)
        }

        val detail = when (state) {
            State.ERROR -> clean(lastError)?.let { com.example.companion.i18n.ConnectionErrorText.localize(it, language) } ?: ""
            State.PAIRING -> t(StringKey.SETTINGS_PAIRING_DETAIL)
            State.RECONNECTING -> {
                val attempt = if (retryAttempt > 0) attemptText(retryAttempt, language) else ""
                listOfNotNull(attempt.ifEmpty { null }, clean(lastError)?.let { com.example.companion.i18n.ConnectionErrorText.localize(it, language) }).joinToString("\n")
            }
            else -> ""
        }

        val canConnect = role != null && (active || !isMain || hasDevice)
        val hint = if (role != null && !canConnect) t(StringKey.SETTINGS_SELECT_SIM_FIRST) else ""

        return SettingsUiState(
            roleLine = role?.localizedLabel(language) ?: t(StringKey.ROLE_NOT_SELECTED),
            deviceLine = deviceLine,
            statusLine = statusText(state, language),
            detail = detail,
            connectLabel = if (active) t(StringKey.SETTINGS_DISCONNECT) else t(StringKey.SETTINGS_CONNECT),
            connectEnabled = canConnect,
            connectHint = hint,
            canChangeDevice = isMain,
            changeDeviceLabel = if (hasDevice) t(StringKey.SETTINGS_CHANGE_DEVICE) else t(StringKey.SETTINGS_SELECT_SIM),
            autoReconnectApplies = isMain,
            autoReconnectNote = if (isMain) t(StringKey.SETTINGS_AUTO_NOTE_MAIN) else t(StringKey.SETTINGS_AUTO_NOTE_SIM)
        )
    }

    /**
     * Label / value rows of the "About this app" section. Stage 15: the labels and the app name / data sentence follow [language]; the
     * values (version, package, Android release, model, Bluetooth name) are data and stay as they are, only kept left-to-right inside
     * Persian text. English output is unchanged.
     */
    fun appInfo(
        versionName: String, versionCode: Long, packageName: String, androidRelease: String, sdk: Int, model: String, bluetoothName: String?,
        language: AppLanguage = AppLanguage.ENGLISH
    ): List<Pair<String, String>> {
        fun t(key: StringKey) = Strings.text(key, language)
        fun ltr(v: String) = if (language == AppLanguage.PERSIAN) TextDirection.isolateLtr(v) else v
        return listOf(
            t(StringKey.SETTINGS_ABOUT_APP) to t(StringKey.APP_NAME),
            t(StringKey.SETTINGS_ABOUT_VERSION) to ltr("$versionName ($versionCode)"),
            t(StringKey.SETTINGS_ABOUT_PACKAGE) to ltr(packageName),
            t(StringKey.SETTINGS_ABOUT_ANDROID) to ltr("$androidRelease (API $sdk)"),
            t(StringKey.SETTINGS_ABOUT_THIS_PHONE) to ltr(model),
            t(StringKey.SETTINGS_ABOUT_BT_NAME) to (clean(bluetoothName)?.let { ltr(it) } ?: "-"),
            t(StringKey.SETTINGS_ABOUT_DATA) to t(StringKey.SETTINGS_ABOUT_DATA_VALUE)
        )
    }

    private fun clean(s: String?): String? = s?.takeIf { it.isNotBlank() }
}
