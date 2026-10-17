package com.watchplay.sdk.encoder

import com.watchplay.sdk.capture.CaptureResult
import com.watchplay.sdk.protocol.VideoCodec

/**
 * Turns captured frames into coded bytes.
 *
 * ## Contract
 *
 * ```
 *   prepare(profile, w, h) -> configure the codec; false means "this profile is not usable"
 *   encode(capture, ptsUs) -> coded bytes, or null when this frame cannot be produced
 *   requestKeyframe()      -> ask for an IDR at the next opportunity (H.264 only)
 *   release()              -> stop the codec and free buffers
 * ```
 *
 * `encode` returning `null` is **not** fatal: the pipeline counts it as a skipped frame and keeps
 * going. A hardware `MediaCodec` legitimately refuses work while it is being reconfigured or while all
 * of its input buffers are busy, and an implementation must never throw for that — a throw would kill
 * the pipeline thread and end the stream. Failures set [lastError] instead.
 *
 * ## Latency rules
 *
 * Every implementation of this interface must produce a frame that is *immediately decodable*:
 *
 *  * no frame reordering — `KEY_FLAG`-style PTS reordering is meaningless for MJPEG and forbidden for
 *    the H.264 profile (`noBFrame`),
 *  * no buffering of multiple frames inside the encoder,
 *  * Baseline profile only for H.264, because that is what the watch's hardware decoder supports.
 *
 * ## Implementations
 *
 *  * [MjpegEncoder] — working today on the CPU path; the Profile A reference encoder.
 *  * [H264Encoder] — stub; needs `MediaCodec` plus an input `Surface` from the GL capture path.
 *
 * ## Threading
 *
 * `prepare`/`release` are called from the main thread; `encode` from the pipeline thread. The shipped
 * implementations keep that safe by confining mutable state to `prepare`-then-`encode` ordering.
 */
interface EncoderModule {

    /** Short implementation name for logs, e.g. `mjpeg-cpu`. */
    val name: String

    /** Codec currently configured, or `null` before a successful [prepare]. */
    val codec: VideoCodec?

    /** `true` once [prepare] succeeded and [release] has not been called. */
    val isReady: Boolean

    /** Last recoverable failure, or `null`. Never used for control flow. */
    val lastError: String?

    /** Configured output width in pixels; `0` before [prepare]. */
    val width: Int

    /** Configured output height in pixels; `0` before [prepare]. */
    val height: Int

    /**
     * Configures the encoder for [profile] and a [width] x [height] output.
     *
     * Implementations must reject a profile they cannot honour (for example a CPU MJPEG encoder asked
     * for H.264) by returning `false` and setting [lastError], so the negotiator can fall back.
     */
    fun prepare(profile: EncodeProfile, width: Int, height: Int): Boolean

    /**
     * Encodes one captured frame.
     *
     * @param capture the frame to encode; implementations that need CPU pixels require
     *   [CaptureResult.rgbaBytes] and must return `null` (with [lastError]) when it is absent.
     * @param ptsUs presentation timestamp in microseconds, propagated from the capture.
     */
    fun encode(capture: CaptureResult, ptsUs: Long): EncodedFrame?

    /** Requests a keyframe as soon as the codec can produce one. A no-op for MJPEG. */
    fun requestKeyframe()

    /**
     * Changes the quality of subsequent frames without re-preparing the codec.
     *
     * `04-video.md` §6 requires this to be a runtime knob: a re-prepare costs frames, and on H.264 it
     * also forces a new IDR. An encoder that is not quality-driven (bitrate-driven H.264, or a stub that
     * cannot encode at all) returns `false` and keeps its current settings — that is not an error, and
     * the pipeline keeps streaming.
     *
     * @param quality the §6 quality, `40..85`.
     * @return `true` when the next [encode] call uses it.
     */
    fun setQuality(quality: Int): Boolean = false

    /** Stops the codec and frees its buffers. Safe to call more than once. */
    fun release()
}
