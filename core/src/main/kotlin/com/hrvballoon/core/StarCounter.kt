package com.hrvballoon.core

/** Star slot at session time [tMs]: 10 s long, edges 7.5 s into each 10 s (mid-exhale at 6.0/min). */
fun starSlot(tMs: Double): Int = ((tMs + STAR_SLOT_OFFSET_MS) / STAR_SLOT_MS).toInt()

const val STAR_SLOT_MS = 10_000.0
const val STAR_SLOT_OFFSET_MS = 2_500.0

/** A star for each slot the drawn balloon spends at the ceiling (≥ [hit]): the first touch, then one more per new slot it stays. */
class StarCounter(private val hit: Double = 0.985) {
    var count = 0
        private set
    private var lastSlot = Int.MIN_VALUE

    /** [slot] = the current star slot ([starSlot]). [live] false (settling: the height is held, not measured) never pays and changes nothing. */
    fun update(height01: Double, slot: Int, live: Boolean = true): Boolean {
        if (!live) return false
        if (height01 < hit || slot == lastSlot) return false
        lastSlot = slot
        count++
        return true
    }
}
