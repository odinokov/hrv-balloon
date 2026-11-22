package com.hrvballoon.ui

import com.hrvballoon.core.HrKalman
import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.TrendPeriod
import com.hrvballoon.core.TrendPoint
import com.hrvballoon.core.coherenceTrend
import com.hrvballoon.core.rankSessions
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import org.junit.Test

/** Writes the README frames as PPM when SCENE_DUMP_DIR is set (see scripts/ppm2png.py); a no-op otherwise. */
class ScreenshotDump {
    @Test fun dump() {
        val dir = System.getenv("SCENE_DUMP_DIR") ?: return
        fun save(name: String, px: IntArray) = File(dir, "$name.ppm").outputStream().use { out ->
            out.write("P6\n$W $H\n255\n".toByteArray())
            for (p in px) out.write(byteArrayOf((p shr 16).toByte(), (p shr 8).toByte(), p.toByte()))
        }
        val period = 60_000 / 5.5   // the default 5.5 breaths/min, inhale 50:50
        // Confirmed beats stop ~3 s before now (filter and notification lag), as on a phone; the real filter forecasts the rest.
        fun trace(nowMs: Long, mean: Double, swing: Double): Triple<DoubleArray, DoubleArray, (Double) -> Double> {
            val t = DoubleArray(34) { nowMs - 32_600.0 + it * 900 }
            val hr = DoubleArray(t.size) { mean + swing / 2 * cos(2 * PI * (t[it] / period - 0.4)) }
            val k = HrKalman()
            for (i in t.indices) k.update(t[i], t[i] / period, hr[i])
            val f = k.forecast()!!
            return Triple(t, hr) { x -> f.at(x / period) }
        }
        val scene = PixelScene(W, H)
        val (t1, hr1, f1) = trace(450_000, 66.0, 12.0)
        save(
            "session",
            scene.render(
                SceneState(
                    0.82, true, 0.7, true, true, false, 12_345, stars = 24, falling = listOf(11_900),
                    traceT = t1, traceHr = hr1, traceNowMs = 450_000, pacerPeriodMs = period, inhaleFraction = 0.5, progress = 0.5, forecast = f1,
                    hud = Hud.session(TestText.scene, Palette.DOT_GREEN, "", 68, 5.5, "07:30"),
                ),
            ),
        )
        val (t2, hr2, f2) = trace(828_000, 63.0, 9.0)
        save(
            "dusk",
            scene.render(
                SceneState(
                    0.38, false, 0.25, false, true, false, 98_765, stars = 31,
                    traceT = t2, traceHr = hr2, traceNowMs = 828_000, pacerPeriodMs = period, inhaleFraction = 0.5, progress = 0.92, forecast = f2,
                    hud = Hud.session(TestText.scene, Palette.DOT_GREEN, "", 63, 5.5, "01:12"),
                ),
            ),
        )
        val day = 86_400_000L
        val at = Instant.parse("2026-10-31T07:30:00Z").toEpochMilli()
        val records = rankSessions(
            listOf(31 to 13.4, 24 to 12.1, 36 to 14.0, 18 to 10.2, 27 to 12.6, 22 to 11.3).mapIndexed { i, (stars, p) ->
                SessionRecord(at - i * day, 900_000, 5.5, p, 0.84 - i * 0.01, stars, 64.0 + i, 0.6 + i * 0.2, 880_000, 46.0 + i, 58.0 + i, 965 + i * 7)
            },
        )
        val ranks = RankScene(W, H, TestText.scene)
        save("ranks", ranks.render(records, 1, 5_000))
        val shown = records[1].copy(coherence = 2.7)
        val weekAt = listOf(2.1, 2.8, 1.9, 3.4, 4.1, 2.5, 2.7).mapIndexed { i, c -> TrendPoint(shown.startMs - (6 - i) * day, 900_000, c) }
        save("result", ranks.renderResult(shown, 2, records.size, records[0], 5_000, 212, week = coherenceTrend(weekAt, shown.startMs, ZoneOffset.UTC, TrendPeriod.D7)))

        // 2.8: the coherence strip and the TREND page, for the owner's approval.
        val now = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
        fun point(ago: Int, c: Double) = TrendPoint(now - ago * day, 900_000, c)
        val practice = (0 until 364).filter { it % 4 != 3 && it * 7 % 11 != 0 }.map { point(it, (2.6 + 1.3 * kotlin.math.sin(it / 9.0) - it / 364.0 * 0.5).coerceIn(0.4, 5.8)) }
        fun trend(p: TrendPeriod, pts: List<TrendPoint> = practice) = coherenceTrend(pts, now, ZoneOffset.UTC, p)
        fun card(c: Double?, week: com.hrvballoon.core.Trend?) =
            ranks.renderResult(SessionRecord(now, 900_000, 5.5, 13.4, 0.84, 31, 64.0, 0.6, 880_000, 46.0, 58.0, 965, coherence = c), 1, 6, null, 5_000, 212, week = week)
        val weekly = listOf(2.1, 2.8, 1.9, 3.4, 4.1, 2.5, 2.7).mapIndexed { i, c -> point(6 - i, c) }
        save("u_card_week", card(2.7, trend(TrendPeriod.D7, weekly)))
        save("u_card_oneday", card(2.7, trend(TrendPeriod.D7, listOf(point(0, 2.7)))))
        save("u_card_nocoherence", card(null, null))
        save("u_best_tabs", ranks.render(records, 1, 5_000))
        for (p in TrendPeriod.values()) save("u_trend_${p.name.lowercase()}", ranks.renderTrend(trend(p), p, 5_000))
        save("u_trend_empty", ranks.renderTrend(trend(TrendPeriod.D30, emptyList()), TrendPeriod.D30, 5_000))
        save("u_trend_oneday", ranks.renderTrend(trend(TrendPeriod.D14, listOf(point(0, 3.2))), TrendPeriod.D14, 5_000))
    }

    private companion object {
        const val W = 160
        const val H = 284
    }
}
