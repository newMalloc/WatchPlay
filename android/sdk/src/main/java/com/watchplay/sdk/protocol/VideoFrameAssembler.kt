package com.watchplay.sdk.protocol

/**
 * Why an inbound `VIDEO` chunk could not be turned into a frame.
 *
 * Every reason costs exactly one frame and none of them is fatal to the session; each maps onto a
 * counter the watch reports back in `FEEDBACK` (`04-video.md` §4.2) so the phone can see why the
 * picture is bad rather than just that it is.
 */
enum class DropReason {

    /** The frame was not a `VIDEO` frame at all — a caller bug in the receive path. */
    NOT_A_VIDEO_FRAME,

    /** The payload was shorter than the 20-byte sub-header, or named an unknown codec. */
    MALFORMED_SUBHEADER,

    /** `payload_len` was below 21 (no media byte) or above the negotiated `max_chunk_bytes`. */
    CHUNK_SIZE_OUT_OF_RANGE,

    /** `frame_bytes` exceeded the reassembly budget from `CAPS_ACK.STREAM`. */
    FRAME_TOO_LARGE,

    /** A chunk's `chunk_total`/`codec`/`width`/`height`/`frame_bytes` disagreed with chunk 0. */
    CHUNK_MISMATCH,

    /** A chunk arrived out of order, or belonged to a frame that is no longer in flight. */
    OUT_OF_ORDER,

    /** Reassembled length did not equal `frame_bytes` — the last line of defence (`04-video.md` §3.1). */
    LENGTH_MISMATCH,

    /** An in-flight frame stopped receiving chunks and was abandoned. */
    STALE,
}

/**
 * A fully reassembled video frame, ready for the decoder.
 *
 * @property header the sub-header of chunk 0.
 * @property codedData the complete encoded media, timestamp prefix removed.
 * @property seq protocol sequence number shared by all of the frame's chunks.
 * @property captureTsMs the sender's capture timestamp when `V_TS_PRESENT` was set, else `null`.
 */
class AssembledVideoFrame(
    val header: VideoFrameHeader,
    val codedData: ByteArray,
    val seq: Int,
    val captureTsMs: Long? = null,
) {

    /** Frame counter from the sub-header. */
    val frameId: Long
        get() = header.frameId

    /** Size of [codedData]; always equal to `header.frameBytes`. */
    val sizeBytes: Int
        get() = codedData.size

    /** `true` when the frame is a random-access point. */
    val isKeyframe: Boolean
        get() = header.isKeyframe

    /** Codec of the media data. */
    val codec: VideoCodec
        get() = header.codec

    /** Equality including payload contents, not array identity. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssembledVideoFrame) return false
        return header == other.header && seq == other.seq &&
            captureTsMs == other.captureTsMs && codedData.contentEquals(other.codedData)
    }

    /** Consistent with [equals]. */
    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + seq
        result = 31 * result + (captureTsMs?.hashCode() ?: 0)
        result = 31 * result + codedData.contentHashCode()
        return result
    }

    override fun toString(): String =
        "AssembledVideoFrame(frame=$frameId, ${codec.displayName}, ${sizeBytes}B, " +
            "keyframe=$isKeyframe, seq=$seq, ts=$captureTsMs)"
}

/** Outcome of feeding one chunk into [VideoFrameAssembler]. */
sealed interface AssemblyResult {

    /** The frame is still incomplete. */
    data class Progress(
        val frameId: Long,
        val chunksReceived: Int,
        val chunksExpected: Int,
    ) : AssemblyResult

    /** The frame is complete and [frame] holds the media bytes. */
    data class Complete(val frame: AssembledVideoFrame) : AssemblyResult

    /** The chunk was discarded; the session continues. */
    data class Rejected(
        val reason: DropReason,
        val detail: String,
    ) : AssemblyResult
}

/**
 * Counters for a [VideoFrameAssembler], mirroring what the watch echoes in `FEEDBACK`.
 *
 * @property framesCompleted frames successfully reassembled.
 * @property framesRejected frames dropped, for any [DropReason].
 * @property chunksReceived total `VIDEO` chunks accepted.
 * @property bytesAssembled total media bytes delivered to the decoder.
 * @property lastDropReason most recent reason, for diagnostics.
 */
data class AssemblerStats(
    val framesCompleted: Long = 0L,
    val framesRejected: Long = 0L,
    val chunksReceived: Long = 0L,
    val bytesAssembled: Long = 0L,
    val lastDropReason: DropReason? = null,
)

/**
 * Reassembles `VIDEO` chunks into complete frames — the receive-side counterpart of
 * [VideoFrameChunker], implementing the invariants of `04-video.md` §3.1.
 *
 * ## How it works
 *
 * Every chunk carries the full 20-byte sub-header, so the assembler does **not** rely on arrival order
 * alone: it checks `chunk_index` against the expected next index, verifies every chunk agrees with
 * chunk 0 on `chunk_total`, `codec`, `width`, `height` and `frame_bytes`, strips the optional
 * timestamp from chunk 0, and finally asserts that the assembled length is **exactly** `frame_bytes`.
 * Any violation drops the whole frame — a partially decoded JPEG shows grey garbage, so a frame is
 * either complete or discarded (`04-video.md` §5.2).
 *
 * Chunks of one frame share a `seq`, so a change of `seq` mid-frame means the previous frame is gone.
 *
 * ## Buffering
 *
 * The buffer is allocated once at [maxFrameBytes], never per frame: `04-video.md` §3.3 forbids dynamic
 * allocation because changing frame sizes would fragment the heap on a long-running session. A frame
 * larger than [maxFrameBytes] is dropped, and an in-flight frame idle for longer than
 * [staleTimeoutMs] is abandoned by the next call or by [resetStale].
 *
 * ## Threading
 *
 * Not thread safe; confine one instance to the session's receive thread.
 *
 * @property maxFrameBytes reassembly ceiling, from `CAPS_ACK.STREAM.max_frame_bytes`.
 * @property maxChunkBytes negotiated `max_chunk_bytes`; a larger chunk is a protocol violation.
 * @property staleTimeoutMs how long an incomplete frame may sit before it is abandoned.
 */
class VideoFrameAssembler(
    private val maxFrameBytes: Int = ProtocolLimits.DEFAULT_MAX_FRAME_BYTES,
    private val maxChunkBytes: Int = ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES,
    private val staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS,
) {

    init {
        require(maxFrameBytes >= VideoFrameHeader.SIZE_BYTES) {
            "maxFrameBytes must fit at least one sub-header (${VideoFrameHeader.SIZE_BYTES})"
        }
        require(maxChunkBytes >= ProtocolLimits.MIN_MAX_CHUNK_BYTES) {
            "maxChunkBytes must be at least ${ProtocolLimits.MIN_MAX_CHUNK_BYTES}"
        }
        require(staleTimeoutMs > 0) { "staleTimeoutMs must be positive" }
    }

    private val buffer = ByteArray(maxFrameBytes)
    private var bufferSize = 0
    private var firstChunk: VideoFrameHeader? = null
    private var nextChunkIndex = 0
    private var frameSeq = 0
    private var frameStartedAtMs = 0L

    private var framesCompleted = 0L
    private var framesRejected = 0L
    private var chunksAccepted = 0L
    private var bytesAssembled = 0L
    private var lastDropReason: DropReason? = null

    /** `true` while a frame is partially assembled. */
    val isAssembling: Boolean
        get() = firstChunk != null

    /** Frame id of the in-flight frame, or `-1` when idle. */
    val activeFrameId: Long
        get() = firstChunk?.frameId ?: -1L

    /** Chunks accepted so far for the in-flight frame. */
    val chunksReceivedInFrame: Int
        get() = nextChunkIndex

    /** Media bytes buffered for the in-flight frame. */
    val bufferedBytes: Int
        get() = bufferSize

    /** Snapshot of the counters. */
    val stats: AssemblerStats
        get() = AssemblerStats(
            framesCompleted = framesCompleted,
            framesRejected = framesRejected,
            chunksReceived = chunksAccepted,
            bytesAssembled = bytesAssembled,
            lastDropReason = lastDropReason,
        )

    /**
     * Feeds one inbound protocol frame into the assembler.
     *
     * @param frame a frame received from the transport; only `VIDEO` frames are accepted.
     * @param nowMs current time in milliseconds, injectable so tests need no clock.
     */
    fun onChunk(frame: Frame, nowMs: Long = System.currentTimeMillis()): AssemblyResult {
        if (frame.typeCode != FrameType.VIDEO.code) {
            return reject(DropReason.NOT_A_VIDEO_FRAME, "frame type 0x%02X is not VIDEO".format(frame.typeCode))
        }

        resetStale(nowMs)?.let { return it }

        val payload = frame.payload
        if (payload.size <= VideoFrameHeader.SIZE_BYTES) {
            // `payload_len >= 21` is normative: a chunk with no media byte is a protocol violation.
            return reject(
                DropReason.CHUNK_SIZE_OUT_OF_RANGE,
                "payload of ${payload.size} bytes carries no media (needs > ${VideoFrameHeader.SIZE_BYTES})",
            )
        }
        if (payload.size > maxChunkBytes) {
            return reject(
                DropReason.CHUNK_SIZE_OUT_OF_RANGE,
                "payload of ${payload.size} bytes exceeds max_chunk_bytes $maxChunkBytes",
            )
        }
        val header = VideoFrameHeader.decodeOrNull(payload)
            ?: return reject(DropReason.MALFORMED_SUBHEADER, "payload has no valid video sub-header")

        val active = firstChunk
        if (active == null || header.chunkIndex == 0) {
            if (header.chunkIndex != 0) {
                return reject(
                    DropReason.OUT_OF_ORDER,
                    "chunk ${header.chunkIndex} of frame ${header.frameId} arrived with no frame in flight",
                )
            }
            if (active != null) {
                // A new frame started before the previous finished: the previous one is lost.
                rejectInFlight(
                    DropReason.OUT_OF_ORDER,
                    "frame ${active.frameId} superseded by frame ${header.frameId}",
                )
            }
            return startFrame(frame, header, nowMs)
        }

        if (frame.seq != frameSeq) {
            return rejectInFlight(
                DropReason.OUT_OF_ORDER,
                "chunk seq=${frame.seq} does not belong to frame ${active.frameId} (seq=$frameSeq)",
            )
        }
        if (header.frameId != active.frameId) {
            return rejectInFlight(
                DropReason.OUT_OF_ORDER,
                "chunk for frame ${header.frameId} while assembling frame ${active.frameId}",
            )
        }
        if (header.chunkIndex != nextChunkIndex) {
            return rejectInFlight(
                DropReason.OUT_OF_ORDER,
                "expected chunk $nextChunkIndex, got ${header.chunkIndex}",
            )
        }
        if (!agreesWithFirstChunk(header, active)) {
            return rejectInFlight(
                DropReason.CHUNK_MISMATCH,
                "chunk ${header.chunkIndex} disagrees with chunk 0 on frame geometry",
            )
        }
        if (header.hasTimestamp) {
            // `04-video.md` §2.3: the timestamp exists only on chunk 0.
            return rejectInFlight(
                DropReason.CHUNK_MISMATCH,
                "chunk ${header.chunkIndex} claims V_TS_PRESENT but is not chunk 0",
            )
        }

        return appendChunk(frame, header)
    }

    /**
     * Abandons the in-flight frame after `staleTimeoutMs` without a chunk.
     *
     * Called automatically by [onChunk]; a session tick should call it too so that a frame is released
     * even when the link goes quiet.
     *
     * @return the rejection that was recorded, or `null` when the in-flight frame is still fresh.
     */
    fun resetStale(nowMs: Long = System.currentTimeMillis()): AssemblyResult.Rejected? {
        val active = firstChunk ?: return null
        if (nowMs - frameStartedAtMs <= staleTimeoutMs) return null
        return rejectInFlight(
            DropReason.STALE,
            "frame ${active.frameId} idle for ${nowMs - frameStartedAtMs} ms",
        )
    }

    /** Drops any in-flight frame and clears the counters. Call on reconnect. */
    fun reset() {
        clearInFlight()
        framesCompleted = 0L
        framesRejected = 0L
        chunksAccepted = 0L
        bytesAssembled = 0L
        lastDropReason = null
    }

    private fun startFrame(frame: Frame, header: VideoFrameHeader, nowMs: Long): AssemblyResult {
        if (header.frameBytes > maxFrameBytes) {
            return reject(
                DropReason.FRAME_TOO_LARGE,
                "frame ${header.frameId} declares ${header.frameBytes} bytes, budget is $maxFrameBytes",
            )
        }
        firstChunk = header
        frameSeq = frame.seq
        nextChunkIndex = 0
        bufferSize = 0
        frameStartedAtMs = nowMs
        return appendChunk(frame, header)
    }

    private fun appendChunk(frame: Frame, header: VideoFrameHeader): AssemblyResult {
        val active = firstChunk ?: return reject(DropReason.OUT_OF_ORDER, "no frame in flight")

        var offset = VideoFrameHeader.SIZE_BYTES
        var captureTs: Long? = null
        if (header.chunkIndex == 0 && header.hasTimestamp) {
            if (frame.payload.size < offset + VideoFrameHeader.CAPTURE_TS_BYTES) {
                return rejectInFlight(DropReason.MALFORMED_SUBHEADER, "chunk 0 is too short for its timestamp")
            }
            var timestamp = 0L
            for (byteIndex in 0 until VideoFrameHeader.CAPTURE_TS_BYTES) {
                timestamp = timestamp or
                    ((frame.payload[offset + byteIndex].toLong() and 0xFFL) shl (8 * byteIndex))
            }
            captureTs = timestamp
            offset += VideoFrameHeader.CAPTURE_TS_BYTES
        }

        val mediaLength = frame.payload.size - offset
        if (bufferSize + mediaLength > active.frameBytes) {
            // More media than the frame declared: the chunk stream is corrupt.
            return rejectInFlight(
                DropReason.LENGTH_MISMATCH,
                "frame ${active.frameId} would exceed its declared ${active.frameBytes} bytes",
            )
        }
        frame.payload.copyInto(buffer, destinationOffset = bufferSize, startIndex = offset, endIndex = offset + mediaLength)
        bufferSize += mediaLength
        nextChunkIndex++
        chunksAccepted++

        if (!header.isEndOfFrame) {
            return AssemblyResult.Progress(active.frameId, nextChunkIndex, active.chunkTotal)
        }

        if (nextChunkIndex != active.chunkTotal) {
            return rejectInFlight(
                DropReason.CHUNK_MISMATCH,
                "frame ${active.frameId} ended after $nextChunkIndex of ${active.chunkTotal} chunks",
            )
        }
        if (bufferSize.toLong() != active.frameBytes) {
            return rejectInFlight(
                DropReason.LENGTH_MISMATCH,
                "frame ${active.frameId} assembled $bufferSize bytes, declared ${active.frameBytes}",
            )
        }

        val assembled = AssembledVideoFrame(
            header = active,
            codedData = buffer.copyOf(bufferSize),
            seq = frameSeq,
            captureTsMs = captureTs,
        )
        framesCompleted++
        bytesAssembled += bufferSize
        clearInFlight()
        return AssemblyResult.Complete(assembled)
    }

    private fun agreesWithFirstChunk(candidate: VideoFrameHeader, first: VideoFrameHeader): Boolean =
        candidate.chunkTotal == first.chunkTotal &&
            candidate.codec == first.codec &&
            candidate.widthPx == first.widthPx &&
            candidate.heightPx == first.heightPx &&
            candidate.frameBytes == first.frameBytes

    private fun reject(reason: DropReason, detail: String): AssemblyResult.Rejected {
        framesRejected++
        lastDropReason = reason
        return AssemblyResult.Rejected(reason, detail)
    }

    private fun rejectInFlight(reason: DropReason, detail: String): AssemblyResult.Rejected {
        clearInFlight()
        return reject(reason, detail)
    }

    private fun clearInFlight() {
        firstChunk = null
        nextChunkIndex = 0
        bufferSize = 0
        frameSeq = 0
        frameStartedAtMs = 0L
    }

    companion object {
        /** Default idle timeout before an incomplete frame is abandoned. */
        const val DEFAULT_STALE_TIMEOUT_MS: Long = 1_000L
    }
}
