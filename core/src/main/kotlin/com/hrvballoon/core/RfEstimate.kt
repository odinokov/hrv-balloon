package com.hrvballoon.core

import kotlin.math.roundToInt

enum class Sex { MALE, FEMALE }

/** A starting rate for the resonance search; [raw] outside 5–7 /min means the formula was used beyond its data. */
class RfEstimate(val rateBpm: Double, val raw: Double) {
    val extrapolated get() = raw < 5.0 || raw > 7.0
}

/**
 * Hasuo et al. 2023 (doi:10.1007/s10484-023-09602-5): resonance rate from height and sex in 122 healthy Japanese adults,
 * men 17.90 − 0.07 × cm, women 15.88 − 0.06 × cm (adjusted R² 0.55 and 0.47). Rates were tested at 5–7 /min only, so the
 * result is clamped there and kept in the search's 0.1 /min steps. A start for the search, not a measurement.
 */
fun hasuoEstimate(heightCm: Double, sex: Sex): RfEstimate {
    require(heightCm in 130.0..210.0) { "height must be 130-210 cm" }
    val raw = if (sex == Sex.MALE) 17.90 - 0.07 * heightCm else 15.88 - 0.06 * heightCm
    return RfEstimate((raw.coerceIn(5.0, 7.0) * 10).roundToInt() / 10.0, raw)
}
