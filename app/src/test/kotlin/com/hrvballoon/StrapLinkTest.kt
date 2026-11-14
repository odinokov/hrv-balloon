package com.hrvballoon

import org.junit.Assert.assertEquals
import org.junit.Test

class StrapLinkTest {
    @Test fun statusLineBeforeASessionFollowsTheLink() {
        assertEquals(R.string.hud_allow_bt, linkStatusRes(Link.NO_PERMISSION, rrFresh = false, neverRr = false))
        assertEquals(R.string.hud_bt_off, linkStatusRes(Link.BT_OFF, false, false))
        assertEquals(R.string.hud_pair, linkStatusRes(Link.NO_DEVICE, false, false))
        assertEquals(R.string.hud_looking, linkStatusRes(Link.SCANNING, false, false))
        assertEquals(R.string.hud_connecting, linkStatusRes(Link.CONNECTING, false, false))
        assertEquals(R.string.hud_check_strap, linkStatusRes(Link.CONNECTED, rrFresh = false, neverRr = true))
        assertEquals(R.string.hud_waiting, linkStatusRes(Link.CONNECTED, rrFresh = false, neverRr = false))
        assertEquals(R.string.hud_ready, linkStatusRes(Link.CONNECTED, rrFresh = true, neverRr = false))
    }
}
