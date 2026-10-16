package com.watchplay.sdk.encoder

import com.watchplay.sdk.protocol.VideoCodec

/**
 * One encoded frame, ready for [com.watchplay.sdk.protocol.VideoFrameChunker].
 *
 * This is the boundary between the encoder package and the protocol package: nothing above this type
 * knows whether the bytes came from a JPEG compressor, a `MediaCodec` output buffer or a test double.
 *
 * @property data the coded image bytes: one complete JPEG for [VideoCodec.MJPEG], one access unit for
 *   [VideoCodec.H264].
 * @property codec codec that produced [data].
 * @property isKeyframe `true` when the frame can be decoded without reference to any previous frame.
 *   Always `true` for MJPEG; for H.264 it means IDR. It becomes the `KEYFRAME` flag in both the frame
 *   header and the video header.
 * @property ptsUs presentation timestamp in microseconds, matching `MediaCodec`'s convention. The
 *   protocol carries milliseconds ([com.watchplay.sdk.protocol.VideoFrameHeader.captureTsMs]), so
 *   whoever chunks the frame divides by 1000.
 * @property width coded width in pixels.
 * @property height coded height in pixels.
 * @property quality quality actually used, `0` when the codec has no quality knob.
 */
data class EncodedFrame(
    val data: ByteArray,
    val codec: VideoCodec,
    val isKeyframe: Boolean,
    val ptsUs: Long,
    val width: Int,
    val height: Int,
    val quality: Int = 0,
) {

    init {
        require(data.isNotEmpty()) { "an encoded frame must carry data" }
        require(width > 0 && height > 0) { "coded size must be positive: ${width}x$height" }
    }

    /** Size of [data] in bytes. */
    val sizeBytes: Int
        get() = data.size

    /** Structural equality that compares [data] contents rather than array identity. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFrame) return false
        return codec == other.codec &&
            isKeyframe == other.isKeyframe &&
            ptsUs == other.ptsUs &&
            width == other.width &&
            height == other.height &&
            quality == other.quality &&
            data.contentEquals(other.data)
    }

    /** Consistent with [equals]. */
    override fun hashCode(): Int {
        var result = codec.hashCode()
        result = 31 * result + isKeyframe.hashCode()
        result = 31 * result + ptsUs.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + quality
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String =
        "EncodedFrame(${codec.displayName}, ${sizeBytes}B, ${width}x$height, keyframe=$isKeyframe, " +
            "pts=${ptsUs}us, q=$quality)"
}
