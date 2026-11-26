package com.hrvballoon.core

import kotlin.math.ceil

/** [durationMs] null = open-ended session. */
class SessionConfig(
    val rateBpm: Double, val inhaleFraction: Double, val durationMs: Long?, val floorBpm: Double, val ceilingBpm: Double,
    /** Multiply the lift by [shapeGate] of the oscillation's sinusoidality. */
    val shapeGate: Boolean,
    /** Run the resonance-frequency search: the pacer rate is probed and adapted during the session. */
    val rfSearch: Boolean,
    /** How far the search's start may be off, /min: [RfSearch.START_SD_ESTIMATE] after a height estimate. */
    val rfStartSd: Double = RfSearch.START_SD_NONE,
    /** Compute the Citi m label of every beat (diagnostic, spec 7.1); false leaves the column empty and does no model work. */
    val citi: Boolean = true,
) {
    companion object {
        /** The app's config: [hasEstimate] sets the search's start spread; Citi runs only when a per-beat CSV is written ([saveCsv]). */
        fun of(
            rateBpm: Double, inhaleFraction: Double, durationMs: Long?, floorBpm: Double, ceilingBpm: Double,
            shapeGate: Boolean, rfSearch: Boolean, hasEstimate: Boolean, saveCsv: Boolean,
        ) = SessionConfig(
            rateBpm, inhaleFraction, durationMs, floorBpm, ceilingBpm, shapeGate, rfSearch,
            if (hasEstimate) RfSearch.START_SD_ESTIMATE else RfSearch.START_SD_NONE, saveCsv,
        )
    }
}

/**
 * Current resonance-search probe: the rate being breathed, its 1-based index, the best rate so far, and
 * [settlingMs] > 0 while the window is being refilled after a rate change (not a warm-up failure).
 */
class RfProbe(val rateBpm: Double, val index: Int, val bestBpm: Double?, val converged: Boolean, val settlingMs: Long, val finished: Boolean)

/**
 * [traceT]/[traceHr]: midpoint-stamped HR of the usable beats from the last 30 s, oldest first.
 * [lostReason] is set only while LOST; [warmupRemainingMs] counts down the two-period window (0 once an estimate exists).
 * The balloon target holds its last value through loss and warm-up — a sensor gap must not read as a failure.
 * [rateBpm] is the pacer's current rate. [forecast] projects HR ahead for the trace panel (display only); null unless the
 * signal is good and a full breath has been seen since the last reset.
 * [boundaryRemainingMs] is the time to the warm-up boundary T (spec 3.5): 60 000 while t0 is unknown, rounded up, 0 from T on.
 */
class UiState(
    val tMs: Long, val signal: Signal, val lostReason: LostReason?, val hrBpm: Int?, val p2tBpm: Double?, val phaseDeg: Double?,
    val shape: Double?, val warmingUp: Boolean, val warmupRemainingMs: Long, val balloonTarget: Double, val inhale: Boolean,
    val pacerLevel: Double, val elapsedMs: Long, val remainingMs: Long?, val finished: Boolean,
    val traceT: DoubleArray, val traceHr: DoubleArray, val rfProbe: RfProbe?,
    val rateBpm: Double, val forecast: HrForecast?, val boundaryRemainingMs: Long = 0,
)

const val TRACE_MS = 30_000

/**
 * Deterministic session core driven by injected session-clock timestamps (ms since Start).
 * The CSV header is the caller's job; the engine writes rows and events only.
 */
class SessionEngine(private val cfg: SessionConfig, private val csv: SessionCsvWriter) {
    /** The pacer; rate may change during a resonance search. Query it from the UI thread only. */
    val pacer = PacerTrack(cfg.rateBpm, cfg.inhaleFraction)
    private val rf = if (cfg.rfSearch) RfSearch(cfg.rateBpm, cfg.rfStartSd) else null
    private var probeStartMs = 0.0
    private var probeRate = cfg.rateBpm
    /** When the next probe's rate was scheduled in this probe's last breath: the inhale start where it takes over. */
    private var pending: Double? = null
    private var probeIndex = 1
    private var probeReset = false
    private val probeScores = ArrayList<Double>()
    private var firstScoreMs = 0.0
    private val timeline = BeatTimeline()
    private val filter = ArtifactFilter()
    private val monitor = SignalMonitor(0)
    private val estimator = AmplitudeEstimator()
    private val kalman = HrKalman()
    private val summary = SummaryAccumulator()
    private val coherence = CoherenceMeter()
    private val citi = if (cfg.citi) CitiModel() else null
    private var beatIndex = 0L        // +1 per measured RR, never reset: the model restarts on a hole (spec 8.4)
    private var citiOpen = false      // a model segment is running; closed by a loss or a confirmed gap
    private val clock = WarmupClock()
    private var searchBegun = false   // the first probe is counted from T, so nothing runs before it
    private var csSegment = 0
    private var csLastMs = Double.NEGATIVE_INFINITY
    private var hr: Int? = null
    private val trace = ArrayDeque<DoubleArray>()
    private var estimate: Estimate? = null
    private var shownP: Double? = null
    private var shownShape: Double? = null
    private var finished = false
    private var lastTickMs = 0L
    private var paused = false
    private var freshFromMs = 0L // beats that arrived earlier were classified late; they stay out of the metric path

    fun onPacket(nowMs: Long, packet: HrsPacket) {
        hr = packet.hrBpm
        monitor.onNotification(nowMs, packet.contact, packet.rr1024.isNotEmpty())?.let { transition(it, nowMs) }
        val update = timeline.onNotification(nowMs, packet.rr1024)
        if (update.gapMs > 0) {
            csv.event(nowMs.toDouble(), CsvEvent.GAP)
            clock.onBreak(nowMs.toDouble())   // the confirmation moment, never the gap start (spec 3.3 item 4)
            monitor.onGap(update.gapMs, nowMs)?.let { transition(it, nowMs) }
            freshFromMs = nowMs
            restartWindow()
            citiOpen = false
        }
        for (beat in update.beats) {
            beat.index = beatIndex++
            pushCiti(beat)   // before the filter: decide(i) needs beats i+1 and i+2 in the model
            classify(beat, nowMs)
        }
    }

    /** Spec 7.2: the first segment opens at the first RR wholly after T; a loss or a gap closes it, the next RR after it reopens. */
    private fun pushCiti(b: Beat) {
        val m = citi ?: return
        if (monitor.state == Signal.LOST) return
        if (!citiOpen) {
            if (!clock.isGame(b.tEndMs, b.rrMs)) return
            m.startSegment()
            citiOpen = true
        }
        m.push(b.index, b.rrMs)
    }

    private fun classify(beat: Beat, nowMs: Long) {
        for (c in filter.push(beat.also { it.paused = paused })) {
            if (record(c)) drive(c.beat)
            monitor.onClassified(c.accepted, nowMs)?.let { transition(it, nowMs) }
        }
    }

    /**
     * A usable beat. The first one after a reset sets t0. Every usable RR feeds the Kalman filter and the trace (spec 3.2);
     * only game RR feed the estimator and the coherence meter.
     */
    private fun drive(b: Beat) {
        if (clock.onUsable(b.tEndMs, b.arrivalMs.toDouble())) csv.event(clock.t0Ms!!, CsvEvent.WARMUP_START)
        val tMid = b.tEndMs - b.rrMs / 2
        kalman.update(tMid, pacer.cycles(tMid), 60000 / b.rrMs)
        trace.addLast(doubleArrayOf(tMid, 60000 / b.rrMs))
        if (clock.isGame(b.tEndMs, b.rrMs)) {
            estimator.add(tMid, 60000 / b.rrMs)
            feedCoherence(b)
        }
    }

    fun onLink(connected: Boolean, nowMs: Long) {
        csv.event(nowMs.toDouble(), if (connected) CsvEvent.RECONNECT else CsvEvent.DISCONNECT)
        monitor.onLink(connected, nowMs)?.let { transition(it, nowMs) }
    }

    /**
     * Away from the app: beats are still written to the CSV, but the summary, the swing estimate and the resonance search
     * pause until [resume], which also discards the current probe's scores.
     */
    fun pause(nowMs: Long) {
        if (paused) return
        paused = true
        clock.onBreak(nowMs.toDouble())
        pending?.let { if (nowMs < it) withdraw(nowMs.toDouble()) }
        csv.event(nowMs.toDouble(), CsvEvent.PAUSE)
    }

    fun resume(nowMs: Long) {
        if (!paused) return
        paused = false
        freshFromMs = nowMs
        csv.event(nowMs.toDouble(), CsvEvent.RESUME)
        restartWindow()
        probeScores.clear()
    }

    /** The metric window starts over: warm-up again. */
    private fun restartWindow() {
        estimator.reset()
        kalman.reset()
        estimate = null
        csSegment++   // also splits the coherence segments at a confirmed 1.5-3 s loss: acceptable, the gap rule mostly fires there anyway
    }

    /** Same beats as the estimator; the meter needs strictly increasing times and a new segment after each gap over 3 s. */
    private fun feedCoherence(b: Beat) {
        if (b.tEndMs <= csLastMs) return
        if (b.tEndMs - csLastMs > CS_GAP_MS) csSegment++
        csLastMs = b.tEndMs
        coherence.add(CsBeat(b.tEndMs, 60000 / b.rrMs, pacer.rateAt(b.tEndMs), csSegment))
    }

    fun tick(nowMs: Long): UiState {
        monitor.tick(nowMs)?.let { transition(it, nowMs) }
        val t = nowMs.toDouble()
        if (clock.takeEnd(t)) csv.event(clock.boundaryMs!!, CsvEvent.WARMUP_END)
        val rate = pacer.rateAt(t)
        if (!paused) rf?.let { probe(it, t) }
        if (monitor.state == Signal.OK && !paused) {
            estimate = estimator.estimate(rate, pacer.cycleOffset(t))
            estimate?.let {
                summary.addP(it.p2tBpm)
                summary.addShape(it.shape)
                summary.addValid(nowMs - lastTickMs)
                shownP = it.p2tBpm
                shownShape = it.shape
                if (rf?.finished == false && t - probeStartMs >= SCORE_FROM * 60_000 / probeRate) {
                    if (probeScores.isEmpty()) firstScoreMs = t
                    probeScores += it.p2tBpm * it.shape
                }
            }
        }
        lastTickMs = nowMs
        val remaining = cfg.durationMs?.let { (it - nowMs).coerceAtLeast(0) }
        if (remaining == 0L) finished = true
        while (trace.isNotEmpty() && trace.first()[0] < nowMs - TRACE_MS) trace.removeFirst()
        val gate = if (cfg.shapeGate) shapeGate(shownShape ?: 0.0) else 1.0
        val windowMs = 2 * 60_000 / rate
        return UiState(
            tMs = nowMs, signal = monitor.state, lostReason = if (monitor.state == Signal.LOST) monitor.reason else null,
            hrBpm = hr, p2tBpm = shownP, phaseDeg = estimate?.phaseDeg, shape = shownShape,
            warmingUp = estimate == null, warmupRemainingMs = if (estimate != null) 0 else (windowMs - estimator.spanMs).toLong().coerceAtLeast(0),
            balloonTarget = balloonTarget(shownP, cfg.floorBpm, cfg.ceilingBpm) * gate,
            inhale = pacer.isInhale(t), pacerLevel = pacer.level(t),
            elapsedMs = nowMs, remainingMs = remaining, finished = finished,
            traceT = DoubleArray(trace.size) { trace[it][0] }, traceHr = DoubleArray(trace.size) { trace[it][1] },
            rfProbe = rf?.let {
                val settling = if (it.finished) 0L else (SCORE_FROM * 60_000 / probeRate - (t - probeStartMs)).toLong().coerceAtLeast(0)
                RfProbe(rate, probeIndex, it.best, it.found, settling, it.finished)
            },
            rateBpm = rate,
            forecast = if (monitor.state == Signal.OK && !paused) kalman.forecast() else null,
            boundaryRemainingMs = ceil(clock.remainingMs(t)).toLong(),
        )
    }

    /**
     * Nothing runs before T. The first probe is counted from T minus one breath period: the last warm-up breath is its transient
     * breath, so it is scored from T plus two periods and ends eight periods after T (mid-breath possible; a rate change still
     * waits for the next inhale start). T moves when an event before it restarts the warm-up, so the start is taken only once T is reached.
     * One probe = 9 breath periods at [probeRate]: period 1 is the physiological transient (then the window is cleared),
     * periods 2–3 refill the window, periods 4–9 are measured (median of P × rhythm, four two-breath windows of data).
     * When the next rate does not hang on this probe's score (no fit follows it), it is scheduled during the last breath and
     * takes over as breath 9 ends; otherwise it is scheduled at the end and starts at the following inhale, a breath later.
     * A signal loss or a return to the app discards the probe's scores and withdraws an early change (a confirmed 1.5–3 s
     * loss restarts the warm-up but keeps the scores). Once converged the
     * pacer moves to the best rate and the search stops; it also stops when the next probe could not end [FINAL_MS] before
     * the session does. After the first probe they run in pairs (2, 3), (4, 5), …: the probe that opens a pair starts only when
     * both fit, the wait for the inhale included; a closing probe and a restart need room for themselves only. The pair is checked
     * early (pending) only at the 1 to 2 change, with the closing probe at [SLOWEST_RATE]; if that fails the exact check follows at
     * probe end. When no pair fits the search ends; without a peak it then ends near its start.
     */
    private fun probe(search: RfSearch, t: Double) {
        if (!searchBegun) {
            val boundary = clock.boundaryMs
            if (boundary == null || t < boundary) return
            probeStartMs = boundary - 60_000 / probeRate
            probeReset = true   // the transient breath came before T: no game RR to clear
            searchBegun = true
        }
        val period = 60_000 / probeRate
        val elapsed = t - probeStartMs
        if (!probeReset && elapsed >= period) { estimator.reset(); estimate = null; probeReset = true }
        if (search.finished) return
        val end = pending ?: (probeStartMs + PROBE_BREATHS * period)
        val endBy = cfg.durationMs?.let { it - FINAL_MS }
        if (t < end) {
            val scheduled = pending
            if (scheduled != null) { if (short(scheduled, period)) withdraw(t) }
            else if (elapsed >= (PROBE_BREATHS - 1) * period && !short(end, period)) {
                val next = search.following()
                // Only once the next inhale is the one that ends breath 9: a restart that began mid-breath runs on to the
                // inhale after its nominal end, as before.
                val at = t + (1 - pacer.phase(t)) * period
                if (next != null && next != probeRate && at >= end - 1 && (endBy == null || at + probeMs(next) + (if (probeIndex == 1) probeMs(SLOWEST_RATE) else 0.0) <= endBy)) pending = pacer.changeRate(next, t)
            }
            return
        }
        // Under three breaths scored (signal loss or time away late in the probe): probe the same rate again.
        val restart = short(t, period)
        if (!restart) { search.report(probeRate, probeScores.sorted().let { it[it.size / 2] }); probeIndex++ }
        probeScores.clear()
        val scheduled = pending
        pending = null
        if (scheduled != null && !restart) {   // already under way: the change took effect at the end of breath 9
            probeRate = pacer.rateAt(scheduled)
            probeStartMs = scheduled
            probeReset = false
            return
        }
        var next = when {
            restart -> probeRate
            search.converged -> search.best
            else -> search.next()
        }
        // A probe that opens a pair (2, 4, 6, …; not a restart) also needs room for its closing probe.
        val closing = if (!restart && probeIndex % 2 == 0) search.following() else null
        if (!search.converged && endBy != null && t + pacer.periodMs(t) + probeMs(next) + (closing?.let { probeMs(it) } ?: 0.0) > endBy) { next = search.conclude() ?: probeRate }
        val current = pacer.rateAt(t)   // differs from probeRate only when a restart follows a change already in effect
        probeStartMs = if (next != current) pacer.changeRate(next, t) else t
        probeReset = search.finished && next == current   // staying at the rate: no transient to clear
        probeRate = next
    }

    private fun probeMs(rate: Double) = PROBE_BREATHS * 60_000 / rate

    /** Under [MIN_SCORED] breaths of scores by [end]: the probe would have to restart. */
    private fun short(end: Double, period: Double) = probeScores.isEmpty() || end - firstScoreMs < MIN_SCORED * period

    /** Takes back a change scheduled in the last breath, before it takes effect; the restart rule then applies as before. */
    private fun withdraw(t: Double) {
        pacer.changeRate(probeRate, t)
        pending = null
    }

    /** The drawn balloon touched the ceiling (decided by the UI, which owns the smoothed height). */
    fun onStar(nowMs: Long) {
        csv.event(nowMs.toDouble(), CsvEvent.STAR)
        summary.addStar()
    }

    fun stop(nowMs: Long): Summary {
        filter.flush().forEach { record(it) }
        csv.close()
        return snapshot(nowMs)
    }

    /** The summary as it stands, without ending the session: what [stop] would give, less the beats the filter still holds. */
    fun snapshot(nowMs: Long): Summary {
        rf?.let { summary.rfBest = it.best; summary.rfConverged = it.found; summary.rfNoPeak = it.noPeak && it.finished; summary.rfProbes = it.probes }
        summary.coherence = coherence.median()
        return summary.result(nowMs)
    }

    /**
     * Beats flushed at loss were classified before it and stay usable for the summary; beats that arrive
     * while LOST (e.g. no skin contact but RR still flowing) reach the CSV and count as rejected in the summary. The metric
     * window is cleared on both edges so recovery always warms up again.
     */
    private fun transition(signal: Signal, nowMs: Long) {
        if (signal == Signal.LOST) {
            filter.flush().forEach { record(it, usable = it.accepted) }
            filter.reset()
            citiOpen = false
            csv.event(nowMs.toDouble(), CsvEvent.SIGNAL_LOST)
            clock.onBreak(nowMs.toDouble())
        } else {
            csv.event(nowMs.toDouble(), CsvEvent.SIGNAL_BACK)
        }
        restartWindow()
        summary.breakChain()
        probeScores.clear()
    }

    /** Writes the row and feeds the summary; returns whether the beat may drive the metric. */
    private fun record(c: ClassifiedBeat, usable: Boolean = c.accepted && monitor.state == Signal.OK): Boolean {
        val b = c.beat
        val hrBpm = if (b.rrMs > 0) 60000 / b.rrMs else null
        if (b.paused || b.afterGap) summary.breakChain()   // a skipped paused beat breaks the chain
        if (!b.paused) summary.addBeat(b.rrMs, usable, game = clock.isGame(b.tEndMs, b.rrMs))   // by the state at arrival, not at classification
        csv.beat(b.tEndMs, b.arrivalMs, b.rrMs, c.accepted, hrBpm, pacer.rateAt(b.tEndMs), pacer.phase(b.tEndMs), estimate?.p2tBpm, estimate?.phaseDeg, citi?.decide(b.index, c.accepted))
        return usable && !b.paused && b.arrivalMs >= freshFromMs
    }

    private companion object {
        const val PROBE_BREATHS = 9   // 1 transient + 2 to refill the two-breath window + 6 scored
        const val SCORE_FROM = 3      // breaths into a probe before scoring starts
        const val MIN_SCORED = 3      // breaths of scores a probe needs to count (of six)
        const val CS_GAP_MS = 3_000.0
        const val SLOWEST_RATE = 4.0  // the slowest rate RfSearch gives: a conservative closing probe when its rate is not exposed
        const val FINAL_MS = 120_000L // a probe must end this long before the session does
    }
}
