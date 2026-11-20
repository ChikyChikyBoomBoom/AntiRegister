package com.example.companion.audio.transport

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build

/**
 * Stage 40: reads [AudioFacts] for the Diagnostics screen. Read-only: it never creates an `AudioRecord`, never calls `startRecording`,
 * never changes the audio mode or the routing, and needs no permission. Anything that cannot be read is reported as null (unknown).
 * `AudioRecord.getMinBufferSize` is a static query that opens nothing.
 */
class AndroidAudioFacts(context: Context) {
    private val appContext = context.applicationContext

    fun read(): AudioFacts {
        val audio = try { appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager } catch (e: Exception) { null }
        val outputTypes: List<Int>? = try { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type } } catch (e: Exception) { null }
        return AudioFacts(
            audioMode = try { audio?.mode } catch (e: Exception) { null },
            communicationDeviceTypes = communicationDeviceTypes(audio),
            micRates = RATES_TO_REPORT.map { it to minBufferReported(it) },
            nativeOutputRate = try { audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() } catch (e: Exception) { null },
            headsetOutputPresent = outputTypes?.let { AudioFactsText.hasHeadsetOutput(it) }
        )
    }

    private fun communicationDeviceTypes(audio: AudioManager?): List<Int>? {
        if (audio == null || Build.VERSION.SDK_INT < 31) return null      // getAvailableCommunicationDevices() is API 31
        return try { audio.availableCommunicationDevices.map { it.type } } catch (e: Exception) { null }
    }

    private fun minBufferReported(rate: Int): Boolean? = try {
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
    } catch (e: Exception) { null }

    private companion object {
        /** The two rates the transport can use, plus two common native rates for information. */
        val RATES_TO_REPORT = listOf(8_000, 16_000, 44_100, 48_000)
    }
}
