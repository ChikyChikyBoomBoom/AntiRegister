package com.example.companion.calls

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager

/**
 * Android only delivers cellular calls to an InCallService that belongs to the default phone (dialer) app,
 * so the SIM phone must hold this role. We can only ASK the user; Android shows its own confirmation dialog.
 */
object CallRole {

    fun isHeld(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            c.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == c.packageName
        }

    /** Intent for startActivityForResult, or null if this device does not offer the role. */
    fun requestIntent(c: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = c.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) rm.createRequestRoleIntent(RoleManager.ROLE_DIALER) else null
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, c.packageName)
        }
}
