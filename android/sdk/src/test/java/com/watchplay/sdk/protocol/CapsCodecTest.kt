package com.watchplay.sdk.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural tests for the `CAPS` / `CAPS_ACK` TLV codecs (`03-session.md` §3–§4,
 * `07-capabilities.md` §2).
 *
 * The sizes asserted here are normative and are the cheapest possible regression guard against a field
 * being added in the wrong place: `DEVICE_INFO` 16, `DISPLAY` 16, `CODEC_MJPEG` 4, `CODEC_H264` 8,
 * `INPUT` 4, `AUDIO` 4, `MEMORY` 8, `RESULT` 24, `STREAM` 12.
 */
class CapsCodecTest {

    @Test
    fun `reference watch capabilities round trip`() {
        val original = Capabilities.referenceWatch()
        val encoded = CapsCodec.encode(original)
        val decoded = CapsCodec.decode(encoded)

        assertEquals(original.deviceInfo, decoded.deviceInfo)
        assertEquals(original.display, decoded.display)
        assertEquals(original.mjpeg, decoded.mjpeg)
        assertEquals(original.h264, decoded.h264)
        assertEquals(original.input, decoded.input)
        assertEquals(original.audio, decoded.audio)
        assertEquals(original.memory, decoded.memory)
        assertEquals(original.power, decoded.power)
        assertEquals(original.transport, decoded.transport)
        assertEquals(original.sensors, decoded.sensors)
        assertTrue(decoded.isComplete)
        assertEquals(setOf(VideoCodec.MJPEG), decoded.supportedCodecs)
        assertEquals(140, decoded.display!!.safeInsetPermille)
        assertEquals(ScreenShape.ROUND, decoded.display!!.shape)
        assertEquals(15, decoded.maxFpsFor(VideoCodec.MJPEG))
    }

    @Test
    fun `registered value sizes match the specification`() {
        assertEquals(16, DeviceInfo.reference().encode().size)
        assertEquals(16, DisplayCaps(466, 466, ScreenShape.ROUND, ColorFormat.RGB565_BE, 140).encode().size)
        assertEquals(4, MjpegCaps(2, 40, 90, 15).encode().size)
        assertEquals(8, H264Caps(66, 31, 8, false, 500, 466).encode().size)
        assertEquals(4, InputCaps(1, 60, true, 0).encode().size)
        assertEquals(4, AudioCaps(0, AudioCaps.PATH_NONE, 0).encode().size)
        assertEquals(8, MemoryCaps(8L * 1024 * 1024, 1024).encode().size)
        assertEquals(4, PowerCaps(82, false, 0, 1).encode().size)
        assertEquals(4, TransportCaps(1, 1, 244).encode().size)
        assertEquals(4, SensorsCaps(true, false, false, true).encode().size)
        assertEquals(24, CapsResult.ok(VideoCodec.MJPEG, 466, 466, 10, 70, 244, 64).encode().size)
        assertEquals(12, CapsStream(1024, 200).encode().size)
    }

    @Test
    fun `unknown TLV types are skipped by length and never fatal`() {
        val payload = CapsCodec.encode(Capabilities.referenceWatch()).toMutableList()
        // Prepend a vendor TLV of 5 bytes and an unregistered standard TLV of 2 bytes.
        val vendor = listOf(0x90.toByte(), 5, 1, 2, 3, 4, 5)
        val reservedStandard = listOf(0x40.toByte(), 2, 9, 9)
        val withUnknown = (vendor + reservedStandard + payload).toByteArray()

        val decoded = CapsCodec.decode(withUnknown)
        assertTrue(decoded.isComplete)
        assertEquals(1, decoded.vendorTlvs.size)
        assertEquals(0x90, decoded.vendorTlvs[0].type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded.vendorTlvs[0].value)
        // Reserved standard types are dropped, not preserved.
        assertTrue(decoded.vendorTlvs.none { it.type == 0x40 })
    }

    @Test
    fun `a TLV shorter than its registered size is ignored`() {
        // DISPLAY is 16 bytes; a 4-byte value is corrupt, so the whole TLV must be ignored.
        val truncatedDisplay = byteArrayOf(
            CapsCodec.Type.DISPLAY.toByte(), 4, 0x01, 0x02, 0x03, 0x04,
        )
        val rest = CapsCodec.encode(Capabilities.referenceWatch())
        val decoded = CapsCodec.decode(truncatedDisplay + rest)

        // The well-formed DISPLAY that follows is still picked up.
        assertTrue(decoded.isComplete)
        assertEquals(466, decoded.display!!.widthPx)
    }

    @Test
    fun `a TLV longer than its registered size is read as a prefix and skipped`() {
        val extra = ByteArray(8) { 0x7F }
        val display = DisplayCaps(240, 240, ScreenShape.RECTANGULAR, ColorFormat.RGB565, 40).encode()
        val longDisplay = byteArrayOf(
            CapsCodec.Type.DISPLAY.toByte(), (display.size + extra.size).toByte(),
        ) + display + extra
        val rest = CapsCodec.encode(
            Capabilities(
                deviceInfo = DeviceInfo.reference(),
                mjpeg = MjpegCaps(2, 40, 90, 10),
            ),
        )
        val decoded = CapsCodec.decode(longDisplay + rest)

        assertEquals(240, decoded.display!!.widthPx)
        assertEquals(ScreenShape.RECTANGULAR, decoded.display!!.shape)
        assertTrue(decoded.isComplete)
    }

    @Test
    fun `a truncated TLV list is a structural error`() {
        val payload = byteArrayOf(CapsCodec.Type.DEVICE_INFO.toByte(), 16, 1, 2, 3)
        try {
            CapsCodec.decode(payload)
            throw AssertionError("a TLV running past the payload must throw")
        } catch (expected: ProtocolException) {
            assertTrue(expected.message!!.contains("0x01"))
        }
    }

    @Test
    fun `caps ack round trips including a rejection reason`() {
        val result = CapsResult.ok(
            codec = VideoCodec.MJPEG,
            widthPx = 466,
            heightPx = 466,
            targetFps = 10,
            quality = 72,
            maxChunkBytes = 244,
            initialCredits = 64,
            sessionId = 0x11223344L,
        )
        val stream = CapsStream(maxFrameBytes = 1_048_576, deadlineMs = 200, heartbeatMs = 2000, drainTimeoutMs = 2000)
        val encoded = CapsAckCodec.encode(result, stream)
        val decoded = CapsAckCodec.decode(encoded)

        assertTrue(decoded.isComplete)
        assertTrue(decoded.isOk)
        assertEquals(result, decoded.result)
        assertEquals(stream, decoded.stream)
        assertNull(decoded.rejectReason)
        assertEquals(244 - 20, decoded.result!!.mediaBytesPerChunk)
    }

    @Test
    fun `caps ack carries a rejection`() {
        val rejected = CapsResult(
            status = ResultStatus.NO_COMMON_CODEC,
            codec = VideoCodec.MJPEG,
            widthPx = 466,
            heightPx = 466,
            targetFps = 0,
            quality = 0,
            maxChunkBytes = 244,
            initialCredits = 0,
        )
        val decoded = CapsAckCodec.decode(
            CapsAckCodec.encode(rejected, CapsStream(1024, 200), rejectReason = "watch offers nothing we can encode"),
        )
        assertFalse(decoded.isOk)
        assertEquals(ResultStatus.NO_COMMON_CODEC, decoded.result!!.status)
        assertEquals("watch offers nothing we can encode", decoded.rejectReason)
    }

    @Test
    fun `hello and hello ack round trip`() {
        val hello = Hello(
            protoVersion = 1,
            minVersion = 1,
            flags = HelloFlags.HAS_DISPLAY or HelloFlags.HAS_TOUCH or HelloFlags.HAS_PSRAM,
            nonce = 0xDEADBEEFL,
            deviceId = ByteArray(16) { it.toByte() },
            model = "T-Disp-S3",
            firmwareVersion = "0.1.0",
        )
        val decodedHello = Hello.decode(hello.encode())
        assertEquals(hello, decodedHello)
        assertEquals(52, hello.encode().size)
        assertTrue(decodedHello.hasTouch)

        val ack = HelloAck(
            protoVersion = 1,
            status = HelloAckStatus.OK,
            flags = HelloFlags.HAS_DISPLAY,
            peerNonce = hello.nonce,
            sdkVersion = "0.1.0",
        )
        val decodedAck = HelloAck.decode(ack.encode())
        assertEquals(ack, decodedAck)
        assertEquals(24, ack.encode().size)
        assertTrue(decodedAck.isOk)
    }

    @Test
    fun `feedback round trips with absolute credits`() {
        val feedback = Feedback(
            credits = 64,
            framesDecoded = 12,
            framesDropped = 1,
            decodeMsAvg = 31,
            queueDepth = 2,
            lastFrameStatus = LastFrameStatus.OK,
            flags = FeedbackFlags.REQUEST_KEYFRAME,
            lastFrameId = 11,
        )
        val decoded = Feedback.decode(feedback.encode())
        // 22 bytes: the field table of 04-video.md section 4.2, which contradicts the 20-byte prose.
        assertEquals(22, feedback.encode().size)
        assertEquals(Feedback.SIZE_BYTES, feedback.encode().size)
        assertEquals(feedback, decoded)
        assertEquals(64L, decoded.credits)
        assertTrue(decoded.requestsKeyframe)
        assertFalse(decoded.isOverloaded)
    }

    @Test
    fun `a twenty byte feedback payload is still accepted`() {
        // A peer that followed the "fixed 20 bytes" prose truncates last_frame_id; the frame must still
        // be usable, with the frame id reported as unknown.
        val full = Feedback(credits = 32, framesDecoded = 5, lastFrameId = 4).encode()
        val truncated = full.copyOf(Feedback.LEGACY_SIZE_BYTES)
        val decoded = Feedback.decode(truncated)
        assertEquals(32L, decoded.credits)
        assertEquals(5L, decoded.framesDecoded)
        assertEquals(Feedback.NO_FRAME_ID, decoded.lastFrameId)
        assertFalse(decoded.hasDecodedAnyFrame)
    }

    private fun DeviceInfo.Companion.reference(): DeviceInfo = DeviceInfo(
        protoVersion = ProtocolVersion.CURRENT,
        fwMajor = 0,
        fwMinor = 1,
        fwPatch = 0,
        chipId = ByteArray(8),
        buildId = 1L,
    )
}
