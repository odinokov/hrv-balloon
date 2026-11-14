package com.hrvballoon.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** What a finished session saves: the record's rate and kind, and the Settings offer in tenths (null = keep, 0 = clear). */
class SessionOutcome(val rateBpm: Double, val kind: Int, val offerTenths: Int?)

/**
 * [finalRate] is the pacer's rate at the end, [currentRate] the user's chosen rate. A search without a peak offers its
 * fallback ([rfBest]: the height estimate or 5.5) only when the user breathes at another rate; an incomplete one records its best guess and offers nothing.
 */
fun sessionOutcome(
    wasSearch: Boolean, finalRate: Double, currentRate: Double, rfBest: Double?, converged: Boolean, noPeak: Boolean,
    probes: List<Pair<Double, Double>>,
): SessionOutcome = when {
    !wasSearch -> SessionOutcome(finalRate, SessionRecord.KIND_TRAINING, null)
    converged && rfBest != null -> SessionOutcome(rfBest, SessionRecord.KIND_RF_SEARCH, tenths(rfBest))
    noPeak -> (rfBest ?: RfSearch.FALLBACK_BPM).let { rate ->
        SessionOutcome(rate, SessionRecord.KIND_RF_NO_PEAK, if (abs(currentRate - rate) < 0.05) 0 else tenths(rate))
    }
    else -> SessionOutcome(rfBest ?: probes.maxByOrNull { it.second }?.first ?: currentRate, SessionRecord.KIND_RF_INCOMPLETE, null)
}

private fun tenths(rate: Double) = (rate * 10).roundToInt()
