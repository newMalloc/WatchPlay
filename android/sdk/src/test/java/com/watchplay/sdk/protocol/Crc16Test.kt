package com.watchplay.sdk.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the frame-header checksum to the normative parameters of `02-wire-format.md` §2.
 *
 * Note for readers of the original SDK brief: the brief asked for a zlib **CRC-32** helper alongside a
 * header CRC. The wire-format specification is unambiguous that the header uses **CRC-16/CCITT-FALSE**,
 * and the project maintainer asked for the CRC-32 helper to be removed so that its presence could not
 * imply otherwise. The known-answer test therefore uses the CRC-16 check value from the spec, `0x29B1`.
 */
class Crc16Test {

    @Test
    fun `check value for 123456789 is 0x29B1`() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, Crc16.ccittFalse(data))
        assertEquals(Crc16.CHECK_VALUE, Crc16.ccittFalse(data))
    }

    @Test
    fun `empty input yields the initial register value`() {
        assertEquals(0xFFFF, Crc16.ccittFalse(ByteArray(0)))
    }

    @Test
    fun `incremental updates equal a single pass`() {
        val data = "The quick brown fox jumps over the lazy dog".toByteArray(Charsets.US_ASCII)
        val oneShot = Crc16.ccittFalse(data)
        var incremental = Crc16.INITIAL
        incremental = Crc16.update(incremental, data, 0, 10)
        incremental = Crc16.update(incremental, data, 10, 15)
        incremental = Crc16.update(incremental, data, 25, data.size - 25)
        assertEquals(oneShot, incremental)
    }

    @Test
    fun `byte 14 and 15 do not affect the computed header crc`() {
        val header = ByteArray(16)
        header[0] = 0x57
        header[1] = 0x50
        header[3] = 0x05
        val withoutCrcField = FrameCodec.computeHeaderCrc(header)
        header[14] = 0x12
        header[15] = 0x34
        assertEquals(withoutCrcField, FrameCodec.computeHeaderCrc(header))
    }
}
