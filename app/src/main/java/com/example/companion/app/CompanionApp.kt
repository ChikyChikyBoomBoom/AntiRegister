package com.example.companion.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.companion.audio.call.AndroidCallAudioManager
import com.example.companion.audio.call.AndroidTransportBackendFactory
import com.example.companion.audio.AudioTransferFeatures
import com.example.companion.audio.call.CallAudioManager
import com.example.companion.audio.diag.AudioDiagnostics
import com.example.companion.audio.hfp.BluetoothHfpManager
import com.example.companion.audio.transport.AndroidUplinkEnvironment
import com.example.companion.audio.transport.AudioDirection
import com.example.companion.audio.transport.AudioTestController
import com.example.companion.audio.transport.AudioTransport
import com.example.companion.audio.transport.BluetoothAudioLink
import com.example.companion.audio.transport.UplinkFlags
import com.example.companion.audio.transport.UplinkLifecycle
import com.example.companion.bluetooth.BluetoothDiagnostics
import com.example.companion.bluetooth.BluetoothManager
import com.example.companion.bluetooth.BtPermissions
import com.example.companion.bluetooth.ConnectionRestorePolicy
import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.calls.CallHistoryRecorder
import com.example.companion.calls.CallHistoryStore
import com.example.companion.calls.CallManager
import com.example.companion.calls.ClientCallController
import com.example.companion.calls.HostCallController
import com.example.companion.calls.SimAudioControl
import com.example.companion.contacts.AndroidContactLookup
import com.example.companion.data.AppPrefs
import com.example.companion.i18n.AppLocale
import com.example.companion.data.Role
import com.example.companion.protocol.CallSnapshot
import com.example.companion.protocol.CallState
import com.example.companion.sms.AndroidSmsGateway
import com.example.companion.sms.ClientSmsController
import com.example.companion.sms.HostSmsController
import com.example.companion.sms.SimSmsStore
import com.example.companion.sms.SmsStore
import com.example.companion.ui.Avatars
import com.example.companion.ui.CallNotifier
import com.example.companion.ui.CallScreenLauncher
import com.example.companion.ui.IncomingCallNotifier
import com.example.companion.ui.SmsNotifier

/** Simple manual wiring; swap for DI later if needed. */
class CompanionApp : Application() {
    lateinit var prefs: AppPrefs; private set
    lateinit var bluetooth: BluetoothManager; private set
    lateinit var connection: ConnectionManager; private set
    lateinit var callManager: CallManager; private set
    lateinit var hostCalls: HostCallController; private set
    lateinit var clientCalls: ClientCallController; private set
    lateinit var audioDiag: AudioDiagnostics; private set
    lateinit var audioTest: AudioTestController; private set
    lateinit var hfp: BluetoothHfpManager; private set
    lateinit var simAudio: SimAudioControl; private set
    lateinit var callAudio: CallAudioManager; private set
    /** Stage 40: microphone / uplink lifecycle. Nothing starts by itself; only a user action calls start(). */
    lateinit var uplink: UplinkLifecycle; private set
    // Stage 8: SMS. The databases are opened lazily, so a phone never creates the store it does not use.
    val smsStore: SmsStore by lazy { SmsStore(this) }                 // Main Phone: conversation history
    lateinit var hostSms: HostSmsController; private set              // SIM Phone: cellular SMS <-> Bluetooth
    lateinit var clientSms: ClientSmsController; private set          // Main Phone: SMS UI <-> Bluetooth
    // Stage 12: Main Phone Recent Calls. Fed by the existing ClientCallController; the database is opened lazily.
    val callHistory: CallHistoryStore by lazy { CallHistoryStore(this) }
    lateinit var callHistoryRecorder: CallHistoryRecorder; private set
    // Stage 19: optional Contacts (Main Phone). Official Contacts provider only, answers kept in memory for minutes, nothing copied or stored.
    // Created lazily, so a phone (or a run without the permission) that never asks for a name never touches the provider.
    val contacts: AndroidContactLookup by lazy { AndroidContactLookup(this) }
    val avatars: Avatars by lazy { Avatars(this, contacts) }
    // Stage 15: Main Phone SMS notifications. Only a listener of clientSms; no connection or transport of its own.
    lateinit var smsNotifier: SmsNotifier; private set
    private lateinit var callNotifier: CallNotifier
    // Stage 16: Main Phone incoming-call notification. Only a listener of clientCalls; no connection or call control of its own.
    lateinit var incomingCallNotifier: IncomingCallNotifier; private set
    // Stage 10: opens the Main Phone call screen when the SIM Phone reports a call.
    private lateinit var callScreenLauncher: CallScreenLauncher
    /** True while at least one activity of this app is started. */
    @Volatile var appVisible = false; private set

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        AppLocale.load(prefs)      // Stage 12: selected UI language (default English). Nothing reads it yet except code that calls tr().
        bluetooth = BluetoothManager(this)
        connection = ConnectionManager(bluetooth, prefs)

        // Stage 3. Telecom side knows nothing about Bluetooth; only the two controllers bridge them.
        callManager = CallManager(this)
        simAudio = SimAudioControl()
        hostCalls = HostCallController(this, prefs, bluetooth, callManager, connection, simAudio)
        clientCalls = ClientCallController(prefs, connection)
        callScreenLauncher = CallScreenLauncher(this, prefs, clientCalls) { appVisible }
        clientCalls.addListener(callScreenLauncher)
        callHistoryRecorder = CallHistoryRecorder(prefs, clientCalls) { callHistory }
        clientCalls.addListener(callHistoryRecorder)
        incomingCallNotifier = IncomingCallNotifier(
            this, prefs, clientCalls, isAppVisible = { appVisible },
            nameFor = { contacts.find(it)?.name }, photoFor = { avatars.notificationPhoto(it) }
        )
        clientCalls.addListener(incomingCallNotifier)
        callNotifier = CallNotifier(this)
        // Stage 4: diagnostic experiment state (memory only). Independent of Bluetooth.
        audioDiag = AudioDiagnostics(this) { callManager.snapshot }
        // Stage 5: Bluetooth audio transport test. Rides on the existing connection; no audio source is wired to a call.
        audioTest = AudioTestController(this, prefs, BluetoothAudioLink(connection)) { audioDiag.running }
        // Stage 6: read-only HFP capability probe. Adds no connection, no audio and no listener of its own.
        hfp = BluetoothHfpManager(this, bluetooth, prefs, connection)
        // Stage 7: unified call audio. Fed by call state + Bluetooth link. Stage 34A: a backend exists only behind DOWNLINK_BRIDGE (default false).
        // Stage 34A: the backend factory uses the SHARED audioTest.transport (no second link / transport / listener). The factory only
        // holds references; nothing is created or opened until a call needs audio AND DOWNLINK_BRIDGE is on (default off = old behaviour).
        callAudio = AndroidCallAudioManager(
            prefs, connection, callManager, clientCalls, hfp, audioDiag,
            backendFactory = AndroidTransportBackendFactory(this, audioTest.transport),
            downlinkBridge = { AudioTransferFeatures.isTransferEnabled() && DOWNLINK_BRIDGE }   // Stage 1: master switch ANDed in
        )
        wireUplinkLifecycle()
        simAudio.addListener(hostCalls)
        registerActivityLifecycleCallbacks(visibility)
        callManager.addListener(hostCalls)
        callManager.addListener(callNotifier)
        connection.addListener(hostCalls)
        connection.addListener(clientCalls)
        // Stage 8: same Bluetooth connection, same listener mechanism as calls.
        hostSms = HostSmsController(this, prefs, bluetooth, connection, SimSmsStore(this), AndroidSmsGateway(this))
        clientSms = ClientSmsController(prefs, connection, smsStore)
        connection.addListener(hostSms)
        connection.addListener(clientSms)
        smsNotifier = SmsNotifier(
            this, prefs, isAppVisible = { appVisible },
            nameFor = { contacts.find(it)?.name }, photoFor = { avatars.notificationPhoto(it) }
        )
        clientSms.addListener(smsNotifier)
    }

    /**
     * Stage 40: builds the ONE [UplinkLifecycle] over the existing [AudioTestController.uplink] (same transport, same microphone object)
     * and feeds it from the existing state owners. These listeners only call [UplinkLifecycle.onEnvironmentChanged]; they open nothing,
     * start nothing, and add no connection, thread or socket.
     */
    private fun wireUplinkLifecycle() {
        val direction: () -> AudioDirection? = {
            when (prefs.role) { Role.MAIN_PHONE -> AudioDirection.MAIN_TO_SIM; Role.SIM_PHONE -> AudioDirection.SIM_TO_MAIN; null -> null }
        }
        val env = AndroidUplinkEnvironment(
            this,
            callActiveProvider = {
                when (prefs.role) {
                    Role.MAIN_PHONE -> clientCalls.snapshot?.state?.let { it == CallState.ACTIVE }
                    Role.SIM_PHONE -> callManager.snapshot.state == CallState.ACTIVE
                    null -> null
                }
            },
            linkUpProvider = { connection.state == ConnectionManager.State.CONNECTED },
            appVisibleProvider = { appVisible },
            microphoneBusyProvider = { audioDiag.running }
        )
        uplink = UplinkLifecycle(audioTest.uplink, env, UplinkFlags(
            // Stage 1: both bridge constants only count while the audio-transfer master switch is on as well.
            uplinkBridge = AudioTransferFeatures.isTransferEnabled() && UPLINK_BRIDGE,
            downlinkBridge = AudioTransferFeatures.isTransferEnabled() && DOWNLINK_BRIDGE
        ), direction)
        clientCalls.addListener(object : ClientCallController.Listener { override fun onCallUiChanged() = uplink.onEnvironmentChanged() })
        callManager.addListener(object : CallManager.Listener { override fun onSnapshotChanged(snapshot: CallSnapshot) = uplink.onEnvironmentChanged() })
        connection.addListener(object : ConnectionManager.Listener { override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) = uplink.onEnvironmentChanged() })
        audioTest.transport.addListener(object : AudioTransport.Listener { override fun onTransportChanged() = uplink.onEnvironmentChanged() })
    }

    /** Only reached on emulators (Android never calls it on a real device; the OS releases the microphone when the process dies). */
    override fun onTerminate() {
        if (::uplink.isInitialized) uplink.shutdown()
        super.onTerminate()
    }

    /**
     * Stage 30: restores the connection after the app was reopened or the process restarted. The saved role and SIM Phone are read from
     * [AppPrefs]; [ConnectionRestorePolicy] decides, and the existing ConnectionManager.start() does the work (nothing new connects).
     * Safe to call repeatedly: a second call finds the connection no longer idle and does nothing. Main thread.
     */
    fun restoreConnection(userIntent: Boolean = false) {
        val role = prefs.role
        val go = ConnectionRestorePolicy.shouldStart(
            role = role,
            hasSavedDevice = prefs.deviceAddress != null,
            state = connection.state,
            userStopped = connection.userStopped,
            resumePending = connection.resumePending,
            permissionsGranted = BtPermissions.granted(this, role),
            bluetoothEnabled = bluetooth.isEnabled,
            autoReconnect = prefs.autoReconnect,
            userIntent = userIntent
        )
        if (go) connection.start(userInitiated = false)
    }

    companion object {
        /**
         * Stage 34A switch for the one-way cellular downlink backend. MUST stay false by default. Two-way audio is not implemented and
         * stays "NO" whatever this is. No UI / pref yet (a later stage); change it here only for a deliberate device experiment.
         */
        const val DOWNLINK_BRIDGE = false

        /**
         * Stage 40 master switch of the Main Phone microphone -> SIM Phone uplink experiment (feature flag `uplinkBridge`). MUST stay false by
         * default. Even when true: the microphone starts only after an explicit user press on the call screen, only during an active call
         * with Bluetooth up and the app visible, and only after the SIM Phone's sink accepted the stream (this build's only sink refuses).
         * `twoWayCellularAudio` stays "NO" whatever this is.
         */
        const val UPLINK_BRIDGE = false
    }

    /** Counts started activities so call audio knows whether the app is visible (microphone capture must stop otherwise). */
    private val visibility = object : Application.ActivityLifecycleCallbacks {
        private var started = 0
        // Stage 30: every step runs on its own inside a guard. These callbacks run for EVERY screen of the app, so one step that throws (a
        // listener in a bad state, a notification failure) used to end the app the moment any screen opened, and skipped the steps after it.
        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) {
                appVisible = true
                guardedStep("restore connection") { restoreConnection() }
                guardedStep("call audio visible") { callAudio.onAppVisibilityChanged(true) }
                guardedStep("incoming call notifier visible") { incomingCallNotifier.onAppVisibilityChanged(true) }
                guardedStep("call screen launcher") { callScreenLauncher.onAppVisible() }
                guardedStep("uplink environment") { uplink.onEnvironmentChanged() }
            }
        }
        override fun onActivityStopped(activity: Activity) {
            if (started > 0 && --started == 0) {      // Stage 30: never below zero (an unbalanced stop would otherwise make the app look visible forever)
                appVisible = false
                guardedStep("call audio hidden") { callAudio.onAppVisibilityChanged(false) }
                guardedStep("incoming call notifier hidden") { incomingCallNotifier.onAppVisibilityChanged(false) }
                guardedStep("uplink environment") { uplink.onEnvironmentChanged() }
            }
        }
        private inline fun guardedStep(what: String, block: () -> Unit) {
            try { block() } catch (e: Exception) { BluetoothDiagnostics.record(BluetoothDiagnostics.Area.LISTENER, "app visibility: $what", e) }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
