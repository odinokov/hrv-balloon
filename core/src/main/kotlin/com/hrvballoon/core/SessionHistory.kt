package com.hrvballoon.core

import kotlin.math.roundToInt

/**
 * The session history file. Version 2: a schema line, a header of named columns, one row per session; readers match
 * columns by name, so a new optional column needs no migration and unknown columns are skipped. Version 1 is the
 * old positional sessions.txt (four layouts, see [SessionRecord.fromLine]); [parse] reads both, [write] writes v2.
 */
object SessionHistory {
    const val SCHEMA = "# hrv-sessions v2"
    val COLUMNS = listOf(
        "start_ms", "duration_ms", "rate_bpm", "mean_p", "mean_shape", "stars", "mean_hr", "rejected_pct",
        "valid_ms", "rmssd", "sdnn", "beats", "kind", "probes", "rf_estimate_bpm",
    )

    fun header() = SCHEMA + "\n" + COLUMNS.joinToString(",") + "\n"

    fun row(r: SessionRecord) = listOf(
        r.startMs, r.durationMs, f(r.rateBpm), o(r.meanP), o(r.meanShape), r.stars, o(r.meanHr), o(r.rejectedPct),
        r.validMs, o(r.rmssd), o(r.sdnn), r.beats, r.kind, r.probes.joinToString(";") { "${f(it.first)}:${f(it.second)}" },
        o(r.rfEstimateBpm),
    ).joinToString(",")

    fun write(records: List<SessionRecord>) = header() + records.joinToString("") { row(it) + "\n" }

    fun parse(text: String): List<SessionRecord> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != SCHEMA) return lines.mapNotNull(SessionRecord::fromLine)   // v1: positional lines
        val col = lines.getOrNull(1)?.split(",")?.withIndex()?.associate { (i, name) -> name to i } ?: return emptyList()
        return lines.drop(2).mapNotNull { line ->
            val v = line.split(",")
            fun s(name: String) = col[name]?.let { v.getOrNull(it) } ?: ""
            try {
                val duration = s("duration_ms").toLong()
                SessionRecord(
                    s("start_ms").toLong(), duration, s("rate_bpm").toDouble(), s("mean_p").toDoubleOrNull(), s("mean_shape").toDoubleOrNull(),
                    s("stars").toInt(), s("mean_hr").toDoubleOrNull(), s("rejected_pct").toDoubleOrNull(),
                    s("valid_ms").toLongOrNull() ?: duration, s("rmssd").toDoubleOrNull(), s("sdnn").toDoubleOrNull(),
                    s("beats").toIntOrNull() ?: 0, s("kind").toIntOrNull() ?: SessionRecord.KIND_TRAINING,
                    s("probes").takeIf { it.isNotEmpty() }?.split(";")?.map { it.split(":").let { (r, sc) -> r.toDouble() to sc.toDouble() } } ?: emptyList(),
                    s("rf_estimate_bpm").toDoubleOrNull(),
                )
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    private fun f(x: Double) = ((x * 1000).roundToInt() / 1000.0).toString()
    private fun o(x: Double?) = x?.let(::f) ?: ""
}
