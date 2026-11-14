package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RfEstimateTest {
    @Test fun hasuoFormulaBySexInTenths() {
        assertEquals(6.0, hasuoEstimate(170.0, Sex.MALE).rateBpm, 1e-9)     // 17.90 - 0.07 x 170 = 6.00
        assertEquals(6.3, hasuoEstimate(160.0, Sex.FEMALE).rateBpm, 1e-9)   // 15.88 - 0.06 x 160 = 6.28
        assertFalse(hasuoEstimate(170.0, Sex.MALE).extrapolated)
    }

    @Test fun clampedToTheMeasuredFiveToSevenAndFlaggedOutsideIt() {
        val tall = hasuoEstimate(190.0, Sex.MALE)                           // raw 4.6
        assertEquals(5.0, tall.rateBpm, 1e-9)
        assertTrue(tall.extrapolated)
        val short = hasuoEstimate(140.0, Sex.FEMALE)                        // raw 7.48
        assertEquals(7.0, short.rateBpm, 1e-9)
        assertTrue(short.extrapolated)
    }

    @Test(expected = IllegalArgumentException::class) fun heightOutside130To210IsRejected() {
        hasuoEstimate(225.0, Sex.FEMALE)
    }
}
