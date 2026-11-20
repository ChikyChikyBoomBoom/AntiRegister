package com.example.companion.audio.diag

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/** In-memory PCM16 level statistics. Samples are never stored by this class. */
class LevelMeter {
    var samples = 0L; private set
    var peak = 0; private set
    private var sumSq = 0.0
    private var zeros = 0L

    fun add(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            val s = buf[i].toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            if (s == 0) zeros++
            sumSq += s.toDouble() * s
        }
        samples += n
    }

    val avgDb: Double get() = toDb(if (samples == 0L) 0.0 else sqrt(sumSq / samples))
    val peakDb: Double get() = toDb(peak.toDouble())
    val zeroFraction: Double get() = if (samples == 0L) 1.0 else zeros.toDouble() / samples

    companion object {
        /** dBFS for a PCM16 amplitude; -120 is the floor ("nothing"). */
        fun toDb(amp: Double): Double = if (amp <= 0.0) -120.0 else maxOf(-120.0, 20.0 * log10(amp / 32768.0))

        /** Fraction (0..1) of the signal power that sits at [hz] (Goertzel). Used to detect our own test tone. */
        fun toneRatio(x: ShortArray, from: Int, to: Int, rate: Int, hz: Double): Double {
            val n = to - from
            if (from < 0 || to > x.size || n < rate / 10) return 0.0
            val coeff = 2.0 * cos(2.0 * PI * hz / rate)
            var s1 = 0.0
            var s2 = 0.0
            var sumSq = 0.0
            for (i in from until to) {
                val v = x[i].toDouble()
                val s0 = v + coeff * s1 - s2
                s2 = s1; s1 = s0
                sumSq += v * v
            }
            val mag2 = s1 * s1 + s2 * s2 - coeff * s1 * s2
            val amp = 2.0 * sqrt(maxOf(mag2, 0.0)) / n
            val tonePower = amp * amp / 2.0
            val total = sumSq / n
            return if (total <= 0.0) 0.0 else minOf(1.0, tonePower / total)
        }
    }
}
