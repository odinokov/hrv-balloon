package com.hrvballoon.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp
import kotlin.math.roundToInt

/** As [RankScenePinsTest], for the parts of the session scene that carry data: status text and the heart-rate trace. */
class PixelScenePinsTest {
    private val w = 160

    private fun state(now: Long, hr: Double, periodMs: Double, forecast: ((Double) -> Double)? = null) = SceneState(
        0.3, false, 0.5, true, true, false, 12_345,
        traceT = DoubleArray(30) { now - 30_000.0 + 1_000 * it }, traceHr = DoubleArray(30) { hr }, traceNowMs = now,
        pacerPeriodMs = periodMs, forecast = forecast,
    )

    /** (px + 1) / ADVANCE characters: 29 characters are 29 × 5 − 1 = 144 px wide. */
    @Test fun fitKeepsWholeCharactersThatFitTheWidth() {
        val scene = PixelScene(w, 284)
        assertEquals("A".repeat(29), scene.fit("A".repeat(40), 144))
        assertEquals("A".repeat(28), scene.fit("A".repeat(40), 143))
        assertEquals("AB CD", scene.fit("AB CD", 144))
        assertEquals("AB ".repeat(20).take(29).trimEnd(), scene.fit("AB ".repeat(20), 144))
    }

    /**
     * A flat trace is one row: row(v) = round(yBot − 1 − (v − (centre − 15)) / 30 × (yBot − yTop − 2)), here with yBot = 275
     * and yTop = 232. The first frame centres on 68; a second, 1 s later at 80, moves the centre 1 − e^(−1/8) of the way.
     */
    @Test fun theTraceCentreFollowsTheMedianWithAnEightSecondTimeConstant() {
        val scene = PixelScene(w, 284)
        fun rows(now: Long, hr: Double) =
            scene.render(state(now, hr, 10_909.0)).let { px -> px.indices.filter { px[it] == PixelScene.TRACE }.map { it / w }.toSet() }
        assertEquals(254, (274 - (68 - (68 - 15)) / 30.0 * 41).roundToInt())
        assertEquals(setOf(254), rows(100_000, 68.0))
        val centre = 68 + (80 - 68) * (1 - exp(-1000 / 8000.0))
        assertEquals(239, (274 - (80 - (centre - 15)) / 30 * 41).roundToInt())
        assertEquals(setOf(239), rows(101_000, 80.0))
    }

    /**
     * Flat 68 beats/min and a constant forecast of 98: the line ends 2.8 s before now and the first dash column is 142,
     * 200 ms later, where v = 98 + (68 − 98) × e^(−0.2); with the centre at 68, v sits on row round(274 − (v − 53) / 30 × 41).
     */
    @Test fun theForecastJoinsTheLineOverOneSecond() {
        val px = PixelScene(w, 284).render(state(100_000, 68.0, 0.0) { 98.0 })
        val v = 98 + (68 - 98) * exp(-0.2)
        val row = (274 - (v - 53) / 30 * 41).roundToInt()
        assertEquals(246, row)
        assertEquals(PixelScene.TRACE_DIM, px[row * w + 142])
    }
}
