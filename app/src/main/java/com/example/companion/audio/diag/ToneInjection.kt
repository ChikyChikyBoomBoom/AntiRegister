package com.example.companion.audio.diag

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin

/**
 * Injection experiment: play a known tone through the normal app playback path (AudioTrack) while a call is active
 * and, at the same time, listen on the local microphone for that tone. The microphone check tells us whether the
 * remote could be hearing the tone ACOUSTICALLY (speaker/earpiece -> mic). Whether the remote really heard it is
 * only ever known from the human answer collected afterwards. Android offers no API to write into the cellular
 * uplink, so this experiment can only demonstrate absence or an acoustic path, and is built to say so.
 */
object ToneInjection {
    private const val RATE = 16000

    @SuppressLint("MissingPermission")
    fun run(
        c: Context, variant: String, usage: Int, content: Int, start: RunContext,
        callActiveNow: () -> Boolean, cancelled: () -> Boolean,
        durationMs: Int = 4000, hz: Int = 1000
    ): InjectionRun {
        // ---- local microphone listener (to detect acoustic leakage of our own tone)
        val micBuf = ShortArray(RATE * (durationMs + 2000) / 1000)
        var micLen = 0
        var micRec: AudioRecord? = null
        var micDetail = "not attempted"
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min > 0) {
                    val r = AudioRecord(
                        MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, RATE / 5 * 2)
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        r.startRecording()
                        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { micRec = r; micDetail = "recording" }
                        else { r.release(); micDetail = "microphone did not start" }
                    } else { r.release(); micDetail = "microphone init failed" }
                } else micDetail = "getMinBufferSize=$min"
            } catch (e: Throwable) { micDetail = "${e.javaClass.simpleName}: ${e.message}" }
        } else micDetail = "RECORD_AUDIO not granted"
        val mic = micRec

        fun drainMic() {
            val m = mic ?: return
            while (micLen < micBuf.size) {
                val n = m.read(micBuf, micLen, minOf(1600, micBuf.size - micLen), AudioRecord.READ_NON_BLOCKING)
                if (n <= 0) break
                micLen += n
            }
        }

        // ---- tone playback through the ordinary AudioTrack path
        var track: AudioTrack? = null
        var trackDetail = ""
        var started = false
        var routed: String? = null
        var toneStartIdx = 0
        try {
            drainMic(); SystemClock.sleep(300); drainMic()   // short pre-roll
            val minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val attrs = AudioAttributes.Builder().setUsage(usage).setContentType(content).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minOut, RATE / 5 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
            track = t
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                trackDetail = "AudioTrack not initialized"
            } else {
                toneStartIdx = micLen
                t.play()
                started = t.playState == AudioTrack.PLAYSTATE_PLAYING
                routed = t.routedDevice?.let { DeviceInfo.typeName(it.type) + " \"" + it.productName + "\"" }
                val total = RATE * durationMs / 1000
                val fade = RATE / 10
                val step = 2.0 * PI * hz / RATE
                val chunk = ShortArray(RATE / 50)
                var written = 0
                var ph = 0.0
                while (written < total && !cancelled()) {
                    val n = minOf(chunk.size, total - written)
                    for (i in 0 until n) {
                        val pos = written + i
                        val env = minOf(1.0, minOf(pos, total - pos).toDouble() / fade)
                        chunk[i] = (sin(ph) * 0.5 * env * 32767.0).toInt().toShort()
                        ph += step
                        if (ph > 2.0 * PI) ph -= 2.0 * PI
                    }
                    val w = t.write(chunk, 0, n)
                    if (w < 0) { trackDetail = "AudioTrack.write error $w"; break }
                    written += n
                    drainMic()
                }
                val tail = SystemClock.elapsedRealtime() + 500
                while (SystemClock.elapsedRealtime() < tail) { drainMic(); SystemClock.sleep(20) }
                if (trackDetail.isEmpty()) trackDetail = "played ${written * 1000 / RATE} ms"
            }
        } catch (e: Throwable) {
            trackDetail = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            if (mic != null) { drainMic(); try { mic.stop() } catch (_: Throwable) {}; mic.release() }
        }

        // ---- did our own tone reach the local microphone?
        var detected: Boolean? = null
        var ratio: Double? = null
        var micCheck = "microphone check unavailable ($micDetail)"
        val from = toneStartIdx + RATE * 4 / 10
        val to = minOf(micLen, toneStartIdx + (durationMs - 200) * RATE / 1000)
        if (mic != null && to - from > RATE / 2) {
            val meter = LevelMeter()
            meter.add(micBuf.copyOfRange(from, to), to - from)
            if (meter.zeroFraction >= 0.999) {
                micCheck = "microphone delivered digital silence: acoustic leakage could NOT be ruled out"
            } else {
                val tone = LevelMeter.toneRatio(micBuf, from, to, RATE, hz.toDouble())
                val control = LevelMeter.toneRatio(micBuf, from, to, RATE, hz * 1.13)
                ratio = tone
                detected = tone >= 0.01 && tone >= 10.0 * control
                micCheck = if (detected) "tone DETECTED on local microphone (acoustic path exists)"
                else "tone not detected on local microphone"
            }
        }

        val end = start.copy(callActive = start.callActive && callActiveNow())
        return InjectionRun(variant, hz, durationMs, end, started, trackDetail, routed, micCheck, detected, ratio, null)
    }
}
