package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RfEstimateTest {
    @Test fun hasuoFormulaBySexInTenths() {
        assertEquals(6.0, hasuoEstimate(170.0, Sex.MALE).rateBpm, 1e-9)     // 17.90 - 0.07 x 170 = 6.00
        assertEquals(5.7, hasuoEstimate(170.0, Sex.FEMALE).rateBpm, 1e-9)   // 15.88 - 0.06 x 170 = 5.68
        assertFalse(hasuoEstimate(170.0, Sex.MALE).extrapolated)
    }

    @Test fun startsAreCappedAtFiveToSixAndFlaggedOutsideTheMeasuredFiveToSeven() {
        val tall = hasuoEstimate(190.0, Sex.MALE)
        assertEquals(4.60, tall.raw, 1e-9)
        assertEquals(5.0, tall.rateBpm, 1e-9)
        assertTrue(tall.extrapolated)
        assertEquals(5.0, hasuoEstimate(193.0, Sex.MALE).rateBpm, 1e-9)    // raw 4.39
        assertEquals(5.0, hasuoEstimate(210.0, Sex.MALE).rateBpm, 1e-9)    // raw 3.2
        val short = hasuoEstimate(140.0, Sex.FEMALE)                        // raw 7.48
        assertEquals(6.0, short.rateBpm, 1e-9)
        assertEquals(6.0, hasuoEstimate(155.0, Sex.FEMALE).rateBpm, 1e-9)  // raw 6.58, inside the measured range
        assertFalse(hasuoEstimate(155.0, Sex.FEMALE).extrapolated)
        assertTrue(short.extrapolated)
    }

    @Test fun shortWomanIsCappedAtSix() {
        val w = hasuoEstimate(150.0, Sex.FEMALE)
        assertEquals(6.88, w.raw, 1e-9)
        assertEquals(6.0, w.rateBpm, 1e-9)
    }

    @Test fun midHeightManIsNotCapped() {
        val m = hasuoEstimate(176.0, Sex.MALE)
        assertEquals(5.58, m.raw, 1e-9)
        assertEquals(5.6, m.rateBpm, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class) fun heightOutside130To210IsRejected() {
        hasuoEstimate(225.0, Sex.FEMALE)
    }
}
