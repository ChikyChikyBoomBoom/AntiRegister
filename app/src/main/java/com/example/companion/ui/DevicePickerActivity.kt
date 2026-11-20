package com.example.companion.ui

import com.example.companion.i18n.LocalizedActivity
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.data.Role
import com.example.companion.i18n.AppLocale
import com.example.companion.i18n.ConnectionErrorText
import com.example.companion.i18n.StringKey
import com.example.companion.i18n.TextDirection
import com.example.companion.i18n.tr

/**
 * Main phone only: one-shot scan, shows pairing status, tapping a device pairs (if needed) and connects.
 * Stage 15: all texts are in the selected language (English fallback); scanning, pairing and connecting are unchanged.
 */
@SuppressLint("MissingPermission")
class DevicePickerActivity : LocalizedActivity(), BluetoothManager.Listener, ConnectionManager.Listener {
    private val devices = mutableListOf<BluetoothDevice>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var hint: TextView
    private lateinit var scanDot: StatusIndicator
    private lateinit var emptyV: View
    private lateinit var listV: ListView
    private var scanning = false
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = tr(StringKey.PICKER_TITLE)
        if (app.prefs.role != Role.MAIN_PHONE || !BtPermissions.granted(this, app.prefs.role) || !app.bluetooth.isEnabled) {
            // Stage 27: say which one it is. Before, "Main Phone only" was also shown when the role was right and Bluetooth was just off
            // or the permission missing. The existing error texts are reused; the checks themselves are unchanged.
            val why = when {
                app.prefs.role != Role.MAIN_PHONE -> StringKey.PICKER_MAIN_ONLY
                !app.bluetooth.isSupported -> StringKey.ERR_BT_UNSUPPORTED
                !BtPermissions.granted(this, app.prefs.role) -> StringKey.ERR_BT_PERMISSION
                else -> StringKey.ERR_BT_OFF
            }
            Toast.makeText(this, tr(why), Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (locationOff())
            Toast.makeText(this, tr(StringKey.PICKER_LOCATION_OFF), Toast.LENGTH_LONG).show()

        // Stage 17: each row is "name / address · status", app text with isolated data. In Persian the whole row is right-to-left and starts
        // at the right edge; without this the second line (it begins with the Latin address) was drawn as a left-to-right paragraph.
        // English is left exactly as it was.
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { if (AppLocale.isRtl) (it as? TextView)?.followAppDirection() }
        }
        val list = ListView(this).apply { this.adapter = this@DevicePickerActivity.adapter }
        listV = list
        list.setOnItemClickListener { _, _, pos, _ ->
            val d = devices.getOrNull(pos) ?: return@setOnItemClickListener
            app.connection.connectTo(d.address, d.name)   // stops discovery, pairs if needed, connects
            finish()                                      // progress is shown on the status screen
        }
        // Stage 24: a spinner while scanning, an explained empty state when nothing was found.
        scanDot = StatusIndicator(this, 10)
        hint = label(tr(StringKey.PICKER_SCANNING)).apply { textSize = 14f; setTextColor(UiColors.TEXT_SECONDARY); setPaddingRelative(dp(8), dp(4), 0, dp(4)) }
        val scanRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(scanDot); addView(hint)
        }
        emptyV = emptyState(UiIcon.BLUETOOTH, EmptyText(tr(StringKey.PICKER_EMPTY_TITLE), tr(StringKey.PICKER_EMPTY_BODY)))
        val root = vbox()
        root.addView(scanRow)
        root.addView(styledButton(tr(StringKey.PICKER_RESCAN), ButtonStyle.PRIMARY) { scan() })
        root.addView(emptyV, fillRemaining())
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(constrainWidth(root))
        showScanState()

        ready = true
        app.bluetooth.addListener(this); app.connection.addListener(this)
        scan()
    }

    private fun scan() {
        devices.clear()
        app.bluetooth.bondedDevices().forEach { add(it) }
        render()
        if (!app.connection.startDiscovery()) {
            // Stage 27: the error was shown in English whatever the app language; same wording mapping as the Status and Settings screens.
            Toast.makeText(this, ConnectionErrorText.localizeOrNull(app.connection.lastError, AppLocale.current) ?: tr(StringKey.PICKER_DISCOVERY_FAILED), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        scanning = true
        showScanState()
    }

    /** Spinner + words while scanning; the empty state only once a scan has finished with nothing to show. */
    private fun showScanState() {
        if (!::emptyV.isInitialized) return
        scanDot.set(if (scanning) UiColors.CAUTION else UiColors.TEXT_HINT, scanning)
        hint.text = if (scanning) tr(StringKey.PICKER_SCANNING) else tr(StringKey.PICKER_SCAN_FINISHED)
        emptyV.show(devices.isEmpty() && !scanning)
        listV.show(devices.isNotEmpty())
    }

    /** "Potentially compatible": already-paired devices, phones, and devices that do not report a class. */
    private fun isCandidate(d: BluetoothDevice): Boolean {
        if (app.bluetooth.isBonded(d)) return true
        val major = d.bluetoothClass?.majorDeviceClass ?: return true
        return major == BluetoothClass.Device.Major.PHONE ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED ||
            major == BluetoothClass.Device.Major.MISC
    }

    private fun add(d: BluetoothDevice) {
        if (devices.any { it.address == d.address } || !isCandidate(d)) return
        devices.add(d)
    }

    private fun describe(d: BluetoothDevice): String {
        val status = when (d.bondState) {
            BluetoothDevice.BOND_BONDED -> tr(StringKey.PICKER_PAIRED)
            BluetoothDevice.BOND_BONDING -> tr(StringKey.PICKER_PAIRING)
            else -> tr(StringKey.PICKER_NOT_PAIRED)
        }
        val selected = if (d.address == app.prefs.deviceAddress) " · " + tr(StringKey.PICKER_SELECTED) else ""
        // The name and the address are data: kept as they are, only isolated so a Persian sentence around them cannot re-order them.
        val lang = AppLocale.current
        val name = d.name?.let { TextDirection.isolate(it, lang) } ?: tr(StringKey.PICKER_UNKNOWN_DEVICE)
        return "$name\n${TextDirection.isolate(d.address, lang)} · $status$selected"
    }

    private fun render() {
        devices.sortWith(compareByDescending<BluetoothDevice> { it.bondState == BluetoothDevice.BOND_BONDED }
            .thenBy { it.name ?: "~" })
        adapter.clear()
        adapter.addAll(devices.map { describe(it) })
        showScanState()
    }

    private fun locationOff(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 || Build.VERSION.SDK_INT < 28) return false
        return !(getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    }

    override fun onDeviceFound(device: BluetoothDevice) { add(device); render() }
    override fun onBondStateChanged(device: BluetoothDevice, state: Int, previousState: Int) = render()

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) {
        scanning = state == ConnectionManager.State.DISCOVERING
        showScanState()
    }

    override fun onDestroy() {
        if (ready) {
            app.bluetooth.removeListener(this); app.connection.removeListener(this)
            app.connection.stopDiscovery()            // no-op unless still scanning
        }
        super.onDestroy()
    }
}
