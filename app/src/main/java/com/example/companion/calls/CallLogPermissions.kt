package com.example.companion.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Stage 5: the ONE permission the SIM Phone's call history needs. Deliberately separate from [CallPermissions]
 * (which holds CALL_PHONE for the legacy call-control code): asking for call history never asks for CALL_PHONE or RECORD_AUDIO.
 */
object CallLogPermissions {
    const val READ = Manifest.permission.READ_CALL_LOG

    fun granted(c: Context): Boolean = c.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED
}
