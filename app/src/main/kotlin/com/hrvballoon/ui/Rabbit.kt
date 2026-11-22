package com.hrvballoon.ui

import java.util.Random
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Now and then a rabbit runs along the top of the meadow; it may stop, look around, and run on the same way or
 * back. While a star is falling it sits and watches; when it lands it usually jumps for joy, then runs on. Driven
 * by the ambient clock, so it is frozen under reduced motion; all choices come from its own seeded dice at state
 * changes, so frames stay deterministic.
 */
internal class Rabbit(private val dice: Random, private val w: Int, private val shy: Boolean) {
    private var state = HIDDEN
    var x = 0.0
        private set
    private var dir = 1
    private var facing = 1
    private var until = 0L
    private var lookedAt = 0L
    private var jumpAt = 0L
    private var dashUntil = 0L
    private var last = Long.MIN_VALUE
    private var ran = 0.0

    private fun ms(lo: Int, hi: Int) = (lo + dice.nextDouble() * (hi - lo)).toLong()
    private fun wait() = if (shy) ms(40_000, 120_000) else ms(15_000, 50_000)
    private fun decision() = ms(1500, 4000)
    private fun watch(starX: Int) { state = WATCH; facing = if (starX >= x) 1 else -1 }
    private fun runOn(now: Long) { state = RUN; until = now + decision() }

    /** The basket is coming down on it: it bolts out from under at three times its running pace, slows to twice, then runs on at its own (0.6 s each). */
    fun fleeFrom(bx: Int, now: Long) {
        if (state == HIDDEN || now < dashUntil || kotlin.math.abs(x + 4 - bx) > 9) return
        dir = if (x + 4 < bx) -1 else 1
        dashUntil = now + FLEE_FAST_MS + FLEE_EASE_MS
        runOn(now)
    }

    fun update(now: Long, starX: Int?) {
        if (last == Long.MIN_VALUE) { last = now; until = now + wait(); return }
        val dt = (now - last).coerceIn(0, 250)
        last = now
        when (state) {
            HIDDEN -> if (now >= until && starX == null) {
                dir = if (dice.nextBoolean()) 1 else -1
                x = if (dir > 0) -8.0 else w + 1.0
                runOn(now)
            }
            RUN -> if (starX != null && now >= dashUntil) watch(starX) else {
                val speed = SPEED * fleePace(dashUntil - now)
                x += dir * speed * dt / 1000
                ran += speed * dt / 1000
                if (x > w + 1 || x < -9) { state = HIDDEN; until = now + wait() }
                else if (now >= until) {
                    if (dice.nextBoolean()) { state = SIT; facing = dir; until = now + ms(800, 2200); lookedAt = now }
                    else until = now + decision()
                }
            }
            SIT -> if (starX != null) watch(starX) else if (now >= until) {
                if (dice.nextBoolean()) dir = -dir
                runOn(now)
            } else if (now - lookedAt > 500) { if (dice.nextInt(3) == 0) facing = -facing; lookedAt = now }
            WATCH -> if (starX != null) facing = if (starX >= x) 1 else -1
            else if (dice.nextDouble() < JUMP_CHANCE) { state = JUMP; jumpAt = now; until = now + JUMP_MS } else runOn(now)
            JUMP -> if (now >= until) runOn(now)
        }
    }

    fun draw(s: PixelScene, baseY: Int) {
        if (state == HIDDEN) return
        val hop = state == RUN && (ran / 6).toInt() % 2 == 1
        val sprite = if (state != RUN) SIT_FRAME else if (hop) RUN_B else RUN_A
        val f = if (state == RUN) dir else facing
        val lift = if (state == JUMP) (4 * sin(PI * ((last - jumpAt).toDouble() / JUMP_MS).coerceIn(0.0, 1.0))).roundToInt() else if (hop) 1 else 0
        val x0 = x.roundToInt()
        val top = baseY - sprite.size + 1 - lift
        for ((r, row) in sprite.withIndex()) for ((c, ch) in row.withIndex()) {
            val col = if (f > 0) c else row.length - 1 - c
            when (ch) {
                '#' -> s.put(x0 + col, top + r, PixelScene.RABBIT)
                '+' -> s.put(x0 + col, top + r, PixelScene.RABBIT_DARK)
                'o' -> s.put(x0 + col, top + r, PixelScene.DARK)
            }
        }
    }

    internal companion object {
        const val HIDDEN = 0
        const val RUN = 1
        const val SIT = 2
        const val WATCH = 3
        const val JUMP = 4
        const val SPEED = 22.0   // px/s
        const val JUMP_CHANCE = 0.75
        const val JUMP_MS = 400L
        private const val FLEE_FAST_MS = 600L
        private const val FLEE_EASE_MS = 600L
        /** A fleeing rabbit's pace multiple; msLeft is the time before the whole dash ends. */
        fun fleePace(msLeft: Long): Int = when { msLeft > FLEE_EASE_MS -> 3; msLeft > 0 -> 2; else -> 1 }
        // Facing right: '#' fur, '+' tail, 'o' eye. Ears back while running, up while sitting.
        val RUN_A = arrayOf("...##...", "....####", "+#####o#", ".######.", ".#....#.")
        val RUN_B = arrayOf("...##...", "....####", "+#####o#", "..#####.", "...##...")
        val SIT_FRAME = arrayOf("..#.#.", "..#.#.", ".###o#", "+#####", ".#..#.")
    }
}
