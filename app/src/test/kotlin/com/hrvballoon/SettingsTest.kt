package com.hrvballoon

import org.junit.Assert.assertEquals
import org.junit.Test

class MapPrefs : Prefs {
    val map = HashMap<String, Any?>()
    override fun getInt(key: String, def: Int) = map[key] as Int? ?: def
    override fun getBoolean(key: String, def: Boolean) = map[key] as Boolean? ?: def
    override fun getString(key: String, def: String?) = if (key in map) map[key] as String? else def
    override fun put(key: String, value: Any?) { map[key] = value }
}

class SettingsTest {
    @Test fun defaultsAre55BreathsWithEqualInhaleAndExhale() {
        val s = Settings(MapPrefs())
        assertEquals(5.5, s.rateBpm, 1e-9)
        assertEquals(0.5, s.inhaleFraction, 1e-9)
    }

    @Test fun storedChoicesOverrideTheDefaults() {
        val s = Settings(MapPrefs())
        s.rateTenths = 60
        s.inhale5050 = false
        assertEquals(6.0, s.rateBpm, 1e-9)
        assertEquals(0.4, s.inhaleFraction, 1e-9)
    }

    @Test fun liftThresholdAndSkyCeilingKeepFiveApartWithinTheirBounds() {
        val s = Settings(MapPrefs())   // defaults 1 and 6
        s.stepFloor(+1)
        assertEquals(1, s.floorBpm)    // 2 would leave the ceiling only 4 above
        s.stepCeiling(-1)
        assertEquals(6, s.ceilingBpm)
        s.stepFloor(-1); s.stepFloor(-1)
        assertEquals(0, s.floorBpm)
        repeat(40) { s.stepCeiling(+1) }
        assertEquals(40, s.ceilingBpm)
        repeat(40) { s.stepFloor(+1) }
        assertEquals(35, s.floorBpm)
    }
}
