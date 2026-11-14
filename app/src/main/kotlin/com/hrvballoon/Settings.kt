package com.hrvballoon

/** Key-value store behind [Settings]; [AndroidPrefs] on the phone, a map in tests. */
interface Prefs {
    fun getInt(key: String, def: Int): Int
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getString(key: String, def: String?): String?
    fun put(key: String, value: Any?)
}

class Settings(private val p: Prefs) {

    var rateTenths: Int
        get() = p.getInt("rateTenths", 55)
        set(v) = p.put("rateTenths", v)

    var inhale5050: Boolean
        get() = p.getBoolean("inhale5050", true)
        set(v) = p.put("inhale5050", v)

    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.put("vibrate", v)

    /** Peak-to-trough HR (bpm) below which the balloon stays on the ground. */
    var floorBpm: Int
        get() = p.getInt("floorBpm", 1)
        set(v) = p.put("floorBpm", v)

    var ceilingBpm: Int
        get() = p.getInt("ceilingBpm", 6)
        set(v) = p.put("ceilingBpm", v)

    /** Balloon smoothing time constant, tenths of a second. */
    var responseTenths: Int
        get() = p.getInt("responseTenths", 10)
        set(v) = p.put("responseTenths", v)

    val responseMs get() = responseTenths * 100.0

    /** Multiply lift by the rhythm (sinusoidality) credit. */
    var shapeGate: Boolean
        get() = p.getBoolean("shapeGate", true)
        set(v) = p.put("shapeGate", v)

    /** Last converged resonance-search result, tenths of a breath/min; 0 = none. Kept so "Not now" isn't final. */
    var lastRfTenths: Int
        get() = p.getInt("lastRfTenths", 0)
        set(v) = p.put("lastRfTenths", v)

    /** One-shot: the next session searches for the resonance rate instead of training at a fixed one. */
    var rfSearch: Boolean
        get() = p.getBoolean("rfSearch", false)
        set(v) = p.put("rfSearch", v)

    /** Write the per-beat CSV to Download/HRV. Off by default; the session history is kept regardless. */
    var saveCsv: Boolean
        get() = p.getBoolean("saveCsv", false)
        set(v) = p.put("saveCsv", v)

    var lifetimeStars: Int
        get() = p.getInt("lifetimeStars", 0)
        set(v) = p.put("lifetimeStars", v)

    var deviceAddress: String?
        get() = p.getString("deviceAddress", null)
        set(v) = p.put("deviceAddress", v)

    var deviceName: String?
        get() = p.getString("deviceName", null)
        set(v) = p.put("deviceName", v)

    val rateBpm get() = rateTenths / 10.0
    val inhaleFraction get() = if (inhale5050) 0.5 else 0.4

    /** Sessions are a fixed 15 minutes: long enough for a resonance search, short enough to do daily. */
    val durationMs = 15 * 60_000L
    val durationLabel = "15"
}
