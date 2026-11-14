package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalMonitorTest {
    @Test fun noRrForFourSeconds() {
        val m = SignalMonitor(0)
        assertNull(m.tick(4000))
        assertEquals(Signal.LOST, m.tick(4001))
    }

    @Test fun hrOnlyNotificationsAreNotRr() {
        val m = SignalMonitor(0)
        for (t in 1000L..4000L step 1000) assertNull(m.onNotification(t, Contact.NOT_SUPPORTED, hasRr = false))
        assertEquals(Signal.LOST, m.tick(4500))
    }

    @Test fun ectopicPairAtLowHrNeverTrips() {
        val m = SignalMonitor(0)
        var t = 0L
        for (k in 1..20) {
            t += 1330
            assertNull(m.onNotification(t, Contact.DETECTED, hasRr = true))
            assertNull(m.tick(t + 1000))
            if (k == 10 || k == 11) assertNull(m.onClassified(false, t)) else assertNull(m.onClassified(true, t))
        }
        assertEquals(Signal.OK, m.state)
    }

    @Test fun rejectRun() {
        val m = SignalMonitor(0)
        m.onNotification(100, Contact.DETECTED, true)
        repeat(4) { assertNull(m.onClassified(false, 100)) }
        assertEquals(Signal.LOST, m.onClassified(false, 100))

        val n = SignalMonitor(0)
        n.onNotification(100, Contact.DETECTED, true)
        repeat(4) { assertNull(n.onClassified(false, 100)) }
        assertNull(n.onClassified(true, 100))
        repeat(4) { assertNull(n.onClassified(false, 100)) }
        assertEquals(Signal.OK, n.state)
    }

    @Test fun contactFlags() {
        assertEquals(Signal.LOST, SignalMonitor(0).onNotification(1000, Contact.NOT_DETECTED, true))
        val m = SignalMonitor(0)
        assertNull(m.onNotification(1000, Contact.NOT_SUPPORTED, true))
        assertNull(m.onNotification(2000, Contact.DETECTED, true))
        assertEquals(Signal.OK, m.state)
    }

    @Test fun timelineGap() {
        val m = SignalMonitor(0)
        assertNull(m.onGap(3000.0, 1000))
        assertEquals(Signal.LOST, m.onGap(3000.1, 1000))
    }

    @Test fun linkDownAndRecovery() {
        val m = SignalMonitor(0)
        assertEquals(Signal.LOST, m.onLink(false, 1000))
        assertNull(m.onLink(true, 2000))
        assertNull(m.onNotification(2500, Contact.DETECTED, true))
        assertEquals(Signal.OK, m.onClassified(true, 2500))
    }

    @Test fun reasonForLossIsReported() {
        assertEquals(LostReason.LINK, SignalMonitor(0).also { it.onLink(false, 100) }.reason)
        assertEquals(LostReason.CONTACT, SignalMonitor(0).also { it.onNotification(100, Contact.NOT_DETECTED, true) }.reason)
        assertEquals(LostReason.STALE, SignalMonitor(0).also { it.tick(5000) }.reason)
        assertEquals(LostReason.GAP, SignalMonitor(0).also { it.onGap(3500.0, 100) }.reason)
        assertEquals(LostReason.REJECTS, SignalMonitor(0).also { m -> m.onNotification(100, Contact.DETECTED, true); repeat(5) { m.onClassified(false, 100) } }.reason)
        assertNull(SignalMonitor(0).reason)
    }

    @Test fun recoveryNeedsFreshRr() {
        val m = SignalMonitor(0)
        m.onLink(false, 1000)
        m.onLink(true, 2000)
        assertNull(m.onClassified(true, 9000)) // accepted beat, but RR is stale → still LOST
        assertEquals(Signal.OK, m.onNotification(9100, Contact.DETECTED, true)) // fresh RR completes recovery
        assertNull(m.onClassified(true, 9100))
    }
}
