package com.example.companion.audio.transport

import com.example.companion.bluetooth.ConnectionManager
import com.example.companion.protocol.Message

/**
 * The pipe underneath AudioTransport: "control messages + binary audio packets to the other phone". AudioTransport
 * never imports Bluetooth; only [BluetoothAudioLink] does. Keeps control and audio logically separate.
 */
interface AudioLink {
    interface Listener {
        /** Main thread. */ fun onLinkUp()
        /** Main thread. */ fun onLinkDown()
        /** Main thread. Every non-keepalive control message (the listener ignores the ones it does not know). */
        fun onControl(message: Message)
    }

    val isUp: Boolean
    /** Audio packets the link had to throw away because it could not send fast enough (since connect). */
    val txDropped: Long
    fun sendControl(message: Message)
    /** Non-blocking, any thread. */
    fun sendAudio(packet: ByteArray): Boolean
    /** Called on the Bluetooth read thread, NOT the main thread. */
    fun setAudioReceiver(receiver: ((ByteArray) -> Unit)?)
    fun setListener(listener: Listener?)
}

/** AudioLink over the existing Bluetooth connection (same RFCOMM socket, separate frame kind and send queue). */
class BluetoothAudioLink(private val connection: ConnectionManager) : AudioLink, ConnectionManager.Listener {
    private var listener: AudioLink.Listener? = null
    private var up = connection.state == ConnectionManager.State.CONNECTED

    init { connection.addListener(this) }

    override val isUp get() = connection.state == ConnectionManager.State.CONNECTED
    override val txDropped get() = connection.audioTxDropped
    override fun sendControl(message: Message) = connection.send(message)
    override fun sendAudio(packet: ByteArray) = connection.sendAudio(packet)
    override fun setAudioReceiver(receiver: ((ByteArray) -> Unit)?) { connection.audioSink = receiver }
    override fun setListener(listener: AudioLink.Listener?) { this.listener = listener }

    override fun onStateChanged(state: ConnectionManager.State, deviceName: String?) {
        val now = state == ConnectionManager.State.CONNECTED
        if (now == up) return
        up = now
        if (now) listener?.onLinkUp() else listener?.onLinkDown()
    }

    override fun onMessage(message: Message) { listener?.onControl(message) }
}
