package com.hrvballoon

import android.app.Activity
import android.view.View
import android.view.WindowInsets

/**
 * Draws behind the system bars on every Android version (enforced from targetSdk 35) and pads [root] by the bars and
 * the camera cutout, so nothing sits under them; the window background fills the space behind the bars.
 */
fun Activity.edgeToEdge(root: View) {
    window.setDecorFitsSystemWindows(false)
    root.setOnApplyWindowInsetsListener { v, insets ->
        val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsets.CONSUMED
    }
}
