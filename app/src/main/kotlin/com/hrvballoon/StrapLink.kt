package com.hrvballoon

/** Connection phase, rendered in the scene's top band; transient messages are states, so nothing can overwrite them. */
enum class Link { NO_PERMISSION, BT_OFF, NO_DEVICE, SCANNING, CONNECTING, CONNECTED }

/** The status line before a session. [rrFresh]: beats arrived in the last 4 s; [neverRr]: connected 10 s without any. */
fun linkStatusRes(link: Link, rrFresh: Boolean, neverRr: Boolean): Int = when (link) {
    Link.NO_PERMISSION -> R.string.hud_allow_bt
    Link.BT_OFF -> R.string.hud_bt_off
    Link.NO_DEVICE -> R.string.hud_pair
    Link.SCANNING -> R.string.hud_looking
    Link.CONNECTING -> R.string.hud_connecting
    Link.CONNECTED -> when {
        neverRr -> R.string.hud_check_strap
        !rrFresh -> R.string.hud_waiting
        else -> R.string.hud_ready
    }
}
