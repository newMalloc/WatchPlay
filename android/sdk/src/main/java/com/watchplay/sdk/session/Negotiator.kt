package com.watchplay.sdk.session

import com.watchplay.sdk.protocol.CapsAck
import com.watchplay.sdk.protocol.CapsResult
import com.watchplay.sdk.protocol.CapsStream
import com.watchplay.sdk.protocol.Capabilities
import com.watchplay.sdk.protocol.ColorFormat
import com.watchplay.sdk.protocol.H264Caps
import com.watchplay.sdk.protocol.ProtocolLimits
import com.watchplay.sdk.protocol.ResultStatus
import com.watchplay.sdk.protocol.VideoCodec
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Result of negotiation: either a complete `CAPS_ACK` to send, or a rejection.
 *
 * @property ack the payload to send (always present, including for rejections, because
 *   `03-session.md` §4.3 requires `REJECT_REASON` plus `RESULT` even on failure).
 * @property status one of [ResultStatus].
 * @property rejectReason human-readable cause when [status] is not `OK`.
 * @property display the watch's display TLV, for building the render layout; `null` when absent.
 */
data class NegotiationOutcome(
    val ack: CapsAck,
    val status: Int,
    val rejectReason: String? = null,
    val display: com.watchplay.sdk.protocol.DisplayCaps? = null,
) {

    /** `true` when the session may enter `STREAMING`. */
    val succeeded: Boolean
        get() = status == ResultStatus.OK

    /** The decided parameters, or `null` for a rejection. */
    val result: CapsResult?
        get() = ack.result

    override fun toString(): String =
        "NegotiationOutcome(${ResultStatus.describe(status)}" +
            (rejectReason?.let { ": $it" } ?: "") + ", result=${ack.result})"
}

/**
 * Decides the session parameters from the watch's `CAPS` — the phone side of
 * `03-session.md` §4.2.
 *
 * ## The algorithm, in the specification's own order
 *
 * ```
 * 1. width/height   = min(watch DISPLAY, local capability); v0.1: exactly the watch's DISPLAY
 * 2. codec          = H.264 if the watch declares CODEC_H264 with hardware_decode == 1 and the user
 *                     has not forced MJPEG; else MJPEG if declared; else NO_COMMON_CODEC
 * 3. target_fps     = min(watch codec max_fps, local ceiling, user setting), clamped to [1, 30]
 * 4. quality/bitrate: MJPEG -> clamp(preferred, 40, min(watch max_quality, 85))
 *                     H.264 -> min(watch max_bitrate_kbps * 1000, width * height * fps * 0.12)
 * 5. max_chunk_bytes = min(local, watch, bearer budget), and never below 84
 * 6. gop            = clamp(round(fps * 0.8), 8, 12)
 * 7. initial_credits = clamp(free_queue_slots, 16, 128)
 * ```
 *
 * Step 2a is the most consequential: ESP32-S3 watches advertise `hardware_decode = 0` with
 * `max_fps = 8`, so requiring hardware decoding keeps the session on MJPEG — which is faster, cheaper
 * and lower-power than a software H.264 decoder on the watch.
 *
 * ## Reproducibility
 *
 * The same inputs always produce the same outputs (`03-session.md` §4.2 requires this so the algorithm
 * can be unit-tested). The only non-deterministic input, `RESULT.session_id`, is therefore a parameter
 * with a deterministic default.
 *
 * ## Degradation order
 *
 * `07-capabilities.md` §3.3 fixes what gives first: keep decodability, then resolution, then frame rate,
 * then quality — resolution is protected because non-integer scaling blurs text, and text is what a
 * watch UI mostly carries.
 *
 * ## A specification conflict worth knowing
 *
 * `01-transport.md` §2.2 step 3 says a negotiated MTU below 247 sets `max_chunk_bytes = MTU − 3`
 * (20 bytes at MTU 23), while `03-session.md` §4.2 step 5 requires `max_chunk_bytes >= 84` and
 * `04-video.md` §3.1 divides by `max_chunk_bytes − 20`. Those cannot all hold at MTU 23: this
 * implementation enforces the >= 84 floor (so the chunk arithmetic stays well defined) and treats the
 * bearer's `bearer_tu` as fragmentation granularity only. Raise with the spec maintainer; see the
 * project report.
 *
 * @property config phone-side ceilings.
 */
class Negotiator(
    private val config: SessionConfig = SessionConfig(),
) {

    /**
     * Computes the `CAPS_ACK` for [capabilities].
     *
     * @param sessionId random session id for logs and captures; passed in so tests stay deterministic.
     */
    fun negotiate(capabilities: Capabilities, sessionId: Long = 0L): NegotiationOutcome {
        if (!capabilities.isComplete) {
            val missing = buildList {
                if (capabilities.deviceInfo == null) add("DEVICE_INFO")
                if (capabilities.display == null) add("DISPLAY")
                if (capabilities.supportedCodecs.isEmpty()) add("CODEC_MJPEG|CODEC_H264")
            }
            return reject(
                capabilities = capabilities,
                status = ResultStatus.CAPS_MALFORMED,
                reason = "CAPS is missing mandatory TLV(s): ${missing.joinToString()}",
                sessionId = sessionId,
            )
        }

        val display = capabilities.display!!
        if (display.widthPx <= 0 || display.heightPx <= 0) {
            return reject(
                capabilities = capabilities,
                status = ResultStatus.RESOLUTION_UNSUPPORTED,
                reason = "watch reported a degenerate panel ${display.widthPx}x${display.heightPx}",
                sessionId = sessionId,
            )
        }

        val codec = selectCodec(capabilities)
            ?: return reject(
                capabilities = capabilities,
                status = ResultStatus.NO_COMMON_CODEC,
                reason = buildString {
                    append("no usable common codec; watch offers ")
                    append(capabilities.supportedCodecs.joinToString("/") { it.displayName }.ifEmpty { "nothing" })
                    if (capabilities.h264 != null && capabilities.h264?.hardwareDecode == false) {
                        append(" (H.264 has no hardware decoder, so Profile B is not selected)")
                    }
                },
                sessionId = sessionId,
            )

        // Step 1: v0.1 always renders at the watch's native resolution.
        val widthPx = display.widthPx
        val heightPx = display.heightPx

        // Step 3.
        val watchMaxFps = capabilities.maxFpsFor(codec)
        val targetFps = minOf(watchMaxFps.coerceAtLeast(1), config.maxFps, ProtocolLimits.MAX_FPS)
            .coerceIn(ProtocolLimits.MIN_FPS, ProtocolLimits.MAX_FPS)

        // Step 4.
        val quality: Int
        val targetBitrateBps: Long
        if (codec == VideoCodec.MJPEG) {
            val watchMaxQuality = capabilities.mjpeg?.maxQuality ?: ProtocolLimits.MAX_QUALITY
            val upper = min(watchMaxQuality, ProtocolLimits.MAX_QUALITY)
            quality = config.preferredQuality.coerceIn(ProtocolLimits.MIN_QUALITY, maxOf(ProtocolLimits.MIN_QUALITY, upper))
            targetBitrateBps = 0L
        } else {
            quality = 0
            val watchBitrate = (capabilities.h264?.maxBitrateKbps ?: 0) * 1000L
            val heuristic = (widthPx.toLong() * heightPx.toLong() * targetFps * BITS_PER_PIXEL_PER_FRAME).toLong()
            targetBitrateBps = if (watchBitrate > 0) min(watchBitrate, heuristic) else heuristic
        }

        // Step 5.
        val watchChunkHint = capabilities.transport?.mtuPayload?.takeIf { it > 0 } ?: config.maxChunkBytesCeiling
        val maxChunkBytes = minOf(config.maxChunkBytesCeiling, watchChunkHint, config.bearerTuBytes)
            .coerceIn(ProtocolLimits.MIN_MAX_CHUNK_BYTES, ProtocolLimits.MAX_PAYLOAD_BYTES)

        // Step 6.
        val gop = if (codec == VideoCodec.H264) {
            (targetFps * GOP_SECONDS).roundToInt().coerceIn(ProtocolLimits.MIN_GOP, ProtocolLimits.MAX_GOP)
        } else {
            0
        }

        // Step 7.
        val initialCredits = config.freeQueueSlots
            .coerceIn(ProtocolLimits.MIN_INITIAL_CREDITS, ProtocolLimits.MAX_INITIAL_CREDITS)

        val result = CapsResult.ok(
            codec = codec,
            widthPx = widthPx,
            heightPx = heightPx,
            targetFps = targetFps,
            quality = quality,
            maxChunkBytes = maxChunkBytes,
            initialCredits = initialCredits,
            targetBitrateBps = targetBitrateBps,
            gop = gop,
            h264Profile = capabilities.h264?.profile ?: H264Caps.PROFILE_BASELINE,
            colorFormat = display.colorFormat,
            sessionId = sessionId,
        )
        val stream = CapsStream(
            maxFrameBytes = config.maxFrameBytes,
            deadlineMs = config.deadlineMs,
            heartbeatMs = config.heartbeatMs,
            drainTimeoutMs = config.drainTimeoutMs,
        )
        return NegotiationOutcome(
            ack = CapsAck(result = result, stream = stream),
            status = ResultStatus.OK,
            display = display,
        )
    }

    /**
     * Step 2: H.264 only when the watch can decode it in hardware (`03-session.md` §4.2 step 2a),
     * otherwise MJPEG, otherwise nothing.
     */
    private fun selectCodec(capabilities: Capabilities): VideoCodec? {
        val h264 = capabilities.h264
        if (config.allowH264 && h264 != null && h264.hardwareDecode) return VideoCodec.H264
        if (capabilities.mjpeg != null) return VideoCodec.MJPEG
        return null
    }

    private fun reject(
        capabilities: Capabilities,
        status: Int,
        reason: String,
        sessionId: Long,
    ): NegotiationOutcome {
        val display = capabilities.display
        val result = CapsResult(
            status = status,
            codec = VideoCodec.MJPEG,
            widthPx = display?.widthPx ?: 0,
            heightPx = display?.heightPx ?: 0,
            targetFps = 0,
            quality = 0,
            maxChunkBytes = ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES,
            initialCredits = 0,
            colorFormat = display?.colorFormat ?: ColorFormat.RGB565_BE,
            sessionId = sessionId,
        )
        val stream = CapsStream(
            maxFrameBytes = config.maxFrameBytes,
            deadlineMs = config.deadlineMs,
            heartbeatMs = config.heartbeatMs,
            drainTimeoutMs = config.drainTimeoutMs,
        )
        return NegotiationOutcome(
            ack = CapsAck(result = result, stream = stream, rejectReason = reason),
            status = status,
            rejectReason = reason,
            display = display,
        )
    }

    companion object {
        /** GOP target as a fraction of a second (`03-session.md` §4.2 step 6: 0.8 s). */
        const val GOP_SECONDS: Double = 0.8

        /** H.264 bitrate heuristic: bits per pixel per frame (`03-session.md` §4.2 step 4). */
        const val BITS_PER_PIXEL_PER_FRAME: Double = 0.12
    }
}
