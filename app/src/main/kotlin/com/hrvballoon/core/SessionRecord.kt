package com.hrvballoon.core

import kotlin.math.roundToInt

/** One finished session, as kept in the local history (one comma-separated line, Locale-independent). */
class SessionRecord(
    val startMs: Long, val durationMs: Long, val rateBpm: Double, val meanP: Double?, val meanShape: Double?,
    val stars: Int, val meanHr: Double?, val rejectedPct: Double?,
    /** Time with a live estimate; sessions below [MIN_RANKED_MS] stay in history but are not ranked. */
    val validMs: Long, val rmssd: Double?, val sdnn: Double?, val beats: Int,
    /** [KIND_TRAINING], [KIND_RF_SEARCH] (converged; [rateBpm] is the rate found) or [KIND_RF_INCOMPLETE]. */
    val kind: Int = KIND_TRAINING,
    /** Search probes as (rate, score), in probe order. */
    val probes: List<Pair<Double, Double>> = emptyList(),
) {
    /** Effective amplitude in bpm, retained for history and resonance-search comparisons. */
    val score get() = (meanP ?: 0.0) * (meanShape ?: 0.0)

    /** Searches mix rates, so they are kept but never ranked. */
    val ranked get() = kind == KIND_TRAINING && validMs >= MIN_RANKED_MS

    val isSearch get() = kind != KIND_TRAINING

    fun toLine() = listOf(
        startMs, durationMs, f(rateBpm), meanP?.let(::f) ?: "", meanShape?.let(::f) ?: "", stars,
        meanHr?.let(::f) ?: "", rejectedPct?.let(::f) ?: "", validMs, rmssd?.let(::f) ?: "", sdnn?.let(::f) ?: "", beats, kind,
        probes.joinToString(";") { "${f(it.first)}:${f(it.second)}" },
    ).joinToString(",")

    private fun f(x: Double) = ((x * 1000).roundToInt() / 1000.0).toString()

    companion object {
        const val MIN_RANKED_MS = 120_000L
        const val KIND_TRAINING = 0
        const val KIND_RF_SEARCH = 1
        const val KIND_RF_INCOMPLETE = 2

        /** Accepts the current 14-field line and the older 13-, 12- and 8-field ones (8 = fully valid, no RMSSD/SDNN/beats). */
        fun fromLine(line: String): SessionRecord? {
            val p = line.split(",")
            if (p.size !in setOf(8, 12, 13, 14)) return null
            return try {
                val duration = p[1].toLong()
                val probes = if (p.size == 14 && p[13].isNotEmpty()) p[13].split(";").map { it.split(":").let { (r, s) -> r.toDouble() to s.toDouble() } } else emptyList()
                SessionRecord(
                    p[0].toLong(), duration, p[2].toDouble(), p[3].toDoubleOrNull(), p[4].toDoubleOrNull(),
                    p[5].toInt(), p[6].toDoubleOrNull(), p[7].toDoubleOrNull(),
                    if (p.size >= 12) p[8].toLong() else duration,
                    if (p.size >= 12) p[9].toDoubleOrNull() else null,
                    if (p.size >= 12) p[10].toDoubleOrNull() else null,
                    if (p.size >= 12) p[11].toInt() else 0,
                    if (p.size >= 13) p[12].toInt() else KIND_TRAINING,
                    probes,
                )
            } catch (e: RuntimeException) {
                null
            }
        }
    }
}

/** Ranked sessions only: most stars first, newest first when star counts tie. */
fun rankSessions(records: List<SessionRecord>) = records.filter { it.ranked }
    .sortedWith(compareByDescending<SessionRecord> { it.stars }.thenByDescending { it.startMs })
