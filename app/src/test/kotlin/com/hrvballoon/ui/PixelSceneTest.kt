package com.hrvballoon.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelSceneTest {
    private val scene = PixelScene(160, 284)

    private fun state(
        height: Double = 0.3, rising: Boolean = false, level: Double = 0.5, inhale: Boolean = true,
        running: Boolean = true, frozen: Boolean = false, timeMs: Long = 12_345,
    ) = SceneState(height, rising, level, inhale, running, frozen, timeMs)

    private fun count(px: IntArray, color: Int) = px.count { it == color }

    @Test fun everyPixelComesFromThePalette() {
        val palette = PixelScene.PALETTE.toSet()
        val states = listOf(state(), state(height = 1.0, rising = true), state(running = false, timeMs = 999_999)) +
            listOf(0.3, 0.62, 0.8, 1.0).map { state(timeMs = 77_000).copy(progress = it) }
        for (s in states) assertTrue(scene.render(s).all { it in palette })
    }

    @Test fun frozenFrameIsGreyscaleExceptThePacer() {
        val pacer = setOf(PixelScene.PACER_FRAME, PixelScene.PACER_BG, PixelScene.PACER_IN, PixelScene.PACER_OUT)
        val px = scene.render(state(frozen = true))
        assertTrue(px.all { it in pacer || (it shr 16 and 0xFF).let { r -> r == (it shr 8 and 0xFF) && r == (it and 0xFF) } })
        assertTrue(px.count { it in pacer } > 100)
    }

    @Test fun balloonHeightMapsToRows() {
        assertEquals(scene.groundY - 1, scene.basketBottomY(0.0))
        assertTrue(scene.basketBottomY(1.0) < scene.h / 4)
        var prev = scene.basketBottomY(0.0)
        for (i in 1..10) {
            val y = scene.basketBottomY(i / 10.0)
            assertTrue(y < prev)
            prev = y
        }
    }

    @Test fun ceilingContactSquashesThenStretchesOnlyTheEnvelope() {
        val envelope = setOf(PixelScene.CORAL, PixelScene.CORAL_LIGHT, PixelScene.CORAL_DARK,
            PixelScene.CREAM, PixelScene.CREAM_LIGHT, PixelScene.CREAM_DARK)
        fun frame(after: Long, hit: Boolean) = scene.render(state(height = 0.9, running = false, timeMs = 10_000 + after)
            .copy(ceilingHitAtMs = if (hit) 10_000 else null)).copyOf()
        fun bounds(px: IntArray): Pair<Int, Int> {
            val ink = px.indices.filter { px[it] in envelope }
            return (ink.maxOf { it % scene.w } - ink.minOf { it % scene.w } + 1) to
                (ink.maxOf { it / scene.w } - ink.minOf { it / scene.w } + 1)
        }
        val normal = frame(0, false)
        val squashed = frame(0, true)
        assertTrue(bounds(squashed).first > bounds(normal).first)
        assertTrue(bounds(squashed).second < bounds(normal).second)
        val stretched = frame(250, true)
        assertTrue(bounds(stretched).first < bounds(normal).first)
        assertTrue(bounds(stretched).second > bounds(normal).second)
        val neck = normal.indices.filter { normal[it] in envelope }.maxOf { it / scene.w } + 1
        for (y in neck until scene.groundY) for (x in 48..86) assertEquals(normal[y * scene.w + x], squashed[y * scene.w + x])
        val palette = PixelScene.PALETTE.toSet()
        assertTrue(squashed.all { it in palette })
    }

    @Test fun ceilingEnvelopeReturnsExactlyToRestAndHonoursReducedMotion() {
        fun frame(t: Long, hit: Long?, reduced: Boolean = false) = scene.render(state(height = 0.9, running = false, timeMs = t)
            .copy(ceilingHitAtMs = hit, reducedMotion = reduced)).copyOf()
        assertArrayEquals(frame(9_999, null), frame(9_999, 10_000))
        assertArrayEquals(frame(11_000, null), frame(11_000, 10_000))
        assertArrayEquals(frame(10_000, null, true), frame(10_000, 10_000, true))
    }

    @Test fun everyTwelveSessionStarsAddsOneRabbitAndANewSessionResetsTheExtras() {
        for ((stars, expected) in listOf(0 to 2, 11 to 2, 12 to 3, 23 to 3, 24 to 4, 36 to 5, 0 to 2)) {
            scene.render(state(running = false).copy(stars = stars))
            assertEquals("rabbits after $stars stars", expected, scene.rabbitCount)
        }
    }

    @Test fun earnedRabbitsKeepTheirSeededMotionAndWatchFallingStars() {
        val earned = PixelScene(160, 284)
        val repeated = PixelScene(160, 284)
        val original = PixelScene(160, 284)
        fun rabbitX(px: IntArray) = px.indices.filter { px[it] == PixelScene.RABBIT }.minOfOrNull { it % scene.w }
        var isolatedAt = -1L
        for (t in 0L..300_000 step 250) {
            val s = state(running = false, timeMs = t)
            val a = earned.render(s.copy(stars = 12)).copyOf()
            assertArrayEquals(a, repeated.render(s.copy(stars = 12)))
            if (rabbitX(original.render(s)) == null && rabbitX(a) != null) { isolatedAt = t; break }
        }
        assertTrue("the earned rabbit must eventually emerge", isolatedAt >= 0)
        val star = listOf(isolatedAt)
        fun watching(t: Long) = rabbitX(earned.render(state(running = false, timeMs = t).copy(stars = 12, falling = star)))
        val x = watching(isolatedAt)
        assertEquals(x, watching(isolatedAt + 300))
        assertEquals(x, watching(isolatedAt + 900))
        assertTrue((2_100L..3_600L step 300).any { watching(isolatedAt + it) != x })
        val still = PixelScene(160, 284)
        val a = still.render(state(running = false, timeMs = 1_000).copy(stars = 24, reducedMotion = true)).copyOf()
        assertArrayEquals(a, still.render(state(running = false, timeMs = 4_000).copy(stars = 24, reducedMotion = true)))
    }

    @Test fun burnerFlameOnlyWhileRising() {
        val lit = scene.render(state(rising = true)).copyOf()
        val idle = scene.render(state(rising = false))
        assertTrue(count(lit, PixelScene.FLAME_A) + count(lit, PixelScene.FLAME_B) > 0)
        assertEquals(0, count(idle, PixelScene.FLAME_A) + count(idle, PixelScene.FLAME_B))
    }

    @Test fun pacerBarFillsWithLevel() {
        val full = count(scene.render(state(level = 1.0)), PixelScene.PACER_IN)
        val low = count(scene.render(state(level = 0.1)), PixelScene.PACER_IN)
        assertTrue(full > low && low > 0)
        assertEquals(0, count(scene.render(state(running = false)), PixelScene.PACER_IN))
        assertTrue(count(scene.render(state(inhale = false)), PixelScene.PACER_OUT) > 0)
    }

    @Test fun collectedStarsRowGrowsWithCount() {
        val none = count(scene.render(state().copy(stars = 0)), PixelScene.STAR)
        val one = count(scene.render(state().copy(stars = 1)), PixelScene.STAR)
        val five = count(scene.render(state().copy(stars = 5)), PixelScene.STAR)
        assertEquals(0, none)
        assertTrue(one > 0 && five > one)
    }

    @Test fun largeStarPileKeepsItsOverflowCountInsideTheScene() {
        val px = scene.render(state().copy(stars = 125))
        // Make room for the complete +106 instead of clipping the final digit off-screen.
        PixelFont.draw(136, scene.h - 7, "+106", PixelScene.STAR) { x, y, color ->
            assertEquals("Overflow count at $x,$y", color, px[y * scene.w + x])
        }
    }

    @Test fun statusShowsHeartRatePaceAndCountdownWithoutClippingAnyValue() {
        val hud = Hud.session(PixelScene.DOT_GREEN, "", 168, 5.5, "12:34")
        val px = scene.render(state().copy(hud = hud))
        var expectedInk = 0
        PixelFont.draw(12, 4, "HR 168 PACE 5.5/MIN", PixelScene.HUD_TEXT) { x, y, color ->
            expectedInk++
            assertEquals("Complete status at $x,$y", color, px[y * scene.w + x])
        }
        PixelFont.draw(scene.w - PixelFont.width("12:34") - 4, 4, "12:34", PixelScene.HUD_TEXT) { x, y, color ->
            expectedInk++
            assertEquals("Countdown at $x,$y", color, px[y * scene.w + x])
        }
        assertEquals(expectedInk, count(px, PixelScene.HUD_TEXT))
        // The longest labelled line the app produces (No signal always shows HR as --) still fits whole beside the countdown.
        val long = scene.render(state().copy(hud = Hud.session(PixelScene.DOT_GREEN, "Settling", 168, 5.5, "00:59")))
        var longInk = 0
        PixelFont.draw(12, 4, "SETTLING HR 168 5.5/MIN", PixelScene.HUD_TEXT) { x, y, color -> longInk++; assertEquals(color, long[y * scene.w + x]) }
        PixelFont.draw(scene.w - PixelFont.width("00:59") - 4, 4, "00:59", PixelScene.HUD_TEXT) { _, _, _ -> longInk++ }
        assertEquals(longInk, count(long, PixelScene.HUD_TEXT))
        assertEquals(0, count(px, PixelScene.HUD_DIM))
        assertEquals(16, scene.topMargin)
    }

    @Test fun missingOrInvalidHeartRateIsShownAsUnavailable() {
        for (hr in listOf(null, 0, -1, 1000)) {
            val hud = Hud.session(PixelScene.DOT_RED, "No signal", hr, 5.4)
            assertEquals("No signal HR -- 5.4/min", hud.status)
            assertTrue(PixelFont.width(hud.status) <= scene.w - 16)
        }
    }

    @Test fun longStatusStaysInsideTheSingleLineMargins() {
        val px = scene.render(state().copy(hud = Hud(PixelScene.DOT_GREEN,
            "BREATHING / AUTO-TUNE SLOWER PLEASE KEEP BREATHING GENTLY")))
        val textPixels = px.indices.filter { px[it] == PixelScene.HUD_TEXT }
        assertTrue(textPixels.isNotEmpty())
        assertTrue(textPixels.all { it % scene.w in 12 until scene.w - 4 && it / scene.w in 4..9 })
        assertEquals(16, scene.topMargin)
    }

    @Test fun fallingStarDescendsThenLands() {
        fun starRows(t: Long) = scene.render(state(timeMs = 42_000 + t).copy(falling = listOf(42_000L)))
            .withIndex().filter { it.value == PixelScene.STAR }.map { it.index / scene.w }
        val early = starRows(100)
        val late = starRows(1_200)
        assertTrue(early.isNotEmpty() && late.isNotEmpty())
        assertTrue(early.min() < late.min())
        assertTrue(starRows(3_000).isEmpty())
    }

    @Test fun starJoinsThePileOnlyAfterItLands() {
        fun pileCentre(t: Long) = scene.render(state(timeMs = 42_000 + t).copy(stars = 1, falling = listOf(42_000L)))[(scene.h - 4) * scene.w + 3]
        assertTrue(pileCentre(100) != PixelScene.STAR_DARK)
        assertEquals(PixelScene.STAR_DARK, pileCentre(1_800))
    }

    @Test fun hrTraceDrawnFromBeats() {
        val n = 36
        val t = DoubleArray(n) { 90_000.0 + it * 850 }
        val hr = DoubleArray(n) { 68 + 6 * Math.sin(it * 0.6) }
        val withTrace = scene.render(state().copy(traceT = t, traceHr = hr, traceNowMs = 120_000, pacerPeriodMs = 10_909.0))
        assertTrue(count(withTrace, PixelScene.TRACE) > n)
        assertTrue(count(withTrace, PixelScene.PANEL_INHALE) > 0)
        assertEquals(0, count(scene.render(state()), PixelScene.TRACE))
        assertEquals(0, count(scene.render(state(running = false).copy(traceT = t, traceHr = hr, traceNowMs = 120_000)), PixelScene.TRACE))
    }

    @Test fun sunSetsBehindTheMountainsOverTheSession() {
        fun sun(p: Double) = scene.render(state().copy(progress = p)).let { count(it, PixelScene.SUN) + count(it, PixelScene.SUN_LOW) }
        val full = sun(0.0)
        assertTrue(full > 50)
        assertTrue(Math.abs(sun(0.1) - full) <= 4)   // still clear of the crests (a drifting cloud may clip a pixel or two)
        assertTrue(sun(0.7) in 1 until full)        // partly behind the crests, reddened
        assertEquals(0, sun(0.9))
        assertEquals(0, scene.render(state().copy(progress = 0.9)).let { count(it, PixelScene.SUN_RIM) + count(it, PixelScene.SUN_LOW_RIM) })
    }

    @Test fun sunStartsBelowTheHudBandWithASoftGlowAndNoRays() {
        val px = scene.render(state(level = 1.0).copy(hud = Hud(PixelScene.DOT_GREEN, "BREATHING / AUTO-TUNE ON")))
        val sun = setOf(PixelScene.SUN, PixelScene.SUN_RIM, PixelScene.SUN_GLOW)
        for (y in 0 until PixelScene.HUD_MARGIN) for (x in 0 until scene.w) assertFalse(px[y * scene.w + x] in sun)
        val disc = px.indices.filter { px[it] == PixelScene.SUN }
        assertTrue(disc.size > 50)
        val cx = disc.map { it % scene.w }.average()
        val cy = disc.map { it / scene.w }.average()
        assertTrue(count(px, PixelScene.SUN_GLOW) > 20)
        for (i in px.indices) if (px[i] == PixelScene.SUN_GLOW) assertTrue(Math.hypot(i % scene.w - cx, i / scene.w - cy) <= 13.0)
    }

    @Test fun skyDarkensToNightOverTheSession() {
        fun band(p: Double) = scene.render(state(timeMs = 77_000).copy(progress = p)).let { px ->
            (PixelScene.HUD_MARGIN + 1..PixelScene.HUD_MARGIN + 10).flatMap { y -> (0 until scene.w).map { x -> px[y * scene.w + x] } }
        }
        val day = band(0.0)
        assertTrue(day.count { it in PixelScene.SKY_PHASES[0] } > day.size * 0.7)
        val dusk = band(0.5)
        assertTrue(dusk.count { it in PixelScene.SKY_PHASES[2] } > dusk.size * 0.7)
        val night = band(1.0)
        assertTrue(night.count { it in PixelScene.SKY_PHASES[4] } > night.size * 0.7)
    }

    @Test fun theMeadowAndRidgesDimToDuskWhileTheBalloonStaysLit() {
        val day = scene.render(state(running = false).copy(progress = 0.0)).copyOf()
        val night = scene.render(state(running = false).copy(progress = 1.0))
        assertTrue(count(day, PixelScene.GROUND) > 300)
        assertEquals(0, count(night, PixelScene.GROUND))
        assertTrue(count(night, PixelScene.dusk(PixelScene.GROUND, PixelScene.PHASES - 1)) > 300)
        assertEquals(count(day, PixelScene.CORAL), count(night, PixelScene.CORAL))
    }

    @Test fun cloudsHigherUpDriftSlower() {
        assertEquals(4, scene.clouds.map { it.layer }.toSet().size)
        for (a in scene.clouds) for (b in scene.clouds) if (a.y0 < b.y0) assertTrue(a.speed <= b.speed)
        assertTrue(scene.clouds.maxOf { it.speed } >= 6 * scene.clouds.minOf { it.speed })
    }

    @Test fun rollingHillsInThreeLayersWithSmoothCrests() {
        val px = scene.render(state(running = false))
        for (c in intArrayOf(PixelScene.HILL_FAR, PixelScene.HILL_FAR_CREST, PixelScene.HILL_MID, PixelScene.HILL_MID_CREST, PixelScene.HILL, PixelScene.HILL_LIGHT))
            assertTrue("missing ${Integer.toHexString(c)}", count(px, c) > 0)
        // The mid crest line, where visible, never steps more than 1 px between neighbouring columns: hills, not peaks.
        val crest = IntArray(scene.w) { x -> (0 until scene.groundY).firstOrNull { px[it * scene.w + x] == PixelScene.HILL_MID_CREST } ?: -1 }
        for (x in 1 until scene.w) if (crest[x] >= 0 && crest[x - 1] >= 0) assertTrue("step at $x", Math.abs(crest[x] - crest[x - 1]) <= 1)
    }

    @Test fun skyIsReddishAroundTheSunAndBlueFarFromIt() {
        val px = scene.render(state(running = false))
        val disc = px.indices.filter { px[it] == PixelScene.SUN }
        val cx = disc.map { it % scene.w }.average().toInt()
        val cy = disc.map { it / scene.w }.average().toInt()
        val near = px[(cy - 14) * scene.w + cx]
        val far = px[(PixelScene.HUD_MARGIN + 3) * scene.w + 6]
        val warmEnd = PixelScene.SKY_PHASES[0].takeLast(3)
        val coolEnd = PixelScene.SKY_PHASES[0].take(3)
        assertTrue(near in warmEnd)
        assertTrue(far in coolEnd)
    }

    @Test fun aFewLoneStarsComeOutAtDuskAndTwinkleUnlessMotionIsReduced() {
        assertEquals(0, count(scene.render(state().copy(progress = 0.3)), PixelScene.STAR_SKY))
        fun lit(t: Long) = scene.render(state(timeMs = t).copy(progress = 1.0)).withIndex().filter { it.value == PixelScene.STAR_SKY }.map { it.index }.toSet()
        val a = lit(1_000)
        assertTrue("lit=${a.size}", a.size in 4..20)
        assertTrue(a != lit(1_700))
        // Single pixels, well apart: no two star pixels within 6 px of each other, no clumps.
        val all = scene.render(state(timeMs = 1_000).copy(progress = 1.0)).withIndex().filter { it.value == PixelScene.STAR_SKY || it.value == PixelScene.STAR_SKY_DIM }.map { it.index % scene.w to it.index / scene.w }
        assertTrue("stars=${all.size}", all.size in 8..24)
        for (p in all) for (q in all) if (p != q) assertTrue(Math.abs(p.first - q.first) > 6 || Math.abs(p.second - q.second) > 6)
        val still = scene.render(state(running = false, timeMs = 1_000).copy(progress = 1.0, reducedMotion = true)).copyOf()
        assertArrayEquals(still, scene.render(state(running = false, timeMs = 4_000).copy(progress = 1.0, reducedMotion = true)))
    }

    @Test fun noRingsSurroundTheBalloon() {
        val inhale = scene.render(state(inhale = true, level = 0.9)).copyOf()
        val exhale = scene.render(state(inhale = false, level = 0.9))
        // The breath phase may only recolour the pacer bar (right edge) and the trace panel (below the ground): nothing mid-sky.
        for (i in inhale.indices) if (inhale[i] != exhale[i]) assertTrue(i % scene.w >= scene.w - 16 || i / scene.w >= scene.groundY)
    }

    @Test fun reducedMotionFreezesTheIdleScene() {
        val a = scene.render(state(running = false, timeMs = 1_000).copy(reducedMotion = true)).copyOf()
        val b = scene.render(state(running = false, timeMs = 4_000).copy(reducedMotion = true))
        assertArrayEquals(a, b)
    }

    @Test fun hudBandHasAFixedHeightAndThePacerSitsBelowIt() {
        val idle = Hud(PixelScene.DOT_GREEN, "READY TO BREATHE")
        val training = Hud(PixelScene.DOT_GREEN, "BREATHING / AUTO-TUNE SLOWER")
        scene.render(state().copy(hud = idle))
        val m1 = scene.topMargin
        val px = scene.render(state().copy(hud = training))
        assertEquals(m1, scene.topMargin)
        for (i in 0 until scene.topMargin * scene.w) assertFalse(px[i] == PixelScene.PACER_FRAME || px[i] == PixelScene.PACER_IN)
        assertTrue(count(px, PixelScene.PACER_FRAME) > 50)
    }

    @Test fun skyMeltsSlowly() {
        val a = scene.render(state(running = false, timeMs = 1_000)).copyOf()
        val b = scene.render(state(running = false, timeMs = 4_000))
        val changed = a.indices.count { a[it] != b[it] }
        assertTrue("changed=$changed", changed in 200..20_000)
    }

    @Test fun frozenKeepsThePacerInColour() {
        val px = scene.render(state(frozen = true, level = 0.8))
        assertTrue(count(px, PixelScene.PACER_IN) > 40)
    }

    @Test fun starPileNeverReachesTheTracePanel() {
        val px = scene.render(state().copy(stars = 40))
        val panelBottom = scene.h - 9
        for (y in panelBottom - 6..panelBottom) for (x in 0 until scene.w) assertFalse(px[y * scene.w + x] == PixelScene.STAR)
        assertTrue(count(px, PixelScene.STAR) > 0)
    }

    @Test fun topMarginInsetsTheSky() {
        val inset = PixelScene(160, 284).apply { topMargin = 30 }
        assertTrue(inset.basketBottomY(1.0) > scene.basketBottomY(1.0))
    }

    @Test fun traceBandsFollowAPacerPhaseFunction() {
        val n = 36
        val t = DoubleArray(n) { 90_000.0 + it * 850 }
        val hr = DoubleArray(n) { 68.0 }
        val a = scene.render(state().copy(traceT = t, traceHr = hr, traceNowMs = 120_000, pacerPeriodMs = 10_909.0)).copyOf()
        val b = scene.render(state().copy(traceT = t, traceHr = hr, traceNowMs = 120_000, pacerPeriodMs = 10_909.0, pacerPhaseAt = { 0.9 }))
        assertFalse(a.contentEquals(b))
        assertEquals(0, count(b, PixelScene.PANEL_INHALE))
    }

    @Test fun theBandAtTheRightEdgeIsTheCurrentBreathAndSlidesInWithoutJumping() {
        val period = 10_000.0   // inhale 0..4 s, exhale 4..10 s of each cycle; 30 s across 150 columns of 200 ms
        fun px(nowMs: Long, beats: DoubleArray = DoubleArray(0)) = scene.render(state().copy(
            traceT = beats, traceHr = DoubleArray(beats.size) { 68.0 }, traceNowMs = nowMs, pacerPeriodMs = period, pacerPhaseAt = { t -> (t / period) % 1.0 },
        ))
        val edge = scene.w - 6
        val row = (scene.groundY + 10) * scene.w
        val early = px(101_000)   // 1 s into an inhale: the band at the edge is already ~9 columns, not the 5 of real time
        assertEquals(PixelScene.PANEL_INHALE, early[row + edge])
        assertEquals(PixelScene.PANEL_INHALE, early[row + edge - 7])
        assertEquals(PixelScene.PANEL, early[row + edge - 10])
        val mid = px(102_000)     // halfway: three quarters of the inhale band (15 columns) at the edge
        assertEquals(PixelScene.PANEL_INHALE, mid[row + edge - 14])
        assertEquals(PixelScene.PANEL, mid[row + edge - 16])
        assertEquals(PixelScene.PANEL, px(106_000)[row + edge])   // exhaling: dark band at the edge
        // Continuity across the flip at 104 s: the curve's left end stays in the same column.
        val beats = DoubleArray(10) { 90_000.0 + it * 850 }
        fun beatX(nowMs: Long) = px(nowMs, beats).withIndex().filter { it.value == PixelScene.TRACE }.map { it.index % scene.w }.min()
        assertTrue(Math.abs(beatX(103_990) - beatX(104_010)) <= 1)
    }

    @Test fun hudTextLivesInTheTopBandAboveTheCeiling() {
        val hud = Hud(dot = PixelScene.DOT_GREEN, status = "BREATHING / AUTO-TUNE STEADY")
        val px = scene.render(state().copy(hud = hud))
        assertTrue(count(px, PixelScene.HUD_TEXT) > 100)
        assertTrue(count(px, PixelScene.DOT_GREEN) > 0)
        assertEquals(16, scene.topMargin)
        val band = (0 until scene.topMargin * scene.w).count { px[it] == PixelScene.HUD_TEXT }
        assertEquals(count(px, PixelScene.HUD_TEXT), band)
        assertEquals(0, count(scene.render(state()), PixelScene.HUD_TEXT))
    }

    @Test fun lossCauseIsAPixelPlate() {
        val px = scene.render(state(frozen = true).copy(hud = Hud(PixelScene.DOT_RED, "CHECK STRAP / AUTO-TUNE PAUSED", lost = listOf("NO SKIN CONTACT", "WET THE ELECTRODES"))))
        assertTrue(count(px, PixelScene.LOST_TEXT) > 100)
        assertTrue(count(px, PixelScene.PLATE) > 500)
    }

    @Test fun rabbitsRunAlongTheGroundNowAndThen() {
        var frames = 0
        var seen = 0
        for (t in 0..200_000 step 250) {
            val px = scene.render(state(running = false, timeMs = t.toLong()))
            val rabbit = px.indices.filter { px[it] == PixelScene.RABBIT }
            frames++
            if (rabbit.isNotEmpty()) seen++
            for (i in rabbit) assertTrue(i / scene.w in scene.groundY - 1..scene.groundY + 4)
        }
        assertTrue("seen=$seen of $frames", seen > 0 && seen < frames / 2)
    }

    @Test fun rabbitsStopToWatchAFallingStarThenRunOn() {
        fun x(px: IntArray) = px.indices.filter { px[it] == PixelScene.RABBIT }.minOfOrNull { it % scene.w }
        var prev: Int? = null
        var t = 0L
        var at = -1L
        while (t < 300_000 && at < 0) {
            val cur = x(scene.render(state(running = false, timeMs = t)))
            if (cur != null && prev != null && cur != prev) at = t
            prev = cur
            t += 100
        }
        assertTrue(at > 0)
        val star = listOf(at)   // falls for 1.8 s
        val watching = x(scene.render(state(timeMs = at).copy(falling = star)))
        assertEquals(watching, x(scene.render(state(timeMs = at + 300).copy(falling = star))))
        assertEquals(watching, x(scene.render(state(timeMs = at + 900).copy(falling = star))))
        var moved = false   // landed at +1.8 s: it may first jump for joy (0.4 s), then runs on
        for (dt in 2_100L..3_600L step 300) if (x(scene.render(state(timeMs = at + dt).copy(falling = star))) != watching) moved = true
        assertTrue(moved)
    }

    @Test fun rabbitsJumpForJoyWhenAStarLandsMostOfTheTime() {
        fun pixels(px: IntArray) = px.indices.filter { px[it] == PixelScene.RABBIT }
        var t = 0L
        var prev: Int? = null
        var events = 0
        var jumps = 0
        while (t < 900_000 && events < 6) {
            val cur = pixels(scene.render(state(running = false, timeMs = t))).minOfOrNull { it % scene.w }
            if (cur != null && prev != null && cur != prev) {
                events++
                val star = listOf(t)
                for (dt in longArrayOf(300, 900, 1500, 1850, 2050)) {
                    val px = scene.render(state(timeMs = t + dt).copy(falling = star))
                    // 200 ms into a jump the rabbit is 4 px up; a hop only lifts it 1 px.
                    if (dt == 2050L && (pixels(px).minOfOrNull { it / scene.w } ?: 999) <= scene.groundY - 2) jumps++
                }
                t += 6_000
                prev = null
            } else { prev = cur; t += 200 }
        }
        assertTrue("events=$events jumps=$jumps", events >= 4 && jumps >= 1)
    }

    @Test fun traceIsOneSmoothCurveWithOnePointPerColumn() {
        val period = 10_909.0
        val rnd = java.util.Random(3)
        val n = 36
        val t = DoubleArray(n) { 90_000.0 + it * 850 }
        val hr = DoubleArray(n) { 68 + 5 * Math.sin(2 * Math.PI * t[it] / period) + rnd.nextGaussian() * 2.5 }   // breathing wave + beat jitter
        fun frame(tt: DoubleArray, hh: DoubleArray = hr) = scene.render(state().copy(
            traceT = tt, traceHr = hh, traceNowMs = 120_000, pacerPeriodMs = period, pacerPhaseAt = { x -> (x / period) % 1.0 },
        )).copyOf()
        val px = frame(t)
        val rows = (0 until scene.w).map { x -> (0 until scene.h).filter { px[it * scene.w + x] == PixelScene.TRACE } }
        val drawn = rows.indices.filter { rows[it].isNotEmpty() }
        assertTrue("columns=${drawn.size}", drawn.size > 100)
        for (x in drawn.first()..drawn.last()) assertTrue("gap at $x", rows[x].isNotEmpty())   // one continuous curve
        // Smooth: the column-to-column travel of the curve is close to the breathing wave's own, not the jitter's.
        val travel = (drawn.first() + 1..drawn.last()).sumOf { Math.abs(rows[it].average() - rows[it - 1].average()) }
        assertTrue("travel=$travel", travel < 200)
        // A beat stamped earlier than its predecessor (re-anchored clock) is simply sorted into place.
        val back = t.copyOf().also { it[10] = it[9] - 300 }
        val order = back.indices.sortedBy { back[it] }
        assertArrayEquals(frame(DoubleArray(n) { back[order[it]] }, DoubleArray(n) { hr[order[it]] }), frame(back))
    }

    @Test fun traceIsSteadyFrameToFrame() {
        val period = 10_909.0
        val rnd = java.util.Random(5)
        val n = 40
        val t = DoubleArray(n) { 88_000.0 + it * 850 }
        val hr = DoubleArray(n) { 68 + 5 * Math.sin(2 * Math.PI * t[it] / period) + rnd.nextGaussian() * 2.5 }
        fun rows(nowMs: Long, beats: Int): List<Double?> {
            val px = scene.render(state().copy(
                traceT = t.copyOf(beats), traceHr = hr.copyOf(beats), traceNowMs = nowMs, pacerPeriodMs = period, pacerPhaseAt = { x -> (x / period) % 1.0 },
            ))
            return (0 until scene.w).map { x -> (0 until scene.h).filter { px[it * scene.w + x] == PixelScene.TRACE }.takeIf { it.isNotEmpty() }?.average() }
        }
        // A newly arrived beat extends the curve on the right and changes nothing already drawn.
        val a = rows(122_000, n - 1)
        val b = rows(122_000, n)
        for (x in 0 until scene.w) if (a[x] != null) assertEquals("column $x", a[x], b[x])
        assertTrue(b.count { it != null } > a.count { it != null })
        // Scrolling moves the curve, but no column jumps more than a pixel between frames 40 ms apart.
        val c = rows(122_040, n)
        for (x in 0 until scene.w) if (b[x] != null && c[x] != null) assertTrue("column $x", Math.abs(b[x]!! - c[x]!!) <= 1.0)
        // The vertical centre never jumps: the mean drawn row drifts by less than a pixel per quarter second.
        var prev = c.filterNotNull().average()
        for (k in 1..8) {
            val m = rows(122_040 + k * 250L, n).filterNotNull().average()
            assertTrue("mean step ${Math.abs(m - prev)}", Math.abs(m - prev) < 1.0)
            prev = m
        }
    }

    @Test fun aBlankStatusLeavesJustHrAndPace() {
        assertEquals("HR 168 Pace 5.5/min", Hud.session(PixelScene.DOT_GREEN, "", 168, 5.5).status)
        assertEquals("Settling HR -- 5.5/min", Hud.session(PixelScene.DOT_GREEN, "Settling", null, 5.5).status)   // a label displaces the word Pace
        assertEquals("12:34", Hud.session(PixelScene.DOT_GREEN, "", 68, 5.5, "12:34").timer)
    }

    @Test fun skyDissolvesInSmallSteps() {
        val a = scene.render(state(timeMs = 77_000).copy(progress = 0.50)).copyOf()
        val b = scene.render(state(timeMs = 77_000).copy(progress = 0.53))
        var maxDelta = 0
        for (i in a.indices) if (a[i] != b[i] && i / scene.w in PixelScene.HUD_MARGIN until 90)
            for (sh in intArrayOf(16, 8, 0)) maxDelta = maxOf(maxDelta, Math.abs((a[i] shr sh and 0xFF) - (b[i] shr sh and 0xFF)))
        assertTrue("maxDelta=$maxDelta", maxDelta in 1..28)
    }

    @Test fun aRabbitUnderADescendingBalloonEscapes() {
        fun rabbitXs(px: IntArray) = px.indices.filter { px[it] == PixelScene.RABBIT }.map { it % scene.w }
        fun bx(t: Long) = Math.round(scene.w * 0.42 + Math.sin(t / 1700.0) * 2).toInt()   // the idle sway, as the scene computes it
        var t = 0L
        var hit = -1L
        while (t < 900_000 && hit < 0) {
            if (rabbitXs(scene.render(state(running = false, height = 0.3, timeMs = t))).any { Math.abs(it + 1 - bx(t)) <= 2 }) hit = t
            t += 100
        }
        assertTrue(hit > 0)
        // The basket comes down: the rabbit dashes clear of its span before it lands.
        for (dt in longArrayOf(100, 400, 700, 1_000)) scene.render(state(running = false, height = 0.12 - dt / 10_000.0, timeMs = hit + dt))
        val landed = rabbitXs(scene.render(state(running = false, height = 0.0, timeMs = hit + 1_300)))
        assertTrue("rabbits at $landed, basket at ${bx(hit + 1_300)}", landed.all { Math.abs(it + 4 - bx(hit + 1_300)) > 7 })
    }

    @Test fun deterministicAndAnimated() {
        val a = scene.render(state()).copyOf()
        assertArrayEquals(a, scene.render(state()))
        assertFalse(a.contentEquals(scene.render(state(timeMs = 60_000))))
    }

    @Test fun forecastBridgesTheLineToTheEdgeInDimDashes() {
        val period = 10_000.0
        val t = DoubleArray(30) { 90_000.0 + it * 850 }
        val hr = DoubleArray(30) { 68 + 5 * Math.sin(2 * Math.PI * t[it] / period) }
        fun frame(f: ((Double) -> Double)?) = scene.render(state().copy(
            traceT = t, traceHr = hr, traceNowMs = 118_000, pacerPeriodMs = period, pacerPhaseAt = { x -> (x / period) % 1.0 }, forecast = f,
        )).copyOf()
        val top = scene.groundY + 6
        fun ys(px: IntArray, x: Int, c: Int) = (top until scene.h).filter { px[it * scene.w + x] == c }
        fun cols(px: IntArray, c: Int) = (0 until scene.w).filter { ys(px, it, c).isNotEmpty() }
        val plain = frame(null)
        val lineEnd = cols(plain, PixelScene.TRACE).max()
        assertTrue(cols(plain, PixelScene.TRACE_DIM).none { it > lineEnd })
        val with = frame { x -> 68 + 5 * Math.sin(2 * Math.PI * x / period) }
        val dim = cols(with, PixelScene.TRACE_DIM).filter { it > lineEnd }
        assertTrue("dim=$dim", dim.size > 10)                                   // the gap is bridged, in dashes
        assertTrue("dim=$dim", dim.max() >= scene.w - 8)                        // all the way to the right edge
        assertTrue("dim=$dim", (lineEnd + 1..scene.w - 7).any { it !in dim })   // dashed, so it never passes for data
        val joinGap = Math.abs(ys(with, dim.min(), PixelScene.TRACE_DIM).average() - ys(with, lineEnd, PixelScene.TRACE).average())
        assertTrue("join=$joinGap", joinGap <= 3)                                // it starts where the real line stops
        assertEquals(plain.indices.filter { plain[it] == PixelScene.TRACE }, with.indices.filter { with[it] == PixelScene.TRACE })
    }
}
