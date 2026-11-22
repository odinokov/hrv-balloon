package com.hrvballoon.core

import kotlin.math.roundToInt

/**
 * The session history file. Version 3: a schema line, a header of named columns, one row per session; readers match
 * columns by name, so a new optional column needs no migration and unknown columns are skipped. A session saved again
 * (on leaving the app, then at its end) is appended again and its last row counts. Version 2 lacks `coherence`; version 1
 * is the old positional sessions.txt (four layouts, see [SessionRecord.fromLine]). [parse] reads all three, [write] writes v3.
 */
object SessionHistory {
    const val SCHEMA_V2 = "# hrv-sessions v2"
    const val SCHEMA = "# hrv-sessions v3"
    val COLUMNS_V2 = listOf(
        "start_ms", "duration_ms", "rate_bpm", "mean_p", "mean_shape", "stars", "mean_hr", "rejected_pct",
        "valid_ms", "rmssd", "sdnn", "beats", "kind", "probes", "rf_estimate_bpm",
    )
    val COLUMNS = COLUMNS_V2 + "coherence"

    fun header() = SCHEMA + "\n" + COLUMNS.joinToString(",") + "\n"

    /** [v2]: a row of the 15-column layout, for appending to a version-2 file. */
    fun row(r: SessionRecord, v2: Boolean = false) = listOf(
        r.startMs, r.durationMs, f(r.rateBpm), o(r.meanP), o(r.meanShape), r.stars, o(r.meanHr), o(r.rejectedPct),
        r.validMs, o(r.rmssd), o(r.sdnn), r.beats, r.kind, r.probes.joinToString(";") { "${f(it.first)}:${f(it.second)}" },
        o(r.rfEstimateBpm), o(r.coherence),
    ).let { if (v2) it.dropLast(1) else it }.joinToString(",")

    fun write(records: List<SessionRecord>) = header() + records.joinToString("") { row(it) + "\n" }

    /** Column names and the index of the first data line; null for version 1 (positional lines). */
    private class Layout(val names: List<String>, val dataStart: Int)

    private fun layout(lines: List<String>): Layout? {
        val first = lines.firstOrNull() ?: return null
        val second = lines.getOrNull(1)
        val known = mapOf(SCHEMA_V2 to COLUMNS_V2, SCHEMA to COLUMNS)[first]
        if (known == null) {
            if (!SCHEMA.startsWith(first)) return null   // cut schema line (no v1 line can match: it starts with a digit and has commas)
            // the version is lost: the first line of full width tells (a cut row before it, as in 2.7, is skipped)
            val v2 = lines.drop(1).map { it.split(",").size }.firstOrNull { it >= COLUMNS_V2.size } == COLUMNS_V2.size
            return Layout(if (v2) COLUMNS_V2 else COLUMNS, 1)
        }
        val default = known.joinToString(",")
        val cutHeader = second != null && second != default && default.startsWith(second)
        val noHeader = second?.substringBefore(",")?.toLongOrNull() != null   // line 2 is already a data row
        return if (cutHeader || noHeader) Layout(known, if (noHeader) 1 else 2) else Layout(second?.split(",") ?: known, 2)
    }

    private fun lines(text: String) = text.lines().filter { it.isNotBlank() }

    fun isV2(text: String) = layout(lines(text))?.names == COLUMNS_V2

    /** A version-2 file as version 3: the new header, every data line kept, a full-width row cut to 15 cells and gaining an empty `coherence`; null when [text] is not version 2. */
    fun toV3(text: String): String? {
        val lines = lines(text)
        val l = layout(lines)?.takeIf { it.names == COLUMNS_V2 } ?: return null
        return header() + lines.drop(l.dataStart).joinToString("") { (it.split(",").let { v -> if (v.size >= COLUMNS_V2.size) v.take(COLUMNS_V2.size).joinToString(",") + "," else it }) + "\n" }
    }

    fun parse(text: String): List<SessionRecord> {
        val lines = lines(text)
        val l = layout(lines) ?: return lines.mapNotNull(SessionRecord::fromLine)   // v1: positional lines
        val col = l.names.withIndex().associate { (i, name) -> name to i }
        return lines.drop(l.dataStart).mapNotNull { line ->
            val v = line.split(",")
            if (v.size < l.names.size) return@mapNotNull null
            fun s(name: String) = col[name]?.let { v.getOrNull(it) } ?: ""
            try {
                val duration = s("duration_ms").toLong()
                val rec = SessionRecord(
                    s("start_ms").toLong(), duration, s("rate_bpm").toDouble(), s("mean_p").toDoubleOrNull(), s("mean_shape").toDoubleOrNull(),
                    s("stars").toInt(), s("mean_hr").toDoubleOrNull(), s("rejected_pct").toDoubleOrNull(),
                    s("valid_ms").toLongOrNull() ?: duration, s("rmssd").toDoubleOrNull(), s("sdnn").toDoubleOrNull(),
                    s("beats").toIntOrNull() ?: 0, s("kind").toIntOrNull() ?: SessionRecord.KIND_TRAINING,
                    s("probes").takeIf { it.isNotEmpty() }?.split(";")?.map { it.split(":").let { (r, sc) -> r.toDouble() to sc.toDouble() } } ?: emptyList(),
                    s("rf_estimate_bpm").toDoubleOrNull(), s("coherence").toDoubleOrNull(),
                )
                val doubles = listOfNotNull(rec.rateBpm, rec.meanP, rec.meanShape, rec.meanHr, rec.rejectedPct, rec.rmssd, rec.sdnn, rec.rfEstimateBpm, rec.coherence) +
                    rec.probes.flatMap { listOf(it.first, it.second) }
                rec.takeIf { doubles.all(Double::isFinite) && rec.stars >= 0 && rec.durationMs >= 0 }
            } catch (e: RuntimeException) {
                null
            }
        }.associateBy { it.startMs }.values.toList()   // the last row of a session, in the place of its first
    }

    internal fun f(x: Double) = ((x * 1000).roundToInt() / 1000.0).toString()
    private fun o(x: Double?) = x?.let(::f) ?: ""
}
