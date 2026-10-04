package com.example.ecgmonitor.domain

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

data class DemoEcgResult(
    val rawSamples: List<Short>,
    val filteredSamples: List<Short>,
    val detectedBeats: Int,
    val estimatedBpm: Int,
    val durationSeconds: Int
)

/**
 * Offline signal for demonstrating the configured 125 Hz ECG processing chain.
 * It is a synthetic signal only; it is not patient data.
 */
object EcgDemoProcessor {
    private const val sampleRate = 125
    private const val durationSeconds = 30

    fun generate(): DemoEcgResult {
        val raw = (0 until sampleRate * durationSeconds).map { index -> syntheticEcg(index.toDouble() / sampleRate) }
        val notch = notch50Hz(raw)
        val detrended = removeBaseline(notch)
        val smoothed = smooth(detrended)
        val beats = panTompkins(smoothed)
        return DemoEcgResult(
            rawSamples = raw.map { (it * 1_000).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort() },
            filteredSamples = smoothed.map { (it * 1_000).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort() },
            detectedBeats = beats.size,
            estimatedBpm = if (beats.size > 1) (60.0 * sampleRate * (beats.size - 1) / (beats.last() - beats.first())).toInt() else 0,
            durationSeconds = durationSeconds
        )
    }

    /** f0 = 50 Hz, r = 0.850: y[n]=.8562x[n]+1.3854x[n-1]+.8562x[n-2]-1.3753y[n-1]-.7225y[n-2]. */
    private fun notch50Hz(input: List<Double>): List<Double> {
        val output = MutableList(input.size) { 0.0 }
        for (n in input.indices) {
            val x0 = input[n]; val x1 = input.getOrElse(n - 1) { 0.0 }; val x2 = input.getOrElse(n - 2) { 0.0 }
            val y1 = output.getOrElse(n - 1) { 0.0 }; val y2 = output.getOrElse(n - 2) { 0.0 }
            output[n] = .8562 * x0 + 1.3854 * x1 + .8562 * x2 - 1.3753 * y1 - .7225 * y2
        }
        return output
    }

    /** alpha = .010: B[n]=.01x[n]+.99B[n-1]; y[n]=x[n]-B[n]. */
    private fun removeBaseline(input: List<Double>): List<Double> {
        var baseline = 0.0
        return input.map { value -> baseline = .01 * value + .99 * baseline; value - baseline }
    }

    /** alpha = .500: y[n]=.5x[n]+.5y[n-1]. */
    private fun smooth(input: List<Double>): List<Double> {
        var previous = 0.0
        return input.map { value -> (.5 * value + .5 * previous).also { previous = it } }
    }

    /** W = 18 samples, refractory = 40 samples, initial threshold = 30, threshold decay = .998. */
    private fun panTompkins(input: List<Double>): List<Int> {
        val energy = input.indices.map { n ->
            val derivative = input[n] - input.getOrElse(n - 1) { input[n] }
            derivative * derivative * 10_000.0
        }
        val beats = mutableListOf<Int>(); var threshold = 30.0; var lastBeat = -40
        for (n in energy.indices) {
            val start = max(0, n - 17)
            val integrated = energy.subList(start, n + 1).average()
            if (integrated > threshold && n - lastBeat >= 40) {
                beats += n; lastBeat = n; threshold = .6 * integrated
            }
            threshold *= .998
        }
        return beats
    }

    private fun syntheticEcg(time: Double): Double {
        val cycle = (time % (60.0 / 72.0)) / (60.0 / 72.0)
        fun gaussian(center: Double, width: Double, amplitude: Double) = amplitude * exp(-.5 * ((cycle - center) / width) * ((cycle - center) / width))
        val waveform = gaussian(.18, .030, .12) + gaussian(.37, .010, -.15) + gaussian(.40, .012, 1.0) + gaussian(.44, .012, -.22) + gaussian(.67, .060, .30)
        val baselineWander = .14 * sin(2.0 * PI * .18 * time)
        val mainsNoise = .08 * sin(2.0 * PI * 50.0 * time)
        val deterministicNoise = .015 * sin(2.0 * PI * 17.0 * time)
        return waveform + baselineWander + mainsNoise + deterministicNoise
    }
}
