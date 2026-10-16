package com.watchplay.sdk.encoder

import com.watchplay.sdk.protocol.VideoCodec

/**
 * How the phone encodes the mirrored watch UI.
 *
 * The two subclasses mirror the two profiles of `docs/spec/00-overview.md` §2, and each maps onto a
 * very different Android encoder:
 *
 *  * [Mjpeg] — Profile A, the reference profile. One independently decodable JPEG per frame. Any frame
 *    can be lost without corrupting the next, which is exactly what a lossy BLE link needs, and even
 *    an ESP32-S3 decodes it in hardware. Higher bandwidth.
 *  * [H264] — Profile B, for chips with an H.264 decoder. Lower bandwidth, but frames depend on each
 *    other, so loss recovery needs a keyframe and the `DISCONTINUITY` flag.
 *
 * Parameter names were requested as `Mjpeg(quality)` / `H264(bitrate, gop, baseline, noBFrame)`; they
 * are spelled out here (`jpegQuality`, `bitrateBps`, `gopFrames`) so that no reader has to guess a
 * unit. `baseline` and `noBFrame` are separate flags because a device can support one without the
 * other, even though the reference profile wants both.
 */
sealed class EncodeProfile {

    /** Codec this profile produces. */
    abstract val codec: VideoCodec

    /**
     * Quality indicator reported to the watch: JPEG quality (1–100) for [Mjpeg], always `0` for
     * [H264] because the concept does not apply.
     */
    abstract val quality: Int

    /**
     * Profile A: per-frame JPEG / MJPEG.
     *
     * @property jpegQuality libjpeg quality passed to `Bitmap.compress` or `MediaCodec`'s JPEG encoder,
     *   `1..100`. The spec's bandwidth target (150–300 KB/s at 10 fps on a 466×466 panel) lands around
     *   `60..75`.
     */
    data class Mjpeg(
        val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ) : EncodeProfile() {

        init {
            require(jpegQuality in 1..100) { "jpegQuality must be in 1..100, was $jpegQuality" }
        }

        override val codec: VideoCodec
            get() = VideoCodec.MJPEG

        override val quality: Int
            get() = jpegQuality
    }

    /**
     * Profile B: H.264, low latency, no B-frames.
     *
     * @property bitrateBps target bitrate in bits per second. The spec's Profile B budget is
     *   200–800 kbps, so `200_000..800_000`.
     * @property gopFrames keyframe interval in frames. Small values cost bandwidth but bound recovery
     *   time; `15..30` is sensible at 10–15 fps.
     * @property baseline request Baseline profile (no B-frames, no CABAC, constrained intra prediction)
     *   — the only H.264 profile a low-end hardware decoder can be trusted with.
     * @property noBFrame request zero B-frames explicitly. Kept independent of [baseline] because a
     *   misbehaving encoder vendor may advertise Baseline while still emitting B-frames.
     */
    data class H264(
        val bitrateBps: Int = DEFAULT_BITRATE_BPS,
        val gopFrames: Int = DEFAULT_GOP_FRAMES,
        val baseline: Boolean = true,
        val noBFrame: Boolean = true,
    ) : EncodeProfile() {

        init {
            require(bitrateBps > 0) { "bitrateBps must be positive, was $bitrateBps" }
            require(gopFrames >= 1) { "gopFrames must be at least 1, was $gopFrames" }
        }

        override val codec: VideoCodec
            get() = VideoCodec.H264

        override val quality: Int
            get() = 0
    }

    companion object {
        /** Default JPEG quality: inside the spec's Profile A bandwidth target at 10 fps. */
        const val DEFAULT_JPEG_QUALITY: Int = 70

        /** Default H.264 bitrate: the middle of the spec's 200–800 kbps Profile B range. */
        const val DEFAULT_BITRATE_BPS: Int = 500_000

        /** Default keyframe interval, i.e. a keyframe roughly every two seconds at 15 fps. */
        const val DEFAULT_GOP_FRAMES: Int = 30

        /**
         * Builds the profile that matches [codec], using defaults. Used by the session negotiator once
         * the codec is agreed.
         */
        fun defaultFor(codec: VideoCodec): EncodeProfile = when (codec) {
            VideoCodec.MJPEG -> Mjpeg()
            VideoCodec.H264 -> H264()
        }
    }
}
