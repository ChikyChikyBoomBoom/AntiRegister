package com.example.companion.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.companion.data.Role
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Thin wrapper over the platform adapter: adapter/bond/discovery events and socket creation.
 * No connection policy here; ConnectionManager owns that. Callbacks arrive on the main thread.
 */
@SuppressLint("MissingPermission") // permissions are checked by ConnectionManager / the UI before use
class BluetoothManager(context: Context) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7b0e6f3a-5c1d-4a52-9f0e-2d8c4b6a1e90")
        const val SERVICE_NAME = "CompanionCall"
    }

    interface Listener {
        fun onAdapterStateChanged(enabled: Boolean) {}
        fun onDeviceFound(device: BluetoothDevice) {}
        fun onDiscoveryFinished() {}
        /** state/previousState are BluetoothDevice.BOND_NONE / BOND_BONDING / BOND_BONDED. */
        fun onBondStateChanged(device: BluetoothDevice, state: Int, previousState: Int) {}
        /** Stage 17: the Bluetooth (ACL) link to [device] went down. Lets the connection layer notice a drop before a read fails. */
        fun onAclDisconnected(device: BluetoothDevice) {}
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager?)?.adapter
    private val listeners = CopyOnWriteArraySet<Listener>()

    private fun deviceExtra(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /**
     * Stage 27: calls [f] for every listener; a listener that throws cannot stop the others or crash the app from inside a system
     * broadcast. The problem goes to [BluetoothDiagnostics]. Listener order and the events themselves are unchanged.
     */
    private fun forEachListener(what: String, f: (Listener) -> Unit) {
        for (l in listeners) {
            try { f(l) } catch (e: Exception) { BluetoothDiagnostics.record(BluetoothDiagnostics.Area.RECEIVER, what, e) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                handle(i)
            } catch (e: Exception) {
                // Stage 27: a malformed broadcast (missing extra, unexpected type) must not crash the app.
                BluetoothDiagnostics.record(BluetoothDiagnostics.Area.RECEIVER, "broadcast handling failed", e, i.action)
            }
        }

        private fun handle(i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (s == BluetoothAdapter.STATE_ON || s == BluetoothAdapter.STATE_OFF)
                        forEachListener("adapter state listener") { it.onAdapterStateChanged(s == BluetoothAdapter.STATE_ON) }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val d = deviceExtra(i) ?: return
                    forEachListener("device found listener") { it.onDeviceFound(d) }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = deviceExtra(i) ?: return
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    forEachListener("bond state listener") { it.onBondStateChanged(d, st, prev) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> forEachListener("discovery finished listener") { it.onDiscoveryFinished() }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d = deviceExtra(i) ?: return
                    forEachListener("acl disconnected listener") { it.onAclDisconnected(d) }
                }
            }
        }
    }

    init {
        // Stage 27: this runs while the Application is being created, so an exception here would crash the app at start-up. Without the
        // receiver the app still opens (adapter changes and drops are then noticed only by read errors and the keepalive), and the
        // problem is recorded for developers.
        try {
            appContext.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            })
        } catch (e: Exception) {
            BluetoothDiagnostics.record(BluetoothDiagnostics.Area.RECEIVER, "could not register the Bluetooth receiver", e)
        }
    }

    val isSupported get() = adapter != null
    val isEnabled get() = adapter?.isEnabled == true
    val isDiscovering get() = try { adapter?.isDiscovering == true } catch (e: SecurityException) { false }
    /** Null if unavailable or the permission is missing. */
    val localName: String? get() = try { adapter?.name } catch (e: SecurityException) { null }

    fun hasPermissions(role: Role?) = BtPermissions.granted(appContext, role)

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun bondedDevices(): List<BluetoothDevice> =
        try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }

    fun remoteDevice(address: String): BluetoothDevice? =
        try { adapter?.getRemoteDevice(address) } catch (e: IllegalArgumentException) { null }

    fun isBonded(d: BluetoothDevice) = d.bondState == BluetoothDevice.BOND_BONDED

    /** Starts the system pairing flow (dialogs on both phones). Returns false if it could not start. */
    fun createBond(d: BluetoothDevice): Boolean = try { d.createBond() } catch (e: SecurityException) { false }   // Stage 27: permission revoked meanwhile = "could not start", not a crash

    /** One-shot discovery (the system stops it after ~12 s). Never scans in the background. */
    fun startDiscovery(): Boolean = try {
        val a = adapter
        if (a == null) false else { if (a.isDiscovering) a.cancelDiscovery(); a.startDiscovery() }
    } catch (e: SecurityException) { false }

    fun stopDiscovery() {
        try { adapter?.takeIf { it.isDiscovering }?.cancelDiscovery() } catch (e: SecurityException) {}
    }

    /** Server side (SIM phone). Returns immediately; accept() is called by the owner on its own thread. */
    @Throws(IOException::class)
    fun listen(): BluetoothServerSocket =
        adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            ?: throw IOException("Bluetooth adapter unavailable")

    /** Client side (main phone). Returns immediately; connect() blocks and must run off the main thread. */
    @Throws(IOException::class)
    fun createClientSocket(device: BluetoothDevice): BluetoothSocket =
        device.createRfcommSocketToServiceRecord(SERVICE_UUID)
}
