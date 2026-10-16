package com.watchplay.sdk.protocol

/**
 * Video codecs WatchPlay can negotiate.
 *
 * The specification (`docs/spec/00-overview.md` §2) defines two profiles:
 *
 *  * **Profile A — [MJPEG]** — the reference profile. Every frame is an independent JPEG, which the
 *    ESP32-S3 can decode in hardware, at 10–15 fps. It tolerates arbitrary frame loss.
 *  * **Profile B — [H264]** — MediaCodec Baseline for chips with an H.264 decoder (e.g. ATS3089),
 *    15–30 fps at lower bandwidth. Requires the receiver to keep decoder state, so a dropped frame
 *    needs a keyframe and the `DISCONTINUITY` flag.
 *
 * The numeric codes are provisional: `docs/spec/07-capabilities.md` had not been written when this
 * SDK skeleton was created, so the capability bitmask values here are this SDK's proposal and must be
 * reconciled with that document before the first firmware handshake is attempted.
 */
enum class VideoCodec(
    /** Wire value used in capability bitmasks and in the `VideoFrameHeader.codec` byte. */
    val code: Int,
    /** Android `MediaCodec` MIME type, for the encoder implementations. */
    val mediaCodecMime: String,
    /** Stable human-readable name for logs. */
    val displayName: String,
) {

    /** Profile A: one JPEG per frame; no inter-frame state, loss-tolerant, hardware-decodable. */
    MJPEG(code = 0x01, mediaCodecMime = "image/jpeg", displayName = "MJPEG"),

    /** Profile B: H.264 Baseline, low latency, no B-frames. */
    H264(code = 0x02, mediaCodecMime = "video/avc", displayName = "H.264");

    companion object {
        /** Resolves a wire value, or `null` when the codec is unknown to this SDK version. */
        fun fromCode(code: Int): VideoCodec? = entries.firstOrNull { it.code == code }

        /**
         * Packs a set of codecs into the capability bitmask (bit `code - 1`).
         *
         * Provisional encoding — see the class KDoc.
         */
        fun toBitmask(codecs: Set<VideoCodec>): Int = codecs.fold(0) { mask, codec -> mask or (1 shl (codec.code - 1)) }

        /** Unpacks a capability bitmask, ignoring bits this SDK does not know. */
        fun fromBitmask(mask: Int): Set<VideoCodec> =
            entries.filterTo(linkedSetOf()) { mask and (1 shl (it.code - 1)) != 0 }
    }
}
