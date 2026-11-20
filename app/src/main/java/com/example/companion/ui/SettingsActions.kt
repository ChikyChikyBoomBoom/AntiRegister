package com.example.companion.ui

import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role

/**
 * Stage 18: the two local configuration changes of the Settings screen, in the order that keeps the existing Bluetooth
 * architecture safe. It never opens a socket itself: [ConnectionManager.stop] is the one call that tears everything down
 * (it bumps the attempt generation, so late callbacks from the old configuration are discarded).
 */
class SettingsActions(private val prefs: AppPrefs, private val connection: ConnectionManager) {

    /** Stage 29: what a role change did. [SAVE_FAILED] = the new role could not be saved, so the old role (and its setup state) is still in place. */
    enum class RoleChangeResult { CHANGED, UNCHANGED, SAVE_FAILED }

    /** Stop the old role's connection first, then switch. Returns false if nothing changed. The caller reopens the new role's home screen. */
    fun changeRole(newRole: Role): Boolean {
        if (prefs.role == newRole) return false
        connection.stop()
        prefs.userStopped = false      // an old "Disconnect" must not silently block the new role's first connection
        prefs.role = newRole
        prefs.setupCompleted = false   // Stage 22: the new role's permissions are explained once (Setup opens only if something is missing)
        return true
    }

    /**
     * Stage 29: [changeRole] with a result that tells "nothing to change" from "could not be saved". The role, the cleared "Disconnect" and the
     * cleared "setup finished" are written in ONE commit ([AppPrefs.saveRoleChange]): an interruption never leaves the new role with the old
     * role's setup state. The old connection is stopped first, exactly as before; if the save then fails the old role stays and the person
     * can press Connect again.
     */
    fun switchRole(newRole: Role): RoleChangeResult {
        if (prefs.role == newRole) return RoleChangeResult.UNCHANGED
        connection.stop()
        return if (prefs.saveRoleChange(newRole)) RoleChangeResult.CHANGED else RoleChangeResult.SAVE_FAILED
    }

    /**
     * Stage 29: the first-run role choice. A new role is saved like [switchRole]. Choosing the role that is already saved changes nothing
     * but "setup finished" (reset, so its setup is shown), as the welcome screen always did. [RoleChangeResult.UNCHANGED] is a success here.
     */
    fun chooseRole(role: Role): RoleChangeResult {
        if (prefs.role == role) {
            return if (prefs.saveSetupCompleted(false)) RoleChangeResult.UNCHANGED else RoleChangeResult.SAVE_FAILED
        }
        return switchRole(role)
    }

    /** Stage 29: [beginDeviceChange] that reports whether the old selection was really cleared (one commit). The connection is stopped either way. */
    fun beginDeviceChangeSafely(): Boolean {
        connection.stop()
        return prefs.clearDeviceDurably()
    }

    /** Main Phone: disconnect and forget the selected SIM Phone. The caller opens the device picker next. */
    fun beginDeviceChange() {
        connection.stop()
        prefs.clearDevice()
    }
}
