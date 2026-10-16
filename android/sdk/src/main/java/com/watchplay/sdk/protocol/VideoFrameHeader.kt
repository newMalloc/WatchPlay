package com.watchplay.sdk.protocol

/**
 * Bit masks for the video sub-header's `vflags` byte (`04-video.md` §2.1).
 *
 * Distinct from [FrameFlags.Video], which lives in the 16-byte protocol frame header. The two carry
 * overlapping information on purpose (a capture tool can find frame boundaries without parsing
 * payloads); when they disagree, these bits — the ones the decoder actually receives — win. The bit
 * positions differ between the two, so never mix the constants.
 */
object VideoSubFlags {

    /** `0x01` — the frame is a random-access point (always set for MJPEG, IDR for H.264). */
    const val V_KEYFRAME: Int = 0x01

    /** `0x02` — chunk 0's `chunk_data` is prefixed with a 6-byte capture timestamp. */
    const val V_TS_PRESENT: Int = 0x02

    /** `0x04` — redundant form of `chunk_index == chunk_total - 1`, for capture analysis. */
    const val V_END_OF_FRAME: Int = 0x04

    /** `0x08` — H.264: later frames reference this one (not disposable). */
    const val V_REFERENCED: Int = 0x08

    /** Bits `4..7` are reserved and must be zero. */
    const val RESERVED_MASK: Int = 0xF0

    /** `true` when every bit in [mask] is present in `vflags`. */
    fun isSet(vflags: Int, mask: Int): Boolean = (vflags and mask) == mask
}

/**
 * The 20-byte video sub-header that prefixes **every** `VIDEO` chunk's payload (`04-video.md` §2).
 *
 * ```
 * offset size field
 * 0      4    frame_id      u32, sender-scoped, increments once per video frame
 * 4      2    chunk_index   u16, 0-based index of this chunk
 * 6      2    chunk_total   u16
 * 8      1    codec         VideoCodec code (1 = MJPEG, 2 = H.264)
 * 9      1    vflags        VideoSubFlags
 * 10     2    width         u16, encoded width
 * 12     2    height        u16, encoded height
 * 14     1    quality       u8, JPEG quality (0 for H.264)
 * 15     1    reserved      MUST be 0
 * 16     4    frame_bytes   u32, size of the **whole** encoded frame, not this chunk
 * 20     ...  chunk_data
 * ```
 *
 * `frame_bytes` is what makes reassembly self-checking: the receiver knows the exact target length
 * before the last chunk arrives, so a truncated frame is detected rather than decoded
 * (`04-video.md` §3.1, §3.3).
 *
 * The capture timestamp is **not** in this header. `04-video.md` §2.3 puts it in `chunk_data` of chunk
 * 0 only, gated by [VideoSubFlags.V_TS_PRESENT], because repeating 6 bytes in all ~37 chunks would cost
 * about 2.7% of the media budget for no benefit.
 *
 * @property frameId frame counter, shared by all chunks of one frame.
 * @property chunkIndex 0-based index of this chunk.
 * @property chunkTotal total chunks in the frame.
 * @property codec codec of the media data.
 * @property vflags [VideoSubFlags] bits.
 * @property widthPx encoded width.
 * @property heightPx encoded height.
 * @property quality JPEG quality, `1..100` for MJPEG and `0` for H.264.
 * @property frameBytes encoded size of the whole frame in bytes.
 */
data class VideoFrameHeader(
    val frameId: Long,
    val chunkIndex: Int,
    val chunkTotal: Int,
    val codec: VideoCodec,
    val vflags: Int,
    val widthPx: Int,
    val heightPx: Int,
    val quality: Int = 0,
    val frameBytes: Long,
) {

    init {
        require(frameId in 0L..0xFFFFFFFFL) { "frameId out of u32 range: $frameId" }
        require(chunkTotal in 1..0xFFFF) { "chunkTotal out of u16 range: $chunkTotal" }
        require(chunkIndex in 0 until chunkTotal) { "chunkIndex $chunkIndex outside 0..${chunkTotal - 1}" }
        require(widthPx in 1..0xFFFF) { "widthPx out of range: $widthPx" }
        require(heightPx in 1..0xFFFF) { "heightPx out of range: $heightPx" }
        require(quality in 0..100) { "quality out of range: $quality" }
        require(frameBytes in 0L..0xFFFFFFFFL) { "frameBytes out of u32 range: $frameBytes" }
    }

    /** `true` when this chunk belongs to a random-access frame. */
    val isKeyframe: Boolean
        get() = VideoSubFlags.isSet(vflags, VideoSubFlags.V_KEYFRAME)

    /** `true` when chunk 0 carries a 6-byte capture timestamp ahead of its media data. */
    val hasTimestamp: Boolean
        get() = VideoSubFlags.isSet(vflags, VideoSubFlags.V_TS_PRESENT)

    /** `true` when this is the final chunk, by index or by explicit flag. */
    val isEndOfFrame: Boolean
        get() = VideoSubFlags.isSet(vflags, VideoSubFlags.V_END_OF_FRAME) || chunkIndex == chunkTotal - 1

    /** `true` when later H.264 frames depend on this one. */
    val isReferenced: Boolean
        get() = VideoSubFlags.isSet(vflags, VideoSubFlags.V_REFERENCED)

    /** Serialises the sub-header into exactly [SIZE_BYTES] bytes. */
    fun encode(): ByteArray = ByteArray(SIZE_BYTES).also { encodeInto(it, 0) }

    /** Serialises the sub-header into [destination] at [offset]. */
    fun encodeInto(destination: ByteArray, offset: Int = 0) {
        LittleEndian.requireRange(destination, offset, SIZE_BYTES)
        LittleEndian.writeU32(destination, offset, frameId)
        LittleEndian.writeU16(destination, offset + 4, chunkIndex)
        LittleEndian.writeU16(destination, offset + 6, chunkTotal)
        LittleEndian.writeU8(destination, offset + 8, codec.code)
        LittleEndian.writeU8(destination, offset + 9, vflags)
        LittleEndian.writeU16(destination, offset + 10, widthPx)
        LittleEndian.writeU16(destination, offset + 12, heightPx)
        LittleEndian.writeU8(destination, offset + 14, quality)
        LittleEndian.writeU8(destination, offset + 15, 0) // reserved, MUST be 0
        LittleEndian.writeU32(destination, offset + 16, frameBytes)
    }

    override fun toString(): String =
        "VideoFrameHeader(frame=$frameId, chunk=$chunkIndex/$chunkTotal, " +
            "codec=${codec.displayName}, ${widthPx}x$heightPx, q=$quality, frameBytes=$frameBytes, " +
            "vflags=0x%02X)".format(vflags)

    companion object {
        /** Fixed sub-header size (`04-video.md` §2). */
        const val SIZE_BYTES: Int = ProtocolLimits.VIDEO_SUBHEADER_SIZE

        /** Bytes the optional capture timestamp occupies at the head of chunk 0's `chunk_data`. */
        const val CAPTURE_TS_BYTES: Int = 6

        /**
         * Parses a sub-header from the start of a chunk payload.
         *
         * @throws ProtocolException when fewer than [SIZE_BYTES] bytes are available or the codec id is
         *   unknown — an undecodable frame must be dropped, never guessed at.
         */
        fun decode(data: ByteArray, offset: Int = 0): VideoFrameHeader {
            if (offset < 0 || offset + SIZE_BYTES > data.size) {
                throw ProtocolException(
                    "video sub-header needs $SIZE_BYTES bytes at offset $offset, have ${data.size - offset}",
                )
            }
            val rawCodec = LittleEndian.readU8(data, offset + 8)
            val codec = VideoCodec.fromCode(rawCodec)
                ?: throw ProtocolException("unknown video codec id 0x%02X".format(rawCodec))
            return VideoFrameHeader(
                frameId = LittleEndian.readU32(data, offset),
                chunkIndex = LittleEndian.readU16(data, offset + 4),
                chunkTotal = LittleEndian.readU16(data, offset + 6),
                codec = codec,
                vflags = LittleEndian.readU8(data, offset + 9),
                widthPx = LittleEndian.readU16(data, offset + 10),
                heightPx = LittleEndian.readU16(data, offset + 12),
                quality = LittleEndian.readU8(data, offset + 14),
                frameBytes = LittleEndian.readU32(data, offset + 16),
            )
        }

        /** Like [decode] but returns `null` instead of throwing, for defensive receive paths. */
        fun decodeOrNull(data: ByteArray, offset: Int = 0): VideoFrameHeader? = try {
            decode(data, offset)
        } catch (exception: ProtocolException) {
            null
        }

        /**
         * The authoritative chunk count: `max(1, ceil(frame_bytes / (max_chunk_bytes - 20)))`.
         *
         * `04-video.md` §3.1 calls the off-by-20 variant the most common bug in the whole protocol:
         * using `ceil(frame_bytes / max_chunk_bytes)` yields 34 instead of 37 for the reference
         * 8123-byte JPEG, and the receiver then decodes a truncated image.
         */
        fun chunkTotalFor(frameBytes: Int, maxChunkBytes: Int): Int {
            require(frameBytes >= 0) { "frameBytes must not be negative" }
            val mediaPerChunk = maxChunkBytes - SIZE_BYTES
            require(mediaPerChunk > 0) { "maxChunkBytes must exceed the $SIZE_BYTES-byte sub-header" }
            return LittleEndian.ceilDiv(frameBytes, mediaPerChunk).coerceAtLeast(1)
        }
    }
}
