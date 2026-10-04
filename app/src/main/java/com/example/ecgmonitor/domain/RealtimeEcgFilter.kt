package com.example.ecgmonitor.domain

/** Streaming implementation of the Notch 50 Hz → baseline removal → smoothing chain. */
class RealtimeEcgFilter {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var baseline = 0.0
    private var smoothPrevious = 0.0

    fun process(raw: Float): Float {
        val x0 = raw.toDouble()
        val notch = .8562 * x0 + 1.3854 * x1 + .8562 * x2 - 1.3753 * y1 - .7225 * y2
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = notch
        baseline = .01 * notch + .99 * baseline
        val smooth = .5 * (notch - baseline) + .5 * smoothPrevious
        smoothPrevious = smooth
        return smooth.toFloat()
    }

    fun process(raw: Short): Short {
        return process(raw.toFloat()).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
        baseline = 0.0
        smoothPrevious = 0.0
    }
}
