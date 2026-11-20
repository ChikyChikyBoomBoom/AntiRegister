package com.example.companion.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * READ_CONTACTS is optional. It is requested only from the two Main Phone screens that show names (Recent Calls, Messages),
 * and only when the user taps "Show contact names". Nothing in the app needs it to work: without it every screen shows the
 * phone number exactly as before. The contact picker (see [ContactPicker]) uses the system's own picker and needs no permission.
 */
object ContactsPermissions {
    const val READ = Manifest.permission.READ_CONTACTS

    fun granted(c: Context): Boolean = c.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED
}
