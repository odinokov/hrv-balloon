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
}
