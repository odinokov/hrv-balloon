package com.hrvballoon.core

/** A star for each breath the drawn balloon spends at the ceiling (≥ [hit]): the first touch, then one more per new breath it stays. */
class StarCounter(private val hit: Double = 0.985) {
    var count = 0
        private set
    private var lastBreath = Int.MIN_VALUE

    /** [breath] = index of the current pacer breath. */
    fun update(height01: Double, breath: Int): Boolean {
        if (height01 < hit || breath == lastBreath) return false
        lastBreath = breath
        count++
        return true
    }
}
