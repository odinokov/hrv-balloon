package com.hrvballoon.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** What a finished session saves: the record's rate and kind, and the Settings offer in tenths (null = keep, 0 = clear). */
class SessionOutcome(val rateBpm: Double, val kind: Int, val offerTenths: Int?)

/**
 * [finalRate] is the pacer's rate at the end, [currentRate] the user's chosen rate. A search without a peak offers its
 * answer ([rfBest], near its start: the height estimate or the user's rate) only when the user breathes at another rate; an incomplete one records its best guess and offers nothing.
 */
fun sessionOutcome(
    wasSearch: Boolean, finalRate: Double, currentRate: Double, rfBest: Double?, converged: Boolean, noPeak: Boolean,
    probes: List<Pair<Double, Double>>,
): SessionOutcome = when {
    !wasSearch -> SessionOutcome(finalRate, SessionRecord.KIND_TRAINING, null)
    converged && rfBest != null -> SessionOutcome(rfBest, SessionRecord.KIND_RF_SEARCH, tenths(rfBest))
    noPeak -> (rfBest ?: currentRate).let { rate ->
        SessionOutcome(rate, SessionRecord.KIND_RF_NO_PEAK, if (offersRate(SessionRecord.KIND_RF_NO_PEAK, rate, currentRate)) tenths(rate) else 0)
    }
    else -> SessionOutcome(rfBest ?: probes.maxByOrNull { it.second }?.first ?: currentRate, SessionRecord.KIND_RF_INCOMPLETE, null)
}

/** A found rate is always offered; a search without a peak offers its answer, near its start, only to someone breathing at another rate. */
fun offersRate(kind: Int, rate: Double, currentRate: Double) =
    kind == SessionRecord.KIND_RF_SEARCH || (kind == SessionRecord.KIND_RF_NO_PEAK && abs(currentRate - rate) >= 0.05)

private fun tenths(rate: Double) = (rate * 10).roundToInt()
