package com.example.companion.ui

import android.app.Activity

/**
 * Starts the existing [com.example.companion.bluetooth.ConnectionManager] from a clean idle state. The rule itself lives in
 * [com.example.companion.bluetooth.ConnectionRestorePolicy] and is applied by [com.example.companion.app.CompanionApp.restoreConnection],
 * which the app also calls whenever it comes to the foreground (Stage 30), so a screen that Android restored after the process died
 * (a call screen, a message thread...) reconnects too, not only the home screen.
 * After an ERROR, or after the user pressed Disconnect, nothing starts automatically.
 *
 * [userIntent] = the person just asked for this by tapping a notification, so Auto-reconnect being off does not block it.
 */
fun Activity.ensureConnectionStarted(userIntent: Boolean = false) {
    app.restoreConnection(userIntent)
}
