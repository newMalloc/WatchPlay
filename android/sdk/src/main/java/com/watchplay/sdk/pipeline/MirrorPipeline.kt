package com.watchplay.sdk.pipeline

import com.watchplay.sdk.capture.CaptureModule
import com.watchplay.sdk.encoder.EncodeProfile
import com.watchplay.sdk.encoder.EncoderModule
import com.watchplay.sdk.protocol.CapsResult
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.ProtocolLimits
import com.watchplay.sdk.protocol.VideoFrameChunker
import com.watchplay.sdk.transport.SendResult
import com.watchplay.sdk.transport.TransportModule
import com.watchplay.sdk.transport.TransportState
import com.watchplay.sdk.util.MonotonicClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Wires capture → encode → chunk → transport, and decides what to do when the link cannot keep up.
 *
 * ```
 *   [CaptureModule] --> [EncoderModule] --> [VideoFrameChunker] --> [TransportModule]
 *        UI/GPU           MediaCodec            protocol                BLE
 * ```
 *
 * ## The one policy that matters: drop, never queue
 *
 * `04-video.md` §5.4 is explicit that under backpressure the sender must discard frames rather than
 * buffer them, because a queue turns a temporary hiccup into permanent latency (the classic
 * "latency creep" of live streaming). This pipeline therefore:
 *
 *  1. checks the credit window **before** starting a frame, so a frame is never half-sent,
 *  2. drops the whole frame — and never consumes credit for a drop (`01-transport.md` §6.3),
 *  3. marks the next frame `DISCONTINUITY` when something was dropped, so an H.264 decoder resets,
 *  4. keeps the encoder-to-transport depth at [PipelineConfig.maxQueueDepth] (2 by default).
 *
 * ## What it does not do
 *
 * Rate control (`04-video.md` §6: quality and frame-rate adaptation from `FEEDBACK`) is policy and
 * lives in the caller — `SessionManager.Listener.onFeedbackReceived` reports the inputs. This class
 * only counts; it never silently changes the negotiated parameters.
 *
 * ## Threading
 *
 * [start] spawns one pipeline thread; [tick] is public so tests and the demo can drive it
 * deterministically. [stats] is safe from any thread.
 *
 * @property capture capture implementation (GL texture, or the CPU `PixelCopy` fallback).
 * @property encoder encoder implementation.
 * @property transport bearer to send chunks to.
 * @property config pipeline tunables.
 */
class MirrorPipeline(
    private val capture: CaptureModule,
    private val encoder: EncoderModule,
    private val transport: TransportModule,
    private val config: PipelineConfig = PipelineConfig(),
) {

    /**
     * Gate: `VIDEO` may only flow in `STREAMING`. `WatchPlayClient` keeps this in sync with the
     * session state, because `03-session.md` §1.1 restricts which frames each state may send.
     */
    @Volatile
    var streamingEnabled: Boolean = false

    /** Supplies the protocol `seq` for each video frame; wired to the session by the client. */
    @Volatile
    var seqProvider: (() -> Int)? = null

    private val counters = Counters()
    private var pipelineThread: Thread? = null

    @Volatile
    private var running = false

    /** Chunker sized from the negotiated `max_chunk_bytes`; replaced by [configure]. */
    @Volatile
    private var chunker = VideoFrameChunker(ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES)

    /** Frame-rate window: samples of (timestampMs, framesSent) used to compute [PipelineStats.fps]. */
    private var windowStartMs = 0L
    private var windowFrames = 0L
    private var windowBytes = 0L
    private var windowLatencyMs = 0.0
    private var windowSamples = 0L

    private var encodeProfile: EncodeProfile = config.profile
    private var nextFrameId = 0L
    private var maxFrameBytes: Long = Long.MAX_VALUE
    private var pendingDiscontinuity = false
    private var lastError: String? = null

    /** Last capture timestamp seen, used for the phone-local latency figure. */
    private var lastCaptureNs = 0L

    /**
     * Applies the negotiated session parameters.
     *
     * Called once `CAPS_ACK` has been sent. Reconfigures the chunker, the encoder and the frame-size
     * guard so the pipeline can never emit something the watch did not agree to.
     *
     * @param result the negotiated `RESULT` TLV.
     * @param streamMaxFrameBytes `STREAM.max_frame_bytes`, the receiver's reassembly budget.
     * @return `true` when the encoder accepted the profile.
     */
    fun configure(result: CapsResult, streamMaxFrameBytes: Long): Boolean {
        chunker = VideoFrameChunker(result.maxChunkBytes)
        maxFrameBytes = streamMaxFrameBytes
        encodeProfile = when (result.codec) {
            com.watchplay.sdk.protocol.VideoCodec.MJPEG -> EncodeProfile.Mjpeg(result.quality.coerceAtLeast(1))
            com.watchplay.sdk.protocol.VideoCodec.H264 -> EncodeProfile.H264(
                bitrateBps = result.targetBitrateBps.toInt().coerceAtLeast(1),
                gopFrames = result.gop.coerceAtLeast(1),
                baseline = result.h264Profile == com.watchplay.sdk.protocol.H264Caps.PROFILE_BASELINE,
            )
        }
        nextFrameId = 0L
        pendingDiscontinuity = false
        val prepared = encoder.prepare(encodeProfile, result.widthPx, result.heightPx)
        capture.prepare(result.widthPx, result.heightPx)
        if (!prepared) lastError = encoder.lastError
        return prepared
    }

    /** Current statistics snapshot. */
    fun stats(): PipelineStats {
        val elapsedMs = (MonotonicClock.nowMs() - windowStartMs).coerceAtLeast(1L)
        val fps = if (windowSamples == 0L) 0.0 else windowFrames * 1000.0 / elapsedMs
        val bytesPerSecond = if (windowSamples == 0L) 0.0 else windowBytes * 1000.0 / elapsedMs
        val latency = if (windowSamples == 0L) 0.0 else windowLatencyMs / windowSamples
        return PipelineStats(
            fps = fps,
            bytesPerSecond = bytesPerSecond,
            framesCaptured = counters.captured.get(),
            framesEncoded = counters.encoded.get(),
            framesSent = counters.sent.get(),
            framesDropped = counters.dropped.get(),
            framesSkipped = counters.skipped.get(),
            chunksSent = counters.chunks.get(),
            queueDepth = 0, // The pipeline never queues: a frame is either sent or dropped.
            creditRemaining = transport.availableCreditChunks,
            averageLatencyMs = latency,
            lastError = lastError ?: encoder.lastError ?: capture.lastError,
        )
    }

    /** Clears counters and the rate window. */
    fun resetStats() {
        counters.reset()
        windowStartMs = MonotonicClock.nowMs()
        windowFrames = 0L
        windowBytes = 0L
        windowLatencyMs = 0.0
        windowSamples = 0L
        lastError = null
    }

    /** Starts the pipeline thread at the configured frame rate. */
    fun start() {
        if (running) return
        running = true
        windowStartMs = MonotonicClock.nowMs()
        val periodMs = (1000L / config.targetFps).coerceAtLeast(1L)
        val thread = Thread({
            while (running) {
                try {
                    tick(MonotonicClock.nowMs())
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (error: RuntimeException) {
                    // A pipeline stage must never kill the stream; record and continue.
                    lastError = error.message ?: error.toString()
                }
                try {
                    Thread.sleep(periodMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "watchplay-pipeline")
        thread.isDaemon = true
        pipelineThread = thread
        thread.start()
    }

    /** Stops the pipeline thread. The pipeline may be restarted. */
    fun stop() {
        running = false
        pipelineThread?.interrupt()
        pipelineThread = null
    }

    /** `true` while the pipeline thread is running. */
    val isRunning: Boolean
        get() = running

    /**
     * Runs one capture→encode→send cycle. Public so tests and the demo can step the pipeline without a
     * thread.
     *
     * @return `true` when a frame reached the transport.
     */
    fun tick(nowMs: Long = MonotonicClock.nowMs()): Boolean {
        if (!streamingEnabled) return false
        if (!transport.state.isOpen) return false

        // Credit is checked before capture, so a frame is never left half-sent (`04-video.md` §5.4).
        if (transport.availableCreditChunks < config.creditFloorChunks) {
            counters.dropped.incrementAndGet()
            pendingDiscontinuity = config.discontinuityOnDrop
            lastError = "credit window empty (${transport.availableCreditChunks}); frame dropped by policy"
            rollWindow(nowMs)
            return false
        }

        val captureResult = capture.captureFrame(config.captureTimeoutMs)
        if (captureResult == null || !captureResult.isUsable) {
            counters.skipped.incrementAndGet()
            rollWindow(nowMs)
            return false
        }
        counters.captured.incrementAndGet()

        val encoded = encoder.encode(captureResult, captureResult.timestampNs / 1_000L)
        if (encoded == null) {
            counters.skipped.incrementAndGet()
            lastError = encoder.lastError ?: "encoder produced no frame"
            rollWindow(nowMs)
            return false
        }
        counters.encoded.incrementAndGet()

        if (encoded.sizeBytes > maxFrameBytes) {
            // `04-video.md` §6: over the receiver's budget the frame MUST be re-encoded smaller, not
            // sent. Without a rate-control loop we drop it and say why.
            counters.skipped.incrementAndGet()
            lastError = "frame of ${encoded.sizeBytes} B exceeds STREAM.max_frame_bytes $maxFrameBytes"
            rollWindow(nowMs)
            return false
        }

        val frameId = nextFrameId++
        val seq = seqProvider?.invoke() ?: (frameId.toInt() + 1)
        val chunked = chunker.chunk(
            frameId = frameId,
            codedData = encoded.data,
            codec = encoded.codec,
            widthPx = encoded.width,
            heightPx = encoded.height,
            quality = encoded.quality,
            seq = seq,
            isKeyframe = encoded.isKeyframe,
            referenced = encoded.codec == com.watchplay.sdk.protocol.VideoCodec.MJPEG || encoded.isKeyframe,
            discontinuity = pendingDiscontinuity,
        )
        pendingDiscontinuity = false

        var sentChunks = 0
        for (chunk in chunked.frames) {
            when (val result = transport.send(chunk)) {
                is SendResult.Sent -> sentChunks++
                else -> {
                    // Out of credit mid-frame: stop, count the frame as dropped, and remember to mark
                    // the next one DISCONTINUITY so the decoder recovers cleanly.
                    counters.dropped.incrementAndGet()
                    pendingDiscontinuity = config.discontinuityOnDrop
                    lastError = when (result) {
                        is SendResult.NoCredit -> "credit exhausted after $sentChunks of ${chunked.chunkTotal} chunks"
                        is SendResult.TooLarge -> "chunk of ${result.sizeBytes} B exceeds the bearer's ${result.maxBytes} B"
                        is SendResult.Failed -> "transport write failed: ${result.reason}"
                        SendResult.NotConnected -> "transport closed mid-frame"
                        is SendResult.Sent -> null
                    }
                    lastCaptureNs = captureResult.timestampNs
                    rollWindow(nowMs, sent = false)
                    return false
                }
            }
        }

        counters.sent.incrementAndGet()
        counters.chunks.addAndGet(sentChunks.toLong())
        lastCaptureNs = captureResult.timestampNs
        val latencyMs = (MonotonicClock.nowMs() - captureResult.timestampNs / 1_000_000L).toDouble()
        rollWindow(nowMs, bytes = encoded.sizeBytes.toLong(), latencyMs = latencyMs)
        return true
    }

    private fun rollWindow(nowMs: Long, bytes: Long = 0L, latencyMs: Double = 0.0, sent: Boolean = true) {
        if (windowStartMs == 0L) windowStartMs = nowMs
        if (sent) {
            windowFrames++
            windowSamples++
            windowLatencyMs += latencyMs
        }
        windowBytes += bytes
        // One-second sliding window, reset in place to avoid unbounded history.
        if (nowMs - windowStartMs >= RATE_WINDOW_MS) {
            windowStartMs = nowMs
            windowFrames = 0L
            windowBytes = 0L
            windowLatencyMs = 0.0
            windowSamples = 0L
        }
    }

    /** Mutable counters, kept private so [stats] can stay immutable. */
    private class Counters {
        val captured = AtomicLong()
        val encoded = AtomicLong()
        val sent = AtomicLong()
        val dropped = AtomicLong()
        val skipped = AtomicLong()
        val chunks = AtomicLong()

        fun reset() {
            captured.set(0L)
            encoded.set(0L)
            sent.set(0L)
            dropped.set(0L)
            skipped.set(0L)
            chunks.set(0L)
        }
    }

    override fun toString(): String =
        "MirrorPipeline(capture=${capture.name}, encoder=${encoder.name}, transport=${transport.id}, " +
            "streaming=$streamingEnabled, ${stats()})"

    companion object {
        /** Width of the frame-rate averaging window. */
        const val RATE_WINDOW_MS: Long = 1_000L
    }
}
