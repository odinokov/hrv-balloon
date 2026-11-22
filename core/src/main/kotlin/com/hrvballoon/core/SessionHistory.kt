package com.hrvballoon.core

import kotlin.math.roundToInt

/**
 * The session history file. Version 2: a schema line, a header of named columns, one row per session; readers match
 * columns by name, so a new optional column needs no migration and unknown columns are skipped. A session saved again
 * (on leaving the app, then at its end) is appended again and its last row counts. Version 1 is the old positional
 * sessions.txt (four layouts, see [SessionRecord.fromLine]); [parse] reads both, [write] writes v2.
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
        val first = lines.firstOrNull()
        val cutSchema = first != null && first != SCHEMA && SCHEMA.startsWith(first)   // no v1 line can match: it starts with a digit and has commas
        if (first != SCHEMA && !cutSchema) return lines.mapNotNull(SessionRecord::fromLine)   // v1: positional lines
        val defaultHeader = COLUMNS.joinToString(",")
        val cutHeader = !cutSchema && lines.getOrNull(1)?.let { it != defaultHeader && defaultHeader.startsWith(it) } == true
        val noHeader = !cutSchema && lines.getOrNull(1)?.substringBefore(",")?.toLongOrNull() != null   // line 2 is already a data row
        val names = if (cutSchema || cutHeader || noHeader) COLUMNS else lines.getOrNull(1)?.split(",") ?: return emptyList()
        val col = names.withIndex().associate { (i, name) -> name to i }
        return lines.drop(if (cutSchema || noHeader) 1 else 2).mapNotNull { line ->
            val v = line.split(",")
            if (v.size < names.size) return@mapNotNull null
            fun s(name: String) = col[name]?.let { v.getOrNull(it) } ?: ""
            try {
                val duration = s("duration_ms").toLong()
                val rec = SessionRecord(
                    s("start_ms").toLong(), duration, s("rate_bpm").toDouble(), s("mean_p").toDoubleOrNull(), s("mean_shape").toDoubleOrNull(),
                    s("stars").toInt(), s("mean_hr").toDoubleOrNull(), s("rejected_pct").toDoubleOrNull(),
                    s("valid_ms").toLongOrNull() ?: duration, s("rmssd").toDoubleOrNull(), s("sdnn").toDoubleOrNull(),
                    s("beats").toIntOrNull() ?: 0, s("kind").toIntOrNull() ?: SessionRecord.KIND_TRAINING,
                    s("probes").takeIf { it.isNotEmpty() }?.split(";")?.map { it.split(":").let { (r, sc) -> r.toDouble() to sc.toDouble() } } ?: emptyList(),
                    s("rf_estimate_bpm").toDoubleOrNull(),
                )
                val doubles = listOfNotNull(rec.rateBpm, rec.meanP, rec.meanShape, rec.meanHr, rec.rejectedPct, rec.rmssd, rec.sdnn, rec.rfEstimateBpm) +
                    rec.probes.flatMap { listOf(it.first, it.second) }
                rec.takeIf { doubles.all(Double::isFinite) && rec.stars >= 0 && rec.durationMs >= 0 }
            } catch (e: RuntimeException) {
                null
            }
        }.associateBy { it.startMs }.values.toList()   // the last row of a session, in the place of its first
    }

    private fun f(x: Double) = ((x * 1000).roundToInt() / 1000.0).toString()
    private fun o(x: Double?) = x?.let(::f) ?: ""
}
