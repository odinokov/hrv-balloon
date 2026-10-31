package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class PacerTest {
    private val pacer = Pacer(rateBpm = 6.0, inhaleFraction = 0.4)

    @Test fun period() = assertEquals(10000.0, pacer.periodMs, 1e-9)

    @Test fun phaseWraps() {
        assertEquals(0.0, pacer.phase(0.0), 1e-9)
        assertEquals(0.25, pacer.phase(2500.0), 1e-9)
        assertEquals(0.0, pacer.phase(10000.0), 1e-9)
        assertEquals(0.1, pacer.phase(21000.0), 1e-9)
    }

    @Test fun inhaleBoundary() {
        assertTrue(pacer.isInhale(3999.0))
        assertFalse(pacer.isInhale(4000.0))
        assertTrue(pacer.isInhale(10000.0))
    }

    @Test fun levelIsCosineEased() {
        assertEquals(0.0, pacer.level(0.0), 1e-9)
        assertEquals(0.5, pacer.level(2000.0), 1e-9)
        assertEquals(1.0, pacer.level(4000.0), 1e-9)
        assertEquals(0.5, pacer.level(7000.0), 1e-9)
        assertEquals(0.0, pacer.level(10000.0), 1e-9)
    }

    @Test fun balloonTargetClamps() {
        assertEquals(1.0, balloonTarget(15.0, 0.0, 15.0), 1e-9)
        assertEquals(1.0, balloonTarget(30.0, 0.0, 15.0), 1e-9)
        assertEquals(0.5, balloonTarget(7.5, 0.0, 15.0), 1e-9)
        assertEquals(0.0, balloonTarget(null, 0.0, 15.0), 1e-9)
    }

    @Test fun balloonTargetLiftThreshold() {
        assertEquals(0.0, balloonTarget(3.0, 3.0, 20.0), 1e-9)
        assertEquals(0.0, balloonTarget(1.0, 3.0, 20.0), 1e-9)
        assertEquals(0.5, balloonTarget(11.5, 3.0, 20.0), 1e-9)
        assertEquals(1.0, balloonTarget(20.0, 3.0, 20.0), 1e-9)
    }

    @Test fun shapeGateMapsSinusoidalityToCredit() {
        assertEquals(0.0, shapeGate(0.3), 1e-9)
        assertEquals(0.0, shapeGate(0.4), 1e-9)
        assertEquals(0.5, shapeGate(0.625), 1e-9)
        assertEquals(1.0, shapeGate(0.85), 1e-9)
        assertEquals(1.0, shapeGate(0.99), 1e-9)
    }

    @Test fun smootherIsFrameRateIndependent() {
        val one = Smoother(1000.0).step(1.0, 1000.0)
        assertEquals(1 - exp(-1.0), one, 1e-6)
        val ten = Smoother(1000.0)
        repeat(10) { ten.step(1.0, 100.0) }
        assertEquals(one, ten.value, 1e-9)
    }

    @Test fun rateSteps() {
        assertEquals(40, stepRateTenths(40, -1))
        assertEquals(70, stepRateTenths(70, 1))
        assertEquals(56, stepRateTenths(55, 1))
    }
}
