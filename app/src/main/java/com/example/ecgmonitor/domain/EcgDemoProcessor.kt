package com.example.ecgmonitor.domain

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

data class DemoEcgResult(
    val rawSamples: List<Short>,
    val filteredSamples: List<Short>,
    val detectedBeats: Int,
    val estimatedBpm: Int,
    val durationSeconds: Int
)

/**
 * Offline synthetic ECG signal generator for demonstration and testing of the
 * 125 Hz ECG processing chain and On-Device TFLite inference.
 */
object EcgDemoProcessor {
    const val SAMPLE_RATE = 125
    private const val DURATION_SECONDS = 30

    fun generate(): DemoEcgResult {
        val raw = (0 until SAMPLE_RATE * DURATION_SECONDS).map { index ->
            syntheticEcgSample(index.toDouble() / SAMPLE_RATE)
        }
        val notch = notch50Hz(raw)
        val detrended = removeBaseline(notch)
        val smoothed = smooth(detrended)
        val beats = panTompkins(smoothed)
        return DemoEcgResult(
            rawSamples = raw.map { (it * 1_000).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort() },
            filteredSamples = smoothed.map { (it * 1_000).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort() },
            detectedBeats = beats.size,
            estimatedBpm = if (beats.size > 1) (60.0 * SAMPLE_RATE * (beats.size - 1) / (beats.last() - beats.first())).toInt() else 0,
            durationSeconds = DURATION_SECONDS
        )
    }

    /**
     * Generates a single synthetic sample at elapsed time [time].
     * Introduces simulated heartbeats with periodic ectopic PVC variation every 8 beats.
     */
    fun syntheticEcgSample(time: Double): Double {
        val baseHeartRateBpm = 75.0
        val beatPeriod = 60.0 / baseHeartRateBpm
        val beatIndex = (time / beatPeriod).toInt()
        val cycle = (time % beatPeriod) / beatPeriod

        fun gaussian(center: Double, width: Double, amplitude: Double) =
            amplitude * exp(-0.5 * ((cycle - center) / width) * ((cycle - center) / width))

        // Every 8th beat has a wider QRS and inverted T-wave mimicking a Ventricular Ectopic Beat (PVC)
        val isPvc = (beatIndex % 8 == 7)

        val waveform = if (isPvc) {
            // Premature Ventricular contraction: No P-wave, wide QRS, inverted T
            val qrs = gaussian(0.32, 0.040, 1.4) + gaussian(0.39, 0.035, -0.6)
            val tWave = gaussian(0.58, 0.080, -0.45)
            qrs + tWave
        } else {
            // Normal sinus rhythm: P, Q, R, S, T
            val pWave = gaussian(0.18, 0.030, 0.14)
            val qWave = gaussian(0.37, 0.010, -0.15)
            val rWave = gaussian(0.40, 0.012, 1.05)
            val sWave = gaussian(0.44, 0.012, -0.22)
            val tWave = gaussian(0.67, 0.060, 0.32)
            pWave + qWave + rWave + sWave + tWave
        }

        // Realistic interference: 0.18 Hz respiration baseline wander + 50 Hz powerline interference
        val baselineWander = 0.12 * sin(2.0 * PI * 0.18 * time)
        val mainsNoise = 0.06 * sin(2.0 * PI * 50.0 * time)
        val electromyogramNoise = 0.012 * sin(2.0 * PI * 19.0 * time)

        return waveform + baselineWander + mainsNoise + electromyogramNoise
    }

    private fun notch50Hz(input: List<Double>): List<Double> {
        val output = MutableList(input.size) { 0.0 }
        for (n in input.indices) {
            val x0 = input[n]
            val x1 = input.getOrElse(n - 1) { 0.0 }
            val x2 = input.getOrElse(n - 2) { 0.0 }
            val y1 = output.getOrElse(n - 1) { 0.0 }
            val y2 = output.getOrElse(n - 2) { 0.0 }
            output[n] = 0.8562 * x0 + 1.3854 * x1 + 0.8562 * x2 - 1.3753 * y1 - 0.7225 * y2
        }
        return output
    }

    private fun removeBaseline(input: List<Double>): List<Double> {
        var baseline = 0.0
        return input.map { value ->
            baseline = 0.01 * value + 0.99 * baseline
            value - baseline
        }
    }

    private fun smooth(input: List<Double>): List<Double> {
        var previous = 0.0
        return input.map { value ->
            val res = 0.5 * value + 0.5 * previous
            previous = res
            res
        }
    }

    private fun panTompkins(input: List<Double>): List<Int> {
        val energy = input.indices.map { n ->
            val derivative = input[n] - input.getOrElse(n - 1) { input[n] }
            derivative * derivative * 10_000.0
        }
        val beats = mutableListOf<Int>()
        var threshold = 30.0
        var lastBeat = -40
        for (n in energy.indices) {
            val start = kotlin.math.max(0, n - 17)
            val integrated = energy.subList(start, n + 1).average()
            if (integrated > threshold && n - lastBeat >= 40) {
                beats += n
                lastBeat = n
                threshold = 0.6 * integrated
            }
            threshold *= 0.998
        }
        return beats
    }
}
