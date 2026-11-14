package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HrKalmanTest {
    @Test fun forecastsAPacedSineFiveSecondsAhead() {
        val rate = 6.0
        val k = HrKalman()
        for (b in synthBeats(68.0, 12.0, rate, 60.0, seed = 1).beats(0)) if (b.tEndMs <= 40_000) {
            val tMid = b.tEndMs - b.rrMs / 2
            k.update(tMid, tMid * rate / 60_000, 60_000 / b.rrMs)
        }
        val f = k.forecast()!!
        for (tS in listOf(41.0, 43.0, 45.0)) assertEquals("t=$tS", 68 + 6 * sin(2 * PI * rate / 60 * tS), f.at(tS * 1000 * rate / 60_000), 1.5)
    }

    @Test fun staysPhaseLockedThroughAPacerRateChange() {
        val pacer = PacerTrack(6.0, 0.4)
        pacer.changeRate(5.0, 20_000.0)
        fun hr(t: Double) = 68 + 6 * sin(2 * PI * pacer.cycles(t))
        val k = HrKalman()
        var t = 500.0
        while (t < 40_000) { k.update(t, pacer.cycles(t), hr(t)); t += 850 }
        val f = k.forecast()!!
        for (ahead in listOf(1_000.0, 3_000.0, 5_000.0)) assertEquals("ahead=$ahead", hr(t + ahead), f.at(pacer.cycles(t + ahead)), 1.0)
    }

    @Test fun noForecastUntilOneFullBreathSinceReset() {
        val k = HrKalman()
        assertNull(k.forecast())
        var t = 0.0
        while (t < 9_000) { k.update(t, t * 6 / 60_000, 70.0); t += 850 }   // 0.9 of a breath at 6/min
        assertNull(k.forecast())
        while (t < 11_000) { k.update(t, t * 6 / 60_000, 70.0); t += 850 }
        assertNotNull(k.forecast())
        k.reset()
        assertNull(k.forecast())
    }
}
