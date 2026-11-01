package com.hrvballoon.ui

import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [falling] holds the spawn times (scene clock) of stars still tumbling down; [stars] is the collected count.
 * The HR trace is in session time: [traceT]/[traceHr] beats, [traceNowMs] the right edge, pacer params for the inhale bands.
 */
data class SceneState(
    val height01: Double, val rising: Boolean, val pacerLevel: Double, val inhale: Boolean,
    val running: Boolean, val frozen: Boolean, val timeMs: Long,
    val stars: Int = 0, val falling: List<Long> = emptyList(),
    val traceT: DoubleArray = DoubleArray(0), val traceHr: DoubleArray = DoubleArray(0), val traceNowMs: Long = 0,
    val pacerPeriodMs: Double = 0.0, val inhaleFraction: Double = 0.4, val pacerCycleOffset: Double = 0.0,
    /** Exact pacer phase at a session time, when the rate may have changed within the trace window. */
    val pacerPhaseAt: ((Double) -> Double)? = null,
    /** Text drawn into the scene in the pixel font; null = no HUD (tests, previews). */
    val hud: Hud? = null,
    /** Honour the system's reduced-motion setting: ambient animation stops; only the breath-locked pacer and sway move. */
    val reducedMotion: Boolean = false,
    /** Session progress 0..1: the sun sets and the sky darkens to night over the session; 0 while idle. */
    val progress: Double = 0.0,
    /** Scene-clock timestamp of the last ceiling contact; drives a short envelope squash and recoil. */
    val ceilingHitAtMs: Long? = null,
    /** Projected HR at a session time (display only); bridges the trace line's end to the right edge in dim dashes. */
    val forecast: ((Double) -> Double)? = null,
)

/** One status line; session headers include live HR and pacer rate, with loss guidance on its own plate. */
/** [timer] is drawn right-aligned on the same line (the countdown in a session, the session length when ready). */
class Hud(val dot: Int, val status: String, val timer: String = "", val lost: List<String> = emptyList()) {
    companion object {
        /** One line: "<label> HR <n> [Pace] <rate>/min". The plain running state has no label; with a label the word Pace goes, so the line still fits beside the timer. */
        fun session(dot: Int, status: String, hrBpm: Int?, rateBpm: Double, timer: String = "", lost: List<String> = emptyList()): Hud {
            val hr = hrBpm?.takeIf { it in 1..999 }?.toString() ?: "--"
            val text = if (status.isEmpty()) String.format(Locale.ROOT, "HR %s Pace %.1f/min", hr, rateBpm)
            else String.format(Locale.ROOT, "%s HR %s %.1f/min", status, hr, rateBpm)
            return Hud(dot, text, timer, lost)
        }
    }
}

/**
 * Software framebuffer scene (~160 px wide), palette-only, no Android types: the View blits it with
 * nearest-neighbour scaling. Static layers (two rolling hill layers, treeline, tufted ground) are painted once and
 * kept in [PHASES] dusk tints; the sky, sun, lone stars, clouds, balloon, shadow, rabbits and pacer bar are
 * composited per frame. A session is one sunset: golden hour at 0, night at 1.
 */
class PixelScene(val w: Int, val h: Int, seed: Long = 7) {
    val groundY = (h * 0.80).toInt()

    /** Sky rows reserved above the ceiling; the status band has a fixed height. */
    var topMargin = 6
    private val background = IntArray(w * h)
    private val backgrounds: Array<IntArray>
    private val skyline: IntArray
    private val frame = IntArray(w * h)
    private val balloon = balloonSprite()
    internal val clouds: List<Cloud>
    private val rabbitSeed = seed
    private val rabbits = mutableListOf(Rabbit(Random(seed xor 0x5A5A), w, shy = false), Rabbit(Random(seed xor 0xA5A5), w, shy = true))
    internal val rabbitCount get() = rabbits.size
    private val skyStars: List<IntArray>
    private val waveOffset = IntArray(w)
    private var phaseK = 0
    private var phaseFrac = 0.0
    private var lastHeight = 1.0
    private var traceCentre = Double.NaN   // bpm at the middle of the trace panel, slowly following the drawn median
    private var traceCentreMs = 0L

    init {
        val rnd = Random(seed)
        // Sky pixels stay 0 in the background and are painted live (the gradient melts slowly and follows the sunset).
        paintHillLayer(rnd, lift = 0.24, amp = 0.045, body = HILL_FAR, crest = HILL_FAR_CREST)
        paintHillLayer(rnd, lift = 0.14, amp = 0.04, body = HILL_MID, crest = HILL_MID_CREST)
        paintHills(ridge(rnd, h * 0.07, groundY - h * 0.08), rnd)
        paintGround(rnd)
        paintFlowers(rnd)
        backgrounds = Array(PHASES) { k -> IntArray(w * h) { i -> if (background[i] == 0 || k == 0) background[i] else dusk(background[i], k) } }
        skyline = IntArray(w) { x -> (0 until h).firstOrNull { background[it * w + x] != 0 } ?: h }
        clouds = List(8) { Cloud.random(rnd, w, groundY, it % 4) }
        skyStars = loneStars(rnd)
    }

    fun basketBottomY(height01: Double) = groundY - 1 - (height01 * (groundY - 1 - topMargin - balloon.h)).roundToInt()

    /** Returns the scene's reused framebuffer — valid until the next call; copy it if you need to keep it. */
    fun render(s: SceneState): IntArray {
        val ambient = if (s.reducedMotion) 0L else s.timeMs   // clock for everything that isn't the breath
        paintSky(ambient, s.progress)
        if (s.hud != null) topMargin = HUD_MARGIN
        drawSun(s.progress)
        drawSkyStars(ambient, s.progress)
        for (c in clouds) c.draw(this, ambient)
        drawBalloon(s)
        val starX = s.falling.firstOrNull { (s.timeMs - it) in 0 until FALL_MS }?.let { balloonX(it) }
        val descending = s.height01 < lastHeight && s.height01 < 0.15   // the basket is coming down on the meadow
        lastHeight = s.height01
        updateRabbitCount(s.stars)
        for (r in rabbits) {
            r.update(ambient, starX)
            if (descending) r.fleeFrom(balloonXNow(s), ambient)
            r.draw(this, groundY + 4)
        }
        if (s.running) drawTrace(s)
        drawStars(s)
        if (s.frozen) for (i in frame.indices) frame[i] = grey(frame[i])
        if (s.running) drawPacer(s)   // stays in colour through a loss: keep breathing to it
        s.hud?.let { drawHud(it) }
        return frame
    }

    /** A single status line leaves the scene clear; the ceiling never moves with its wording. */
    private fun drawHud(hud: Hud) {
        val bandH = topMargin - 2
        for (y in 0 until bandH) for (x in 0 until w) put(x, y, PLATE)
        for (x in 4 until w - 4) put(x, bandH - 1, PANEL_EDGE)
        for (dy in -1..1) for (dx in -1..1) put(6 + dx, 7 + dy, hud.dot)
        var right = w - 4
        if (hud.timer.isNotEmpty()) {   // countdown on the right; the status is cut to what fits before it
            right = w - PixelFont.width(hud.timer) - 4
            text(right, 4, hud.timer, HUD_TEXT)
            right -= 6
        }
        text(12, 4, fit(hud.status.uppercase(Locale.ROOT), right - 12), HUD_TEXT)
        if (hud.lost.isNotEmpty()) {
            val top = groundY / 2 - 4 * hud.lost.size - 6
            val plateH = 8 * hud.lost.size + 10
            for (y in top until top + plateH) for (x in 8 until w - 8) put(x, y, PLATE)
            for ((i, l) in hud.lost.withIndex()) text((w - PixelFont.width(l)) / 2, top + 5 + i * 8, l, LOST_TEXT)
        }
    }

    private fun text(x: Int, y: Int, s: String, color: Int) = PixelFont.draw(x, y, s, color) { px, py, c -> put(px, py, c) }

    /** Keep long statuses inside the scene's horizontal padding. */
    private fun fit(s: String, px: Int) = if (PixelFont.width(s) <= px) s else s.take((px + 1) / PixelFont.ADVANCE).trimEnd()

    // ---- static layers ----

    private fun bg(x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) background[y * w + x] = c
    }

    /** Dusk phase for a pixel: the two keyframes around [phaseFrac] are mixed by ordered dither, so a change is a dissolve. */
    private fun phaseAt(x: Int, y: Int) = if (phaseFrac * 16 > BAYER[((y + 2) and 3) * 4 + ((x + 1) and 3)]) phaseK + 1 else phaseK

    /**
     * Live sky: eight stops of the current sunset keyframe, from deep blue at the top to the warm end at the horizon,
     * pushed up to three stops warmer within half a screen of the sun (reddish around it, dark blue far away);
     * dithered by height and between keyframes by time; the bands undulate slowly across the screen (the horizon
     * melts, a little). Static layers come from their tinted copy.
     */
    private fun paintSky(timeMs: Long, progress: Double) {
        val p = progress.coerceIn(0.0, 1.0) * (PHASES - 1)
        phaseK = minOf(p.toInt(), PHASES - 2)
        phaseFrac = p - phaseK
        for (x in 0 until w) waveOffset[x] = (4 * sin(timeMs / 5000.0 + x / 22.0) + 3 * sin(timeMs / 7300.0 - x / 37.0)).roundToInt()
        val sx = sunX
        val sy = sunY(progress)
        val glowR2 = (w * 0.55) * (w * 0.55)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val k = phaseAt(x, y)
            if (background[i] != 0) { frame[i] = backgrounds[k][i]; continue }
            val yy = (y + waveOffset[x]).coerceIn(0, groundY - 1)
            val dx = (x - sx).toDouble()
            val dy = (y - sy).toDouble()
            val warm = (3 * (1 - (dx * dx + dy * dy) / glowR2)).coerceAtLeast(0.0)
            val f = (yy.toDouble() / groundY * 7 + warm).coerceAtMost(7.0)
            val s0 = f.toInt().coerceAtMost(6)
            val blend = ((f - s0 - 0.4) * 80).coerceIn(0.0, 16.0)
            frame[i] = SKY_RAMP[k][if (blend > BAYER[(y and 3) * 4 + (x and 3)]) s0 + 1 else s0]
        }
    }

    /** A few pale blooms in the grass, seeded. */
    private fun paintFlowers(rnd: Random) {
        repeat(w / 14) {
            val x = rnd.nextInt(w)
            val y = groundY + 4 + rnd.nextInt(maxOf(1, h - groundY - 18))
            val c = FLOWERS[rnd.nextInt(FLOWERS.size)]
            bg(x, y - 1, c); bg(x - 1, y, c); bg(x + 1, y, c); bg(x, y + 1, c); bg(x, y, FLOWER_HEART)
        }
    }

    private val sunX get() = (w * 0.78).toInt()
    private val sunR get() = maxOf(5, h / 40)

    /** A sun's width above the highest crest under it at the start of the session; fully behind the skyline at [SUNSET_END] of it. */
    private fun sunY(progress: Double): Int {
        val r = sunR
        val crests = (sunX - r..sunX + r).map { skyline[it.coerceIn(0, w - 1)] }
        val y0 = maxOf(crests.min() - r - 10, HUD_MARGIN + r + 1)
        return (y0 + (crests.max() + r + 1 - y0) * (progress / SUNSET_END).coerceIn(0.0, 1.0)).roundToInt()
    }

    /** The disc with a thin rim and a dithered glow, on sky pixels only, so the ridges hide it as it sets; it reddens as it drops. */
    private fun drawSun(progress: Double) {
        if (progress > SUNSET_END + 0.1) return
        val r = sunR
        val cx = sunX
        val cy = sunY(progress)
        for (dy in -r - 5..r + 5) for (dx in -r - 5..r + 5) {
            val x = cx + dx
            val y = cy + dy
            if (x !in 0 until w || y !in 0 until h || background[y * w + x] != 0) continue
            val d2 = dx * dx + dy * dy
            val low = phaseAt(x, y) >= PHASES / 2
            val b = BAYER[(y and 3) * 4 + (x and 3)]
            val c = when {
                d2 <= (r - 1) * (r - 1) -> if (low) SUN_LOW else SUN
                d2 <= r * r -> if (low) SUN_LOW_RIM else SUN_RIM
                d2 <= (r + 2) * (r + 2) -> if (b >= 5) SUN_GLOW else continue
                d2 <= (r + 4) * (r + 4) -> if (b >= 10) SUN_GLOW else continue
                d2 <= (r + 5) * (r + 5) -> if (b >= 14) SUN_GLOW else continue
                else -> continue
            }
            put(x, y, c)
        }
    }

    /** A dozen-odd single stars scattered over the upper sky, well apart, each with its own slow twinkle: x, y, period, phase. */
    private fun loneStars(rnd: Random): List<IntArray> {
        val out = ArrayList<IntArray>()
        var tries = 0
        while (out.size < 14 && tries++ < 400) {
            val x = 6 + rnd.nextInt(w - 24)
            val y = HUD_MARGIN + 4 + rnd.nextInt((groundY * 0.45).toInt() - HUD_MARGIN)
            if (out.any { kotlin.math.abs(it[0] - x) <= 9 && kotlin.math.abs(it[1] - y) <= 9 }) continue
            out += intArrayOf(x, y, 1500 + rnd.nextInt(2000), rnd.nextInt(3500))
        }
        return out
    }

    private fun sky(x: Int, y: Int) = x in 0 until w && y in 0 until h && background[y * w + x] == 0

    /** Stars come out one by one between [STARS_FROM] and [STARS_FROM] + [STARS_SPAN] of the session (ordered dither by position). */
    private fun drawSkyStars(ambient: Long, progress: Double) {
        val vis = (progress - STARS_FROM) / STARS_SPAN * 16
        if (vis <= 0) return
        for (st in skyStars) {
            val x = st[0]
            val y = st[1]
            if (vis <= BAYER[(y and 3) * 4 + (x and 3)] || !sky(x, y) || y < topMargin) continue
            put(x, y, if ((ambient + st[3]) % st[2] < st[2] * 0.6) STAR_SKY else STAR_SKY_DIM)
        }
    }

    /** Midpoint displacement heightmap, smoothed: the ridge line (unrounded) per column. */
    private fun ridge(rnd: Random, amp: Double, base: Double): DoubleArray {
        var n = 1
        while (n < w) n *= 2
        val hm = DoubleArray(n + 1)
        hm[0] = base + (rnd.nextDouble() - 0.5) * amp
        hm[n] = base + (rnd.nextDouble() - 0.5) * amp
        var step = n
        var a = amp
        while (step > 1) {
            var i = step / 2
            while (i < n) {
                hm[i] = (hm[i - step / 2] + hm[i + step / 2]) / 2 + (rnd.nextDouble() - 0.5) * a
                i += step
            }
            step /= 2
            a *= 0.55
        }
        return DoubleArray(w) { x ->
            ((x - 1..x + 1).sumOf { hm[it.coerceIn(0, n)] } / 3).coerceIn(2.0, groundY - 1.0)
        }
    }

    /**
     * Rolling hills: three slow waves summed per layer (slope under a pixel per column, so the crest never steps),
     * filled to the ground under a one-pixel lighter crest. Farther layers sit higher and hazier.
     */
    private fun paintHillLayer(rnd: Random, lift: Double, amp: Double, body: Int, crest: Int) {
        val k = 0.8 + rnd.nextDouble() * 0.4
        val a = DoubleArray(3) { rnd.nextDouble() * 2 * PI }
        for (x in 0 until w) {
            val u = x.toDouble() / w * 2 * PI
            val wave = 0.6 * sin(u * k + a[0]) + 0.3 * sin(u * 2 * k + a[1]) + 0.1 * sin(u * 3.5 * k + a[2])
            val top = (groundY - h * (lift + amp * wave)).roundToInt().coerceIn(HUD_MARGIN + 2, groundY - 1)
            bg(x, top, crest)
            for (y in top + 1 until groundY) bg(x, y, body)
        }
    }

    private fun paintHills(line: DoubleArray, rnd: Random) {
        val top = IntArray(w) { line[it].roundToInt() }
        for (x in 0 until w) {
            bg(x, top[x], HILL_LIGHT)
            for (y in top[x] + 1 until groundY) bg(x, y, HILL)
        }
        var x = rnd.nextInt(4)
        while (x < w) {
            tree(x, top[x], 2 + rnd.nextInt(2), rnd.nextInt(2) == 0)
            x += 5 + rnd.nextInt(6)
        }
        x = rnd.nextInt(3)
        while (x < w) {
            tree(x, groundY, 3 + rnd.nextInt(4), false)
            x += 3 + rnd.nextInt(4)
        }
    }

    private fun tree(x: Int, baseY: Int, height: Int, light: Boolean) {
        for (i in 0 until height) {
            val half = (height - 1 - i) / 2
            for (dx in -half..half) bg(x + dx, baseY - height + 1 + i, if (light) TREE_LIGHT else TREE)
        }
    }

    private fun paintGround(rnd: Random) {
        for (y in groundY until h) for (x in 0 until w) bg(x, y, if (y == groundY) GRASS_LIGHT else if (y > h - (h - groundY) / 4) GROUND_DARK else GROUND)
        repeat(w * (h - groundY) / 28) {
            val x = rnd.nextInt(w)
            val y = groundY + 2 + rnd.nextInt(h - groundY - 3)
            bg(x, y, GROUND_DOT)
            bg(x + 1, y - 1, GROUND_DOT)
        }
    }

    // ---- dynamic layers ----

    private fun put(x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) frame[y * w + x] = c
    }

    /** Like [put], but under the pixel's dusk tint (clouds share the sunset with the land). */
    private fun putTinted(x: Int, y: Int, c: Int) = put(x, y, dusk(c, phaseAt(x, y)))

    private fun balloonX(timeMs: Long) = (w * 0.42 + sin(timeMs / 1700.0) * 2).roundToInt()

    /** In a session the sway follows the breath (one swing per breath); idle it drifts on its own slow clock. */
    private fun balloonXNow(s: SceneState): Int {
        val phase = s.pacerPhaseAt?.takeIf { s.running }?.invoke(s.traceNowMs.toDouble())
        return if (phase != null) (w * 0.42 + sin(2 * PI * phase) * 2).roundToInt() else if (s.reducedMotion) (w * 0.42).roundToInt() else balloonX(s.timeMs)
    }

    /** Collected stars pile up bottom-left once they land; a newly earned one tumbles from the balloon to the ground, twinkling. */
    private fun drawStars(s: SceneState) {
        val landed = s.stars - s.falling.count { (s.timeMs - it) in 0 until FALL_MS }
        var shown = minOf(landed, 20)
        while (shown > 0 && landed > shown && 3 + shown * 7 + PixelFont.width("+${landed - shown}") > w - 3) shown--
        for (i in 0 until shown) star(3 + i * 7, h - 4, plus = false)
        if (landed > shown) text(3 + shown * 7, h - 7, "+${landed - shown}", STAR)
        for (spawn in s.falling) {
            val t = s.timeMs - spawn
            if (t < 0 || t >= FALL_MS) continue
            val u = t.toDouble() / FALL_MS
            val y0 = topMargin + 12
            val y = (y0 + (groundY - 3 - y0) * u * u).roundToInt()
            val x = balloonX(spawn) + (sin(t / 150.0) * 3).roundToInt()
            for (i in 1..3) {   // short fading trail
                val ut = (u - 0.04 * i).coerceAtLeast(0.0)
                put(balloonX(spawn) + (sin((t - 60 * i) / 150.0) * 3).roundToInt(), (y0 + (groundY - 3 - y0) * ut * ut).roundToInt(), if (i == 1) STAR else STAR_DARK)
            }
            star(x, y, plus = !s.reducedMotion && (t / 120) % 2 == 1L)
        }
    }

    private fun star(cx: Int, cy: Int, plus: Boolean) {
        for (d in 1..2) { put(cx, cy - d, STAR); put(cx, cy + d, STAR); put(cx - d, cy, STAR); put(cx + d, cy, STAR) }
        if (!plus) for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) put(cx + dx, cy + dy, STAR)
        put(cx, cy, STAR_DARK)
    }

    /** Session stars bring more visitors; existing rabbits keep their state and independent seeded dice. */
    private fun updateRabbitCount(stars: Int) {
        val wanted = 2 + stars.coerceAtLeast(0) / 12
        while (rabbits.size > wanted) rabbits.removeAt(rabbits.lastIndex)
        while (rabbits.size < wanted) {
            val i = rabbits.size
            rabbits += Rabbit(Random(rabbitSeed xor (0xC6BC2796L + i * 0x9E3779B9L)), w, shy = i % 2 != 0)
        }
    }

    private fun drawBalloon(s: SceneState) {
        val bx = balloonXNow(s)
        val bottom = basketBottomY(s.height01)
        val top = bottom - balloon.h + 1
        val left = bx - balloon.w / 2

        val hw = (10 * (1 - 0.65 * s.height01)).roundToInt()
        for (x in bx - hw..bx + hw) put(x, groundY + 1, SHADOW)
        for (x in bx - hw + 3..bx + hw - 3) put(x, groundY + 2, SHADOW)

        // Squash at contact, then briefly stretch with the recoil. Only the envelope is resampled;
        // its mouth, ropes and basket remain on their original pixel grid.
        val afterHit = s.ceilingHitAtMs?.let { s.timeMs - it } ?: -1
        val scaleY = if (s.reducedMotion || afterHit !in 0 until 400) 1.0 else if (afterHit < 100) {
            0.9 + 0.1 * afterHit / 100.0
        } else 1.0 + 0.08 * sin(PI * (afterHit - 100) / 300.0)
        val envH = (ENVELOPE_H * scaleY).roundToInt()
        val envW = (balloon.w * (1 + (1 - scaleY) * 0.7)).roundToInt() or 1
        val envTop = top + ENVELOPE_H - envH
        val envLeft = bx - envW / 2
        for (y in 0 until envH) for (x in 0 until envW) {
            val c = balloon.px[(y * ENVELOPE_H / envH) * balloon.w + x * balloon.w / envW]
            if (c != 0) put(envLeft + x, envTop + y, c)
        }
        for (y in ENVELOPE_H until balloon.h) for (x in 0 until balloon.w) {
            val c = balloon.px[y * balloon.w + x]
            if (c != 0) put(left + x, top + y, c)
        }
        if (s.rising) {
            val base = bottom - BASKET_H - 1
            val f = ((s.timeMs / 90) % 3).toInt()
            for (dx in -2..2) put(bx + dx, base, if (dx in -1..1) FLAME_B else FLAME_A)
            for (dx in -1..1) put(bx + dx, base - 1, if (dx == 0) FLAME_B else FLAME_A)
            put(bx + f - 1, base - 2, FLAME_B)
            if (f == 2) put(bx, base - 3, FLAME_A)
        }
    }

    /**
     * Last 30 s of beat-to-beat HR on a fixed 30-bpm span centred on the visible median, so amplitude reads
     * honestly (auto-ranging would make a 2-bpm wobble look like a 20-bpm swing); inhale phases shaded behind.
     * The right edge runs a little ahead of "now" inside the current breath phase (see the lead below), so the band
     * touching the edge is always the breath you are in and becomes visible within a second of each flip; the HR
     * line grows toward the edge as the phase runs out and reaches it exactly at the flip. (With "now" at the edge
     * the new band was a one-pixel sliver that only grew over the breath, so the edge always read as the previous
     * phase; jumping the edge to the phase end fixed that but lurched by a band at every flip.)
     */
    private fun drawTrace(s: SceneState) {
        val x0 = 4
        val x1 = w - 5
        val yTop = groundY + 5
        val yBot = h - 9
        for (y in yTop..yBot) for (x in x0..x1) {
            put(x, y, if (y == yTop || y == yBot || x == x0 || x == x1) PANEL_EDGE else PANEL)
        }
        val dt = TRACE_MS.toDouble() / (x1 - x0 - 1)   // ms per column; column x covers [tEdge - (x1 - x)·dt, +dt)
        fun phaseAt(t: Double) = s.pacerPhaseAt?.invoke(t) ?: (t / s.pacerPeriodMs + s.pacerCycleOffset).let { it - kotlin.math.floor(it) }
        val now = s.traceNowMs.toDouble()
        val tEdge = if (s.pacerPeriodMs <= 0) now else {
            val ph = phaseAt(now)
            val from = if (ph < s.inhaleFraction) 0.0 else s.inhaleFraction          // where the current breath phase began
            val span = (if (ph < s.inhaleFraction) s.inhaleFraction else 1.0) - from   // and how long it is, in cycles
            val u = (ph - from) / span
            // Ease-out lead: right after a flip the edge runs ahead at twice real time, so the new band shows within a
            // second; it then glides to rest exactly at the phase end. Lead is 0 at both ends, so nothing ever jumps.
            now + ((1 - (1 - u) * (1 - u)) - u) * span * s.pacerPeriodMs
        }
        val tStart = tEdge - TRACE_MS
        if (s.pacerPeriodMs > 0) for (x in x0 + 1 until x1) {
            if (phaseAt(tEdge - (x1 - x) * dt) < s.inhaleFraction) for (y in yTop + 1 until yBot) put(x, y, PANEL_INHALE)
        }
        val bar = ((yBot - yTop - 2) * 10 / HR_SPAN).roundToInt()
        for (y in yBot - 2 - bar..yBot - 2) put(x0 + 2, y, TRACE_DIM)
        text(x0 + 4, yBot - 2 - bar, "10", TRACE_DIM)
        // Beats in time order (a re-anchored clock can stamp one earlier than its predecessor).
        val beats = s.traceT.indices.filter { s.traceT[it] <= now }.sortedBy { s.traceT[it] }
        if (beats.size < 2) { traceCentre = Double.NaN; return }
        val first = s.traceT[beats.first()]
        val last = s.traceT[beats.last()]
        // Steady curve: the beat series is resampled on a FIXED grid of the session clock (k·dt) and smoothed with a
        // Gaussian of σ = SMOOTH_MS there, so a sample's value never depends on where the panel has scrolled to; only
        // samples whose kernel is complete are drawn (the line ends 3σ behind the newest beat), so a new beat extends
        // the curve and changes nothing already on screen. Beat jitter (~1 Hz) goes, the breathing wave stays (94 %).
        val k0 = kotlin.math.ceil(first / dt).toLong()
        val kN = kotlin.math.floor(last / dt).toLong()
        val radius = (3 * SMOOTH_MS / dt).toInt()
        if (kN - k0 < 2 * radius) return
        var j = 0
        val raw = DoubleArray((kN - k0 + 1).toInt()) { i ->
            val t = (k0 + i) * dt
            while (j + 1 < beats.size - 1 && s.traceT[beats[j + 1]] <= t) j++
            val ta = s.traceT[beats[j]]
            val tb = s.traceT[beats[j + 1]]
            val wgt = if (tb > ta) ((t - ta) / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            s.traceHr[beats[j]] + wgt * (s.traceHr[beats[j + 1]] - s.traceHr[beats[j]])
        }
        val drawn = raw.size - radius   // samples 0 until drawn have a complete kernel
        val smooth = DoubleArray(drawn) { i ->
            var sum = 0.0
            var wsum = 0.0
            for (k in maxOf(0, i - radius)..i + radius) {
                val d = (k - i) * dt / SMOOTH_MS
                val wgt = exp(-0.5 * d * d)
                sum += wgt * raw[k]
                wsum += wgt
            }
            sum / wsum
        }
        // The vertical centre follows the median of what is drawn through a slow average, so the curve drifts rather
        // than hops when a beat enters or leaves the window. It restarts with the session clock.
        val med = smooth.sorted()[smooth.size / 2]
        traceCentre = if (traceCentre.isNaN() || now < traceCentreMs) med
        else traceCentre + (med - traceCentre) * (1 - exp(-(now - traceCentreMs) / CENTRE_TAU_MS))
        traceCentreMs = s.traceNowMs
        fun row(v: Double) = (yBot - 1 - (v - (traceCentre - HR_SPAN / 2)) / HR_SPAN * (yBot - yTop - 2)).roundToInt().coerceIn(yTop + 1, yBot - 1)
        var py = -1
        for (x in x0 + 1 until x1) {
            val g = (tEdge - (x1 - x) * dt) / dt - k0   // position in the grid, fractional
            if (g < 0 || g > drawn - 1) { py = -1; continue }
            val i = g.toInt().coerceAtMost(drawn - 2)
            val v = smooth[i] + (g - i) * (smooth[i + 1] - smooth[i])
            val y = row(v)
            if (py >= 0) line(x - 1, py, x, y, TRACE) else put(x, y, TRACE)
            py = y
        }
        // Forecast: dim dashes from the line's end to the edge, joined by an offset that fades over JOIN_MS, so the
        // projection starts where the data stops and never passes for data. Only columns past the line are touched.
        val fc = s.forecast ?: return
        val tEnd = (k0 + drawn - 1) * dt
        val offset = smooth[drawn - 1] - fc(tEnd)
        var xStart = -1
        var fy = -1
        for (x in x0 + 1 until x1) {
            val t = tEdge - (x1 - x) * dt
            if (t <= tEnd) continue
            if (xStart < 0) xStart = x
            val y = row(fc(t) + offset * exp(-(t - tEnd) / JOIN_MS))
            if ((x - xStart) % 4 < 2) { if ((x - xStart) % 4 == 1) line(x, fy, x, y, TRACE_DIM) else put(x, y, TRACE_DIM) }
            fy = y
        }
    }

    private fun line(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0), 1)
        for (i in 0..steps) put(x0 + ((x1 - x0) * i.toDouble() / steps).roundToInt(), y0 + ((y1 - y0) * i.toDouble() / steps).roundToInt(), c)
    }

    private fun drawPacer(s: SceneState) {
        val bw = 6
        val x0 = w - bw - 5
        val top = topMargin + 8   // below the HUD band, leaving room for the direction arrow
        val bottom = groundY - 10
        for (y in top..bottom) for (x in x0 until x0 + bw) {
            put(x, y, if (y == top || y == bottom || x == x0 || x == x0 + bw - 1) PACER_FRAME else PACER_BG)
        }
        val color = if (s.inhale) PACER_IN else PACER_OUT
        val fill = ((bottom - top - 1) * s.pacerLevel).roundToInt()
        for (y in bottom - fill until bottom) for (x in x0 + 1 until x0 + bw - 1) put(x, y, color)
        for (q in 1..3) put(x0 + 1, top + (bottom - top) * q / 4, PACER_FRAME)
        val rows = if (s.inhale) intArrayOf(1, 3, 5) else intArrayOf(5, 3, 1)
        for (r in 0..2) {
            val sx = x0 + 3 - rows[r] / 2
            for (x in sx until sx + rows[r]) put(x, top - 6 + r, color)
        }
    }

    /**
     * Flat-bottomed lumpy cloud: upper half-discs on a softly shaded baseline. Four
     * parallax layers: the higher the layer, the smaller, hazier and slower. Undersides catch the sunset mid-session.
     */
    internal class Cloud(val x0: Double, val y0: Int, val speed: Double, val lumps: List<IntArray>, val span: Int, val layer: Int) {
        fun draw(s: PixelScene, t: Long) {
            val period = s.w + span
            val x = (((x0 + t * speed) % period + period) % period - span).toInt()
            val body = when (layer) { 0, 1 -> CLOUD_FAR; 2 -> CLOUD_WHITE; else -> CLOUD_NEAR }
            val shade = if (layer <= 1) CLOUD_FAR_SHADE else CLOUD_SHADE
            for (l in lumps) {
                val r = l[1]
                for (dy in -r until 0) for (dx in -r..r) if (dx * dx + dy * dy <= r * r) {
                    val px = x + l[0] + dx
                    val py = y0 + dy
                    s.putTinted(px, py, body)
                }
            }
            val left = lumps.minOf { it[0] - it[1] + 1 }
            val right = lumps.maxOf { it[0] + it[1] - 1 }
            for (dx in left..right) s.putTinted(x + dx, y0, if (s.phaseAt(x + dx, y0) in SUNLIT_PHASES) CLOUD_SUNLIT else shade)
        }

        companion object {
            /** px per ms by layer: the top layer barely moves, the bottom one drifts eight times faster. */
            val SPEEDS = doubleArrayOf(0.0005, 0.0012, 0.0024, 0.004)

            /** Layer 0 = far (small, high, slow) … 3 = near (big, low, fast); all behind the balloon so the burner stays visible. */
            fun random(rnd: Random, w: Int, groundY: Int, layer: Int): Cloud {
                val n = 2 + layer / 2 + rnd.nextInt(3)
                val lumps = ArrayList<IntArray>()
                var dx = 0
                repeat(n) {
                    val r = 1 + layer + rnd.nextInt(2 + layer)
                    lumps += intArrayOf(dx, r)
                    dx += r + 1 + rnd.nextInt(2)
                }
                val span = dx + 8
                val y0 = (groundY * (0.15 + 0.13 * layer + rnd.nextDouble() * 0.07)).toInt()
                return Cloud(rnd.nextDouble() * (w + span), y0, SPEEDS[layer], lumps, span, layer)
            }
        }
    }

    /**
     * Now and then a rabbit runs along the top of the meadow; it may stop, look around, and run on the same way or
     * back. While a star is falling it sits and watches; when it lands it usually jumps for joy, then runs on. Driven
     * by the ambient clock, so it is frozen under reduced motion; all choices come from its own seeded dice at state
     * changes, so frames stay deterministic.
     */
    private class Rabbit(private val dice: Random, private val w: Int, private val shy: Boolean) {
        private var state = HIDDEN
        private var x = 0.0
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

        /** The basket is coming down on it: it bolts out from under, three times its running pace, for a second. */
        fun fleeFrom(bx: Int, now: Long) {
            if (state == HIDDEN || now < dashUntil || kotlin.math.abs(x + 4 - bx) > 9) return
            dir = if (x + 4 < bx) -1 else 1
            dashUntil = now + DASH_MS
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
                    val speed = if (now < dashUntil) SPEED * 3 else SPEED
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
                    '#' -> s.put(x0 + col, top + r, RABBIT)
                    '+' -> s.put(x0 + col, top + r, RABBIT_DARK)
                    'o' -> s.put(x0 + col, top + r, DARK)
                }
            }
        }

        private companion object {
            const val HIDDEN = 0
            const val RUN = 1
            const val SIT = 2
            const val WATCH = 3
            const val JUMP = 4
            const val SPEED = 22.0   // px/s
            const val JUMP_CHANCE = 0.75
            const val JUMP_MS = 400L
            const val DASH_MS = 1000L
            // Facing right: '#' fur, '+' tail, 'o' eye. Ears back while running, up while sitting.
            val RUN_A = arrayOf("...##...", "....####", "+#####o#", ".######.", ".#....#.")
            val RUN_B = arrayOf("...##...", "....####", "+#####o#", "..#####.", "...##...")
            val SIT_FRAME = arrayOf("..#.#.", "..#.#.", ".###o#", "+#####", ".#..#.")
        }
    }

    private class Sprite(val w: Int, val h: Int, val px: IntArray)

    /**
     * Checkered envelope: squares are indexed by angular position across the envelope's width, so they narrow toward
     * the edges and read as curvature; shaded from the top-left with a specular highlight. Mouth band, ropes, basket.
     */
    private fun balloonSprite(): Sprite {
        val w = 29
        val cx = 14
        val envH = ENVELOPE_H
        val h = envH + MOUTH_H + ROPE_H + BASKET_H
        val px = IntArray(w * h)
        for (y in 0 until envH) {
            val half = if (y <= 22) (14 * sqrt((1 - ((y - 11) / 12.5).let { it * it }).coerceAtLeast(0.0))).roundToInt() else 7 - (y - 22)
            for (x in cx - half..cx + half) {
                val u = if (half == 0) 0.0 else (x - cx).toDouble() / half
                val col = ((u + 1) * 2.5).toInt().coerceIn(0, 4)
                val coral = (col + y / 5) % 2 == 0
                px[y * w + x] = when {
                    u < -0.45 -> if (coral) CORAL_LIGHT else CREAM_LIGHT
                    u > 0.45 -> if (coral) CORAL_DARK else CREAM_DARK
                    else -> if (coral) CORAL else CREAM
                }
            }
        }
        for ((x, y) in listOf(cx - 7 to 3, cx - 8 to 4, cx - 6 to 2)) px[y * w + x] = WHITE
        for (y in envH until envH + MOUTH_H) for (x in cx - 2..cx + 2) px[y * w + x] = DARK
        for (i in 0 until ROPE_H) {
            val y = envH + MOUTH_H + i
            px[y * w + cx - 3 - i / 2] = DARK
            px[y * w + cx + 3 + i / 2] = DARK
        }
        for (y in h - BASKET_H until h) for (x in cx - 5..cx + 5) {
            px[y * w + x] = if (y == h - BASKET_H || x == cx - 5 || x == cx + 5) DARK else if ((x + y) % 2 == 0) WICKER_A else WICKER_B
        }
        return Sprite(w, h, px)
    }

    companion object {
        /** One status row with fixed padding, so wording changes never move the ceiling. */
        const val HUD_MARGIN = 16
        private const val ENVELOPE_H = 28
        private const val MOUTH_H = 2
        private const val ROPE_H = 5
        private const val BASKET_H = 6
        private const val FALL_MS = 1800
        private const val TRACE_MS = 30_000
        /** Gaussian σ for the trace line, ms: well above a beat (~1 s), well below a breath (~11 s). */
        private const val SMOOTH_MS = 600.0
        private const val JOIN_MS = 1000.0
        /** Time constant of the trace panel's vertical centre, ms. */
        private const val CENTRE_TAU_MS = 8_000.0
        private const val HR_SPAN = 30.0
        /** The sun is fully behind the skyline at this share of the session (about 13 of 15 minutes). */
        private const val SUNSET_END = 0.87
        private const val STARS_FROM = 0.55
        private const val STARS_SPAN = 0.25
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

        /** Sunset keyframes, eight stops deep blue → warm: golden hour, amber, rose-violet, indigo dusk, night. */
        val SKY_PHASES = arrayOf(
            intArrayOf(0xFF2C3F8A.toInt(), 0xFF4A5099.toInt(), 0xFF7466A6.toInt(), 0xFFA07EA8.toInt(), 0xFFC995A0.toInt(), 0xFFE8AE8E.toInt(), 0xFFF8C98A.toInt(), 0xFFFFE3A0.toInt()),
            intArrayOf(0xFF26327A.toInt(), 0xFF433C8C.toInt(), 0xFF6A4890.toInt(), 0xFF92508E.toInt(), 0xFFBE6482.toInt(), 0xFFE2807A.toInt(), 0xFFF5A77E.toInt(), 0xFFFBCF8C.toInt()),
            intArrayOf(0xFF161A42.toInt(), 0xFF2B2160.toInt(), 0xFF4A2D78.toInt(), 0xFF6F3A84.toInt(), 0xFF9A4A86.toInt(), 0xFFC85C72.toInt(), 0xFFE6705C.toInt(), 0xFFF79C6E.toInt()),
            intArrayOf(0xFF0E1236.toInt(), 0xFF1A1748.toInt(), 0xFF2A2062.toInt(), 0xFF3E2A72.toInt(), 0xFF57347C.toInt(), 0xFF7A3F6C.toInt(), 0xFFA0505A.toInt(), 0xFFC86A54.toInt()),
            intArrayOf(0xFF080A1E.toInt(), 0xFF0E1130.toInt(), 0xFF151A42.toInt(), 0xFF1D2250.toInt(), 0xFF27295A.toInt(), 0xFF34305F.toInt(), 0xFF423A62.toInt(), 0xFF524766.toInt()),
        )
        /** Dusk phases: the five keyframes with three interpolated steps between each pair, so each dither step is a quarter of a keyframe. */
        const val PHASES = 17
        private fun mix(a: Int, b: Int, num: Int, den: Int): Int {
            fun ch(sh: Int) = (a shr sh and 0xFF).let { it + ((b shr sh and 0xFF) - it) * num / den }
            return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val SKY_RAMP: Array<IntArray> = Array(PHASES) { p ->
            if (p % 4 == 0) SKY_PHASES[p / 4] else IntArray(8) { i -> mix(SKY_PHASES[p / 4][i], SKY_PHASES[p / 4 + 1][i], p % 4, 4) }
        }
        /** Phases in which cloud undersides catch the low sun (amber to rose-violet). */
        val SUNLIT_PHASES = 3..9
        /** The golden-hour ramp (also the letterbox colour outside the scene). */
        val SKY: IntArray get() = SKY_PHASES[0]
        const val SUN = 0xFFFFE9B0.toInt()
        const val SUN_RIM = 0xFFFFF6DC.toInt()
        const val SUN_LOW = 0xFFFF9A5A.toInt()
        const val SUN_LOW_RIM = 0xFFFFC68A.toInt()
        const val SUN_GLOW = 0xFFF7C86A.toInt()
        const val STAR_SKY = 0xFFFFF8E0.toInt()
        const val STAR_SKY_DIM = 0xFFB8B4D8.toInt()
        const val CLOUD_NEAR = 0xFFFFF6EA.toInt()
        const val CLOUD_WHITE = 0xFFF9E6E0.toInt()
        const val CLOUD_SHADE = 0xFFD8B2CC.toInt()
        const val CLOUD_SUNLIT = 0xFFF2A07A.toInt()
        const val CLOUD_OUTLINE = 0xFFB08AB8.toInt()
        const val CLOUD_FAR = 0xFFD9C4E6.toInt()
        const val CLOUD_FAR_SHADE = 0xFFB89ED0.toInt()
        const val FLOWER_HEART = 0xFFFFD36B.toInt()
        val FLOWERS = intArrayOf(0xFFF2A7C3.toInt(), 0xFFFFE9C2.toInt())
        const val HILL_FAR = 0xFF6A4F99.toInt()
        const val HILL_FAR_CREST = 0xFF8A6AB4.toInt()
        const val HILL_MID = 0xFF3D2A66.toInt()
        const val HILL_MID_CREST = 0xFF5E4190.toInt()
        const val HILL = 0xFF2A5A66.toInt()
        const val HILL_LIGHT = 0xFF3B7A80.toInt()
        const val TREE = 0xFF1C3F4C.toInt()
        const val TREE_LIGHT = 0xFF25525C.toInt()
        const val GROUND = 0xFF3F7D4A.toInt()
        const val GROUND_DOT = 0xFF5A9C58.toInt()
        const val GROUND_DARK = 0xFF2E5C3A.toInt()
        const val GRASS_LIGHT = 0xFF7FB56C.toInt()
        const val SHADOW = 0xFF24492F.toInt()
        const val CORAL = 0xFFE2574B.toInt()
        const val CORAL_LIGHT = 0xFFF28A72.toInt()
        const val CORAL_DARK = 0xFFA83A3A.toInt()
        const val CREAM = 0xFFFFF1D6.toInt()
        const val CREAM_LIGHT = 0xFFFFFBEE.toInt()
        const val CREAM_DARK = 0xFFD9C09A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DARK = 0xFF3A2A22.toInt()
        const val WICKER_A = 0xFF9C6B3C.toInt()
        const val WICKER_B = 0xFF7A4F28.toInt()
        const val FLAME_A = 0xFFFF9B2E.toInt()
        const val FLAME_B = 0xFFFFE34D.toInt()
        const val RABBIT = 0xFFF4E7D2.toInt()
        const val RABBIT_DARK = 0xFFB89C7A.toInt()
        const val STAR = 0xFFFFD83B.toInt()
        const val STAR_DARK = 0xFFE3A41C.toInt()
        const val PANEL = 0xFF1A1033.toInt()
        const val PANEL_EDGE = 0xFF3A2A66.toInt()
        const val PANEL_INHALE = 0xFF36235F.toInt()
        const val TRACE = 0xFFFFEBC4.toInt()
        const val TRACE_DIM = 0xFF9C86C9.toInt()
        const val PACER_FRAME = 0xFF1E1240.toInt()
        const val PACER_BG = 0xFF3A2466.toInt()
        const val PACER_IN = 0xFFFFB84D.toInt()
        const val PACER_OUT = 0xFF7FDDF2.toInt()
        const val PLATE = 0xFF1A1033.toInt()
        const val HUD_TEXT = 0xFFFFF1DC.toInt()
        const val HUD_DIM = 0xFFC9A6E0.toInt()
        const val LOST_TEXT = 0xFFFFB84D.toInt()
        const val DOT_GREEN = 0xFF5FD38A.toInt()
        const val DOT_AMBER = 0xFFFFA62B.toInt()
        const val DOT_RED = 0xFFD63A3A.toInt()
        private const val NIGHT_TINT = 0xFF141A3A.toInt()

        /** A colour under dusk phase [k] (0 = as painted … PHASES − 1 = night): pulled toward a deep blue in fixed steps, so every tint is in the palette. */
        fun dusk(c: Int, k: Int): Int {
            if (k <= 0) return c
            fun ch(shift: Int): Int {
                val v = c shr shift and 0xFF
                val n = NIGHT_TINT shr shift and 0xFF
                return v + (n - v) * k * 68 / (100 * (PHASES - 1))
            }
            return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private val BASE = SKY_RAMP.flatMap { it.asList() }.toIntArray() + FLOWERS + intArrayOf(
            SUN, SUN_RIM, SUN_LOW, SUN_LOW_RIM, SUN_GLOW, STAR_SKY, STAR_SKY_DIM,
            CLOUD_NEAR, CLOUD_WHITE, CLOUD_SHADE, CLOUD_SUNLIT, CLOUD_OUTLINE, CLOUD_FAR, CLOUD_FAR_SHADE, FLOWER_HEART,
            HILL_FAR, HILL_FAR_CREST, HILL_MID, HILL_MID_CREST, HILL, HILL_LIGHT, TREE, TREE_LIGHT,
            GROUND, GROUND_DOT, GROUND_DARK, GRASS_LIGHT, SHADOW,
            CORAL, CORAL_LIGHT, CORAL_DARK, CREAM, CREAM_LIGHT, CREAM_DARK, WHITE, DARK, WICKER_A, WICKER_B, FLAME_A, FLAME_B, RABBIT, RABBIT_DARK,
            STAR, STAR_DARK, PANEL, PANEL_EDGE, PANEL_INHALE, TRACE, TRACE_DIM, PACER_FRAME, PACER_BG, PACER_IN, PACER_OUT,
            PLATE, HUD_TEXT, HUD_DIM, LOST_TEXT, DOT_GREEN, DOT_AMBER, DOT_RED,
        )

        /** Every base colour plus its dusk tints. */
        val PALETTE = BASE + (1 until PHASES).flatMap { k -> BASE.map { dusk(it, k) } }.toIntArray()

        fun grey(c: Int): Int {
            val lum = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            return 0xFF000000.toInt() or (lum shl 16) or (lum shl 8) or lum
        }
    }
}
