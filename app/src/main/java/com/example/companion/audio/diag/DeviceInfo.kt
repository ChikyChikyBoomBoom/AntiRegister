package com.example.companion.audio.diag

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build

/** Device / Android / audio-device facts for comparing results across phones. No manufacturer-specific logic. */
object DeviceInfo {

    fun typeName(type: Int): String = when (type) {
        1 -> "BUILTIN_EARPIECE"; 2 -> "BUILTIN_SPEAKER"; 3 -> "WIRED_HEADSET"; 4 -> "WIRED_HEADPHONES"
        5 -> "LINE_ANALOG"; 6 -> "LINE_DIGITAL"; 7 -> "BLUETOOTH_SCO"; 8 -> "BLUETOOTH_A2DP"
        9 -> "HDMI"; 10 -> "HDMI_ARC"; 11 -> "USB_DEVICE"; 12 -> "USB_ACCESSORY"; 13 -> "DOCK"
        14 -> "FM"; 15 -> "BUILTIN_MIC"; 16 -> "FM_TUNER"; 17 -> "TV_TUNER"; 18 -> "TELEPHONY"
        19 -> "AUX_LINE"; 20 -> "IP"; 21 -> "BUS"; 22 -> "USB_HEADSET"; 23 -> "HEARING_AID"
        24 -> "BUILTIN_SPEAKER_SAFE"; 25 -> "REMOTE_SUBMIX"; 26 -> "BLE_HEADSET"; 27 -> "BLE_SPEAKER"
        29 -> "HDMI_EARC"; 30 -> "BLE_BROADCAST"
        else -> "TYPE_$type"
    }

    fun describe(d: AudioDeviceInfo): String {
        val addr = if (Build.VERSION.SDK_INT >= 28) d.address.orEmpty() else ""
        val rates = d.sampleRates.joinToString(",").ifEmpty { "any/unspecified" }
        val ch = d.channelCounts.joinToString(",").ifEmpty { "any" }
        return "[${d.id}] ${typeName(d.type)} \"${d.productName}\"" +
            (if (addr.isNotEmpty()) " addr=$addr" else "") + " rates=$rates ch=$ch"
    }

    fun lines(c: Context): List<String> {
        val out = ArrayList<String>()
        val am = c.getSystemService(AudioManager::class.java)
        out += "Manufacturer: ${Build.MANUFACTURER}"
        out += "Brand: ${Build.BRAND}"
        out += "Model: ${Build.MODEL}"
        out += "Device / product: ${Build.DEVICE} / ${Build.PRODUCT}"
        out += "Hardware / board: ${Build.HARDWARE} / ${Build.BOARD}"
        out += "Android version: ${Build.VERSION.RELEASE} (codename ${Build.VERSION.CODENAME})"
        out += "API level: ${Build.VERSION.SDK_INT}"
        out += "Security patch: ${Build.VERSION.SECURITY_PATCH}"
        out += "Build ID / display: ${Build.ID} / ${Build.DISPLAY}"
        out += "Build incremental: ${Build.VERSION.INCREMENTAL}"
        out += "Build type/tags: ${Build.TYPE} / ${Build.TAGS}"
        out += "Fingerprint: ${Build.FINGERPRINT}"
        val pm = c.packageManager
        out += "Feature telephony: ${pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)}, " +
            "microphone: ${pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)}"

        runCatching {
            out += "Audio mode now: ${AudioModeProbe.modeName(am.mode)}"
            out += "Speakerphone on: ${am.isSpeakerphoneOn}, Bluetooth SCO on: ${am.isBluetoothScoOn}, " +
                "mic muted: ${am.isMicrophoneMute}"
            out += "Telecom route now: ${RouteControl.routeName(RouteControl.currentRoute())}"
        }

        out += ""
        out += "Audio sources (SDK constants exist != accessible; see probe results):"
        for (s in AudioSources.ALL) out += "  ${s.name} = ${s.id}  (${s.doc})"
        runCatching { out += "  Framework max audio source id: ${MediaRecorder.getAudioSourceMax()}" }
        runCatching {
            out += "  UNPROCESSED declared supported (system property): " +
                (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) ?: "not declared")
        }
        // Read-only diagnostic: we never request this permission and could not obtain it as a normal app.
        val cap = c.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT")
        out += "  System capture permission CAPTURE_AUDIO_OUTPUT held by this app: " +
            (if (cap == PackageManager.PERMISSION_GRANTED) "YES" else "NO (expected for a normal app)")

        out += ""
        runCatching {
            val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            out += "Audio INPUT devices (${ins.size}):"
            ins.forEach { out += "  " + describe(it) }
            out += "  TELEPHONY-type input device listed: " + (ins.any { it.type == 18 })
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            out += "Audio OUTPUT devices (${outs.size}):"
            outs.forEach { out += "  " + describe(it) }
        }
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            val mics = am.microphones
            out += "Microphones reported by framework: ${mics.size}" +
                (if (mics.isNotEmpty()) " (" + mics.joinToString { it.description } + ")" else "")
        }
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            out += "Communication devices: " + am.availableCommunicationDevices.joinToString { typeName(it.type) }
            out += "Current communication device: " + (am.communicationDevice?.let { typeName(it.type) } ?: "none")
        }
        return out
    }
}
