package com.hrvballoon

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hrvballoon.core.stepRateTenths
import com.hrvballoon.ui.SettingsWidgets
import java.util.Locale

/** The Settings dialog; [actions] carries what reaches beyond the settings themselves. */
class SettingsDialog(private val activity: Activity, private val settings: Settings, private val main: Handler, private val actions: Actions) {
    interface Actions {
        fun forgetStrap()
        fun rescan()
        fun showHelp()
        fun exportHistory()
        /** Back, a tap outside and Done all close the dialog, and each may have changed settings. */
        fun closed()
    }

    private fun str(id: Int) = activity.getString(id)
    private fun fmt(id: Int, vararg args: Any?) = String.format(Locale.ROOT, activity.getString(id), *args)

    fun show() {
        val ui = SettingsWidgets(activity, main)
        var dialog: AlertDialog? = null
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(20), 0, ui.px(20), ui.px(8))
            addView(ui.header(str(R.string.settings_breathing)))
            addView(ui.row(str(R.string.settings_rate), ui.stepper(str(R.string.settings_rate_short), { String.format(Locale.ROOT, "%.1f", settings.rateBpm) },
                { settings.rateTenths = stepRateTenths(settings.rateTenths, -1) }, { settings.rateTenths = stepRateTenths(settings.rateTenths, 1) })))
            if (settings.lastRfTenths > 0) {
                addView(ui.row(fmt(if (settings.lastRfNoPeak) R.string.settings_no_peak else R.string.settings_resonance_found, settings.lastRfTenths / 10.0),
                    ui.small(str(R.string.use)).apply { setOnClickListener { settings.rateTenths = settings.lastRfTenths; ui.refresh() } }))
            }
            addView(ui.row(str(R.string.settings_inhale_exhale), ui.choice(listOf("40:60", "50:50"), { if (settings.inhale5050) 1 else 0 }) { settings.inhale5050 = it == 1 }))
            addView(ui.header(str(R.string.settings_session)))
            addView(ui.row(str(R.string.settings_vibration), ui.onOff(settings.vibrate) { settings.vibrate = it }))
            addView(ui.row(str(R.string.settings_find_rate), ui.onOff(settings.rfSearch) { settings.rfSearch = it }))
            addView(ui.header(str(R.string.settings_balloon)))
            addView(ui.row(str(R.string.settings_lift), ui.stepper(str(R.string.settings_lift_short), { "${settings.floorBpm}" },
                { settings.stepFloor(-1) }, { settings.stepFloor(1) })))
            addView(ui.row(str(R.string.settings_ceiling), ui.stepper(str(R.string.settings_ceiling_short), { "${settings.ceilingBpm}" },
                { settings.stepCeiling(-1) }, { settings.stepCeiling(1) })))
            addView(ui.header(str(R.string.settings_data)))
            addView(ui.row(str(R.string.settings_save_csv), ui.onOff(settings.saveCsv) { settings.saveCsv = it }))
            addView(ui.row(str(R.string.settings_history), ui.small(str(R.string.export)).apply { setOnClickListener { actions.exportHistory() } }))
            val forget = ui.small(str(R.string.forget)).apply {
                isEnabled = settings.deviceAddress != null
                setOnClickListener {
                    AlertDialog.Builder(activity).setMessage(activity.getString(R.string.forget_q, settings.deviceName ?: str(R.string.this_strap)))
                        .setPositiveButton(R.string.forget) { _, _ -> dialog?.dismiss(); actions.forgetStrap() }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            }
            // Rescan keeps the saved strap: picking another replaces it, Cancel leaves the link as it was.
            val rescan = ui.small(str(R.string.rescan)).apply { setOnClickListener { dialog?.dismiss(); actions.rescan() } }
            addView(ui.row(settings.deviceName ?: str(R.string.no_strap), LinearLayout(activity).apply { addView(rescan); addView(forget) }))
            addView(ui.row(str(R.string.help_title), ui.small(str(R.string.help)).apply { setOnClickListener { actions.showHelp() } }))
        }
        ui.refresh()
        dialog = AlertDialog.Builder(activity).setTitle(R.string.settings)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener { actions.closed() }
            .show()
    }
}
