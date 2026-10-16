package com.watchplay.sdk.protocol

/**
 * A frame split into `VIDEO` frames, ready to hand to the transport.
 *
 * @property header the sub-header embedded in chunk 0, including the final `chunkTotal`.
 * @property chunkTotal number of chunks, equal to `frames.size`.
 * @property frames the `VIDEO` frames in ascending `chunk_index` order.
 * @property frameBytes encoded media size, excluding the sub-header and any timestamp.
 * @property captureTsMs the timestamp actually embedded in chunk 0, or `null` when none was sent.
 */
data class ChunkedVideoFrame(
    val header: VideoFrameHeader,
    val chunkTotal: Int,
    val frames: List<Frame>,
    val frameBytes: Int,
    val captureTsMs: Long? = null,
)

/**
 * Sender-side chunking, exactly as `04-video.md` §3 specifies.
 *
 * Each chunk's payload is `20-byte sub-header + chunk_data`, so a chunk's media capacity is
 * `max_chunk_bytes - 20`. All chunks of one frame share the frame's protocol `seq` (`04-video.md` §9)
 * because ordering is carried by `chunk_index`; `seq` identifies the frame.
 *
 * When [chunk] is asked to embed a capture timestamp, the 6 bytes go at the head of chunk 0's
 * `chunk_data`, which reduces chunk 0's media capacity and can therefore add one chunk for very small
 * `max_chunk_bytes` values. v0.1 senders may omit the timestamp entirely
 * (`V_TS_PRESENT = 0`, `04-video.md` §2.3), which is the default here.
 *
 * @property maxChunkBytes negotiated `max_chunk_bytes`: the `payload_len` ceiling **including** the
 *   sub-header. Must be at least [ProtocolLimits.MIN_MAX_CHUNK_BYTES] (84) per `03-session.md` §4.2
 *   step 5.
 */
class VideoFrameChunker(
    val maxChunkBytes: Int = ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES,
) {

    init {
        require(maxChunkBytes in ProtocolLimits.MIN_MAX_CHUNK_BYTES..ProtocolLimits.MAX_PAYLOAD_BYTES) {
            "maxChunkBytes must be in ${ProtocolLimits.MIN_MAX_CHUNK_BYTES}..${ProtocolLimits.MAX_PAYLOAD_BYTES}, " +
                "was $maxChunkBytes"
        }
    }

    /** Media bytes a chunk can carry, `max_chunk_bytes - 20`. */
    val mediaBytesPerChunk: Int
        get() = maxChunkBytes - VideoFrameHeader.SIZE_BYTES

    /** Chunk count for [frameBytes] of media; see [VideoFrameHeader.chunkTotalFor]. */
    fun chunkTotalFor(frameBytes: Int): Int =
        VideoFrameHeader.chunkTotalFor(frameBytes, maxChunkBytes)

    /**
     * Splits encoded media into `VIDEO` frames.
     *
     * @param frameId frame counter, copied into every chunk's sub-header.
     * @param codedData encoded media (one JPEG for MJPEG, one access unit for H.264), never mutated.
     * @param codec codec that produced [codedData].
     * @param widthPx encoded width.
     * @param heightPx encoded height.
     * @param quality JPEG quality, or `0` for H.264.
     * @param seq protocol sequence number, shared by every chunk of this frame.
     * @param isKeyframe sets `V_KEYFRAME` and the frame-header `KEYFRAME` bit.
     * @param referenced sets `V_REFERENCED` (H.264 non-disposable frames).
     * @param discontinuity sets `DISCONTINUITY` in the frame header: frames were dropped before this.
     * @param captureTsMs when non-null, chunk 0 carries a 6-byte millisecond timestamp.
     * @param offset first byte of [codedData] to send.
     * @param length number of bytes of [codedData] to send.
     */
    fun chunk(
        frameId: Long,
        codedData: ByteArray,
        codec: VideoCodec,
        widthPx: Int,
        heightPx: Int,
        quality: Int,
        seq: Int,
        isKeyframe: Boolean,
        referenced: Boolean = false,
        discontinuity: Boolean = false,
        captureTsMs: Long? = null,
        offset: Int = 0,
        length: Int = codedData.size - offset,
    ): ChunkedVideoFrame {
        LittleEndian.requireRange(codedData, offset, length)

        val tsBytes = if (captureTsMs != null) VideoFrameHeader.CAPTURE_TS_BYTES else 0
        val chunkTotal = if (tsBytes == 0) {
            LittleEndian.ceilDiv(length, mediaBytesPerChunk).coerceAtLeast(1)
        } else {
            // The timestamp eats into chunk 0's media capacity, so count it as extra bytes.
            LittleEndian.ceilDiv(length + tsBytes, mediaBytesPerChunk).coerceAtLeast(1)
        }

        val frames = ArrayList<Frame>(chunkTotal)
        var mediaCursor = offset
        val mediaEnd = offset + length

        for (index in 0 until chunkTotal) {
            val capacity = if (index == 0) mediaBytesPerChunk - tsBytes else mediaBytesPerChunk
            val take = minOf(capacity, mediaEnd - mediaCursor)
            val chunkData = ByteArray(tsBytes + take)
            if (tsBytes > 0) {
                // 48-bit little-endian timestamp in the low 6 bytes.
                val timestamp = captureTsMs ?: 0L
                for (byteIndex in 0 until VideoFrameHeader.CAPTURE_TS_BYTES) {
                    chunkData[byteIndex] = (timestamp ushr (8 * byteIndex)).toByte()
                }
            }
            if (take > 0) {
                codedData.copyInto(
                    chunkData,
                    destinationOffset = tsBytes,
                    startIndex = mediaCursor,
                    endIndex = mediaCursor + take,
                )
            }
            mediaCursor += take

            var vflags = 0
            if (isKeyframe) vflags = vflags or VideoSubFlags.V_KEYFRAME
            if (referenced) vflags = vflags or VideoSubFlags.V_REFERENCED
            if (index == 0 && tsBytes > 0) vflags = vflags or VideoSubFlags.V_TS_PRESENT
            if (index == chunkTotal - 1) vflags = vflags or VideoSubFlags.V_END_OF_FRAME

            val header = VideoFrameHeader(
                frameId = frameId,
                chunkIndex = index,
                chunkTotal = chunkTotal,
                codec = codec,
                vflags = vflags,
                widthPx = widthPx,
                heightPx = heightPx,
                quality = quality,
                frameBytes = length.toLong(),
            )

            val payload = ByteArray(VideoFrameHeader.SIZE_BYTES + chunkData.size)
            header.encodeInto(payload, 0)
            chunkData.copyInto(payload, destinationOffset = VideoFrameHeader.SIZE_BYTES)

            var frameFlags = 0
            if (isKeyframe) frameFlags = FrameFlags.withFlag(frameFlags, FrameFlags.Video.KEYFRAME)
            if (discontinuity) frameFlags = FrameFlags.withFlag(frameFlags, FrameFlags.Video.DISCONTINUITY)
            if (index == 0) frameFlags = FrameFlags.withFlag(frameFlags, FrameFlags.Video.FIRST_CHUNK)
            if (index == chunkTotal - 1) frameFlags = FrameFlags.withFlag(frameFlags, FrameFlags.Video.LAST_CHUNK)

            frames += Frame(typeCode = FrameType.VIDEO.code, flags = frameFlags, seq = seq, payload = payload)
        }

        check(mediaCursor == mediaEnd) {
            "chunking consumed ${mediaCursor - offset} of $length media bytes"
        }

        return ChunkedVideoFrame(
            header = VideoFrameHeader.decode(frames[0].payload),
            chunkTotal = chunkTotal,
            frames = frames,
            frameBytes = length,
            captureTsMs = captureTsMs,
        )
    }
}
