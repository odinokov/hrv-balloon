package com.hrvballoon

import android.content.Context

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var rateTenths: Int
        get() = p.getInt("rateTenths", 60)
        set(v) = p.edit().putInt("rateTenths", v).apply()

    var inhale5050: Boolean
        get() = p.getBoolean("inhale5050", false)
        set(v) = p.edit().putBoolean("inhale5050", v).apply()

    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.edit().putBoolean("vibrate", v).apply()

    /** Peak-to-trough HR (bpm) below which the balloon stays on the ground. */
    var floorBpm: Int
        get() = p.getInt("floorBpm", 3)
        set(v) = p.edit().putInt("floorBpm", v).apply()

    var ceilingBpm: Int
        get() = p.getInt("ceilingBpm", 9)
        set(v) = p.edit().putInt("ceilingBpm", v).apply()

    /** Balloon smoothing time constant, tenths of a second. */
    var responseTenths: Int
        get() = p.getInt("responseTenths", 10)
        set(v) = p.edit().putInt("responseTenths", v).apply()

    val responseMs get() = responseTenths * 100.0

    /** Multiply lift by the rhythm (sinusoidality) credit. */
    var shapeGate: Boolean
        get() = p.getBoolean("shapeGate", true)
        set(v) = p.edit().putBoolean("shapeGate", v).apply()

    /** Last converged resonance-search result, tenths of a breath/min; 0 = none. Kept so "Not now" isn't final. */
    var lastRfTenths: Int
        get() = p.getInt("lastRfTenths", 0)
        set(v) = p.edit().putInt("lastRfTenths", v).apply()

    /** One-shot: the next session searches for the resonance rate instead of training at a fixed one. */
    var rfSearch: Boolean
        get() = p.getBoolean("rfSearch", false)
        set(v) = p.edit().putBoolean("rfSearch", v).apply()

    /** Write the per-beat CSV to Download/HRV. Off by default; the session history is kept regardless. */
    var saveCsv: Boolean
        get() = p.getBoolean("saveCsv", false)
        set(v) = p.edit().putBoolean("saveCsv", v).apply()

    var lifetimeStars: Int
        get() = p.getInt("lifetimeStars", 0)
        set(v) = p.edit().putInt("lifetimeStars", v).apply()

    var deviceAddress: String?
        get() = p.getString("deviceAddress", null)
        set(v) = p.edit().putString("deviceAddress", v).apply()

    var deviceName: String?
        get() = p.getString("deviceName", null)
        set(v) = p.edit().putString("deviceName", v).apply()

    val rateBpm get() = rateTenths / 10.0
    val inhaleFraction get() = if (inhale5050) 0.5 else 0.4

    /** Sessions are a fixed 15 minutes: long enough for a resonance search, short enough to do daily. */
    val durationMs = 15 * 60_000L
    val durationLabel = "15"
}
