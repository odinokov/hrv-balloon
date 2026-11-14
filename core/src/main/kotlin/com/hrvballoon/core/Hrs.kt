package com.hrvballoon.core

enum class Contact { NOT_SUPPORTED, NOT_DETECTED, DETECTED }

class HrsPacket(val hrBpm: Int, val contact: Contact, val rr1024: IntArray)

/** Parses a BLE Heart Rate Measurement (0x2A37) value; RR values stay in 1/1024 s units. */
fun parseHrm(data: ByteArray): HrsPacket {
    require(data.isNotEmpty()) { "empty packet" }
    val flags = data[0].toInt()
    var i = 1
    val hr = if (flags and 0x01 == 0) u8(data, i++) else u16(data, i).also { i += 2 }
    val contact = when (flags shr 1 and 0x03) {
        2 -> Contact.NOT_DETECTED
        3 -> Contact.DETECTED
        else -> Contact.NOT_SUPPORTED
    }
    if (flags and 0x08 != 0) i += 2
    val rest = data.size - i
    require(rest >= 0 && rest % 2 == 0) { "truncated packet" }
    val rr = if (flags and 0x10 != 0) IntArray(rest / 2) { u16(data, i + 2 * it) } else IntArray(0)
    return HrsPacket(hr, contact, rr)
}

private fun u8(d: ByteArray, i: Int): Int {
    require(i < d.size) { "truncated packet" }
    return d[i].toInt() and 0xFF
}

private fun u16(d: ByteArray, i: Int): Int {
    require(i + 1 < d.size) { "truncated packet" }
    return u8(d, i) or (u8(d, i + 1) shl 8)
}
