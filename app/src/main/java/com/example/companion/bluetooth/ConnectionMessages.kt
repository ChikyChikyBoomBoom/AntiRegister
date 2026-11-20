package com.example.companion.bluetooth

/**
 * Stage 27: the English wording of the connection errors that [ConnectionManager] uses in more than one place. Pure Kotlin.
 *
 * [com.example.companion.i18n.ConnectionErrorText] translates by matching the EXACT English sentence against the catalog
 * (`StringKey.ERR_*`), so these texts must equal the catalog's English text character for character. They live here, outside the
 * Android-bound ConnectionManager, so a plain JVM test can check that (see `BluetoothErrorHandlingTest`).
 * The older one-off sentences still written inline in ConnectionManager are unchanged.
 */
object ConnectionMessages {
    const val PERMISSION_DENIED = "Bluetooth permission denied. Allow it in system settings."
    const val BT_OFF = "Bluetooth is turned off"
    const val BT_UNSUPPORTED = "Bluetooth is not supported on this device"
    /** Stage 27: the Main Phone stopped retrying because the link kept connecting and dropping at once ([ReconnectPolicy.flapLimitReached]). */
    const val LINK_UNSTABLE = "The Bluetooth connection keeps dropping. Press Connect to try again."
}
