package com.example.companion.audio.hfp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.AppPrefs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 6: READ-ONLY probe of what Android lets a normal app do with HFP. Public SDK calls only:
 * getProfileProxy(HEADSET), getProfileConnectionState, BluetoothDevice.getUuids, PackageManager, Build.
 * It never creates a socket, never opens SCO, never touches audio and never changes the Stage 2-5 connection.
 * The HEADSET_CLIENT profile id is a hidden constant and is deliberately not referenced.
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission") // permission is checked in connectPermission() before every guarded call
class BluetoothHfpManager(
    context: Context,
    private val bluetooth: BluetoothManager,
    private val prefs: AppPrefs,
    private val connection: ConnectionManager
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    // Stage 1: lazy, so constructing this manager (CompanionApp.onCreate) touches no Bluetooth service. Only reached from probe() / baseFacts()
    // when HFP is enabled.
    private val adapter: BluetoothAdapter? by lazy {
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    }

    @Volatile var latestFacts: HfpFacts? = null; private set
    @Volatile var latestCapability: HfpCapability? = null; private set

    private fun connectPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Main thread only. [onDone] is called exactly once on the main thread (within ~3 s). */
    fun probe(onDone: (HfpFacts, HfpCapability) -> Unit) {
        if (!AudioTransferFeatures.isHfpEnabled()) {
            // Stage 1: no profile proxy, no HFP/A2DP profile-state read, no bonded-device read. Answers once, synchronously, and stores nothing
            // (latestFacts / latestCapability stay null, so CallAudioResolver keeps treating HFP call audio as NO).
            val facts = disabledFacts()
            onDone(facts, HfpAssessor.assess(facts))
            return
        }
        val ad = adapter
        val base = baseFacts(emptySet())
        val canProxy = ad != null && bluetooth.isEnabled && connectPermission()
        if (!canProxy) {
            val why = when {
                ad == null -> "no Bluetooth adapter"
                !bluetooth.isEnabled -> "Bluetooth is off"
                else -> "BLUETOOTH_CONNECT not granted"
            }
            finish(base.copy(headsetProxy = if (ad == null) Tri.NO else Tri.UNKNOWN, headsetProxyDetail = "not probed: $why"), onDone)
            return
        }

        val done = AtomicBoolean(false)
        fun complete(facts: HfpFacts) { if (done.compareAndSet(false, true)) finish(facts, onDone) }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HEADSET) return
                val connected: Set<String>? = try { proxy.connectedDevices.map { it.address }.toSet() } catch (e: SecurityException) { null }
                complete(baseFacts(connected.orEmpty(), connected != null)
                    .copy(headsetProxy = Tri.YES, headsetProxyDetail = "BluetoothHeadset proxy obtained (this is the phone's Audio Gateway service)"))
                try { ad!!.closeProfileProxy(BluetoothProfile.HEADSET, proxy) } catch (e: Exception) { /* best effort */ }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }
        val started = try { ad!!.getProfileProxy(app, listener, BluetoothProfile.HEADSET) } catch (e: SecurityException) { false }
        if (!started) {
            complete(base.copy(headsetProxy = Tri.NO, headsetProxyDetail = "getProfileProxy(HEADSET) was refused"))
        } else {
            main.postDelayed({
                complete(base.copy(headsetProxy = Tri.NO, headsetProxyDetail = "BluetoothHeadset proxy not delivered within 3 s"))
            }, PROXY_TIMEOUT_MS)
        }
    }

    /** Plain device facts plus "not probed" for everything HFP-related (proxy, profile states, bonded peers). */
    private fun disabledFacts() = HfpFacts(
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        role = prefs.role?.label ?: "-",
        // Plain device facts only (same calls baseFacts uses); nothing here touches an HFP profile, so the report does not
        // claim "Bluetooth unsupported" or "no telephony" just because the probe is switched off.
        bluetoothSupported = bluetooth.isSupported,
        bluetoothEnabled = bluetooth.isEnabled,
        connectPermission = connectPermission(),
        telephonyFeature = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        headsetProxy = Tri.UNKNOWN,
        headsetProxyDetail = "not probed: ${AudioTransferFeatures.HFP_DISABLED_MESSAGE}",
        headsetProfileState = "not probed",
        a2dpProfileState = "not probed",
        bluetoothPrivilegedHeld = false,
        bondedPeers = emptyList(),
        appConnection = "not probed"
    )

    private fun finish(facts: HfpFacts, onDone: (HfpFacts, HfpCapability) -> Unit) {
        val cap = HfpAssessor.assess(facts)
        // Stage 2: second guard. probe() already returns early while HFP is off; if a future edit ever reaches here, still store nothing.
        if (AudioTransferFeatures.isHfpEnabled()) { latestFacts = facts; latestCapability = cap }
        onDone(facts, cap)
    }

    private fun stateName(s: Int?) = when (s) {
        null -> "unknown"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "state=$s"
    }

    private fun profileState(profile: Int): String {
        if (!AudioTransferFeatures.isHfpEnabled()) return "not probed"   // Stage 2 guard: never read profile state while HFP is off
        val ad = adapter   // local copy: a lazy property cannot be smart-cast
        if (ad == null || !connectPermission()) return "unknown"
        return try { stateName(ad.getProfileConnectionState(profile)) } catch (e: Exception) { "unknown" }
    }

    private fun peers(headsetConnected: Set<String>, known: Boolean): List<BondedPeer> {
        if (!AudioTransferFeatures.isHfpEnabled()) return emptyList()   // Stage 2 guard: never read bonded devices / UUIDs while HFP is off
        if (!connectPermission()) return emptyList()
        return bluetooth.bondedDevices().take(MAX_PEERS).map { d -> peerOf(d, headsetConnected, known) }
    }

    private fun peerOf(d: BluetoothDevice, headsetConnected: Set<String>, known: Boolean): BondedPeer {
        val uuids = try { d.uuids?.map { it.uuid.toString().lowercase() } } catch (e: SecurityException) { null }
        val name = (try { d.name } catch (e: SecurityException) { null }) ?: d.address
        return BondedPeer(name, d.address, d.address == prefs.deviceAddress, uuids,
            if (known) headsetConnected.contains(d.address) else null)
    }

    private fun baseFacts(headsetConnected: Set<String>, known: Boolean = false) = HfpFacts(
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        role = prefs.role?.label ?: "-",
        bluetoothSupported = bluetooth.isSupported,
        bluetoothEnabled = bluetooth.isEnabled,
        connectPermission = connectPermission(),
        telephonyFeature = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        headsetProxy = Tri.UNKNOWN,
        headsetProxyDetail = "not probed",
        headsetProfileState = profileState(BluetoothProfile.HEADSET),
        a2dpProfileState = profileState(BluetoothProfile.A2DP),
        // Read-only, same pattern as Stage 4 (CAPTURE_AUDIO_OUTPUT). Never requested, not declared in the manifest.
        bluetoothPrivilegedHeld = app.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED,
        bondedPeers = peers(headsetConnected, known),
        appConnection = connection.state.name + (connection.connectedName?.let { " ($it)" } ?: "")
    )

    private companion object {
        const val PROXY_TIMEOUT_MS = 3000L
        const val MAX_PEERS = 20
    }
}
