package com.example.companion.audio.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager

/**
 * Stage 40: production [UplinkEnvironment]. Call state, link state, visibility and "microphone busy" are supplied by the
 * existing owners (wired in CompanionApp); the permission and headset answers are read from Android here. Uses no new permission.
 */
class AndroidUplinkEnvironment(
    context: Context,
    private val callActiveProvider: () -> Boolean?,
    private val linkUpProvider: () -> Boolean,
    private val appVisibleProvider: () -> Boolean,
    private val microphoneBusyProvider: () -> Boolean
) : UplinkEnvironment {
    private val appContext = context.applicationContext

    override fun callActive(): Boolean? = callActiveProvider()
    override fun linkUp(): Boolean = linkUpProvider()
    override fun appVisible(): Boolean = appVisibleProvider()
    override fun microphoneBusyElsewhere(): Boolean = microphoneBusyProvider()

    override fun microphonePermission(): Boolean? = try {
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { null }

    override fun headsetOutputPresent(): Boolean? {
        return try {
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            AudioFactsText.hasHeadsetOutput(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
        } catch (e: Exception) { null }
    }
}
