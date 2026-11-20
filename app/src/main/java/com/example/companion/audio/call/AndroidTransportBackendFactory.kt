package com.example.companion.audio.call

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.audio.transport.AndroidCellularRecorderOpener
import com.example.companion.audio.transport.AudioTransport
import com.example.companion.audio.transport.CellularDownlinkSource
import com.example.companion.data.Role

/**
 * Production factory. Uses the ONE shared `audioTest.transport` (passed in by [com.example.companion.app.CompanionApp]); it creates no
 * `BluetoothAudioLink`, no `AudioTransport` and never touches the link listener. Only the capture source and the backend are new per call.
 * Constructing this class does no work, so it cannot fail at application start.
 */
class AndroidTransportBackendFactory(
    context: Context,
    private val transport: AudioTransport
) : CallBackendFactory {
    private val appContext = context.applicationContext

    override fun create(role: Role): CallAudioBackend {
        val source = CellularDownlinkSource(
            // Stage 2: the privileged permission only exists through the Magisk priv-app install; while Magisk support is off it is never relied on.
            hasCapturePermission = {
                AudioTransferFeatures.isMagiskEnabled() &&
                    appContext.checkSelfPermission(CellularDownlinkSource.CAPTURE_AUDIO_OUTPUT) == PackageManager.PERMISSION_GRANTED
            },
            opener = AndroidCellularRecorderOpener(),
            threadInit = { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } }
        )
        return TransportCallAudioBackendFactory.create(role, transport, source)
    }
}
