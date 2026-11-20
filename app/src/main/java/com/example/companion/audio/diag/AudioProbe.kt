package com.example.companion.audio.diag

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock

/**
 * Tries ONE audio source: (1) can an AudioRecord be created and initialized, (2) does it start and deliver frames,
 * (3) what level does it carry. Audio is analysed in memory and discarded; nothing is written anywhere.
 * Whether a level is actually call audio is decided later by [Evaluator], never here.
 */
object AudioProbe {
    private val RATES = intArrayOf(16000, 44100, 48000, 8000)
    private const val SKIP_MS = 250

    @SuppressLint("MissingPermission") // checked explicitly below
    fun run(
        c: Context, src: TestSource, phase: Phase?, durationMs: Int,
        start: RunContext, callActiveNow: () -> Boolean, cancelled: () -> Boolean
    ): ProbeResult {
        fun failed(initOk: Boolean, initDetail: String, rate: Int, recDetail: String) = ProbeResult(
            src, phase, initOk, initDetail, rate, false, recDetail, -120.0, -120.0, 1.0, 0, 0, null, null,
            Signal.NOT_RECORDED, start
        )

        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return failed(false, "RECORD_AUDIO not granted", 0, "not attempted")

        var rec: AudioRecord? = null
        var rate = 0
        val errors = StringBuilder()
        loop@ for (r in RATES) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { errors.append("$r Hz: getMinBufferSize=$min; "); continue }
            try {
                val cand = AudioRecord(
                    src.id, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, r / 5 * 2)
                )
                if (cand.state == AudioRecord.STATE_INITIALIZED) { rec = cand; rate = r; break@loop }
                errors.append("$r Hz: state=UNINITIALIZED; ")
                cand.release()
            } catch (e: SecurityException) {
                errors.append("SecurityException(${e.message}); "); break@loop   // other rates will not change this
            } catch (e: IllegalArgumentException) {
                errors.append("$r Hz: IllegalArgumentException(${e.message}); ")
            } catch (e: UnsupportedOperationException) {
                errors.append("$r Hz: UnsupportedOperationException(${e.message}); ")
            } catch (e: Throwable) {
                errors.append("$r Hz: ${e.javaClass.simpleName}(${e.message}); ")
            }
        }
        val r = rec ?: return failed(false, errors.toString().trim().ifEmpty { "no usable sample rate" }, 0, "not attempted")

        val meter = LevelMeter()
        var recDetail = ""
        var silenced: Boolean? = null
        var routed: String? = null
        var readErrors = 0
        var framesTotal = 0L
        var recordingState = false
        try {
            try { r.startRecording() } catch (e: IllegalStateException) { recDetail = "startRecording threw: ${e.message}" }
            recordingState = r.recordingState == AudioRecord.RECORDSTATE_RECORDING
            if (recordingState) {
                val buf = ShortArray(rate / 10)
                val begin = SystemClock.elapsedRealtime()
                val skipUntil = begin + SKIP_MS
                val end = begin + durationMs
                var consecutive = 0
                var polled = false
                while (!cancelled()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= end) break
                    val n = r.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                    if (n < 0) {
                        readErrors++; consecutive++
                        if (consecutive >= 5) { recDetail = "read() error $n"; break }
                        SystemClock.sleep(10); continue
                    }
                    consecutive = 0
                    if (n == 0) { SystemClock.sleep(10); continue }
                    framesTotal += n
                    if (now >= skipUntil) meter.add(buf, n)
                    if (!polled && now >= begin + durationMs / 2) {
                        polled = true
                        if (Build.VERSION.SDK_INT >= 29) silenced = r.activeRecordingConfiguration?.isClientSilenced
                        routed = r.routedDevice?.let { DeviceInfo.typeName(it.type) + " \"" + it.productName + "\"" }
                    }
                }
            } else if (recDetail.isEmpty()) {
                recDetail = "recordingState=${r.recordingState} after startRecording()"
            }
        } finally {
            try { r.stop() } catch (_: Throwable) {}
            r.release()
        }

        val recOk = recordingState && meter.samples > 0
        if (recordingState && !recOk && recDetail.isEmpty()) recDetail = "state RECORDING but no frames were delivered"
        val signal = when {
            !recOk -> Signal.NOT_RECORDED
            silenced == true || meter.peak == 0 || meter.zeroFraction >= 0.999 -> Signal.SILENT
            meter.avgDb < -50.0 -> Signal.LOW_LEVEL
            else -> Signal.ACTIVE
        }
        val end = start.copy(callActive = start.callActive && callActiveNow())
        return ProbeResult(
            src, phase, true, "initialized at $rate Hz", rate, recOk, if (recOk) "frames delivered ($framesTotal)" else recDetail,
            meter.avgDb, meter.peakDb, meter.zeroFraction, framesTotal, readErrors, silenced, routed, signal, end
        )
    }
}
