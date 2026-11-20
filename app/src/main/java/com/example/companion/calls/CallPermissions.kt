package com.example.companion.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime permissions used by the SIM phone for call control (separate from Bluetooth permissions). */
object CallPermissions {
    /** CALL_PHONE: needed by TelecomManager.placeCall. POST_NOTIFICATIONS (13+): incoming-call notification on the SIM phone. */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.CALL_PHONE)

    fun missing(c: Context): Array<String> =
        required().filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun callPhoneGranted(c: Context) =
        c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
}
