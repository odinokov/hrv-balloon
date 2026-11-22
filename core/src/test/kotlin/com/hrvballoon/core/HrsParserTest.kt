package com.hrvballoon.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HrsParserTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun uint8HrNoRr() {
        val p = parseHrm(b(0x00, 0x48))
        assertEquals(72, p.hrBpm)
        assertEquals(0, p.rr1024.size)
        assertEquals(Contact.NOT_SUPPORTED, p.contact)
    }

    @Test fun uint16Hr() = assertEquals(300, parseHrm(b(0x01, 0x2C, 0x01)).hrBpm)

    @Test fun twoRrsContactDetected() {
        val p = parseHrm(b(0x16, 0x60, 0x00, 0x04, 0x00, 0x04))
        assertArrayEquals(intArrayOf(1024, 1024), p.rr1024)
        assertEquals(Contact.DETECTED, p.contact)
    }

    @Test fun energyExpendedSkipped() =
        assertArrayEquals(intArrayOf(1024), parseHrm(b(0x18, 0x60, 0x34, 0x12, 0x00, 0x04)).rr1024)

    @Test fun contactBits() {
        assertEquals(Contact.NOT_DETECTED, parseHrm(b(0x04, 0x60)).contact)
        assertEquals(Contact.DETECTED, parseHrm(b(0x06, 0x60)).contact)
        assertEquals(Contact.NOT_SUPPORTED, parseHrm(b(0x02, 0x60)).contact)
        assertEquals(Contact.NOT_SUPPORTED, parseHrm(b(0x00, 0x60)).contact)
    }

    @Test fun truncated() {
        assertThrows(IllegalArgumentException::class.java) { parseHrm(b()) }
        assertThrows(IllegalArgumentException::class.java) { parseHrm(b(0x10, 0x60, 0x00)) }
    }

    @Test fun hrAbove127IsUnsigned() {
        assertEquals(150, parseHrm(b(0x00, 150)).hrBpm)
        assertEquals(400, parseHrm(b(0x01, 0x90, 0x01)).hrBpm)
    }

    @Test fun rrLowByteAtOrAbove0x80IsUnsigned() {
        assertArrayEquals(intArrayOf(896), parseHrm(b(0x10, 0x60, 0x80, 0x03)).rr1024)
        assertArrayEquals(intArrayOf(11392), parseHrm(b(0x10, 0x60, 0x80, 0x2C)).rr1024)
    }

    @Test fun energyFlagWithoutTheEnergyBytesIsTruncated() {
        assertThrows(IllegalArgumentException::class.java) { parseHrm(b(0x18, 0x60)) }
    }
}
