package com.hrvballoon.ui

import com.hrvballoon.core.SessionRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt

/** Star rankings and results card: violet night sky with twinkling stars over rolling hills, 4×6 pixel font. */
class RankScene(val w: Int, val h: Int, seed: Long = 11) {
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
        text(centred("BEST SESSIONS"), 8, "BEST SESSIONS", titleColor(timeMs))
        val shown = records.take(MAX_ROWS).withIndex().toMutableList()
        val pinned = highlight >= MAX_ROWS && highlight < records.size
        if (pinned) shown += IndexedValue(highlight, records[highlight])
        val tableBottom = if (shown.isEmpty()) 88 else 30 + (shown.size + if (pinned) 1 else 0) * ROW_H
        surface(4, 17, w - 4, tableBottom + 1, SURFACE)
        text(8, 20, "#", DIM)
        text(30, 20, "SESSION", DIM)
        rightText(w - 12, 20, "STARS", DIM)
        surface(8, 28, w - 8, 29, SEPARATOR)
        if (records.isEmpty()) {
            val msg = if (hasUnranked) "NO RANKED SESSIONS YET" else "NO SESSIONS YET"
            text(centred(msg), 60, msg, DIM)
            if (hasUnranked) text(centred("2 MIN MINIMUM TO RANK"), 70, "2 MIN MINIMUM TO RANK", DIM)
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
        text(centred("TAP A ROW FOR DETAILS"), h - 30, "TAP A ROW FOR DETAILS", DIM)
        text(centred("TAP ELSEWHERE TO CLOSE"), h - 20, "TAP ELSEWHERE TO CLOSE", DIM)
        return frame
    }

    /**
     * End-of-session card. Training: hero star count (2×), star rank, lifetime total and date.
     * Search: the found rate as hero only when converged, else an honest "incomplete"; then the probe curve.
     */
    fun renderResult(r: SessionRecord, rankPos: Int, total: Int, best: SessionRecord?, timeMs: Long, lifetimeStars: Int): IntArray {
        backdrop(timeMs)
        surface(0, 0, w, 17, SURFACE)
        surface(4, 18, w - 4, 47, SURFACE)
        text(centred("SESSION DONE"), 8, "SESSION DONE", titleColor(timeMs))
        if (r.isSearch) {
            val converged = r.kind == SessionRecord.KIND_RF_SEARCH
            val bestProbe = r.probes.maxByOrNull { it.second }
            val hero = when {
                converged -> String.format(Locale.ROOT, "RESONANCE %.1f", r.rateBpm)
                r.probes.size < 3 -> "NO ESTIMATE YET"
                else -> String.format(Locale.ROOT, "BEST SO FAR %.1f", r.rateBpm)
            }
            text(centred(hero, 2), 22, hero, if (converged) HIGHLIGHT else DIM, 2)
            val sub = when {
                converged -> String.format(Locale.ROOT, "MODEL %.1f - BEST PROBE %.1f", r.rateBpm, bestProbe?.first ?: r.rateBpm)
                bestProbe != null -> String.format(Locale.ROOT, "BEST PROBE %.1f - %d PROBES", bestProbe.first, r.probes.size)
                else -> "NO PROBES COMPLETED"
            }
            text(centred(sub), 38, sub, if (converged) TEXT else DIM)
            if (r.probes.isNotEmpty()) surface(4, 48, w - 4, 68 + minOf(12, r.probes.size) * 8, SURFACE)
            probeCurve(r, 52, if (converged) r.rateBpm else null)
            if (converged) {
                val use = String.format(Locale.ROOT, "TAP TO USE %.1f/MIN", r.rateBpm)
                text(centred(use), h - 30, use, HIGHLIGHT)
                text(centred("BACK TO SKIP"), h - 20, "BACK TO SKIP", DIM)
            } else text(centred("TAP FOR RANKS"), h - 20, "TAP FOR RANKS", DIM)
        } else {
            val stars = "STARS *${r.stars}"
            val scale = if (PixelFont.width(stars) * 2 <= w - 8) 2 else 1
            text(centred(stars, scale), 22, stars, HIGHLIGHT, scale)
            val line = when {
                !r.ranked -> "NOT RANKED - UNDER 2 MIN"
                rankPos == 1 -> if (total > 1) "MOST STARS  #1 OF $total" else "FIRST SESSION"
                else -> "#$rankPos OF $total BY STARS"
            }
            text(centred(line), 38, line, if (!r.ranked) DIM else if (rankPos == 1) HIGHLIGHT else TEXT)
            surface(4, 48, w - 4, 82, SURFACE)
            val lifetime = "LIFETIME *$lifetimeStars"
            text(centred(lifetime), 54, lifetime, TEXT)
            val whenAt = fullFmt.format(Instant.ofEpochMilli(r.startMs).atZone(ZoneId.systemDefault()))
            text(centred(whenAt), 70, whenAt, DIM)
            text(centred("TAP FOR RANKS"), h - 20, "TAP FOR RANKS", DIM)
        }
        return frame
    }

    /** Score by rate, sorted by rate: a bar per probe, the best probe highlighted; a caret marks the model's peak rate. */
    private fun probeCurve(r: SessionRecord, y0: Int, modelPeak: Double?) {
        if (r.probes.isEmpty()) return
        text(20, y0, "RATE  SCORE", DIM)
        val maxScore = r.probes.maxOf { it.second }.coerceAtLeast(1e-9)
        val sorted = r.probes.sortedBy { it.first }.take(12)
        val peakRow = modelPeak?.let { peak -> sorted.indices.minByOrNull { kotlin.math.abs(sorted[it].first - peak) } }
        for ((i, p) in sorted.withIndex()) {
            val y = y0 + 10 + i * 8
            text(20, y, String.format(Locale.ROOT, "%.1f", p.first), TEXT)
            val len = (p.second / maxScore * 70).roundToInt()
            val best = p.second == maxScore
            for (x in 46 until 46 + len) for (dy in 1..4) put(x, y + dy, if (best) HIGHLIGHT else BAR)
            text(120, y, String.format(Locale.ROOT, "%.1f", p.second), if (best) HIGHLIGHT else TEXT)
            if (i == peakRow) text(12, y, "^", CARET)
        }
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
