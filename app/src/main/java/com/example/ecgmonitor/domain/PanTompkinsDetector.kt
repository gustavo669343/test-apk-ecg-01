package com.example.ecgmonitor.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time streaming Pan-Tompkins QRS Detector.
 * Matches 1:1 the ESP32 C++ and MATLAB (pan_tompkins_qrs.m) implementations at 125 Hz.
 */
class PanTompkinsDetector(
    private val sampleRate: Int = 125,
    private val windowSize: Int = 18,
    private val refractorySamples: Int = 40,
    private val initThreshold: Double = 30.0,
    private val thresholdDecay: Double = 0.998,
    private val bpmAlpha: Double = 0.1
) {
    private var prevRaw = 0.0
    private var prevHp = 0.0
    private var prevLp = 0.0

    private val diffBuffer = DoubleArray(3) // [lp, prev_lp1, prev_lp2]
    private val mwiWindow = DoubleArray(windowSize)
    private var mwiIdx = 0
    private var mwiSum = 0.0

    private var threshold = initThreshold
    private var lastPeakIndex = -refractorySamples
    private var smoothedBpm = 0.0

    private var sampleCount = 0

    fun reset() {
        prevRaw = 0.0
        prevHp = 0.0
        prevLp = 0.0
        diffBuffer.fill(0.0)
        mwiWindow.fill(0.0)
        mwiIdx = 0
        mwiSum = 0.0
        threshold = initThreshold
        lastPeakIndex = -refractorySamples
        smoothedBpm = 0.0
        sampleCount = 0
    }

    data class DetectionResult(
        val isPeak: Boolean,
        val rPeakIndex: Int,
        val refinedRPeakIndex: Int,
        val instantBpm: Float,
        val smoothedBpm: Float,
        val mwiValue: Double,
        val currentThreshold: Double
    )

    /**
     * Process one sample from the notch-filtered signal.
     * cleanHistory: recent clean samples for [-15, +5] peak refinement.
     */
    fun processSample(notchVal: Float, cleanHistory: List<Float>): DetectionResult {
        val currentIdx = sampleCount++
        val notchDouble = notchVal.toDouble()

        // 1. Highpass (HP 0.95)
        val hpOut = 0.95 * (prevHp + notchDouble - prevRaw)
        prevRaw = notchDouble
        prevHp = hpOut

        // 2. Lowpass (LP 0.15)
        val lpOut = prevLp + 0.15 * (hpOut - prevLp)
        prevLp = lpOut

        // 3. Differentiator (2-sample delay)
        val diffVal = lpOut - diffBuffer[2]
        diffBuffer[2] = diffBuffer[1]
        diffBuffer[1] = lpOut

        // 4. Moving Window Integrator (MWI)
        val absDiff = abs(diffVal)
        mwiSum -= mwiWindow[mwiIdx]
        mwiWindow[mwiIdx] = absDiff
        mwiSum += absDiff
        mwiIdx = (mwiIdx + 1) % windowSize
        val mwiOut = mwiSum / windowSize

        var isPeak = false
        var instantBpm = 0f
        var refinedIndex = currentIdx

        // 5. Peak Detection & Adaptive Threshold
        if (mwiOut > threshold) {
            val interval = currentIdx - lastPeakIndex
            if (interval > refractorySamples) {
                isPeak = true
                if (lastPeakIndex >= 0) {
                    val rawBpm = (60.0 * sampleRate) / interval
                    if (rawBpm in 30.0..220.0) {
                        instantBpm = rawBpm.toFloat()
                        smoothedBpm = if (smoothedBpm == 0.0) {
                            rawBpm
                        } else {
                            bpmAlpha * rawBpm + (1.0 - bpmAlpha) * smoothedBpm
                        }
                    }
                }
                lastPeakIndex = currentIdx
                threshold = mwiOut * 0.6

                // 6. Refine R-peak position in [-15, +5] window on clean signal
                if (cleanHistory.isNotEmpty()) {
                    val historySize = cleanHistory.size
                    // cleanHistory's last element corresponds to currentIdx
                    val searchStartRel = max(0, historySize - 1 - 15)
                    val searchEndRel = historySize - 1 // up to current sample

                    var maxAmp = -1f
                    var bestRelIdx = historySize - 1
                    for (k in searchStartRel..searchEndRel) {
                        val amp = abs(cleanHistory[k])
                        if (amp > maxAmp) {
                            maxAmp = amp
                            bestRelIdx = k
                        }
                    }
                    val offset = (historySize - 1) - bestRelIdx
                    refinedIndex = currentIdx - offset
                }
            } else {
                if (mwiOut * 0.6 > threshold) {
                    threshold = mwiOut * 0.6
                }
            }
        } else {
            threshold *= thresholdDecay
        }

        return DetectionResult(
            isPeak = isPeak,
            rPeakIndex = currentIdx,
            refinedRPeakIndex = refinedIndex,
            instantBpm = instantBpm,
            smoothedBpm = smoothedBpm.toFloat(),
            mwiValue = mwiOut,
            currentThreshold = threshold
        )
    }
}
