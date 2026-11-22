package com.hrvballoon.ui

import com.hrvballoon.core.SessionRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** How drawn dates and numbers are formatted: a fixed zone for the date label, Locale.ROOT for every number. */
class DrawnTextFormatTest {
    private fun rec(startMs: Long) = SessionRecord(startMs, 300_000, 5.5, 10.0, 0.8, 3, 68.0, 1.0, 280_000, 44.0, 55.0, 330)
    private fun search(rate: Double) =
        SessionRecord(1L, 900_000, rate, 1.2, 0.8, 0, 64.0, 1.0, 800_000, 40.0, 50.0, 900, SessionRecord.KIND_RF_SEARCH, listOf(5.0 to 8.0, 5.5 to 9.0, 6.0 to 8.0), 5.0)

    private val zone = ZoneId.of("-07:00")
    private val at = Instant.parse("2026-10-25T17:34:56Z").toEpochMilli()

    @Test fun aRankRowShowsItsStartAsMonthDayHourMinuteInTheScenesZone() {
        val scene = RankScene(160, 284, TestText.scene, zone = zone)
        val px = scene.render(listOf(rec(at)), -1, 0)
        val rowTop = 30
        val y = rowTop + (RankScene.ROW_H - PixelFont.H) / 2
        val expected = HashSet<Int>()
        PixelFont.draw(30, y, "10-25 10:34", RankScene.DIM) { x, yy, _ -> expected += yy * scene.w + x }
        val drawn = (rowTop until rowTop + RankScene.ROW_H).flatMap { r -> (30 until scene.w - 12).map { r * scene.w + it } }
            .filter { px[it] == RankScene.DIM }.toSet()
        assertEquals(expected, drawn)
    }

    @Test fun theResultCardShowsItsStartInTheScenesZone() {
        val scene = RankScene(160, 284, TestText.scene, zone = zone)
        val px = scene.renderResult(rec(at), 2, 3, null, 5_000, 0)
        val label = "2026-10-25 10:34"
        val expected = HashSet<Int>()
        PixelFont.draw((scene.w - PixelFont.width(label)) / 2, 70, label, RankScene.DIM) { x, y, _ -> expected += y * scene.w + x }
        val drawn = (70 until 70 + PixelFont.H).flatMap { r -> (0 until scene.w).map { r * scene.w + it } }.filter { px[it] == RankScene.DIM }.toSet()
        assertEquals(expected, drawn)
    }

    private fun underGermany(block: () -> IntArray): Pair<IntArray, IntArray> {
        val root = block().copyOf()
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            return root to block().copyOf()
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun theStatusLineFramesDoNotDependOnTheDefaultLocale() {
        for (status in listOf("", "No signal")) {
            val (root, de) = underGermany {
                PixelScene(160, 284).render(SceneState(0.3, false, 0.5, true, true, false, 12_345, hud = Hud.session(TestText.scene, Palette.DOT_GREEN, status, 62, 5.5)))
            }
            assertArrayEquals("status '$status'", root, de)
        }
    }

    @Test fun theResultCardFramesDoNotDependOnTheDefaultLocale() {
        val (root, de) = underGermany { RankScene(160, 284, TestText.scene, zone = zone).renderResult(search(5.5), 2, 3, null, 5_000, 1_234) }
        assertArrayEquals(root, de)
    }
}
