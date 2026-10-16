package com.watchplay.sdk.protocol

/**
 * `FEEDBACK` — the watch's credit grant and status report (`04-video.md` §4.2).
 *
 * ## `credits` is an **absolute** value
 *
 * This is the single most important thing about this frame, and both `04-video.md` §4.2 and
 * `01-transport.md` §6.5 depend on it: `credits` is *the total number of chunks the receiver is
 * currently willing to accept*, **not** an increment. Increments are unsafe because BLE notifications
 * can be dropped silently (`01-transport.md` §2.5): losing one "grant +64" would starve the sender
 * forever, whereas losing an absolute window only costs a little throughput until the receiver
 * re-sends it (it should re-send every 500 ms while credit is exhausted).
 *
 * The sender therefore does **not** add [credits] to anything — it **replaces** its window with it.
 * `01-transport.md` §6.1 is emphatic that the initial window comes only from the explicit
 * `CAPS_ACK.RESULT.initial_credits` grant: there is no implicit "assume 64 until told otherwise".
 *
 * ## SPEC CONFLICT: 20 bytes or 22?
 *
 * `04-video.md` §4.2 is titled "定长 20 字节" (fixed 20 bytes), and `02-wire-format.md` §6 and
 * `01-transport.md` §6 both say 20 — but the field table in that very section adds up to **22**:
 *
 * ```
 * 0..3 credits (u32)  4..7 frames_decoded (u32)  8..11 frames_dropped (u32)
 * 12..13 decode_ms_avg (u16)  14..15 queue_depth (u16)
 * 16 last_frame_status (u8)   17 flags (u8)      18..21 last_frame_id (u32)   => 22 bytes
 * ```
 *
 * This SDK follows the **field table** (every offset and width is individually specified, and that is
 * what an implementer codes against) and therefore emits [SIZE_BYTES] = 22. To stay interoperable with
 * an implementation that follows the 20-byte prose, [decode] also accepts a 20-byte payload and treats
 * the missing `last_frame_id` as [NO_FRAME_ID]. This needs a decision from the spec maintainer; it is
 * recorded in the project report.
 *
 * @property credits currently available chunks (absolute, u32).
 * @property framesDecoded cumulative successfully decoded frames.
 * @property framesDropped cumulative dropped frames, including decode failures and overflow.
 * @property decodeMsAvg average decode time over the last 32 frames.
 * @property queueDepth chunks currently waiting to be decoded.
 * @property lastFrameStatus one of [LastFrameStatus].
 * @property flags [FeedbackFlags] bits.
 * @property lastFrameId most recently **successfully decoded** `frame_id`; `0xFFFFFFFF` means none.
 */
data class Feedback(
    val credits: Long,
    val framesDecoded: Long = 0L,
    val framesDropped: Long = 0L,
    val decodeMsAvg: Int = 0,
    val queueDepth: Int = 0,
    val lastFrameStatus: Int = LastFrameStatus.OK,
    val flags: Int = 0,
    val lastFrameId: Long = NO_FRAME_ID,
) {

    init {
        require(credits in 0L..0xFFFFFFFFL) { "credits out of u32 range: $credits" }
        require(framesDecoded in 0L..0xFFFFFFFFL) { "framesDecoded out of u32 range: $framesDecoded" }
        require(framesDropped in 0L..0xFFFFFFFFL) { "framesDropped out of u32 range: $framesDropped" }
        require(decodeMsAvg in 0..0xFFFF) { "decodeMsAvg out of u16 range: $decodeMsAvg" }
        require(queueDepth in 0..0xFFFF) { "queueDepth out of u16 range: $queueDepth" }
        require(lastFrameStatus in 0..0xFF) { "lastFrameStatus out of u8 range: $lastFrameStatus" }
        require(flags in 0..0xFF) { "flags out of u8 range: $flags" }
        require(lastFrameId in 0L..0xFFFFFFFFL) { "lastFrameId out of u32 range: $lastFrameId" }
    }

    /** `true` when the receiver needs a keyframe (H.264 decoder lost its reference state). */
    val requestsKeyframe: Boolean
        get() = FrameFlags.isSet(flags, FeedbackFlags.REQUEST_KEYFRAME)

    /** `true` when the receiver is overloaded and the sender should back off. */
    val isOverloaded: Boolean
        get() = FrameFlags.isSet(flags, FeedbackFlags.OVERLOADED)

    /** `true` when no frame has been decoded yet. */
    val hasDecodedAnyFrame: Boolean
        get() = lastFrameId != NO_FRAME_ID

    /** Serialises the 20-byte payload. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU32(out, 0, credits)
        LittleEndian.writeU32(out, 4, framesDecoded)
        LittleEndian.writeU32(out, 8, framesDropped)
        LittleEndian.writeU16(out, 12, decodeMsAvg)
        LittleEndian.writeU16(out, 14, queueDepth)
        LittleEndian.writeU8(out, 16, lastFrameStatus)
        LittleEndian.writeU8(out, 17, flags)
        LittleEndian.writeU32(out, 18, lastFrameId)
        return out
    }

    override fun toString(): String =
        "Feedback(credits=$credits, decoded=$framesDecoded, dropped=$framesDropped, " +
            "decode=${decodeMsAvg}ms, queue=$queueDepth, " +
            "status=${LastFrameStatus.describe(lastFrameStatus)}, " +
            "flags=0x%02X, lastFrame=$lastFrameId)".format(flags)
    companion object {
        /**
         * Size this implementation writes: the field table of `04-video.md` §4.2, which adds up to 22
         * bytes even though the surrounding prose says 20. See the class documentation.
         */
        const val SIZE_BYTES: Int = ProtocolLimits.FEEDBACK_FIELD_TABLE_SIZE

        /**
         * Size an implementation that followed the "20-byte" prose would send. Accepted on receive for
         * interoperability; the missing `last_frame_id` is reported as [NO_FRAME_ID].
         */
        const val LEGACY_SIZE_BYTES: Int = ProtocolLimits.FEEDBACK_PAYLOAD_SIZE

        /** `last_frame_id` value meaning "no frame decoded yet". */
        const val NO_FRAME_ID: Long = 0xFFFFFFFFL

        /**
         * Parses a `FEEDBACK` payload.
         *
         * Accepts both [SIZE_BYTES] (this implementation, and the field table) and [LEGACY_SIZE_BYTES]
         * (the prose figure) so that a peer which truncated the last field still works.
         */
        fun decode(data: ByteArray, offset: Int = 0): Feedback {
            LittleEndian.requireRange(data, offset, LEGACY_SIZE_BYTES)
            val available = data.size - offset
            return Feedback(
                credits = LittleEndian.readU32(data, offset),
                framesDecoded = LittleEndian.readU32(data, offset + 4),
                framesDropped = LittleEndian.readU32(data, offset + 8),
                decodeMsAvg = LittleEndian.readU16(data, offset + 12),
                queueDepth = LittleEndian.readU16(data, offset + 14),
                lastFrameStatus = LittleEndian.readU8(data, offset + 16),
                flags = LittleEndian.readU8(data, offset + 17),
                lastFrameId = if (available >= SIZE_BYTES) {
                    LittleEndian.readU32(data, offset + 18)
                } else {
                    NO_FRAME_ID
                },
            )
        }
    }
}
