package com.example.companion.bluetooth

import com.example.companion.data.Role

/**
 * Stage 30: the one rule for "should the app start the connection by itself now?" after the app was (re)opened or the process was
 * restarted. Pure (no Android types), so it can be unit-tested on a plain JVM. [ConnectionManager] stays the only thing that connects;
 * this only decides whether to call its existing start().
 *
 * Start only from a clean idle state, and only if every one of these holds:
 *  - a role is saved, and a Main Phone also has its saved SIM Phone (the SIM Phone just listens, it needs no device);
 *  - the connection is DISCONNECTED (a fresh process always is); never while something is running, and never after an ERROR;
 *  - the user did not press Disconnect (that choice is persisted and survives a restart);
 *  - [ConnectionManager] is not already going to resume by itself (Bluetooth was turned off while a connection was wanted);
 *  - the Bluetooth permissions are granted and Bluetooth is on;
 *  - Main Phone: Auto-reconnect is on, unless the person just asked for the connection by tapping a notification ([userIntent]).
 *    The SIM Phone always listens again, as before, so the setting does not apply to it.
 */
object ConnectionRestorePolicy {
    fun shouldStart(
        role: Role?,
        hasSavedDevice: Boolean,
        state: ConnectionManager.State,
        userStopped: Boolean,
        resumePending: Boolean,
        permissionsGranted: Boolean,
        bluetoothEnabled: Boolean,
        autoReconnect: Boolean,
        userIntent: Boolean
    ): Boolean = when {
        role == null -> false
        role == Role.MAIN_PHONE && !hasSavedDevice -> false
        state != ConnectionManager.State.DISCONNECTED -> false
        userStopped -> false
        resumePending -> false
        !permissionsGranted || !bluetoothEnabled -> false
        role == Role.MAIN_PHONE && !autoReconnect && !userIntent -> false
        else -> true
    }
}
