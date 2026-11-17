package com.hrvballoon.ui

import com.hrvballoon.core.RfSearch
import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.rfFit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Star rankings and results card: violet night sky with twinkling stars over rolling hills, 4×6 pixel font. */
class RankScene(val w: Int, val h: Int, private val t: SceneText, seed: Long = 11) {
    private val background = IntArray(w * h)
    private val frame = IntArray(w * h)
    private val twinkles: List<IntArray>
    private val dateFmt = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    init {
        val rnd = Random(seed)
        val groundY = (h * 0.86).toInt()
        for (y in 0 until groundY) {
            val f = y.toDouble() / groundY * (NIGHT.size - 1)
            val i = f.toInt().coerceAtMost(NIGHT.size - 2)
            val frac = f - i
            val blend = ((frac - 0.4) * 80).coerceIn(0.0, 16.0)
            for (x in 0 until w) background[y * w + x] = if (blend > BAYER[(y and 3) * 4 + (x and 3)]) NIGHT[i + 1] else NIGHT[i]
        }
        val ridge = ridge(rnd, h * 0.14, groundY - h * 0.10, groundY)
        for (x in 0 until w) for (y in ridge[x] until groundY) background[y * w + x] = if (y == ridge[x]) RIDGE_EDGE else RIDGE
        for (y in groundY until h) for (x in 0 until w) background[y * w + x] = GROUND
        // Sky only: below the text block (never inside letters) and above the ridge. Bounded draw, so a window too
        // short to have such a band simply gets no twinkles.
        val skyBelowText = ((groundY * 0.9).toInt() - TEXT_BOTTOM).coerceAtLeast(1)
        twinkles = (1..2048).asSequence().map { intArrayOf(rnd.nextInt(w), TEXT_BOTTOM + rnd.nextInt(skyBelowText), rnd.nextInt(3)) }
            .filter { it[1] < ridge[it[0]] - 1 }.take(32).toList()
    }

    /**
     * Returns the scene's reused framebuffer — valid until the next call. [highlight] = index to mark, or -1;
     * a highlighted session below the visible rows is pinned as an extra row. [blink] off honours reduced motion.
     */
    fun render(records: List<SessionRecord>, highlight: Int, timeMs: Long, hasUnranked: Boolean = false, blink: Boolean = true): IntArray {
        backdrop(timeMs)
        surface(0, 0, w, 17, SURFACE)
        text(centred(t.bestSessions), 8, t.bestSessions, titleColor(timeMs))
        val shown = records.take(MAX_ROWS).withIndex().toMutableList()
        val pinned = highlight >= MAX_ROWS && highlight < records.size
        if (pinned) shown += IndexedValue(highlight, records[highlight])
        val tableBottom = if (shown.isEmpty()) 88 else 30 + (shown.size + if (pinned) 1 else 0) * ROW_H
        surface(4, 17, w - 4, tableBottom + 1, SURFACE)
        text(8, 20, "#", DIM)
        text(30, 20, t.colSession, DIM)
        rightText(w - 12, 20, t.colStars, DIM)
        surface(8, 28, w - 8, 29, SEPARATOR)
        if (records.isEmpty()) {
            val msg = if (hasUnranked) t.noRanked else t.noSessions
            text(centred(msg), 60, msg, DIM)
            if (hasUnranked) text(centred(t.minToRank), 70, t.minToRank, DIM)
        }
        val marker = !blink || (timeMs / 400) % 2 == 0L
        if (pinned) text(8, 30 + MAX_ROWS * ROW_H + 5, "...", DIM)
        for ((slot, iv) in shown.withIndex()) {
            val (i, r) = iv
            val rowTop = 30 + (if (slot < MAX_ROWS) slot else slot + 1) * ROW_H
            val y = rowTop + (ROW_H - PixelFont.H) / 2
            val color = if (i == highlight) HIGHLIGHT else TEXT
            if (i == highlight) surface(5, rowTop, w - 5, rowTop + ROW_H - 1, SELECTION)
            if (i == highlight && marker) text(1, y, ">", HIGHLIGHT)
            text(8, y, compactCount(i + 1), color)
            rightText(w - 12, y, "*${r.stars}", color)
            val at = Instant.ofEpochMilli(r.startMs).atZone(ZoneId.systemDefault())
            text(30, y, "${dateFmt.format(at)} ${timeFmt.format(at)}", DIM)
        }
        text(centred(t.tapRow), h - 30, t.tapRow, DIM)
        text(centred(t.tapClose), h - 20, t.tapClose, DIM)
        return frame
    }

    /**
     * End-of-session card. Training: hero star count (2×), star rank, lifetime total and date.
     * Search: the found rate as hero only when converged, "no clear peak" with the rate the search kept, or an honest "incomplete";
     * then the probe chart. [offer]: the card ends with "tap to use" its rate.
     */
    fun renderResult(
        r: SessionRecord, rankPos: Int, total: Int, best: SessionRecord?, timeMs: Long, lifetimeStars: Int,
        offer: Boolean = r.kind == SessionRecord.KIND_RF_SEARCH,
    ): IntArray {
        backdrop(timeMs)
        surface(0, 0, w, 17, SURFACE)
        surface(4, 18, w - 4, 47, SURFACE)
        text(centred(t.sessionDone), 8, t.sessionDone, titleColor(timeMs))
        if (r.isSearch) {
            val converged = r.kind == SessionRecord.KIND_RF_SEARCH
            val noPeak = r.kind == SessionRecord.KIND_RF_NO_PEAK
            val bestProbe = r.probes.maxByOrNull { it.second }
            val hero = when {
                converged -> String.format(Locale.ROOT, t.resonance, r.rateBpm)
                noPeak -> t.noClearPeak
                r.probes.size < 3 -> t.noEstimate
                else -> String.format(Locale.ROOT, t.bestSoFar, r.rateBpm)
            }
            text(centred(hero, 2), 22, hero, if (converged) HIGHLIGHT else DIM, 2)
            if (converged) {
                // The sub line is the chart's legend: each run in the colour of its mark.
                val model = String.format(Locale.ROOT, t.modelRate, r.rateBpm)
                val probe = String.format(Locale.ROOT, t.bestProbeRate, bestProbe?.first ?: r.rateBpm)
                var x = centred("$model - $probe")
                for ((run, c) in listOf(model to CARET, " - " to DIM, probe to HIGHLIGHT)) {
                    text(x, 38, run, c)
                    x += run.length * PixelFont.ADVANCE
                }
            } else {
                val sub = when {
                    noPeak -> String.format(Locale.ROOT, if (r.rfEstimateBpm != null) t.estimateProbes else t.defaultProbes, r.rateBpm, r.probes.size)
                    bestProbe != null -> String.format(Locale.ROOT, t.bestProbe, bestProbe.first, r.probes.size)
                    else -> t.noProbes
                }
                text(centred(sub), 38, sub, DIM)
            }
            probeChart(r, converged)
            if (offer) {
                val use = String.format(Locale.ROOT, t.tapToUse, r.rateBpm)
                text(centred(use), h - 30, use, HIGHLIGHT)
                text(centred(t.backToSkip), h - 20, t.backToSkip, DIM)
            } else text(centred(t.tapForRanks), h - 20, t.tapForRanks, DIM)
        } else {
            val stars = String.format(Locale.ROOT, t.stars, r.stars)
            val scale = if (PixelFont.width(stars) * 2 <= w - 8) 2 else 1
            text(centred(stars, scale), 22, stars, HIGHLIGHT, scale)
            val line = when {
                !r.ranked -> t.notRanked
                rankPos == 1 -> if (total > 1) String.format(Locale.ROOT, t.mostStars, total) else t.firstSession
                else -> String.format(Locale.ROOT, t.rankOf, rankPos, total)
            }
            text(centred(line), 38, line, if (!r.ranked) DIM else if (rankPos == 1) HIGHLIGHT else TEXT)
            val stats = r.meanShape?.let { String.format(Locale.ROOT, t.rhythm, (it * 100).roundToInt()) }
            surface(4, 48, w - 4, if (stats != null) 98 else 82, SURFACE)
            val lifetime = String.format(Locale.ROOT, t.lifetime, lifetimeStars)
            text(centred(lifetime), 54, lifetime, TEXT)
            val whenAt = fullFmt.format(Instant.ofEpochMilli(r.startMs).atZone(ZoneId.systemDefault()))
            text(centred(whenAt), 70, whenAt, DIM)
            stats?.let { text(centred(it), 86, it, TEXT) }
            text(centred(t.tapForRanks), h - 20, t.tapForRanks, DIM)
        }
        return frame
    }

    /**
     * Score against rate on a fixed 4–7 /min axis: a point per probe (best: diamond, failed: hollow), the bump [rfFit] puts
     * through them with a dithered band of ± [RfSearch.NOISE_SD] in ln score, and a dashed line at the reported rate,
     * [CARET] once converged. The reported rate is the fitted centre pulled toward the start, so it can sit off the curve's top.
     */
    private fun probeChart(r: SessionRecord, converged: Boolean) {
        if (r.probes.isEmpty()) return
        surface(4, 48, w - 4, PANEL_BOTTOM, SURFACE)
        val fit = rfFit(r.probes)
        val rates = r.probes.map { it.first } + r.rateBpm
        val lo = minOf(4.0, floor(rates.min() * 2) / 2)
        val hi = maxOf(7.0, ceil(rates.max() * 2) / 2)
        val x1 = w - 12
        fun xOf(rate: Double) = PLOT_LEFT + ((rate - lo) / (hi - lo) * (x1 - PLOT_LEFT)).roundToInt()
        fun rateAt(x: Int) = lo + (x - PLOT_LEFT).toDouble() / (x1 - PLOT_LEFT) * (hi - lo)
        val span = if (fit == null) IntRange.EMPTY
            else xOf(maxOf(lo, r.probes.minOf { it.first } - 0.5))..xOf(minOf(hi, r.probes.maxOf { it.first } + 0.5))
        val band = exp(RfSearch.NOISE_SD)
        val bestScore = r.probes.maxOf { it.second }
        val top = if (bestScore <= 0) 1.0 else niceTop(maxOf(bestScore, span.maxOfOrNull { fit!!.scoreAt(rateAt(it)) * band } ?: 0.0))
        fun yOf(score: Double) = PLOT_BASE - (score / top * (PLOT_BASE - PLOT_TOP)).roundToInt()
        fun plot(x: Int, y: Int, c: Int) { if (y in PLOT_TOP until PLOT_BASE) put(x, y, c) }

        for (x in PLOT_LEFT..x1) {
            if ((x - PLOT_LEFT) % 4 == 0) { put(x, PLOT_TOP, SEPARATOR); put(x, (PLOT_TOP + PLOT_BASE) / 2, SEPARATOR) }
            put(x, PLOT_BASE, SEPARATOR)
        }
        for (k in 0..((hi - lo) * 2).roundToInt()) xOf(lo + k * 0.5).let { put(it, PLOT_BASE + 1, SEPARATOR); put(it, PLOT_BASE + 2, SEPARATOR) }
        if (fit != null) {
            for (x in span) {
                val s = fit.scoreAt(rateAt(x))
                for (y in yOf(s * band)..yOf(s / band)) if ((x + y) % 2 == 0) plot(x, y, SEPARATOR)
            }
            var prev = 0
            for (x in span) {
                val y = yOf(fit.scoreAt(rateAt(x)))
                // One pixel thick without gaps: a steep step fills the column back to the previous one.
                val run = if (x == span.first) y..y else if (y > prev) prev + 1..y else y..maxOf(y, prev - 1)
                for (yy in run) plot(x, yy, BAR)
                prev = y
            }
            val mx = xOf(r.rateBpm)
            for (y in PLOT_TOP until PLOT_BASE) if ((y - PLOT_TOP) % 4 < 2) put(mx, y, if (converged) CARET else DIM)
        }
        for ((rate, score) in r.probes) {
            val x = xOf(rate)
            val y = yOf(score)
            when {
                score == bestScore -> { diamond(x, y, 3, SURFACE); diamond(x, y, 2, HIGHLIGHT) }
                score < RfSearch.FLOOR * bestScore -> { surface(x - 2, y - 2, x + 3, y + 3, SURFACE); surface(x - 1, y - 1, x + 2, y + 2, DIM); put(x, y, SURFACE) }
                else -> { surface(x - 2, y - 2, x + 3, y + 3, SURFACE); surface(x - 1, y - 1, x + 2, y + 2, TEXT) }
            }
        }

        text(8, 52, t.axisScore, DIM)
        if (fit != null) {
            val label = String.format(Locale.ROOT, "%.1f", r.rateBpm)
            val lx = (xOf(r.rateBpm) - PixelFont.width(label) / 2).coerceAtMost(w - 8 - PixelFont.width(label)).coerceAtLeast(38)
            text(lx, 52, label, if (converged) CARET else DIM)
        }
        rightText(23, PLOT_TOP - 2, if (top < 10) String.format(Locale.ROOT, "%.1f", top) else top.roundToInt().toString(), DIM)
        rightText(23, PLOT_BASE - 5, "0", DIM)
        for (k in ceil(lo).toInt()..floor(hi).toInt()) "$k".let { text(xOf(k.toDouble()) - PixelFont.width(it) / 2, PLOT_BASE + 5, it, DIM) }
        text((PLOT_LEFT + x1 - PixelFont.width(t.axisRate)) / 2, PLOT_BASE + 14, t.axisRate, DIM)
    }

    /** The smallest of 1, 1.5, 2, 3, 4, 5, 6, 8 × 10^k that is at least [v] > 0. */
    private fun niceTop(v: Double): Double {
        val p = 10.0.pow(floor(log10(v)))
        return doubleArrayOf(1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0).first { it * p >= v * (1 - 1e-9) } * p
    }

    private fun diamond(cx: Int, cy: Int, r: Int, c: Int) {
        for (dy in -r..r) for (dx in -r..r) if (kotlin.math.abs(dx) + kotlin.math.abs(dy) <= r) put(cx + dx, cy + dy, c)
    }

    private fun backdrop(timeMs: Long) {
        System.arraycopy(background, 0, frame, 0, frame.size)
        // Three quiet threads below the text block, clipped on short windows.
        for ((k, c) in AURORA.withIndex()) for (x in 0 until w) {
            val y = TEXT_BOTTOM + 4 + k * 10 + (3 * kotlin.math.sin(timeMs / 3000.0 + x / 28.0 + k)).roundToInt()
            if (y in 0 until h && background[y * w + x] in NIGHT) put(x, y, c)
        }
        for (t in twinkles) if ((timeMs / 300 + t[2]) % 3 != 0L) put(t[0], t[1], if (t[2] == 0) STAR_BRIGHT else STAR_DIM)
    }

    /** The title drifts through four dusk hues, one step every two seconds — a drift, not a flicker. */
    private fun titleColor(timeMs: Long) = TITLE_CYCLE[((timeMs / 2000) % TITLE_CYCLE.size).toInt()]

    /** Large counts remain visibly approximate rather than losing their final digits at the edge. */
    private fun compactCount(n: Int) = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> "${n / 1_000}K+"
        else -> "${n / 1_000_000}M+"
    }

    private fun rightText(right: Int, y: Int, s: String, color: Int) = text(right - PixelFont.width(s), y, s, color)

    private fun surface(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in maxOf(0, top) until minOf(h, bottom)) for (x in maxOf(0, left) until minOf(w, right)) put(x, y, color)
    }

    private fun centred(s: String, scale: Int = 1) = (w - PixelFont.width(s) * scale) / 2

    private fun text(x: Int, y: Int, s: String, color: Int, scale: Int = 1) = PixelFont.draw(x, y, s, color, scale) { px, py, c -> put(px, py, c) }

    private fun put(x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) frame[y * w + x] = c
    }

    private fun ridge(rnd: Random, amp: Double, base: Double, groundY: Int): IntArray {
        val phase = DoubleArray(3) { rnd.nextDouble() * Math.PI * 2 }
        val k = 0.7 + rnd.nextDouble() * 0.25
        return IntArray(w) { x ->
            val u = x.toDouble() / w * Math.PI * 2
            val wave = 0.62 * kotlin.math.sin(u * k + phase[0]) + 0.28 * kotlin.math.sin(u * k * 2 + phase[1]) + 0.1 * kotlin.math.sin(u * k * 3 + phase[2])
            (base + amp * 0.32 * wave).roundToInt().coerceIn(2, groundY - 1)
        }
    }

    companion object {
        /** Rows are 18 logical px (~45 dp on a 1080-wide phone) so each is a comfortable tap target. */
        const val MAX_ROWS = 7
        const val ROW_H = 18
        private const val TEXT_BOTTOM = 192   // below the pinned row (30 + 8 × ROW_H + glyph)
        // Search chart: score 0 at PLOT_BASE, the axis top at PLOT_TOP; the panel ends above TEXT_BOTTOM.
        private const val PLOT_LEFT = 26
        private const val PLOT_TOP = 64
        private const val PLOT_BASE = 120
        private const val PANEL_BOTTOM = 143
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        val NIGHT = intArrayOf(0xFF100F29.toInt(), 0xFF1A173C.toInt(), 0xFF27204F.toInt(), 0xFF38275F.toInt(), 0xFF4A316C.toInt(), 0xFF5F3A72.toInt())
        const val RIDGE = 0xFF1C193E.toInt()
        const val RIDGE_EDGE = 0xFF37305F.toInt()
        const val GROUND = 0xFF17382F.toInt()
        const val STAR_BRIGHT = 0xFFFFFFFF.toInt()
        const val STAR_DIM = 0xFFC9A6E0.toInt()
        const val TITLE = 0xFFFFD83B.toInt()
        const val TEXT = 0xFFF2E6FA.toInt()
        const val HIGHLIGHT = 0xFFA8F0C6.toInt()
        const val DIM = 0xFFA98FC9.toInt()
        const val BAR = 0xFF9A72D6.toInt()
        const val CARET = 0xFFFF8A5B.toInt()
        const val SURFACE = 0xFF18152E.toInt()
        const val SEPARATOR = 0xFF38304F.toInt()
        const val SELECTION = 0xFF253844.toInt()
        val TITLE_CYCLE = intArrayOf(0xFFFFD83B.toInt(), 0xFFF9B07C.toInt(), 0xFFE88A96.toInt(), 0xFFCDB0F0.toInt())
        val AURORA = intArrayOf(0xFF405A78.toInt(), 0xFF426D67.toInt(), 0xFF674A79.toInt())
        val PALETTE = NIGHT + TITLE_CYCLE + AURORA + intArrayOf(RIDGE, RIDGE_EDGE, GROUND, STAR_BRIGHT, STAR_DIM, TITLE, TEXT, HIGHLIGHT, DIM, BAR, CARET, SURFACE, SEPARATOR, SELECTION)

        /** Which table row (0-based) a logical y hits, or -1. */
        fun rowAt(y: Int, count: Int): Int {
            val i = (y - 30) / ROW_H
            return if (y >= 30 && i < minOf(count, MAX_ROWS)) i else -1
        }
    }
}
