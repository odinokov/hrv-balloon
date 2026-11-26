package com.hrvballoon.core

import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale

enum class CsvEvent(val csv: String) {
    DISCONNECT("disconnect"), RECONNECT("reconnect"), SIGNAL_LOST("signal_lost"), SIGNAL_BACK("signal_back"), STAR("star"),
    PAUSE("pause"), RESUME("resume"), WARMUP_START("warmup_start"), WARMUP_END("warmup_end"), GAP("gap")
}

class SessionMeta(
    val appVersion: String, val deviceAddress: String, val deviceName: String?, val startIso: String,
    val rateBpm: Double, val inhaleFraction: Double, val durationSetting: String, val floorBpm: Double, val ceilingBpm: Double,
    /** Height-and-sex starting estimate of a resonance search, when one was given. */
    val rfEstimateBpm: Double? = null,
)

/** Device names are free text set by the device; keep the header awk/cut-safe. */
fun sanitizeName(name: String?): String =
    name?.takeIf { it.isNotEmpty() }?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "unknown"

/** One CSV per session, flushed per row so a crash loses nothing. Numbers are Locale.ROOT. After a write fails (storage full) every call is a no-op and [failed] is set. */
class SessionCsvWriter(out: OutputStream) {
    private val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
    var failed = false
        private set

    private inline fun guarded(block: () -> Unit) {
        if (failed) return
        try { block() } catch (_: IOException) {
            failed = true
            try { w.close() } catch (_: IOException) {}
        }
    }

    fun writeHeader(meta: SessionMeta) = guarded {
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
        ) + listOfNotNull(meta.rfEstimateBpm?.let { "rf_estimate_bpm" to f1(it) })
        for ((k, v) in fields) w.write("# $k=$v\n")
        w.write(COLUMNS)
        w.write("\n")
        w.flush()
    }

    fun beat(
        tMs: Double, arrivalMs: Long, rrMs: Double, accepted: Boolean, hrBpm: Double?, rateBpm: Double,
        pacerPhase: Double, p2tBpm: Double?, phaseDeg: Double?, citiM: Int? = null,
    ) = guarded {
        w.write(
            "beat,${f1(tMs)},$arrivalMs,${f1(rrMs)},${if (accepted) 1 else 0},${hrBpm?.let(::f1) ?: ""},${f1(rateBpm)}," +
                "${f3(pacerPhase)},${p2tBpm?.let(::f2) ?: ""},${phaseDeg?.let(::f1) ?: ""},,${citiM ?: ""}\n",
        )
        w.flush()
    }

    fun event(tMs: Double, event: CsvEvent) = guarded {
        w.write("event,${f1(tMs)},,,,,,,,,${event.csv},\n")
        w.flush()
    }

    fun close() = guarded { w.close() }

    private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
    private fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)
    private fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)

    companion object {
        const val COLUMNS = "type,t_ms,arrival_ms,rr_ms,accepted,hr_bpm,rate_bpm,pacer_phase,p2t_bpm,phase_deg,event,citi_m"
    }
}
