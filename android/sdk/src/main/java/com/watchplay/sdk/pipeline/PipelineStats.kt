package com.watchplay.sdk.pipeline

import com.watchplay.sdk.encoder.EncodeProfile

/**
 * Tunables for [MirrorPipeline].
 *
 * @property profile what to encode. Defaults to MJPEG (Profile A), the reference profile the
 *   specification requires every implementation to support.
 * @property targetFps capture cadence. The negotiated `target_fps` overrides it once a session is up.
 * @property maxQueueDepth how many encoded frames may wait for the transport. `04-video.md` §7.3 caps
 *   the ring buffer at **2**: deeper queues do not improve throughput, they only add
 *   `depth / fps` seconds of permanent latency.
 * @property creditFloorChunks minimum credit required before a frame is started. One chunk is the
 *   floor; raising it avoids starting a frame that cannot be finished.
 * @property discontinuityOnDrop when a frame is dropped or only partly sent, the next frame is marked
 *   `DISCONTINUITY` so an H.264 decoder resets its reference state (`04-video.md` §5.4).
 * @property captureTimeoutMs how long [com.watchplay.sdk.capture.CaptureModule.captureFrame] may block.
 */
data class PipelineConfig(
    val profile: EncodeProfile = EncodeProfile.Mjpeg(),
    val targetFps: Int = 10,
    val maxQueueDepth: Int = 2,
    val creditFloorChunks: Int = 1,
    val discontinuityOnDrop: Boolean = true,
    val captureTimeoutMs: Long = 0L,
) {

    init {
        require(targetFps in 1..60) { "targetFps out of range: $targetFps" }
        require(maxQueueDepth in 1..8) { "maxQueueDepth out of range: $maxQueueDepth" }
        require(creditFloorChunks >= 1) { "creditFloorChunks must be at least 1" }
    }
}

/**
 * Live statistics for the mirror pipeline, surfaced to apps and to the demo's log pane.
 *
 * @property fps frames successfully handed to the transport per second, averaged over the last second.
 * @property bytesPerSecond media bytes per second (encoded payload, excluding protocol overhead).
 * @property framesCaptured capture calls that produced a frame.
 * @property framesEncoded frames that survived encoding.
 * @property framesSent frames fully handed to the transport.
 * @property framesDropped frames discarded because of credit or a send failure — the deliberate
 *   "drop rather than queue" policy of `04-video.md` §5.4.
 * @property framesSkipped frames lost to a stage that produced nothing (no repaint, encoder busy,
 *   frame over the negotiated size limit). Not an error by itself.
 * @property chunksSent protocol `VIDEO` chunks accepted by the transport.
 * @property queueDepth frames currently waiting between encoder and transport.
 * @property creditRemaining outbound credit window left.
 * @property averageLatencyMs mean time from capture timestamp to successful send, **phone-local**
 *   (it deliberately excludes the watch's clock, which is a different time base).
 * @property lastError most recent recoverable problem, or `null`.
 */
data class PipelineStats(
    val fps: Double = 0.0,
    val bytesPerSecond: Double = 0.0,
    val framesCaptured: Long = 0L,
    val framesEncoded: Long = 0L,
    val framesSent: Long = 0L,
    val framesDropped: Long = 0L,
    val framesSkipped: Long = 0L,
    val chunksSent: Long = 0L,
    val queueDepth: Int = 0,
    val creditRemaining: Int = 0,
    val averageLatencyMs: Double = 0.0,
    val lastError: String? = null,
) {

    /** One-line form, for logs. */
    override fun toString(): String =
        "PipelineStats(${"%.1f".format(fps)}fps, ${"%.1f".format(bytesPerSecond / 1024)}KB/s, " +
            "sent=$framesSent, dropped=$framesDropped, skipped=$framesSkipped, chunks=$chunksSent, " +
            "queue=$queueDepth, credit=$creditRemaining, latency=${"%.0f".format(averageLatencyMs)}ms" +
            (lastError?.let { ", error='$it'" } ?: "") + ")"

    /** Multi-line form, for a status panel such as the demo's `TextView`. */
    fun summary(): String = buildString {
        append("fps: ").append("%.1f".format(fps)).append('\n')
        append("throughput: ").append("%.1f".format(bytesPerSecond / 1024)).append(" KB/s\n")
        append("frames sent: ").append(framesSent).append('\n')
        append("frames dropped (credit): ").append(framesDropped).append('\n')
        append("frames skipped: ").append(framesSkipped).append('\n')
        append("chunks sent: ").append(chunksSent).append('\n')
        append("credit left: ").append(creditRemaining).append('\n')
        append("queue depth: ").append(queueDepth).append('\n')
        append("latency: ").append("%.0f".format(averageLatencyMs)).append(" ms")
        lastError?.let { append("\nlast error: ").append(it) }
    }
}
