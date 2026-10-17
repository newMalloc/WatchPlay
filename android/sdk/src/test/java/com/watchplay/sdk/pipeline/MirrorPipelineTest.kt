package com.watchplay.sdk.pipeline

import com.watchplay.sdk.capture.CaptureModule
import com.watchplay.sdk.capture.CaptureResult
import com.watchplay.sdk.capture.CaptureSource
import com.watchplay.sdk.encoder.EncodeProfile
import com.watchplay.sdk.encoder.EncodedFrame
import com.watchplay.sdk.encoder.EncoderModule
import com.watchplay.sdk.protocol.CapsResult
import com.watchplay.sdk.protocol.ResultStatus
import com.watchplay.sdk.protocol.VideoCodec
import com.watchplay.sdk.transport.LoopbackTransport
import com.watchplay.sdk.transport.TransportListener
import com.watchplay.sdk.transport.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame observer exists so a host can show what the watch will receive, which is the only way to
 * catch a capture path that returns black. What matters is that it fires for frames that really left the
 * encoder, reports the same `frame_id` the chunks carry, and cannot break the stream.
 */
class MirrorPipelineTest {

    private val width = 64
    private val height = 64

    /** A capture that always has a frame; the pixels themselves are irrelevant to this test. */
    private class FakeCapture(private val frameWidth: Int, private val frameHeight: Int) : CaptureModule {
        override val name = "fake-capture"
        override var isReady = false
            private set
        override val lastError: String? = null
        override var width = 0
            private set
        override var height = 0
            private set

        override fun prepare(width: Int, height: Int): Boolean {
            this.width = width
            this.height = height
            isReady = true
            return true
        }

        override fun captureFrame(timeoutMs: Long): CaptureResult = CaptureResult(
            textureId = -1,
            width = frameWidth,
            height = frameHeight,
            timestampNs = System.nanoTime(),
            source = CaptureSource.PIXEL_COPY,
            rgbaBytes = ByteArray(frameWidth * frameHeight * 4),
        )

        override fun release() {
            isReady = false
        }
    }

    /** An encoder with a fixed frame size, so the chunk arithmetic is checkable. */
    private class FakeEncoder(private val bytes: Int) : EncoderModule {
        override val name = "fake-encoder"
        override val codec: VideoCodec = VideoCodec.MJPEG
        override var isReady = false
            private set
        override var lastError: String? = null
            private set
        override var width = 0
            private set
        override var height = 0
            private set

        override fun prepare(profile: EncodeProfile, width: Int, height: Int): Boolean {
            this.width = width
            this.height = height
            isReady = true
            return true
        }

        override fun encode(capture: CaptureResult, ptsUs: Long): EncodedFrame? {
            lastError = null
            return EncodedFrame(
                data = ByteArray(bytes) { 0x42 },
                codec = VideoCodec.MJPEG,
                isKeyframe = true,
                ptsUs = ptsUs,
                width = capture.width,
                height = capture.height,
                quality = 70,
            )
        }

        override fun requestKeyframe() = Unit

        override fun release() {
            isReady = false
        }
    }

    private class NoopListener : TransportListener {
        override fun onTransportStateChanged(state: TransportState) = Unit
        override fun onFrameReceived(frame: com.watchplay.sdk.protocol.Frame) = Unit
        override fun onCreditWindowChanged(credits: Int) = Unit
        override fun onTransportError(error: Throwable) = Unit
    }

    private fun pipeline(
        frameBytes: Int = 1_000,
        observer: (PipelineFrame) -> Unit,
    ): MirrorPipeline {
        val (phone, watch) = LoopbackTransport.pair()
        phone.open(NoopListener())
        watch.open(NoopListener())
        phone.grantCredits(64)
        val pipeline = MirrorPipeline(FakeCapture(width, height), FakeEncoder(frameBytes), phone)
        pipeline.frameObserver = observer
        assertTrue("encoder must accept the profile", pipeline.configure(result(), Long.MAX_VALUE))
        pipeline.streamingEnabled = true
        return pipeline
    }

    private fun result() = CapsResult(
        status = ResultStatus.OK,
        codec = VideoCodec.MJPEG,
        widthPx = width,
        heightPx = height,
        targetFps = 10,
        quality = 70,
        maxChunkBytes = 244,
        initialCredits = 64,
        colorFormat = com.watchplay.sdk.protocol.ColorFormat.RGB565_BE,
    )

    @Test
    fun `a sent frame is reported with the id and the chunk count the watch will see`() {
        val frames = mutableListOf<PipelineFrame>()
        val pipeline = pipeline { frames += it }

        assertTrue(pipeline.tick())

        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals(0L, frame.frameId)
        // ceil(1000 / (244 - 20)) = 5 chunks; the -20 is the video subheader.
        assertEquals(5, frame.chunkTotal)
        assertEquals(1_000, frame.encoded.sizeBytes)
        assertEquals(width, frame.encoded.width)
    }

    @Test
    fun `frame ids keep increasing so a preview can tell frames apart`() {
        val ids = mutableListOf<Long>()
        val pipeline = pipeline { ids += it.frameId }

        pipeline.tick()
        pipeline.tick()

        assertEquals(listOf(0L, 1L), ids)
    }

    @Test
    fun `a throwing observer cannot kill the pipeline`() {
        var calls = 0
        val pipeline = pipeline {
            calls++
            throw IllegalStateException("preview bit me")
        }

        assertTrue("the frame still went out", pipeline.tick())
        assertEquals(1, calls)
        assertEquals("preview bit me", pipeline.stats().lastError?.substringAfterLast(": "))
    }

    @Test
    fun `no observer means no extra work`() {
        val (phone, watch) = LoopbackTransport.pair()
        phone.open(NoopListener())
        watch.open(NoopListener())
        phone.grantCredits(64)
        val pipeline = MirrorPipeline(FakeCapture(width, height), FakeEncoder(1_000), phone)
        pipeline.configure(result(), Long.MAX_VALUE)
        pipeline.streamingEnabled = true

        assertTrue(pipeline.tick())
        assertEquals(1, pipeline.stats().framesSent)
    }
}
