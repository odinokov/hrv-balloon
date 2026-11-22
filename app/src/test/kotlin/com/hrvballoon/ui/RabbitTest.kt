package com.hrvballoon.ui

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dash pins. update() samples the pace at the end of each 50 ms tick, so the distances are tick-derived:
 * pace 3 for ticks 1..11 (1.1 px per pace unit per tick), pace 2 for ticks 12..23, pace 1 from tick 24.
 */
class RabbitTest {
    private val t0 = 120_000L

    /** A rabbit that has just appeared at the edge and runs inward; [dir] is its running direction. */
    private fun running(seed: Long = 7): Pair<Rabbit, Int> {
        val r = Rabbit(Random(seed), 400, shy = false)
        r.update(0, null)
        r.update(t0, null)
        return r to if (r.x < 0) 1 else -1
    }

    /** The basket [offset] px behind the rabbit's body centre, so it flees along its running direction. */
    private fun basket(r: Rabbit, dir: Int, offset: Int) = (r.x + 4).toInt() - dir * offset

    private fun Rabbit.tick(k: Int, starX: Int? = null) = update(t0 + 50L * k, starX)

    @Test fun aFleeingRabbitRunsThreeThenTwoThenOneTimesItsPaceInEitherDirection() {
        val dirs = mutableSetOf<Int>()
        for (seed in 1L..8L) {
            val (r, dir) = running(seed)
            dirs += dir
            val x0 = r.x
            r.fleeFrom(basket(r, dir, 5), t0)
            val d = DoubleArray(30)
            for (k in 1..29) { r.tick(k); d[k] = (r.x - x0) * dir }
            assertEquals(36.3, d[11], 1e-6)
            assertEquals(38.5, d[12], 1e-6)
            assertEquals(62.7, d[23], 1e-6)
            assertEquals(63.8, d[24], 1e-6)
            assertEquals(69.3, d[29], 1e-6)
        }
        assertEquals(setOf(1, -1), dirs)
    }

    @Test fun aRabbitAtTheGuardEdgeFleesAndOneBeyondItStaysPut() {
        for ((offset, fled) in listOf(9 to true, 10 to false)) {
            val (r, dir) = running()
            val x0 = r.x
            r.fleeFrom((r.x + 4).toInt() - dir * offset, t0)
            for (k in 1..11) r.tick(k)
            assertEquals("offset $offset", if (fled) 36.3 else 12.1, (r.x - x0) * dir, 1e-6)
        }
    }

    @Test fun aSecondBasketDuringTheDashDoesNotRestartIt() {
        val (r, dir) = running()
        val x0 = r.x
        r.fleeFrom(basket(r, dir, 5), t0)
        for (k in 1..6) r.tick(k)
        r.fleeFrom(basket(r, dir, 5), t0 + 300)
        for (k in 7..24) r.tick(k)
        assertEquals(63.8, (r.x - x0) * dir, 1e-6)
    }

    @Test fun aHiddenRabbitDoesNotFlee() {
        val r = Rabbit(Random(7), 400, shy = false)
        r.update(0, null)
        r.fleeFrom(4, 0)
        for (k in 1..11) r.update(50L * k, null)
        assertEquals(0.0, abs(r.x), 0.0)
    }
}
