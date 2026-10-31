package com.hrvballoon.core

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale

enum class CsvEvent(val csv: String) {
    DISCONNECT("disconnect"), RECONNECT("reconnect"), SIGNAL_LOST("signal_lost"), SIGNAL_BACK("signal_back"), STAR("star"),
    PAUSE("pause"), RESUME("resume")
}

class SessionMeta(
    val appVersion: String, val deviceAddress: String, val deviceName: String?, val startIso: String,
    val rateBpm: Double, val inhaleFraction: Double, val durationSetting: String, val floorBpm: Double, val ceilingBpm: Double,
)

/** Device names are free text set by the device; keep the header awk/cut-safe. */
fun sanitizeName(name: String?): String =
    name?.takeIf { it.isNotEmpty() }?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "unknown"

/** One CSV per session, flushed per row so a crash loses nothing. Numbers are Locale.ROOT. */
class SessionCsvWriter(out: OutputStream) {
    private val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))

    fun writeHeader(meta: SessionMeta) {
        val fields = listOf(
            "app_version" to meta.appVersion,
            "device_address" to meta.deviceAddress,
            "device_name" to sanitizeName(meta.deviceName),
            "start" to meta.startIso,
            "rate_bpm" to f1(meta.rateBpm),
            "inhale_fraction" to f2(meta.inhaleFraction),
            "duration" to meta.durationSetting,
            "floor_bpm" to f1(meta.floorBpm),
            "ceiling_bpm" to f1(meta.ceilingBpm),
        )
        for ((k, v) in fields) w.write("# $k=$v\n")
        w.write(COLUMNS)
        w.write("\n")
        w.flush()
    }

    fun beat(
        tMs: Double, arrivalMs: Long, rrMs: Double, accepted: Boolean, hrBpm: Double?, rateBpm: Double,
        pacerPhase: Double, p2tBpm: Double?, phaseDeg: Double?,
    ) {
        w.write(
            "beat,${f1(tMs)},$arrivalMs,${f1(rrMs)},${if (accepted) 1 else 0},${hrBpm?.let(::f1) ?: ""},${f1(rateBpm)}," +
                "${f3(pacerPhase)},${p2tBpm?.let(::f2) ?: ""},${phaseDeg?.let(::f1) ?: ""},\n",
        )
        w.flush()
    }

    fun event(tMs: Double, event: CsvEvent) {
        w.write("event,${f1(tMs)},,,,,,,,,${event.csv}\n")
        w.flush()
    }

    fun close() = w.close()

    private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
    private fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)
    private fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)

    companion object {
        const val COLUMNS = "type,t_ms,arrival_ms,rr_ms,accepted,hr_bpm,rate_bpm,pacer_phase,p2t_bpm,phase_deg,event"
    }
}
