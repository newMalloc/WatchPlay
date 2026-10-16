package com.watchplay.sdk.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip, layout and error-handling tests for the 16-byte frame header of `02-wire-format.md`. */
class FrameCodecTest {

    @Test
    fun `encode writes the documented header layout`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val frame = Frame.of(FrameType.VIDEO, payload, flags = 0x0028, seq = 42)
        val bytes = FrameCodec.encode(frame)

        assertEquals(FrameCodec.HEADER_SIZE + payload.size, bytes.size)
        assertEquals(0x57, bytes[0].toInt() and 0xFF)
        assertEquals(0x50, bytes[1].toInt() and 0xFF)
        assertEquals(ProtocolVersion.CURRENT, bytes[2].toInt() and 0xFF)
        assertEquals(FrameType.VIDEO.code, bytes[3].toInt() and 0xFF)
        assertEquals(0x0028, LittleEndian.readU16(bytes, 4))
        assertEquals(16, LittleEndian.readU16(bytes, 6))
        assertEquals(42, LittleEndian.readU32AsInt(bytes, 8))
        assertEquals(payload.size, LittleEndian.readU16(bytes, 12))
        assertEquals(FrameCodec.computeHeaderCrc(bytes), LittleEndian.readU16(bytes, 14))
        assertArrayEquals(payload, bytes.copyOfRange(16, bytes.size))
    }

    @Test
    fun `round trip preserves every field`() {
        val payload = ByteArray(244) { (it * 7).toByte() }
        val original = Frame.of(FrameType.VIDEO, payload, flags = 0x0030, seq = 0x0FFFFFFF)
        val decoded = FrameCodec.decode(FrameCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(FrameType.VIDEO, decoded.type)
        assertEquals(0x0030, decoded.flags)
        assertEquals(0x0FFFFFFF, decoded.seq)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `header crc does not cover the payload`() {
        val frame = Frame.of(FrameType.VIDEO, ByteArray(64) { 0x11 }, seq = 7)
        val encoded = FrameCodec.encode(frame)
        val crcBefore = LittleEndian.readU16(encoded, 14)
        encoded[FrameCodec.HEADER_SIZE + 5] = 0x7F
        // The spec deliberately leaves the payload uncovered; only header bytes 0..13 feed the CRC.
        assertEquals(crcBefore, LittleEndian.readU16(encoded, 14))
    }

    @Test
    fun `corrupted header crc is reported as malformed and refused`() {
        val encoded = FrameCodec.encode(Frame.of(FrameType.CONTROL, byteArrayOf(0x03), seq = 9))
        encoded[14] = (encoded[14].toInt() xor 0x01).toByte()

        val outcome = FrameCodec.tryDecode(encoded)
        assertTrue(outcome is FrameCodec.DecodeOutcome.Malformed)
        try {
            FrameCodec.decode(encoded)
            throw AssertionError("decode should have thrown")
        } catch (expected: ProtocolException) {
            assertTrue(expected.message!!.contains("hdr_crc"))
        }
    }

    @Test
    fun `truncated input reports incomplete with the expected size`() {
        val encoded = FrameCodec.encode(Frame.of(FrameType.VIDEO, ByteArray(100), seq = 3))
        val outcome = FrameCodec.tryDecode(encoded, 0, 40)
        assertTrue(outcome is FrameCodec.DecodeOutcome.Incomplete)
        val incomplete = outcome as FrameCodec.DecodeOutcome.Incomplete
        assertEquals(encoded.size, incomplete.expectedBytes)
        assertEquals(40, incomplete.availableBytes)
    }

    @Test
    fun `unknown frame types decode without failing`() {
        val vendor = Frame(typeCode = 0x81, seq = 4, payload = byteArrayOf(1, 2))
        val decoded = FrameCodec.decode(FrameCodec.encode(vendor))
        assertNull(decoded.type)
        assertTrue(FrameType.isVendorPrivate(decoded.typeCode))
        assertEquals(0x81, decoded.typeCode)
    }

    @Test
    fun `parser reassembles a frame delivered in two halves`() {
        val parser = FrameParser()
        val encoded = FrameCodec.encode(Frame.of(FrameType.HELLO, ByteArray(52) { 0x5A }, seq = 1))

        assertTrue(parser.offer(encoded, 0, 20).isEmpty())
        val frames = parser.offer(encoded, 20, encoded.size - 20)
        assertEquals(1, frames.size)
        assertEquals(FrameType.HELLO, frames[0].type)
        assertArrayEquals(ByteArray(52) { 0x5A }, frames[0].payload)
        assertEquals(0L, parser.headerCrcErrors)
    }

    @Test
    fun `parser resynchronises on garbage and parses the following frames`() {
        val parser = FrameParser()
        val first = FrameCodec.encode(Frame.of(FrameType.PING, ByteArray(0), seq = 1))
        val second = FrameCodec.encode(Frame.of(FrameType.PONG, ByteArray(0), seq = 2))
        val stream = byteArrayOf(0x00, 0x11, 0x57, 0x00) + first + second

        val frames = parser.offer(stream)
        assertEquals(2, frames.size)
        assertEquals(FrameType.PING, frames[0].type)
        assertEquals(FrameType.PONG, frames[1].type)
        assertTrue(parser.resyncCount >= 1L)
    }

    @Test
    fun `parser skips frames with a broken header crc`() {
        val parser = FrameParser()
        val broken = FrameCodec.encode(Frame.of(FrameType.VIDEO, ByteArray(30), seq = 5))
        broken[14] = (broken[14].toInt() xor 0xFF).toByte()
        val good = FrameCodec.encode(Frame.of(FrameType.PING, ByteArray(0), seq = 6))

        val frames = parser.offer(broken + good)
        assertEquals(1, frames.size)
        assertEquals(FrameType.PING, frames[0].type)
        assertTrue(parser.headerCrcErrors >= 1L)
    }

    @Test
    fun `parser respects its buffered byte ceiling`() {
        val parser = FrameParser(maxBufferedBytes = 64)
        // A header claiming a payload that never arrives must not grow the buffer without bound.
        val encoded = FrameCodec.encode(Frame.of(FrameType.VIDEO, ByteArray(200), seq = 1))
        parser.offer(encoded, 0, 16)
        assertTrue(parser.bufferedBytes <= 64)
        assertEquals(16, parser.bufferedBytes)
    }
}
