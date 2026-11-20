package com.example.companion.bluetooth

import android.bluetooth.BluetoothSocket
import com.example.companion.protocol.Message
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Framing over one connected socket. Knows nothing about roles or UI.
 *
 * Stage 5 wire format (replaces the Stage 2-4 one; both phones run the same APK, so update both):
 *   [int32 length][byte kind][body]       length = 1 + body size
 *   kind 0x01 = CONTROL : body = the JSON envelope of [Message] (unchanged)
 *   kind 0x02 = AUDIO   : body = one binary audio packet (see audio/transport/AudioPacket)
 *
 * Control and audio share the socket but not the code path: audio has its own bounded queue and writer thread, so a
 * burst of audio can never grow without limit (oldest packets are dropped to keep latency low) and audio frames
 * never pass through the main thread on receive.
 */
class MessageTransport(
    private val socket: BluetoothSocket,
    private val onMessage: (Message) -> Unit,
    private val onClosed: () -> Unit,
    /** Called on the read thread for every audio body. Must be fast and thread-safe. */
    private val onAudio: ((ByteArray) -> Unit)? = null
) {
    private val input = DataInputStream(socket.inputStream)
    private val output: OutputStream = socket.outputStream
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val audioQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_PACKETS)
    @Volatile private var audioWriter: Thread? = null

    /** Audio packets discarded on the send side because the queue was full (Bluetooth could not keep up). */
    val audioDropped = AtomicLong()

    /** Stage 27: frames whose handler threw on this link (only the first few are recorded, audio arrives ~50 times a second). */
    private var handlerErrors = 0

    fun start() {
        thread(name = "bt-read") {
            try {
                while (!closed.get()) {
                    val len = input.readInt()
                    if (len < 1 || len > MAX_FRAME) {
                        // Stage 27: behaviour unchanged (the link is closed); the reason is now recorded for developers.
                        if (!closed.get()) BluetoothDiagnostics.record(BluetoothDiagnostics.Area.TRANSPORT, "closing: invalid frame length", null, "len=$len")
                        break
                    }
                    val kind = input.readByte()
                    val body = ByteArray(len - 1)
                    input.readFully(body)
                    try {
                        when (kind) {
                            KIND_CONTROL -> Message.decode(body)?.let(onMessage)
                            KIND_AUDIO -> onAudio?.invoke(body)
                            else -> {}                       // unknown kind: ignore, stay in sync because length was honoured
                        }
                    } catch (e: RuntimeException) {
                        // Stage 27: a handler (for example the audio sink) that throws would end this thread with an uncaught exception
                        // and crash the app. The frame is dropped, the link stays up and in sync (the whole frame was already read).
                        if (handlerErrors++ < MAX_RECORDED_HANDLER_ERRORS)
                            BluetoothDiagnostics.record(BluetoothDiagnostics.Area.TRANSPORT, "frame handler threw", e, "kind=$kind")
                    }
                }
            } catch (e: IOException) {
                // The normal way a link ends (remote closed, out of range, our own close). Only an unexpected end is worth a line.
                if (!closed.get()) BluetoothDiagnostics.record(BluetoothDiagnostics.Area.TRANSPORT, "read ended", e)
            } catch (e: RuntimeException) {
                BluetoothDiagnostics.record(BluetoothDiagnostics.Area.TRANSPORT, "read loop failed", e)
            } finally { close() }
        }
    }

    fun send(m: Message): Boolean = writeFrame(KIND_CONTROL, m.encode())

    /** Non-blocking. Queues one audio packet; when the queue is full the OLDEST packet is dropped. */
    fun sendAudio(packet: ByteArray): Boolean {
        if (closed.get()) return false
        ensureAudioWriter()
        var droppedAny = false
        while (!audioQueue.offer(packet)) { if (audioQueue.poll() != null) { audioDropped.incrementAndGet(); droppedAny = true } }
        return !droppedAny
    }

    private fun ensureAudioWriter() {
        if (audioWriter != null) return
        synchronized(this) {
            if (audioWriter != null) return
            audioWriter = thread(name = "bt-audio-write", priority = Thread.MAX_PRIORITY) {
                try {
                    while (!closed.get()) {
                        val p = audioQueue.take()
                        if (!writeFrame(KIND_AUDIO, p)) break
                    }
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    /** One write() per frame: several tiny writes would become several tiny RFCOMM packets. */
    private fun writeFrame(kind: Byte, body: ByteArray): Boolean = try {
        val buf = ByteBuffer.allocate(5 + body.size)
        buf.putInt(1 + body.size).put(kind).put(body)
        synchronized(writeLock) { output.write(buf.array()); output.flush() }
        true
    } catch (e: IOException) {
        if (!closed.get()) BluetoothDiagnostics.record(BluetoothDiagnostics.Area.TRANSPORT, "write failed", e, "kind=$kind")   // Stage 27
        close(); false
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { socket.close() } catch (_: IOException) {}
        audioWriter?.interrupt()
        audioQueue.clear()
        onClosed()
    }

    companion object {
        private const val MAX_FRAME = 64 * 1024
        private const val MAX_RECORDED_HANDLER_ERRORS = 3
        const val KIND_CONTROL: Byte = 0x01
        const val KIND_AUDIO: Byte = 0x02
        /** ~240 ms of 20 ms frames. Beyond that, old audio is useless for a live call anyway. */
        const val AUDIO_QUEUE_PACKETS = 12
    }
}
