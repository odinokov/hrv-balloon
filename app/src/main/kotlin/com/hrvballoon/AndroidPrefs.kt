package com.hrvballoon

import android.content.Context

class AndroidPrefs(context: Context) : Prefs {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun getInt(key: String, def: Int) = p.getInt(key, def)
    override fun getBoolean(key: String, def: Boolean) = p.getBoolean(key, def)
    override fun getString(key: String, def: String?) = p.getString(key, def)
    override fun put(key: String, value: Any?) {
        p.edit().apply {
            when (value) {
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                else -> putString(key, value as String?)
            }
        }.apply()
    }
}
