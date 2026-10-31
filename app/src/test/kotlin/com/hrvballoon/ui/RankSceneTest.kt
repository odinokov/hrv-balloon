package com.hrvballoon.ui

import com.hrvballoon.core.SessionRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankSceneTest {
    private val scene = RankScene(160, 284)
    private fun rec(i: Int, p: Double = 10.0 - i, stars: Int = 3) =
        SessionRecord(1_761_400_000_000 + i * 86_400_000L, 300_000, 5.5, p, 0.8, stars, 68.0, 1.0, 280_000, 44.0, 55.0, 330)
    private val records = List(3) { rec(it) }

    private fun count(px: IntArray, c: Int) = px.count { it == c }

    @Test fun everyPixelFromThePalette() {
        val palette = RankScene.PALETTE.toSet()
        assertTrue(scene.render(records, 1, 5_000).all { it in palette })
        assertTrue(scene.render(emptyList(), -1, 5_000).all { it in palette })
        assertTrue(scene.renderResult(records[1], 2, 3, records[0], 5_000, lifetimeStars = 3).all { it in palette })
    }

    @Test fun rowsAreDrawnInPixelFont() {
        val none = count(scene.render(emptyList(), -1, 5_000), RankScene.TEXT)
        val three = count(scene.render(records, -1, 5_000), RankScene.TEXT)
        assertTrue(three > none + 3 * 20)
        assertEquals(0, count(scene.render(records, -1, 5_000), RankScene.HIGHLIGHT))
        assertTrue(count(scene.render(records, 1, 5_000), RankScene.HIGHLIGHT) > 20)
    }

    @Test fun highlightBeyondTwelveIsPinned() {
        val many = List(15) { rec(it, p = 15.0 - it * 0.5) }
        assertTrue(count(scene.render(many, 14, 5_000), RankScene.HIGHLIGHT) > 40)
        assertEquals(0, count(scene.render(many, -1, 5_000), RankScene.HIGHLIGHT))
    }

    @Test fun resultCardRenders() {
        val px = scene.renderResult(records[1], 2, 3, records[0], 5_000, lifetimeStars = 12).copyOf()
        assertTrue(count(px, RankScene.HIGHLIGHT) > 200)   // hero line drawn at 2×, in one steady colour
        assertTrue(count(px, RankScene.TEXT) > 150)
        val best = scene.renderResult(records[0], 1, 3, records[0], 5_000, lifetimeStars = 12)
        assertFalse(px.contentEquals(best))
        val unranked = SessionRecord(1, 30_000, 5.5, 5.0, 0.5, 0, 70.0, 2.0, 20_000, null, null, 30)
        assertTrue(count(scene.renderResult(unranked, -1, 3, records[0], 5_000, lifetimeStars = 0), RankScene.DIM) > 0)
    }

    @Test fun searchCardShowsProbeCurveAndCertainty() {
        val probes = listOf(5.5 to 8.0, 6.2 to 9.4, 4.8 to 6.1, 6.6 to 7.0, 6.0 to 9.1)
        val done = SessionRecord(1, 900_000, 6.1, 9.0, 0.8, 2, 66.0, 1.0, 800_000, 40.0, 50.0, 900, SessionRecord.KIND_RF_SEARCH, probes)
        val card = scene.renderResult(done, -1, 3, null, 5_000, lifetimeStars = 0).copyOf()
        assertTrue(count(card, RankScene.BAR) > 40)
        val incomplete = SessionRecord(1, 300_000, 5.5, 8.0, 0.8, 1, 66.0, 1.0, 200_000, 40.0, 50.0, 300, SessionRecord.KIND_RF_INCOMPLETE, probes.take(2))
        val card2 = scene.renderResult(incomplete, -1, 3, null, 5_000, lifetimeStars = 0)
        assertTrue(count(card2, RankScene.DIM) > count(card, RankScene.DIM))
    }

    @Test fun emptyStateDistinguishesUnranked() {
        val a = scene.render(emptyList(), -1, 5_000, hasUnranked = false).copyOf()
        val b = scene.render(emptyList(), -1, 5_000, hasUnranked = true)
        assertFalse(a.contentEquals(b))
    }

    @Test fun threeDigitStarsFitTheirColumnWithoutTouchingTheMargin() {
        val px = scene.render(listOf(rec(0, stars = 999)), -1, 5_000).copyOf()
        var starInk = 0
        PixelFont.draw(0, 0, "*999", RankScene.TEXT) { _, _, _ -> starInk++ }
        val visibleInk = (30 until 48).sumOf { y -> (128 until 152).count { x -> px[y * scene.w + x] == RankScene.TEXT } }
        assertEquals("The full star count must fit in the stars column", starInk, visibleInk)
        assertTrue((30 until 48).all { y -> (154 until 160).all { x -> px[y * scene.w + x] != RankScene.TEXT } })
    }

    @Test fun tableShowsStarsWithoutScoreOrRate() {
        val a = rec(0, p = 10.0, stars = 12)
        val b = SessionRecord(a.startMs, a.durationMs, 6.9, 25.0, 0.9, a.stars,
            90.0, 3.0, a.validMs, 80.0, 100.0, 500)
        val first = scene.render(listOf(a), -1, 5_000).copyOf()
        assertArrayEquals("Only stars and session identity should affect a ranking row", first,
            scene.render(listOf(b), -1, 5_000))
    }

    @Test fun starResultIgnoresPhysiologyAndRate() {
        val a = rec(0, p = 10.0, stars = 12)
        val b = SessionRecord(a.startMs, a.durationMs, 6.9, 25.0, 0.9, a.stars,
            90.0, 3.0, a.validMs, 80.0, 100.0, 500)
        val first = scene.renderResult(a, 2, 3, records[0], 5_000, lifetimeStars = 12).copyOf()
        assertArrayEquals("The normal result report should show stars and session identity only", first,
            scene.renderResult(b, 2, 3, records[0], 5_000, lifetimeStars = 12))
    }

    @Test fun rowHitTesting() {
        assertEquals(1, RankScene.rowAt(30 + RankScene.ROW_H + 3, 3))
        assertEquals(-1, RankScene.rowAt(200, 3))
        assertEquals(-1, RankScene.rowAt(30 + 5 * RankScene.ROW_H, 3))
    }

    @Test fun pixelFontGlyphs() {
        assertEquals(6, PixelFont.rows("A").size)
        assertTrue(PixelFont.width("RANK") == 4 * 5 - 1)
        assertTrue(PixelFont.rows("?").all { it == "0000" })
        assertTrue(PixelFont.rows("+").any { '1' in it })
        assertFalse(PixelFont.rows("*") == PixelFont.rows("+"))
    }

    @Test fun convergedCardMarksTheModelPeakAndIncompleteSaysNoEstimate() {
        val probes = listOf(5.5 to 8.0, 6.2 to 9.4, 4.8 to 6.1, 6.6 to 7.0, 6.0 to 9.1)
        val done = SessionRecord(1, 900_000, 6.1, 9.0, 0.8, 2, 66.0, 1.0, 800_000, 40.0, 50.0, 900, SessionRecord.KIND_RF_SEARCH, probes)
        assertTrue(count(scene.renderResult(done, -1, 3, null, 5_000, 0), RankScene.CARET) > 0)
        val two = SessionRecord(1, 300_000, 5.5, 8.0, 0.8, 1, 66.0, 1.0, 200_000, 40.0, 50.0, 300, SessionRecord.KIND_RF_INCOMPLETE, probes.take(2))
        val px = scene.renderResult(two, -1, 3, null, 5_000, 0)
        assertEquals(0, count(px, RankScene.CARET))
        assertTrue(count(px, RankScene.HIGHLIGHT) > 0)
    }

    @Test fun shortWindowsDoNotCrash() {
        val small = RankScene(160, 120)
        assertEquals(160 * 120, small.render(records, 0, 5_000).size)
        assertEquals(160 * 120, small.renderResult(records[0], 1, 3, records[0], 5_000, 1).size)
    }

    @Test fun titleColourChangesGently() {
        fun titlePixels(t: Long) = scene.render(records, -1, t).copyOf().filterIndexed { i, _ -> i / scene.w in 8..14 }.toSet()
        val a = titlePixels(1_000)
        val b = titlePixels(1_500)
        assertEquals(a, b)   // no 2 Hz flicker
    }

    @Test fun deterministicAndTwinkling() {
        val a = scene.render(records, 0, 5_000).copyOf()
        assertArrayEquals(a, scene.render(records, 0, 5_000))
        assertFalse(a.contentEquals(scene.render(records, 0, 5_400)))
    }

    @Test fun twinklesStayInTheSky() {
        // Each twinkle hides in one of three phases, so over these frames every star's underlying pixel shows once.
        val frames = listOf(0L, 300L, 600L).map { scene.render(emptyList(), -1, it).copyOf() }
        val hills = setOf(RankScene.RIDGE, RankScene.RIDGE_EDGE, RankScene.GROUND)
        val stars = setOf(RankScene.STAR_BRIGHT, RankScene.STAR_DIM)
        for (i in frames[0].indices) {
            val c = frames.map { it[i] }
            assertFalse("star over the hills at ${i % 160},${i / 160}", c.any { it in stars } && c.any { it in hills })
        }
    }
}
