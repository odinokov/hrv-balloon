package com.hrvballoon.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.hrvballoon.R
import java.util.Locale

/**
 * Builders for the Settings dialog, one idiom throughout: a section header, then rows of label-left / control-right
 * (stepper, segmented choice, ON/OFF switch). Controls register how to re-read their value; [refresh] re-reads all.
 */
class SettingsWidgets(private val context: Context, private val main: Handler) {
    private val dp = context.resources.displayMetrics.density
    private val refreshers = ArrayList<() -> Unit>()

    fun px(v: Int) = (v * dp).toInt()

    fun refresh() = refreshers.forEach { it() }

    fun header(t: String) = TextView(context).apply {
        text = t.uppercase(Locale.ROOT); textSize = 12f; setTextColor(Chrome.YELLOW); letterSpacing = 0.12f; setPadding(0, px(16), 0, px(2))
    }

    fun row(label: String, control: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(52)
        if (control is Switch) control.contentDescription = label   // read as "<label>, switch, on", not "ON, on"
        addView(TextView(context).apply { text = label; textSize = 15f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(control, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    fun small(label: String) = Button(context).apply { text = label; secondary(); minWidth = px(48); minimumWidth = px(48) }

    /** Tap steps once; holding repeats every 120 ms after 400 ms. */
    private fun repeating(label: String, step: () -> Unit) = small(label).apply {
        var repeat: Runnable? = null
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    step(); refresh()
                    repeat = object : Runnable { override fun run() { step(); refresh(); main.postDelayed(this, 120) } }
                    main.postDelayed(repeat!!, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { repeat?.let { main.removeCallbacks(it) }; v.performClick() }
            }
            true
        }
    }

    fun stepper(label: String, value: () -> String, onMinus: () -> Unit, onPlus: () -> Unit): View {
        val v = TextView(context).apply { textSize = 16f; gravity = Gravity.CENTER; minWidth = px(76) }
        refreshers += { v.text = value() }
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(repeating("−", onMinus).apply { contentDescription = context.getString(R.string.a11y_decrease, label) })
            addView(v)
            addView(repeating("+", onPlus).apply { contentDescription = context.getString(R.string.a11y_increase, label) })
        }
    }

    fun choice(options: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): View {
        val buttons = options.mapIndexed { i, o -> small(o).apply { minWidth = px(60); setOnClickListener { onSelect(i); refresh() } } }
        refreshers += {
            buttons.forEachIndexed { i, b ->
                val on = i == selected()
                b.backgroundTintList = ColorStateList.valueOf(if (on) Chrome.YELLOW else 0xFF1E2C66.toInt())
                b.setTextColor(if (on) Chrome.NAVY else Chrome.PALE)
            }
        }
        return LinearLayout(context).apply { buttons.forEach { addView(it) } }
    }

    fun onOff(initial: Boolean, onChange: (Boolean) -> Unit) = Switch(context).apply {
        val checked = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(checked, intArrayOf(Chrome.YELLOW, 0xFF8AA0C0.toInt()))
        trackTintList = ColorStateList(checked, intArrayOf(0xFF8A7420.toInt(), 0xFF3A4660.toInt()))
        text = context.getString(if (initial) R.string.switch_on else R.string.switch_off)
        isChecked = initial
        setOnCheckedChangeListener { _, v -> text = context.getString(if (v) R.string.switch_on else R.string.switch_off); onChange(v) }
    }
}
