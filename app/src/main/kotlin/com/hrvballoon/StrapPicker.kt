package com.hrvballoon

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * The strap list: scans on open and on Rescan, lists heart-rate straps as [found] reports them, and explains an empty
 * scan. [onPick] runs after the scan has stopped; [onClosed] runs on every dismissal, picked or not.
 */
class StrapPicker(
    private val activity: Activity,
    private val main: Handler,
    private val startScan: () -> Unit,
    private val stopScan: () -> Unit,
    private val onPick: (address: String, name: String?) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val devices = LinkedHashMap<String, String?>()
    private val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, mutableListOf<String>())
    private val hint = TextView(activity).apply {
        val dp = activity.resources.displayMetrics.density
        textSize = 14f
        setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
    }
    private val timeout = Runnable { if (devices.isEmpty()) hint.setText(R.string.scan_none) }
    private var lastScan = 0L

    fun show() {
        var dialog: AlertDialog? = null
        val list = ListView(activity).apply { this.adapter = this@StrapPicker.adapter }
        list.setOnItemClickListener { _, _, i, _ ->
            val address = devices.keys.elementAt(i)
            stopScan()   // before the dialog's dismiss arrives: no connect under a running scan
            dialog?.dismiss()
            onPick(address, devices[address])
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pick_title)
            .setView(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(hint); addView(list) })
            .setNeutralButton(R.string.rescan, null)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                stopScan()
                main.removeCallbacks(timeout)
                onClosed()
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { scan() }
        scan()
    }

    fun found(address: String, name: String?) {
        if (address in devices) return
        devices[address] = name
        adapter.add("${name ?: activity.getString(R.string.unnamed_strap)}\n$address")
        hint.setText(R.string.tap_your_strap)
    }

    fun progress(seen: Int) {
        if (devices.isEmpty()) hint.text = activity.resources.getQuantityString(R.plurals.scanning_seen, seen, seen)
    }

    fun failed(code: Int) {
        hint.text = activity.getString(R.string.scan_failed, code)
    }

    private fun scan() {
        hint.setText(R.string.scanning)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, SCAN_TIMEOUT_MS)
        // Android refuses more than five scan starts in 30 s; a quick second tap just keeps the running scan.
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < 6_000) return
        lastScan = now
        startScan()
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 20_000L
    }
}
