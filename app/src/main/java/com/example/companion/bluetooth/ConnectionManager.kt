package com.example.companion.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.companion.data.AppPrefs
import com.example.companion.data.Role
import com.example.companion.protocol.Message
import com.example.companion.bluetooth.BluetoothDiagnostics.Area
import com.example.companion.protocol.MessageType
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The ONLY owner of connection state. UI and other layers observe [state]; nobody keeps their own copy.
 *
 * Responsibilities by role:
 *  - SIM Phone (host):   LISTENING for an incoming RFCOMM connection, ACCEPTS one, keeps it alive,
 *                        goes back to listening if it drops.
 *  - Main Phone (client): DISCOVERS devices, PAIRS, INITIATES the connection, keeps it alive,
 *                        reconnects with backoff if it drops.
 *
 * Stage 17 (reconnection): a link that was up and is lost is retried with capped backoff for [ReconnectPolicy.LOST_LINK_WINDOW_MS]
 * (see [ReconnectPolicy]); the SIM Phone keeps its listener open while connected, so a Main Phone that comes back replaces a
 * stale link at once and a second device is refused (never two connections). Same RFCOMM transport and wire protocol as before.
 *
 * Stage 27 (error handling): every worker thread (accept / connect / read / send) now catches every exception, not only IOException, and
 * hands it to the main thread as a failure (a revoked permission becomes "permission denied", anything else "unexpected error"), so a
 * Bluetooth problem can no longer crash the app from a background thread. A listener that throws cannot stop the others. Technical detail
 * (exception class, message, top stack frames, context) goes to [BluetoothDiagnostics]; [lastError] stays the plain English sentence that
 * ConnectionErrorText translates. A Main Phone link that connects and drops again at once, over and over, is given up after
 * [ReconnectPolicy.flapLimitReached]. Same RFCOMM transport, same wire protocol, same state machine.
 *
 * Threading: every public method and every state change runs on the main thread. Worker threads
 * (accept/connect/read) only do blocking I/O and hand results back through [ui]. Each attempt has a
 * generation number [gen]; results from an older generation are discarded, so a late callback from a
 * cancelled attempt can never touch the current state.
 */
class ConnectionManager(private val bt: BluetoothManager, private val prefs: AppPrefs) : BluetoothManager.Listener {

    enum class State {
        DISCONNECTED,   // idle
        DISCOVERING,    // main phone scanning for devices
        PAIRING,        // Android bonding dialog in progress
        LISTENING,      // SIM phone waiting for the main phone
        CONNECTING,     // main phone opening the socket
        CONNECTED,      // link up, messages can flow
        RECONNECTING,   // waiting / retrying after a failure or drop
        ERROR           // gave up or fatal problem; see lastError. User action needed
    }

    interface Listener {
        fun onStateChanged(state: State, deviceName: String?) {}
        /** Diagnostics changed without a state change (e.g. ping result). */
        fun onInfoChanged() {}
        fun onMessage(message: Message) {}
    }

    private val ui = Handler(Looper.getMainLooper())      // results from worker threads
    private val timers = Handler(Looper.getMainLooper())  // delayed tasks; cleared on every teardown
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val sender = Executors.newSingleThreadExecutor()

    @Volatile var state = State.DISCONNECTED; private set
    @Volatile var connectedName: String? = null; private set
    @Volatile var lastError: String? = null
        private set(value) { field = value; if (value != null) lastErrorAt = System.currentTimeMillis() }
    /** Stage 23 (diagnostics, read-only): epoch millis when [lastError] was last set; 0 = no error since the app started. */
    @Volatile var lastErrorAt = 0L; private set
    /** Stage 23 (diagnostics, read-only): epoch millis when an established link (CONNECTED) was last left; 0 = none since the app started. */
    @Volatile var lastDisconnectedAt = 0L; private set
    @Volatile var lastPingMs: Long? = null; private set
    /** Current retry attempt (1, 2, ...) while RECONNECTING; see [ReconnectPolicy.attemptText]. */
    @Volatile var retryAttempt = 0; private set
    val maxAttempts get() = ReconnectPolicy.FAST_ATTEMPTS
    /** True after the user explicitly pressed Disconnect (persisted); auto-start is suppressed. */
    val userStopped get() = prefs.userStopped
    /** Stage 30 (read-only): a connection is wanted and this class will retry / resume by itself (e.g. when Bluetooth comes back on). */
    val resumePending get() = wanted
    /**
     * Stage 27 (read-only, display only): why the link cannot be used right now when there is a plain reason (no Bluetooth, permission
     * missing, switched off, retrying), or null. Computed fresh from the adapter each time, so it is never stale. Starts nothing.
     */
    val linkIssue: LinkIssue? get() {
        val role = prefs.role ?: return null
        return try {
            LinkIssues.of(state == State.CONNECTED, bt.isSupported, bt.hasPermissions(role), bt.isEnabled, state == State.RECONNECTING)
        } catch (e: Exception) { null }
    }
    val isActive get() = when (state) {
        State.PAIRING, State.LISTENING, State.CONNECTING, State.CONNECTED, State.RECONNECTING -> true
        else -> false
    }

    @Volatile private var gen = 0       // read by the audio read thread
    private var wanted = false          // do we want to be connected? (drives retries / resume after BT on)
    private var attempt = 0
    /** Stage 17: the link was up earlier in this "wanted" session, so the next attempts are a RE-connection (shown as Reconnecting). */
    private var linkWasUp = false
    /** elapsedRealtime when the established link was lost (0 = not lost). Starts the [ReconnectPolicy.LOST_LINK_WINDOW_MS] window. */
    private var lostAt = 0L
    /** Stage 25: links in a row that dropped soon after connecting (see [ReconnectPolicy.nextQuickDrops]). Only lengthens the retry delay. */
    private var quickDrops = 0
    private var connectedAddress: String? = null
    private var connectedAt = 0L
    /** Stage 17: identifies the current listener (SIM Phone). Bumped whenever the listener is closed, so a late accept is discarded. */
    private var listenGen = 0
    private var pairingAddress: String? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var pendingSocket: BluetoothSocket? = null
    @Volatile private var transport: MessageTransport? = null
    /** Stage 5: receiver of raw audio packets. Called on the Bluetooth read thread, NOT the main thread. */
    @Volatile var audioSink: ((ByteArray) -> Unit)? = null
    private var lastRx = 0L
    private var lastPingSent = 0L

    init { bt.addListener(this) }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    // ---------------------------------------------------------------- public API

    /** "Connect". SIM phone starts listening; main phone connects to the saved device (pairing first if needed). */
    fun start(userInitiated: Boolean = true) {
        if (isActive) return
        bt.stopDiscovery()
        prefs.userStopped = false
        wanted = true; attempt = 0; retryAttempt = 0; linkWasUp = false; lostAt = 0; quickDrops = 0
        begin(reconnecting = false, userInitiated = userInitiated)
    }

    /** "Disconnect" pressed by the user: stop everything and do not auto-start again. */
    fun userStop() { prefs.userStopped = true; stop() }

    /** Stop everything (also used when the role changes or before switching devices). */
    fun stop() {
        wanted = false; attempt = 0; retryAttempt = 0; linkWasUp = false; lostAt = 0; quickDrops = 0
        bt.stopDiscovery()
        teardown()
        setState(State.DISCONNECTED)
    }

    /** Main phone: one-shot discovery for the device picker. Drops any current link first. */
    fun startDiscovery(): Boolean {
        stop()
        val role = prefs.role
        if (role != Role.MAIN_PHONE) { fatal("Only the Main Phone scans for devices"); return false }
        when {
            !bt.isSupported -> { fatal(ConnectionMessages.BT_UNSUPPORTED); return false }       // Stage 27: was reported as "turned off"
            !bt.hasPermissions(role) -> { fatal(PERMISSION_DENIED); return false }
            !bt.isEnabled -> {
                BluetoothDiagnostics.record(Area.ADAPTER, "discovery refused: Bluetooth is off", null, diagContext())
                lastError = ConnectionMessages.BT_OFF; setState(State.ERROR); return false
            }
            // Stage 27: startDiscovery() also answers false when the permission was revoked a moment ago; say so.
            !bt.startDiscovery() -> { fatal(if (bt.hasPermissions(role)) "Could not start Bluetooth discovery" else PERMISSION_DENIED); return false }
        }
        setState(State.DISCOVERING)
        val g = gen
        timers.postDelayed({ if (g == gen) stopDiscovery() }, DISCOVERY_SAFETY_TIMEOUT)
        return true
    }

    fun stopDiscovery() {
        bt.stopDiscovery()
        if (state == State.DISCOVERING) { timers.removeCallbacksAndMessages(null); setState(State.DISCONNECTED) }
    }

    /** Main phone: user picked a device in the list. Pairs first if it is not bonded yet. */
    fun connectTo(address: String, name: String?) {
        stop()
        prefs.selectDevice(address, name)
        start(userInitiated = true)
    }

    /** Lets the UI surface a problem it detected itself (e.g. permission dialog denied). */
    fun reportError(message: String) {
        if (isActive) return
        lastError = message
        setState(State.ERROR)
    }

    fun send(m: Message) {
        val t = transport ?: return
        // Stage 27: an exception on the sender thread (encoding, a closed executor) would be uncaught and crash the app. A write that
        // fails with IOException already closes the transport, which drives the normal "connection lost" path.
        try {
            sender.execute {
                try { t.send(m) } catch (e: Exception) { BluetoothDiagnostics.record(Area.TRANSPORT, "send failed", e, m.type.name) }
            }
        } catch (e: Exception) {
            BluetoothDiagnostics.record(Area.TRANSPORT, "send could not be queued", e, m.type.name)
        }
    }

    /** Stage 5: queue one binary audio packet (non-blocking, drops oldest when Bluetooth is congested). Any thread. */
    fun sendAudio(packet: ByteArray): Boolean = transport?.sendAudio(packet) ?: false

    /** Audio packets dropped on the send side for the current connection. */
    val audioTxDropped: Long get() = transport?.audioDropped?.get() ?: 0L

    /** Diagnostic round trip; result appears in [lastPingMs]. */
    fun ping() {
        if (state != State.CONNECTED) return
        val now = SystemClock.elapsedRealtime()
        lastPingSent = now
        send(Message(MessageType.PING, JSONObject().put("t", now)))
    }

    // ---------------------------------------------------------------- starting an attempt

    private fun begin(reconnecting: Boolean, userInitiated: Boolean) {
        val role = prefs.role ?: return fatal("No role selected")
        try {
            when {
                !bt.isSupported -> fatal(ConnectionMessages.BT_UNSUPPORTED)
                !bt.hasPermissions(role) -> fatal(PERMISSION_DENIED)
                !bt.isEnabled -> {
                    // Keep `wanted`: we resume automatically when Bluetooth comes back. No retry loop.
                    BluetoothDiagnostics.record(Area.ADAPTER, "attempt refused: Bluetooth is off", null, diagContext())
                    teardown(); lastError = ConnectionMessages.BT_OFF; setState(State.ERROR)
                }
                else -> {
                    teardown()
                    val g = gen
                    if (role == Role.SIM_PHONE) beginHost(g) else beginClient(g, reconnecting, userInitiated)
                }
            }
        } catch (e: SecurityException) {
            fatal(PERMISSION_DENIED, e)
        } catch (e: Exception) {
            fatal("Unexpected Bluetooth error: ${detailOf(e)}", e)
        }
    }

    /**
     * Stage 27: runs [block] for a result that arrives from a worker thread or a system callback (outside [begin]'s own try). A revoked
     * permission becomes the permission error and anything else an unexpected-error failure, instead of an uncaught exception on the
     * main thread. [fatal] tears everything down, so the state is consistent afterwards.
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            fatal(PERMISSION_DENIED, e)
        } catch (e: Exception) {
            fatal("Unexpected Bluetooth error: ${detailOf(e)}", e)
        }
    }

    // ---- Host (SIM phone): listen + accept

    private fun beginHost(g: Int) {
        val server = try { bt.listen() } catch (e: IOException) {
            onFailure(g, "Could not start listening: ${detailOf(e)}", e, Area.LISTEN); return
        }
        serverSocket = server
        val lg = ++listenGen
        setState(State.LISTENING)
        // Stage 17: the listener stays open after a connection is accepted (it is only closed by teardown), so a Main Phone
        // that lost the link can reconnect while this side still believes the old link is up. See onHostAccepted.
        thread(name = "bt-accept") {
            try {
                while (true) {
                    val socket = server.accept()       // blocks until the main phone connects
                    ui.post { guarded { onHostAccepted(lg, socket) } }
                }
            } catch (e: IOException) {
                ui.post { onListenerStopped(lg, e) }
            } catch (e: SecurityException) {
                // Stage 27: the permission was revoked while listening. Was an uncaught exception on this thread (app crash).
                ui.post { onListenerDenied(lg, e) }
            } catch (e: RuntimeException) {
                ui.post { onListenerStopped(lg, e) }
            }
        }
    }

    /** SIM Phone: a device connected to the listener. Never more than one live link, never a device other than the current peer. */
    private fun onHostAccepted(lg: Int, socket: BluetoothSocket) {
        if (lg != listenGen) { runCatching { socket.close() }; return }          // listener already torn down
        val address = runCatching { socket.remoteDevice.address }.getOrNull()
        if (state == State.CONNECTED && transport != null) {
            if (address != null && address == connectedAddress) replaceLink(socket)   // same Main Phone: the old link is stale
            else runCatching { socket.close() }                                       // another device: refused, current link untouched
        } else onSocketReady(gen, socket)
    }

    /** The listener failed. While a link is up this does not hurt it; the listener is re-created on the next drop. */
    private fun onListenerStopped(lg: Int, e: Exception) {
        if (lg != listenGen) return
        serverSocket = null
        if (state == State.CONNECTED) {
            BluetoothDiagnostics.record(Area.LISTEN, "listener stopped while linked (link kept)", e, diagContext())
            return
        }
        onFailure(gen, "Listening stopped: ${detailOf(e)}", e, Area.LISTEN)
    }

    /** Stage 27: the listener died because the Bluetooth permission is gone. A live link is left alone; otherwise this is the permission error. */
    private fun onListenerDenied(lg: Int, e: SecurityException) {
        if (lg != listenGen) return
        serverSocket = null
        if (state == State.CONNECTED) {
            BluetoothDiagnostics.record(Area.PERMISSION, "listener lost its permission while linked (link kept)", e, diagContext())
            return
        }
        fatal(PERMISSION_DENIED, e)
    }

    /** Swaps a stale transport for the freshly accepted one without going through DISCONNECTED/RECONNECTING. Listeners get CONNECTED again and re-sync. */
    private fun replaceLink(socket: BluetoothSocket) {
        gen++                                              // the old link's callbacks (including its onClosed) are now ignored
        timers.removeCallbacksAndMessages(null)
        val old = transport; transport = null
        old?.close()
        onSocketReady(gen, socket)
    }

    // ---- Client (main phone): pair if needed, then connect

    private fun beginClient(g: Int, reconnecting: Boolean, userInitiated: Boolean) {
        val address = prefs.deviceAddress
            ?: return fatal("No SIM phone selected. Use \"Select SIM phone\" first.")
        val device = bt.remoteDevice(address) ?: return fatal("Saved device address is invalid. Select the SIM phone again.")
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> connectSocket(g, device, reconnecting)
            BluetoothDevice.BOND_BONDING -> waitForPairing(g, device)       // already in progress
            else -> if (userInitiated) {
                if (!bt.createBond(device)) fatal(if (bt.hasPermissions(prefs.role)) "Could not start pairing" else PERMISSION_DENIED) else waitForPairing(g, device)
            } else fatal("Device is no longer paired. Select the SIM phone again.")
        }
    }

    private fun waitForPairing(g: Int, device: BluetoothDevice) {
        pairingAddress = device.address
        setState(State.PAIRING)
        timers.postDelayed({ if (g == gen && state == State.PAIRING) fatal("Pairing timed out") }, PAIRING_TIMEOUT)
    }

    private fun connectSocket(g: Int, device: BluetoothDevice, reconnecting: Boolean) {
        bt.stopDiscovery()                              // never scan while connecting
        val s = try { bt.createClientSocket(device) } catch (e: IOException) {
            onFailure(g, "Could not open Bluetooth socket: ${detailOf(e)}", e, Area.CONNECT); return
        }
        pendingSocket = s
        setState(if (reconnecting) State.RECONNECTING else State.CONNECTING)
        // Closing the socket from the timeout makes connect() below throw; its late result is ignored (gen changed).
        timers.postDelayed({ onFailure(g, "Connection timed out") }, CONNECT_TIMEOUT)
        thread(name = "bt-connect") {
            try {
                s.connect()
                ui.post { guarded { onSocketReady(g, s) } }
            } catch (e: IOException) {
                runCatching { s.close() }
                // The platform's own message ("read failed, socket might closed or timeout, read ret: -1") is kept for developers only.
                ui.post { onFailure(g, "SIM Phone unavailable (off, out of range, or app not listening)", e, Area.CONNECT) }
            } catch (e: SecurityException) {
                // Stage 27: permission revoked while connecting. Was an uncaught exception on this thread (app crash).
                runCatching { s.close() }
                ui.post { if (g == gen) fatal(PERMISSION_DENIED, e) }
            } catch (e: RuntimeException) {
                runCatching { s.close() }
                ui.post { onFailure(g, "Unexpected Bluetooth error: ${detailOf(e)}", e, Area.CONNECT) }
            }
        }
    }

    // ---------------------------------------------------------------- connection established / lost

    private fun onSocketReady(g: Int, socket: BluetoothSocket) {
        if (g != gen) { runCatching { socket.close() }; return }
        // Stage 27: the transport opens the socket's streams, which throws IOException if the remote dropped the link a moment after
        // connecting. That used to escape on the main thread (app crash). Now it is an ordinary failed attempt, and because nothing
        // below has run yet the retry counters are NOT reset, so a peer that connects and breaks at once cannot keep the count at zero.
        val newTransport = try {
            MessageTransport(socket,
                onMessage = { m -> ui.post { if (g == gen) handleMessage(m) } },
                onClosed = { ui.post { onTransportClosed(g) } },
                onAudio = { b -> if (g == gen) audioSink?.invoke(b) })
        } catch (e: Exception) {
            runCatching { socket.close() }
            onFailure(g, "Could not open Bluetooth socket: ${detailOf(e)}", e, Area.LINK)
            return
        }
        timers.removeCallbacksAndMessages(null)
        if (prefs.role != Role.SIM_PHONE) serverSocket = null      // the SIM Phone keeps its listener open (Stage 17)
        pendingSocket = null; pairingAddress = null
        attempt = 0; retryAttempt = 0; lastPingMs = null; lostAt = 0
        transport = newTransport
        val remote = socket.remoteDevice
        connectedName = (try { remote.name } catch (e: SecurityException) { null }) ?: remote.address
        connectedAddress = remote.address
        linkWasUp = true
        connectedAt = SystemClock.elapsedRealtime()
        lastRx = connectedAt
        prefs.lastConnectedAt = System.currentTimeMillis()
        setState(State.CONNECTED)
        transport?.start()
        timers.postDelayed({ keepaliveTick(g) }, KEEPALIVE_TICK)
    }

    private fun onTransportClosed(g: Int) = onFailure(g, "Connection lost (remote disconnected or out of range)")

    /** Single exit path for failed attempts and dropped connections. */
    private fun onFailure(g: Int, message: String, t: Throwable? = null, area: Area = Area.LINK) {
        if (g != gen) return                              // stale callback from a cancelled attempt
        val wasConnected = state == State.CONNECTED
        BluetoothDiagnostics.record(area, message, t, diagContext())      // Stage 27: technical detail for developers (never shown)
        teardown()
        // Stage 27: if Bluetooth was switched off under us, that is the real cause; "SIM Phone unavailable" would send the person looking
        // at the wrong phone. The original failure is in the diagnostics line above.
        lastError = if (!bt.isEnabled) ConnectionMessages.BT_OFF else message
        if (wasConnected) {
            val now = SystemClock.elapsedRealtime()
            lostAt = now
            quickDrops = ReconnectPolicy.nextQuickDrops(quickDrops, now - connectedAt)   // a link that flaps backs off instead of resetting to 3 s
        }
        scheduleRetry()                                   // state becomes RECONNECTING (or ERROR) right here, before anything else runs
    }

    private fun scheduleRetry() {
        val role = prefs.role
        when {
            !bt.isEnabled -> setState(State.DISCONNECTED) // do not retry while Bluetooth is off; resumes on enable
            // Stage 27: a Main Phone link that connected and dropped again at once, too many times in a row, is not worth more attempts.
            role == Role.MAIN_PHONE && wanted && ReconnectPolicy.flapLimitReached(quickDrops) -> {
                BluetoothDiagnostics.record(Area.RETRY, "giving up: link keeps dropping", null, diagContext())
                wanted = false; retryAttempt = 0; linkWasUp = false; lostAt = 0; quickDrops = 0
                lastError = ConnectionMessages.LINK_UNSTABLE
                setState(State.ERROR)
            }
            wanted && (role == Role.SIM_PHONE || prefs.autoReconnect) &&
                ReconnectPolicy.canRetry(
                    attempt, linkWasUp && role == Role.MAIN_PHONE,       // the SIM Phone only re-listens; its window never needs to run out
                    if (lostAt > 0) SystemClock.elapsedRealtime() - lostAt else 0L) -> {
                attempt++; retryAttempt = attempt
                val delay = ReconnectPolicy.delayMs(attempt + quickDrops, isHost = role == Role.SIM_PHONE)   // client 3s, 6s, 12s, 24s, then 30s (later after quick drops)
                setState(State.RECONNECTING)
                val g = gen
                timers.postDelayed({ if (g == gen) begin(reconnecting = true, userInitiated = false) }, delay)
            }
            else -> {
                if (wanted) BluetoothDiagnostics.record(Area.RETRY, "giving up: no more attempts allowed", null, diagContext())   // Stage 27
                wanted = false; retryAttempt = 0; linkWasUp = false; lostAt = 0; quickDrops = 0; setState(State.ERROR)   // gave up: user must press Connect
            }
        }
    }

    // ---------------------------------------------------------------- messages / keepalive

    private fun handleMessage(m: Message) {
        lastRx = SystemClock.elapsedRealtime()
        when (m.type) {
            MessageType.PING -> send(Message(MessageType.PONG, m.payload))
            MessageType.PONG -> {
                lastPingMs = lastRx - m.payload.optLong("t", lastRx)
                forEachListener("info listener") { it.onInfoChanged() }
            }
            else -> forEachListener("message listener", m.type.name) { it.onMessage(m) }
        }
    }

    /** Detects half-open links: main phone pings periodically, both sides drop the link if nothing arrives. */
    private fun keepaliveTick(g: Int) {
        if (g != gen || state != State.CONNECTED) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRx > RX_TIMEOUT) { onFailure(g, "Connection lost (remote stopped responding)"); return }
        if (prefs.role == Role.MAIN_PHONE && now - lastPingSent >= PING_INTERVAL) ping()
        timers.postDelayed({ keepaliveTick(g) }, KEEPALIVE_TICK)
    }

    // ---------------------------------------------------------------- Bluetooth events

    override fun onAdapterStateChanged(enabled: Boolean) {
        if (!enabled) {
            val hadWork = isActive || state == State.DISCOVERING
            if (state == State.CONNECTED) lostAt = SystemClock.elapsedRealtime()
            teardown()
            if (hadWork || wanted) {
                lastError = "Bluetooth was turned off"
                BluetoothDiagnostics.record(Area.ADAPTER, "Bluetooth was turned off", null, diagContext())     // Stage 27
            }
            setState(State.DISCONNECTED)                   // `wanted` is kept so we resume when it comes back
        } else if (wanted && !userStopped && (state == State.DISCONNECTED || state == State.ERROR)) {
            attempt = 0; retryAttempt = 0; quickDrops = 0
            if (linkWasUp) lostAt = SystemClock.elapsedRealtime()      // the retry window starts when Bluetooth is back
            // After an established link was lost, the screen says Reconnecting (not a fresh Connecting).
            timers.postDelayed({ if (wanted && !isActive) begin(reconnecting = linkWasUp, userInitiated = false) }, BT_ON_SETTLE)
        }
    }

    /** The radio link to the peer dropped: fail the connection now instead of waiting for a read error or the keepalive timeout. */
    override fun onAclDisconnected(device: BluetoothDevice) {
        if (state != State.CONNECTED || device.address != connectedAddress) return
        if (!ReconnectPolicy.aclDisconnectApplies(SystemClock.elapsedRealtime() - connectedAt)) return   // late broadcast of the previous link
        onFailure(gen, "Connection lost (Bluetooth link dropped)")
    }

    override fun onDiscoveryFinished() {
        // A late "finished" from a cancelled scan must not end a newly started one.
        if (state == State.DISCOVERING && !bt.isDiscovering) setState(State.DISCONNECTED)
    }

    override fun onBondStateChanged(device: BluetoothDevice, newState: Int, previousState: Int) {
        if (prefs.role == Role.MAIN_PHONE) {
            if (state != State.PAIRING || device.address != pairingAddress) return
            when (newState) {
                BluetoothDevice.BOND_BONDED -> {
                    timers.removeCallbacksAndMessages(null)
                    pairingAddress = null
                    guarded { connectSocket(gen, device, reconnecting = false) }     // pairing done -> connect (Stage 27: a system callback, so guarded)
                }
                BluetoothDevice.BOND_NONE ->
                    if (previousState == BluetoothDevice.BOND_BONDING) fatal("Pairing failed or was cancelled")
            }
        } else if (state == State.LISTENING || state == State.PAIRING) {
            // SIM phone: the main phone triggered pairing; reflect it, but keep the listener open.
            when {
                newState == BluetoothDevice.BOND_BONDING -> setState(State.PAIRING)
                newState == BluetoothDevice.BOND_BONDED -> setState(State.LISTENING)
                newState == BluetoothDevice.BOND_NONE && previousState == BluetoothDevice.BOND_BONDING -> {
                    lastError = "Pairing failed or was cancelled"; setState(State.LISTENING)
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Cancels everything in flight. Bumping [gen] invalidates all callbacks from earlier attempts. */
    private fun teardown() {
        gen++; listenGen++
        timers.removeCallbacksAndMessages(null)
        pairingAddress = null
        runCatching { serverSocket?.close() }; serverSocket = null
        runCatching { pendingSocket?.close() }; pendingSocket = null
        val t = transport; transport = null
        t?.close()
        connectedName = null; connectedAddress = null; lastPingMs = null
    }

    /** Non-recoverable until the user acts: stop retrying and show the error. */
    private fun fatal(message: String, t: Throwable? = null, area: Area = Area.CONNECT) {
        val a = when (message) { ConnectionMessages.PERMISSION_DENIED -> Area.PERMISSION; ConnectionMessages.BT_UNSUPPORTED -> Area.ADAPTER; else -> area }
        BluetoothDiagnostics.record(a, message, t, diagContext())      // Stage 27
        teardown()
        wanted = false; attempt = 0; retryAttempt = 0; linkWasUp = false; lostAt = 0; quickDrops = 0
        lastError = message
        setState(State.ERROR)
    }

    private fun setState(s: State) {
        if (state == State.CONNECTED && s != State.CONNECTED) lastDisconnectedAt = System.currentTimeMillis()
        state = s
        val name = connectedName
        forEachListener("state listener", s.name) { it.onStateChanged(s, name) }
    }

    /**
     * Stage 27: calls [f] for every listener; a listener (another layer: calls, SMS, audio, a screen) that throws cannot stop the others,
     * crash the main thread or leave this class half-way through a state change. The problem goes to [BluetoothDiagnostics].
     */
    private inline fun forEachListener(what: String, detail: String? = null, f: (Listener) -> Unit) {
        for (l in listeners) {
            try { f(l) } catch (e: Exception) { BluetoothDiagnostics.record(Area.LISTENER, what, e, detail) }
        }
    }

    /** Short context for a diagnostics line: what the state machine was doing. No address, no name. */
    private fun diagContext() = "state=$state attempt=$attempt linkWasUp=$linkWasUp quickDrops=$quickDrops wanted=$wanted role=${prefs.role}"

    /** An exception's message for the one-line error text: one line, short, Bluetooth addresses masked; the class name if there is no message. */
    private fun detailOf(e: Throwable): String = BluetoothDiagnostics.clean(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)

    private companion object {
        const val PERMISSION_DENIED = ConnectionMessages.PERMISSION_DENIED
        const val CONNECT_TIMEOUT = 20_000L
        const val PAIRING_TIMEOUT = 90_000L
        const val DISCOVERY_SAFETY_TIMEOUT = 30_000L
        const val BT_ON_SETTLE = 1_500L
        const val KEEPALIVE_TICK = 5_000L
        const val PING_INTERVAL = 15_000L
        const val RX_TIMEOUT = 45_000L
    }
}
