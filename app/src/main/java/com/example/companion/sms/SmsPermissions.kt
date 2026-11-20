package com.example.companion.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Runtime permissions of the SIM Phone for SMS. The Main Phone needs none (it never touches cellular SMS).
 * RECEIVE_SMS: be told when an SMS arrives (SMS_RECEIVED broadcast; works for a non-default SMS app).
 * SEND_SMS: SmsManager.sendTextMessage. READ_SMS and the default-SMS-app role are deliberately NOT used.
 */
object SmsPermissions {
    fun required(): Array<String> = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)

    fun missing(c: Context): Array<String> =
        required().filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun canSend(c: Context) = c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    fun canReceive(c: Context) = c.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}
