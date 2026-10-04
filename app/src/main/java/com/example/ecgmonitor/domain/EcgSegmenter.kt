package com.example.ecgmonitor.domain

/**
 * Extracts and Min-Max normalizes ECG heartbeats around R-peaks.
 * Conforms 1:1 with MATLAB segment_heartbeats.m.
 */
object EcgSegmenter {
    fun extractBeat(
        cleanHistory: List<Float>,
        rPeakIndexInHistory: Int,
        preSamples: Int,
        postSamples: Int
    ): FloatArray? {
        val startIndex = rPeakIndexInHistory - preSamples
        val endIndex = rPeakIndexInHistory + postSamples
        val expectedLength = preSamples + 1 + postSamples

        if (startIndex < 0 || endIndex >= cleanHistory.size) {
            return null
        }

        val beat = FloatArray(expectedLength)
        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE

        for (i in 0 until expectedLength) {
            val v = cleanHistory[startIndex + i]
            beat[i] = v
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val rangeVal = (maxVal - minVal).let { if (it < 0.0001f) 0.0001f else it }

        for (i in 0 until expectedLength) {
            beat[i] = ((beat[i] - minVal) / rangeVal).coerceIn(0f, 1f)
        }

        return beat
    }
}
