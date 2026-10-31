package com.hrvballoon.ui

import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.rankSessions
import java.io.File
import java.time.Instant
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
        val period = 10_000.0   // 6.0 breaths/min
        fun trace(nowMs: Long, mean: Double, swing: Double): Pair<DoubleArray, DoubleArray> {
            val t = DoubleArray(34) { nowMs - 30_000.0 + it * 900 }
            return t to DoubleArray(t.size) { mean + swing / 2 * cos(2 * PI * (t[it] / period - 0.4)) }
        }
        val scene = PixelScene(W, H)
        val (t1, hr1) = trace(450_000, 66.0, 12.0)
        save(
            "session",
            scene.render(
                SceneState(
                    0.82, true, 0.7, true, true, false, 12_345, stars = 24, falling = listOf(11_900),
                    traceT = t1, traceHr = hr1, traceNowMs = 450_000, pacerPeriodMs = period, progress = 0.5,
                    hud = Hud.session(PixelScene.DOT_GREEN, "", 68, 6.0, "07:30"),
                ),
            ),
        )
        val (t2, hr2) = trace(828_000, 63.0, 9.0)
        save(
            "dusk",
            scene.render(
                SceneState(
                    0.38, false, 0.25, false, true, false, 98_765, stars = 31,
                    traceT = t2, traceHr = hr2, traceNowMs = 828_000, pacerPeriodMs = period, progress = 0.92,
                    hud = Hud.session(PixelScene.DOT_GREEN, "", 63, 6.0, "01:12"),
                ),
            ),
        )
        val day = 86_400_000L
        val at = Instant.parse("2026-10-31T07:30:00Z").toEpochMilli()
        val records = rankSessions(
            listOf(31 to 13.4, 24 to 12.1, 36 to 14.0, 18 to 10.2, 27 to 12.6, 22 to 11.3).mapIndexed { i, (stars, p) ->
                SessionRecord(at - i * day, 900_000, 6.0, p, 0.84 - i * 0.01, stars, 64.0 + i, 0.6 + i * 0.2, 880_000, 46.0 + i, 58.0 + i, 965 + i * 7)
            },
        )
        val ranks = RankScene(W, H)
        save("ranks", ranks.render(records, 1, 5_000))
        save("result", ranks.renderResult(records[1], 2, records.size, records[0], 5_000, 212))
    }

    private companion object {
        const val W = 160
        const val H = 284
    }
}
