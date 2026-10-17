package com.watchplay.sdk.session

import com.watchplay.sdk.protocol.CapsAckCodec
import com.watchplay.sdk.protocol.Capabilities
import com.watchplay.sdk.protocol.ColorFormat
import com.watchplay.sdk.protocol.DeviceInfo
import com.watchplay.sdk.protocol.DisplayCaps
import com.watchplay.sdk.protocol.H264Caps
import com.watchplay.sdk.protocol.MjpegCaps
import com.watchplay.sdk.protocol.ProtocolLimits
import com.watchplay.sdk.protocol.ResultStatus
import com.watchplay.sdk.protocol.ScreenShape
import com.watchplay.sdk.protocol.TransportCaps
import com.watchplay.sdk.protocol.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The negotiation algorithm of `03-session.md` §4.2, including the reproducibility requirement and the
 * heuristic that keeps ESP32-S3 watches on MJPEG.
 */
class NegotiatorTest {

    private val negotiator = Negotiator(SessionConfig(maxFps = 10, preferredQuality = 70))

    @Test
    fun `reference watch negotiates MJPEG at its own resolution`() {
        val outcome = negotiator.negotiate(Capabilities.referenceWatch(), sessionId = 7L)

        assertTrue(outcome.succeeded)
        val result = outcome.result!!
        assertEquals(VideoCodec.MJPEG, result.codec)
        assertEquals(466, result.widthPx)
        assertEquals(466, result.heightPx)
        assertEquals(10, result.targetFps)
        assertEquals(70, result.quality)
        assertEquals(244, result.maxChunkBytes)
        assertEquals(ProtocolLimits.DEFAULT_INITIAL_CREDITS, result.initialCredits)
        assertEquals(0, result.gop)
        assertEquals(0L, result.targetBitrateBps)
        assertEquals(7L, result.sessionId)
        assertEquals(ColorFormat.RGB565_BE, result.colorFormat)
        assertEquals(140, outcome.display!!.safeInsetPermille)
    }

    @Test
    fun `frame rate is the slowest of the three ceilings`() {
        val slowWatch = Capabilities.referenceWatch().copy(
            mjpeg = MjpegCaps(2, 40, 90, 5),
        )
        assertEquals(5, negotiator.negotiate(slowWatch).result!!.targetFps)
        assertEquals(10, negotiator.negotiate(Capabilities.referenceWatch()).result!!.targetFps)
    }

    @Test
    fun `h264 is not chosen without a hardware decoder`() {
        val softwareOnly = Capabilities.referenceWatch().copy(
            h264 = H264Caps(profile = 66, level = 31, maxFps = 8, hardwareDecode = false, maxBitrateKbps = 500, maxWidthPx = 466),
        )
        val withH264Allowed = Negotiator(SessionConfig(allowH264 = true))
        val outcome = withH264Allowed.negotiate(softwareOnly)

        // `03-session.md` §4.2 step 2a: software-only H.264 on an ESP32-S3 is worse than MJPEG.
        assertEquals(VideoCodec.MJPEG, outcome.result!!.codec)
    }

    @Test
    fun `h264 is chosen with hardware decoding when allowed`() {
        val hardwareWatch = Capabilities.referenceWatch().copy(
            h264 = H264Caps(profile = 66, level = 31, maxFps = 25, hardwareDecode = true, maxBitrateKbps = 800, maxWidthPx = 466),
        )
        val outcome = Negotiator(SessionConfig(allowH264 = true, maxFps = 20)).negotiate(hardwareWatch)

        assertTrue(outcome.succeeded)
        val result = outcome.result!!
        assertEquals(VideoCodec.H264, result.codec)
        assertEquals(20, result.targetFps)
        assertEquals(0, result.quality)
        // gop = clamp(round(fps * 0.8), 8, 12).
        assertEquals(12, result.gop)
        // bitrate = min(watch 800_000, 466 * 466 * 20 * 0.12 = 521_174).
        assertEquals(521_174L, result.targetBitrateBps)
        assertEquals(66, result.h264Profile)
    }

    @Test
    fun `quality is clamped into the specification window and below the watch ceiling`() {
        val lowQualityWatch = Capabilities.referenceWatch().copy(
            mjpeg = MjpegCaps(2, 40, 50, 10),
        )
        assertEquals(50, negotiator.negotiate(lowQualityWatch).result!!.quality)

        val generousWatch = Capabilities.referenceWatch().copy(
            mjpeg = MjpegCaps(2, 40, 100, 10),
        )
        // The phone's own preference (70) wins, and 100 from the watch is capped at 85.
        assertEquals(70, negotiator.negotiate(generousWatch).result!!.quality)

        val highPreference = Negotiator(SessionConfig(preferredQuality = 95))
        assertEquals(85, highPreference.negotiate(generousWatch).result!!.quality)
    }

    @Test
    fun `max chunk bytes never drops below the specification floor`() {
        val tinyTransport = Capabilities.referenceWatch().copy(
            transport = TransportCaps(TransportCaps.BEARER_GATT, TransportCaps.BEARER_GATT, mtuPayload = 20),
        )
        val result = negotiator.negotiate(tinyTransport).result!!
        assertEquals(ProtocolLimits.MIN_MAX_CHUNK_BYTES, result.maxChunkBytes)
        assertTrue(result.maxChunkBytes - 20 >= 64)
    }

    @Test
    fun `missing display is caps malformed`() {
        val noDisplay = Capabilities(
            deviceInfo = DeviceInfo(1, 0, 1, 0, ByteArray(8), 1L),
            mjpeg = MjpegCaps(2, 40, 90, 10),
        )
        val outcome = negotiator.negotiate(noDisplay)

        assertFalse(outcome.succeeded)
        assertEquals(ResultStatus.CAPS_MALFORMED, outcome.status)
        assertTrue(outcome.rejectReason!!.contains("DISPLAY"))
        // `03-session.md` §4.3 requires a REJECT_REASON TLV on failure.
        assertTrue(outcome.ack.rejectReason!!.isNotBlank())
    }

    @Test
    fun `no common codec is reported when the watch offers only h264 without hardware support`() {
        val h264Only = Capabilities(
            deviceInfo = DeviceInfo(1, 0, 1, 0, ByteArray(8), 1L),
            display = DisplayCaps(466, 466, ScreenShape.ROUND, ColorFormat.RGB565_BE, 140),
            h264 = H264Caps(66, 31, 8, hardwareDecode = false, maxBitrateKbps = 500, maxWidthPx = 466),
        )
        val outcome = negotiator.negotiate(h264Only)

        assertFalse(outcome.succeeded)
        assertEquals(ResultStatus.NO_COMMON_CODEC, outcome.status)
    }

    @Test
    fun `negotiation is reproducible for identical input`() {
        val caps = Capabilities.referenceWatch()
        val first = CapsAckCodec.encode(negotiator.negotiate(caps, sessionId = 99L).ack)
        val second = CapsAckCodec.encode(negotiator.negotiate(caps, sessionId = 99L).ack)
        assertArrayEquals(first, second)
    }

    @Test
    fun `stream budget reports the phone's reassembly promise`() {
        val config = SessionConfig(maxFrameBytes = 262_144, heartbeatMs = 1500, drainTimeoutMs = 2000)
        val outcome = Negotiator(config).negotiate(Capabilities.referenceWatch())
        val stream = outcome.ack.stream!!
        assertEquals(262_144L, stream.maxFrameBytes)
        assertEquals(1500, stream.heartbeatMs)
        assertEquals(2000, stream.drainTimeoutMs)
    }

    @Test
    fun `a small link budget caps max_chunk_bytes even when the watch over-reports`() {
        // MTU 185 yields bearer_tu 182; the watch still claims 244 in its TRANSPORT TLV.
        val outcome = negotiator.negotiate(Capabilities.referenceWatch(), bearerChunkBudget = 182)

        assertTrue(outcome.succeeded)
        assertEquals(182, outcome.result!!.maxChunkBytes)
        // 04-video.md §3.1: media per chunk is max_chunk_bytes - 20, still above the 64-byte minimum.
        assertTrue(outcome.result!!.maxChunkBytes - 20 >= 64)
    }

    @Test
    fun `a link budget below the chunk floor is ignored instead of rounded up`() {
        // At MTU 23 the bearer submits 20 bytes, which cannot carry the 20-byte sub-header plus data.
        val outcome = negotiator.negotiate(Capabilities.referenceWatch(), bearerChunkBudget = 20)

        assertTrue(outcome.succeeded)
        assertEquals(ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES, outcome.result!!.maxChunkBytes)
    }

    @Test
    fun `an unlimited bearer leaves the configured chunk size alone`() {
        val outcome = negotiator.negotiate(Capabilities.referenceWatch(), bearerChunkBudget = Int.MAX_VALUE)

        assertEquals(ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES, outcome.result!!.maxChunkBytes)
    }
}
