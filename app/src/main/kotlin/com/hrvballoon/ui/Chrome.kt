package com.hrvballoon.ui

import android.content.res.ColorStateList
import android.widget.Button

/** Colours of the Android widgets around the pixel scenes (the scenes keep their own palette). */
object Chrome {
    const val NAVY = 0xFF0B1030.toInt()
    const val PALE = 0xFFDCE6F5.toInt()
    const val YELLOW = 0xFFFFD83B.toInt()
    const val RED = 0xFFD63A3A.toInt()
    const val SLATE = 0xFF2E3A5C.toInt()
    const val SLATE_TEXT = 0xFF6B7A99.toInt()
}

/** Navy secondary button; slate when disabled. */
fun Button.secondary() {
    val states = arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf())
    backgroundTintList = ColorStateList(states, intArrayOf(0xFF1E2C66.toInt(), 0xFF141C4A.toInt()))
    setTextColor(ColorStateList(states, intArrayOf(Chrome.PALE, Chrome.SLATE_TEXT)))
}
