package com.example.companion.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.companion.data.Role

/** Runtime permissions are role-specific so each phone only asks for what it really uses. */
object BtPermissions {
    fun required(role: Role): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> when (role) {
            // Main phone: discover + connect. SIM phone: listen/connect + "discoverable" dialog.
            Role.MAIN_PHONE -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            Role.SIM_PHONE -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        // Android 11 and below: BLUETOOTH/ADMIN are install-time; discovery needs location.
        role == Role.MAIN_PHONE -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    fun granted(c: Context, role: Role?): Boolean =
        role != null && required(role).all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
