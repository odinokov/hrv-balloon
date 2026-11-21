package com.hrvballoon.ui

import com.hrvballoon.core.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Constants and boundaries the other scene tests leave open, found by mutation-testing the Windows port
 * (tests/scene/test_scene_pins.py there). Every expected value is worked out by hand from the drawing rules.
 */
class RankScenePinsTest {
    private val scene = RankScene(160, 284, TestText.scene)
    private val w = scene.w

    private fun rec(i: Int) = SessionRecord(1_761_400_000_000 + i * 86_400_000L, 300_000, 5.5, 10.0, 0.8, 3, 68.0, 1.0, 280_000, 44.0, 55.0, 330)
    private fun search(kind: Int, rate: Double, probes: List<Pair<Double, Double>>) =
        SessionRecord(1L, 900_000, rate, 1.2, 0.8, 0, 64.0, 1.0, 800_000, 40.0, 50.0, 900, kind, probes, 5.0)
    private fun card(r: SessionRecord) = scene.renderResult(r, -1, 3, null, 5_000, 0).copyOf()
    private fun count(px: IntArray, c: Int) = px.count { it == c }
    private fun columns(px: IntArray, c: Int) = px.indices.filter { px[it] == c }.map { it % w }.toSet()

    private val good = listOf(5.0 to 8.0, 5.5 to 9.0, 6.0 to 8.0, 5.2 to 8.5, 5.8 to 8.6)
    private val worked = listOf(5.0 to 1.1, 5.5 to 0.6, 4.5 to 1.3, 4.2 to 1.4, 5.2 to 1.2)

    @Test fun onlySevenRowsAreTappable() {
        assertEquals(7, RankScene.MAX_ROWS)
        assertEquals(18, RankScene.ROW_H)
        assertEquals(6, RankScene.rowAt(30 + 6 * RankScene.ROW_H, 10))
        assertEquals(-1, RankScene.rowAt(30 + 7 * RankScene.ROW_H, 10))
        assertEquals(-1, RankScene.rowAt(29, 10))
    }

    @Test fun theEighthSessionIsTheFirstToBePinned() {
        val nine = List(9) { rec(it) }
        assertTrue(count(scene.render(nine, 7, 5_000), RankScene.HIGHLIGHT) > 0)   // pinned below a "..." row
        assertTrue(count(scene.render(nine, 6, 5_000), RankScene.HIGHLIGHT) > 0)   // shown in its own row
    }

    /** x 26..148 for 4..7 /min, y 64 (top value) to 120 (zero): a dotted top line every 4 px, a solid baseline. */
    @Test fun thePlotAxesAndTicks() {
        val px = card(search(SessionRecord.KIND_RF_SEARCH, 5.5, good))
        assertTrue((26..148).all { px[120 * w + it] == RankScene.SEPARATOR })
        assertEquals(
            listOf(true, false, false, false, true, false, false, false, true, false),
            (26 until 36).map { px[64 * w + it] == RankScene.SEPARATOR },
        )
        assertEquals(RankScene.SEPARATOR, px[121 * w + 26])   // tick at 4.0
        assertEquals(RankScene.SEPARATOR, px[122 * w + 26])
        assertEquals(RankScene.SEPARATOR, px[121 * w + 148])  // and at 7.0
    }

    @Test fun theReportedRateIsADashedLineOnEveryOtherPairOfRows() {
        val px = card(search(SessionRecord.KIND_RF_SEARCH, 4.6, worked))
        assertEquals(28, (64 until 120).count { px[it * w + 50] == RankScene.CARET })   // (y − 64) % 4 < 2
    }

    @Test fun theAxisWidensAroundProbesOutsideFourToSeven() {
        val high = card(search(SessionRecord.KIND_RF_SEARCH, 7.0, good + (7.4 to 5.0)))
        assertEquals(131, 26 + ((7.0 - 4.0) / (7.5 - 4.0) * 122).roundToInt())
        assertEquals(setOf(131), columns(high, RankScene.CARET))
        val low = card(search(SessionRecord.KIND_RF_SEARCH, 4.0, good + (3.6 to 5.0)))
        assertEquals(43, 26 + ((4.0 - 3.5) / (7.0 - 3.5) * 122).roundToInt())
        assertEquals(setOf(43), columns(low, RankScene.CARET))
    }

    @Test fun theCurveRunsHalfARateBeyondTheOuterProbes() {
        val bar = columns(card(search(SessionRecord.KIND_RF_SEARCH, 5.5, good)), RankScene.BAR)
        assertEquals(46, bar.min())    // 5.0 − 0.5: 26 + round(0.5 / 3 × 122)
        assertEquals(128, bar.max())   // 6.0 + 0.5: 26 + round(2.5 / 3 × 122)
    }

    @Test fun allZeroScoresDrawAFlatBaselineAndNoCurve() {
        val px = card(search(SessionRecord.KIND_RF_INCOMPLETE, 5.0, listOf(5.5 to 0.0, 6.0 to 0.0, 5.0 to 0.0)))
        assertEquals(0, count(px, RankScene.BAR))   // top = 1: the flat fit sits at y = 120, outside the plot
    }

    /** A score below 25% of the best is a dim ring with an empty centre (8 px), any other a 3 × 3 block. */
    @Test fun aFailedProbeIsHollow() {
        val base = listOf(5.0 to 8.0, 5.5 to 7.6, 4.5 to 7.2, 5.2 to 7.9)
        val failed = card(search(SessionRecord.KIND_RF_SEARCH, 5.0, base + (6.0 to 1.0)))   // 1.0 < 0.25 × 8.0
        val fine = card(search(SessionRecord.KIND_RF_SEARCH, 5.0, base + (6.0 to 3.0)))
        assertEquals(9, count(fine, RankScene.TEXT) - count(failed, RankScene.TEXT))
        assertEquals(8, count(failed, RankScene.DIM) - count(fine, RankScene.DIM))
    }

    @Test fun threeProbesMakeABestSoFarHeroAndTwoDoNot() {
        fun heroInk(probes: List<Pair<Double, Double>>): Int {
            val px = card(search(SessionRecord.KIND_RF_INCOMPLETE, 4.7, probes))
            return (22 until 34).sumOf { y -> (0 until w).count { x -> px[y * w + x] == RankScene.DIM } }
        }
        fun ink(text: String): Int {
            var n = 0
            PixelFont.draw(0, 0, text, RankScene.DIM, 2) { _, _, _ -> n++ }
            return n
        }
        assertEquals(ink("BEST SO FAR 4.7"), heroInk(worked.take(3)))
        assertEquals(ink(TestText.scene.noEstimate), heroInk(worked.take(2)))
    }

    @Test fun theHighlightMarkerBlinksEvery400Ms() {
        val records = List(3) { rec(it) }
        val on = count(scene.render(records, 1, 5_000), RankScene.HIGHLIGHT)    // 5000 / 400 = 12: even
        val off = count(scene.render(records, 1, 5_400), RankScene.HIGHLIGHT)   // 13: odd
        assertEquals(6, on - off)   // the ">" glyph has six ink pixels
        assertEquals(on, count(scene.render(records, 1, 5_400, blink = false), RankScene.HIGHLIGHT))
    }

    @Test fun twinklesHideOnePhaseInThreeEvery300Ms() {
        assertEquals(32, scene.twinkles.size)
        for (t in 0L until 2_000L step 150) {
            val px = scene.render(emptyList(), -1, t)
            for ((x, y, k) in scene.twinkles) {
                val colour = if (k == 0) RankScene.STAR_BRIGHT else RankScene.STAR_DIM
                assertEquals("t=$t at $x,$y phase $k", (t / 300 + k) % 3 != 0L, px[y * w + x] == colour)
            }
        }
    }

    @Test fun niceTopsAreOneOfTheStepsTimesAPowerOfTen() {
        assertEquals(listOf(1.0, 1.5, 2.0, 3.0, 8.0, 8.0, 10.0), listOf(1.0, 1.2, 1.9, 2.1, 7.0, 8.0, 9.5).map(scene::niceTop))
        assertEquals(0.8, scene.niceTop(0.7), 1e-9)
        assertEquals(150.0, scene.niceTop(120.0), 1e-9)
        assertEquals(100.0, scene.niceTop(100.0), 1e-9)   // at least v, within 1e-9
    }

    @Test fun largeCountsStayApproximateAtTheEdge() {
        assertEquals(
            listOf("7", "999", "1K+", "12K+", "999K+", "1M+", "25M+"),
            listOf(7, 999, 1_000, 12_345, 999_999, 1_000_000, 25_000_000).map(scene::compactCount),
        )
    }
}
