package com.hrvballoon.core

/** [durationMs] null = open-ended session. */
class SessionConfig(
    val rateBpm: Double, val inhaleFraction: Double, val durationMs: Long?, val floorBpm: Double, val ceilingBpm: Double,
    /** Multiply the lift by [shapeGate] of the oscillation's sinusoidality. */
    val shapeGate: Boolean,
    /** Run the resonance-frequency search: the pacer rate is probed and adapted during the session. */
    val rfSearch: Boolean,
    /** Where a search without a peak ends: a height-and-sex estimate when one was given. */
    val rfFallbackBpm: Double = RfSearch.FALLBACK_BPM,
)

/**
 * Current resonance-search probe: the rate being breathed, its 1-based index, the best rate so far, and
 * [settlingMs] > 0 while the window is being refilled after a rate change (not a warm-up failure).
 */
class RfProbe(val rateBpm: Double, val index: Int, val bestBpm: Double?, val converged: Boolean, val settlingMs: Long, val finished: Boolean)

/**
 * [traceT]/[traceHr]: midpoint-stamped HR of the usable beats from the last 30 s, oldest first.
 * [lostReason] is set only while LOST; [warmupRemainingMs] counts down the two-period window (0 once an estimate exists).
 * The balloon target holds its last value through loss and warm-up — a sensor gap must not read as a failure.
 * [phaseLeadDeg]: HR phase relative to the pacer fundamental (> 0 = HR leads = breathing slower than resonance).
 * [rateBpm] is the pacer's current rate. [forecast] projects HR ahead for the trace panel (display only); null unless the
 * signal is good and a full breath has been seen since the last reset.
 */
class UiState(
    val tMs: Long, val signal: Signal, val lostReason: LostReason?, val hrBpm: Int?, val p2tBpm: Double?, val phaseDeg: Double?,
    val shape: Double?, val warmingUp: Boolean, val warmupRemainingMs: Long, val balloonTarget: Double, val inhale: Boolean,
    val pacerLevel: Double, val elapsedMs: Long, val remainingMs: Long?, val finished: Boolean,
    val traceT: DoubleArray, val traceHr: DoubleArray, val rfProbe: RfProbe?,
    val phaseLeadDeg: Double?, val rateBpm: Double, val forecast: HrForecast?,
)

/**
 * Deterministic session core driven by injected session-clock timestamps (ms since Start).
 * The CSV header is the caller's job; the engine writes rows and events only.
 */
class SessionEngine(private val cfg: SessionConfig, private val csv: SessionCsvWriter) {
    /** The pacer; rate may change during a resonance search. Query it from the UI thread only. */
    val pacer = PacerTrack(cfg.rateBpm, cfg.inhaleFraction)
    private val rf = if (cfg.rfSearch) RfSearch(cfg.rateBpm, cfg.rfFallbackBpm) else null
    private var probeStartMs = 0.0
    private var probeIndex = 1
    private var probeReset = false
    private val probeScores = ArrayList<Double>()
    private val phaseRef = PacerTrack.fundamentalPhaseDeg(cfg.inhaleFraction)
    private var lastLeadDeg = 0.0
    private val timeline = BeatTimeline()
    private val filter = ArtifactFilter()
    private val monitor = SignalMonitor(0)
    private val estimator = AmplitudeEstimator()
    private val kalman = HrKalman()
    private val summary = SummaryAccumulator()
    private var hr: Int? = null
    private val trace = ArrayDeque<DoubleArray>()
    private var estimate: Estimate? = null
    private var shownP: Double? = null
    private var shownShape: Double? = null
    private var finished = false
    private var lastTickMs = 0L
    private var paused = false

    fun onPacket(nowMs: Long, packet: HrsPacket) {
        hr = packet.hrBpm
        monitor.onNotification(nowMs, packet.contact, packet.rr1024.isNotEmpty())?.let { transition(it, nowMs) }
        val update = timeline.onNotification(nowMs, packet.rr1024)
        if (update.gapMs > 0) monitor.onGap(update.gapMs, nowMs)?.let { transition(it, nowMs) }
        for (beat in update.beats) for (c in filter.push(beat)) {
            val usable = record(c)
            if (usable) {
                val tMid = c.beat.tEndMs - c.beat.rrMs / 2
                estimator.add(tMid, 60000 / c.beat.rrMs)
                kalman.update(tMid, pacer.cycles(tMid), 60000 / c.beat.rrMs)
                trace.addLast(doubleArrayOf(tMid, 60000 / c.beat.rrMs))
            }
            monitor.onClassified(c.accepted, nowMs)?.let { transition(it, nowMs) }
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
        csv.event(nowMs.toDouble(), CsvEvent.PAUSE)
    }

    fun resume(nowMs: Long) {
        if (!paused) return
        paused = false
        csv.event(nowMs.toDouble(), CsvEvent.RESUME)
        estimator.reset()
        kalman.reset()
        estimate = null
        summary.breakChain()
        probeScores.clear()
    }

    fun tick(nowMs: Long): UiState {
        monitor.tick(nowMs)?.let { transition(it, nowMs) }
        val t = nowMs.toDouble()
        val rate = pacer.rateAt(t)
        if (!paused) rf?.let { probe(it, t, rate) }
        if (monitor.state == Signal.OK && !paused) {
            estimate = estimator.estimate(rate, pacer.cycleOffset(t))
            estimate?.let {
                summary.addP(it.p2tBpm)
                summary.addShape(it.shape)
                summary.addValid(nowMs - lastTickMs)
                shownP = it.p2tBpm
                shownShape = it.shape
                lastLeadDeg = wrapDeg(it.phaseDeg - phaseRef)
                if (rf?.finished == false && t - probeStartMs >= SCORE_FROM * pacer.periodMs(t)) probeScores += it.p2tBpm * it.shape
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
                val settling = if (it.finished) 0L else (SCORE_FROM * pacer.periodMs(t) - (t - probeStartMs)).toLong().coerceAtLeast(0)
                RfProbe(rate, probeIndex, it.best, it.converged, settling, it.finished)
            },
            phaseLeadDeg = estimate?.let { lastLeadDeg }, rateBpm = rate,
            forecast = if (monitor.state == Signal.OK && !paused) kalman.forecast() else null,
        )
    }

    private fun wrapDeg(d: Double) = ((d + 180) % 360 + 360) % 360 - 180

    /**
     * One probe = 9 breath periods at a rate: period 1 is the physiological transient (then the window is cleared),
     * periods 2–3 refill the window, periods 4–9 are measured (median of P × rhythm, four two-breath windows of data).
     * Then the next rate is scheduled for the following inhale start. A signal loss or a return to the app discards the
     * probe's scores. Once converged the pacer moves to the best rate and the search stops; it also stops when the next
     * probe could not end [FINAL_MS] before the session does, at [SessionConfig.rfFallbackBpm] when the curve shows no peak.
     */
    private fun probe(search: RfSearch, t: Double, rate: Double) {
        val period = pacer.periodMs(t)
        val elapsed = t - probeStartMs
        if (!probeReset && elapsed >= period) { estimator.reset(); estimate = null; probeReset = true }
        if (search.finished || elapsed < PROBE_BREATHS * period) return
        val restart = probeScores.isEmpty()   // nothing measured (signal loss, time away): probe the same rate again
        if (!restart) search.report(rate, probeScores.sorted().let { it[it.size / 2] })
        probeScores.clear()
        var next = when {
            restart -> rate
            search.converged -> search.best!!
            else -> search.next(phaseHint = if (lastLeadDeg > 10) 1.0 else if (lastLeadDeg < -10) -1.0 else 0.0)
        }
        val endBy = cfg.durationMs?.let { it - FINAL_MS }
        if (!search.converged && endBy != null && t + period + PROBE_BREATHS * 60_000 / next > endBy) { next = search.conclude() ?: rate }
        probeStartMs = if (next != rate) pacer.changeRate(next, t) else t
        probeReset = search.finished && next == rate   // staying at the rate: no transient to clear
        if (!restart) probeIndex++
    }

    /** The drawn balloon touched the ceiling (decided by the UI, which owns the smoothed height). */
    fun onStar(nowMs: Long) {
        csv.event(nowMs.toDouble(), CsvEvent.STAR)
        summary.addStar()
    }

    fun stop(nowMs: Long): Summary {
        filter.flush().forEach { record(it) }
        csv.close()
        rf?.let { summary.rfBest = it.best; summary.rfConverged = it.converged; summary.rfNoPeak = it.noPeak; summary.rfProbes = it.probes }
        return summary.result(nowMs)
    }

    /**
     * Beats flushed at loss were classified before it and stay usable for the summary; beats that arrive
     * while LOST (e.g. no skin contact but RR still flowing) reach the CSV only. The metric window is
     * cleared on both edges so recovery always warms up again.
     */
    private fun transition(signal: Signal, nowMs: Long) {
        if (signal == Signal.LOST) {
            filter.flush().forEach { record(it, usable = it.accepted) }
            filter.reset()
            csv.event(nowMs.toDouble(), CsvEvent.SIGNAL_LOST)
        } else {
            csv.event(nowMs.toDouble(), CsvEvent.SIGNAL_BACK)
        }
        estimator.reset()
        kalman.reset()
        estimate = null
        summary.breakChain()
        probeScores.clear()
    }

    /** Writes the row and feeds the summary; returns whether the beat may drive the metric. */
    private fun record(c: ClassifiedBeat, usable: Boolean = c.accepted && monitor.state == Signal.OK): Boolean {
        val b = c.beat
        val hrBpm = if (b.rrMs > 0) 60000 / b.rrMs else null
        if (!paused) summary.addBeat(b.rrMs, usable)
        csv.beat(b.tEndMs, b.arrivalMs, b.rrMs, c.accepted, hrBpm, pacer.rateAt(b.tEndMs), pacer.phase(b.tEndMs), estimate?.p2tBpm, estimate?.phaseDeg)
        return usable
    }

    private companion object {
        const val TRACE_MS = 30_000
        const val PROBE_BREATHS = 9   // 1 transient + 2 to refill the two-breath window + 6 scored
        const val SCORE_FROM = 3      // breaths into a probe before scoring starts
        const val FINAL_MS = 120_000L // a probe must end this long before the session does
    }
}
