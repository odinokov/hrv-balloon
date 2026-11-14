package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Rubber-ball dip after a ceiling hit: a damped sine that first pulls the balloon down [amp] of its travel, then settles. */
class Bounce(private val amp: Double = 0.06, private val periodMs: Double = 600.0, private val decayMs: Double = 450.0) {
    private var startMs = Long.MIN_VALUE

    fun hit(nowMs: Long) { startMs = nowMs }

    /** Downward offset in height units (negative = overshoot upward); zero before the hit and after three periods. */
    fun offset(nowMs: Long): Double {
        val t = (nowMs - startMs).toDouble()
        if (t < 0 || t > 3 * periodMs) return 0.0
        return amp * exp(-t / decayMs) * sin(2 * PI * t / periodMs)
    }
}
