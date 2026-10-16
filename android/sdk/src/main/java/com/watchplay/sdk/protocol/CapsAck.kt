package com.watchplay.sdk.protocol

import java.io.ByteArrayOutputStream

/**
 * Codec for `CAPS_ACK` — the **phone's** answer to `CAPS`, and the only authoritative source of
 * session parameters (`03-session.md` §3.9, §4, §4.1).
 *
 * ```
 * CAPS_ACK payload = TLV list
 *   0x01 RESULT         24 bytes, mandatory
 *   0x02 STREAM         12 bytes, mandatory
 *   0x03 REJECT_REASON  UTF-8 text, mandatory when RESULT.status != OK
 * ```
 *
 * Note the deliberate collision with [CapsCodec.Type]: `0x01` means `DEVICE_INFO` from the watch and
 * `RESULT` from the phone. The two directions use disjoint tables and must be parsed with the matching
 * codec (`07-capabilities.md` §2).
 */
object CapsAckCodec {

    /** Registered `CAPS_ACK` TLV type numbers. */
    object Type {
        /** `0x01` — [CapsResult], 24 bytes, mandatory. */
        const val RESULT: Int = 0x01

        /** `0x02` — [CapsStream], 12 bytes, mandatory. */
        const val STREAM: Int = 0x02

        /** `0x03` — UTF-8 rejection text. */
        const val REJECT_REASON: Int = 0x03

        /** First vendor-private type. */
        const val VENDOR_FIRST: Int = 0x80
    }

    /**
     * Parses a `CAPS_ACK` payload.
     *
     * Unknown types are skipped by `len`; a `len` that runs past the payload end is a structural error.
     * A list missing `RESULT` or `STREAM` is *not* rejected here — the watch must do that
     * (`03-session.md` §3.2), so the caller checks [CapsAck.isComplete].
     */
    fun decode(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): CapsAck {
        LittleEndian.requireRange(payload, offset, length)

        var result: CapsResult? = null
        var stream: CapsStream? = null
        var rejectReason: String? = null
        val vendor = mutableListOf<Tlv>()

        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            if (cursor + 2 > end) throw ProtocolException("CAPS_ACK truncated in a TLV header at offset $cursor")
            val type = LittleEndian.readU8(payload, cursor)
            val valueLength = LittleEndian.readU8(payload, cursor + 1)
            cursor += 2
            if (cursor + valueLength > end) {
                throw ProtocolException("CAPS_ACK TLV 0x%02X runs past the payload".format(type))
            }

            when (type) {
                Type.RESULT -> if (valueLength >= CapsResult.SIZE_BYTES) {
                    result = CapsResult.decode(payload, cursor)
                }

                Type.STREAM -> if (valueLength >= CapsStream.SIZE_BYTES) {
                    stream = CapsStream.decode(payload, cursor)
                }

                Type.REJECT_REASON -> rejectReason = LittleEndian.readUtf8(payload, cursor, valueLength)

                else -> if (type >= Type.VENDOR_FIRST) {
                    vendor += Tlv(type, payload.copyOfRange(cursor, cursor + valueLength))
                }
            }

            cursor += valueLength
        }

        return CapsAck(result = result, stream = stream, rejectReason = rejectReason, vendorTlvs = vendor)
    }

    /** Serialises a `CAPS_ACK` payload from its already-built parts. */
    fun encode(ack: CapsAck): ByteArray {
        val out = ByteArrayOutputStream(48)

        fun tlv(type: Int, value: ByteArray) {
            require(value.size <= 0xFF) { "TLV 0x%02X value is too long: ${value.size}".format(type) }
            out.write(type)
            out.write(value.size)
            out.write(value, 0, value.size)
        }

        ack.result?.let { tlv(Type.RESULT, it.encode()) }
        ack.stream?.let { tlv(Type.STREAM, it.encode()) }
        val reason = ack.rejectReason
        if (!reason.isNullOrEmpty()) tlv(Type.REJECT_REASON, reason.toByteArray(Charsets.UTF_8))
        ack.vendorTlvs.forEach { tlv(it.type, it.value) }

        val bytes = out.toByteArray()
        require(bytes.size <= ProtocolLimits.CAPS_MAX_PAYLOAD) {
            "CAPS_ACK payload would be ${bytes.size} bytes, over the ${ProtocolLimits.CAPS_MAX_PAYLOAD}-byte budget"
        }
        return bytes
    }

    /** Convenience wrapper for the common "build both mandatory TLVs" case. */
    fun encode(result: CapsResult, stream: CapsStream, rejectReason: String? = null): ByteArray =
        encode(CapsAck(result = result, stream = stream, rejectReason = rejectReason))
}

/**
 * A parsed `CAPS_ACK` payload.
 *
 * @property result `RESULT` TLV, or `null` when the sender omitted it (a protocol violation).
 * @property stream `STREAM` TLV, or `null` when omitted.
 * @property rejectReason `REJECT_REASON` text, present when negotiation failed.
 * @property vendorTlvs vendor-private TLVs, preserved verbatim.
 */
data class CapsAck(
    val result: CapsResult?,
    val stream: CapsStream?,
    val rejectReason: String? = null,
    val vendorTlvs: List<Tlv> = emptyList(),
) {

    /** `true` when the phone accepted: both mandatory TLVs present and `status == OK`. */
    val isOk: Boolean
        get() = result?.status == ResultStatus.OK

    /** `true` when the mandatory TLVs of `03-session.md` §3.2 are present. */
    val isComplete: Boolean
        get() = result != null && stream != null

    override fun toString(): String =
        "CapsAck(result=$result, stream=$stream" +
            (rejectReason?.let { ", rejectReason='$it'" } ?: "") + ")"
}

/** `RESULT.status` values (`03-session.md` §4.3). */
object ResultStatus {

    /** Negotiation succeeded; both sides may enter `STREAMING`. */
    const val OK: Int = 0x00

    /** No codec both sides support. */
    const val NO_COMMON_CODEC: Int = 0x01

    /** The reported resolution cannot be handled. */
    const val RESOLUTION_UNSUPPORTED: Int = 0x02

    /** Estimated bandwidth is below the acceptable minimum. */
    const val BANDWIDTH_INSUFFICIENT: Int = 0x03

    /** `CAPS` was missing mandatory TLVs or structurally broken. */
    const val CAPS_MALFORMED: Int = 0x04

    /** Incompatibility discovered only at the `CAPS` stage. */
    const val UNSUPPORTED_VERSION: Int = 0x05

    /** Human-readable name of a status code. */
    fun describe(status: Int): String = when (status) {
        OK -> "OK"
        NO_COMMON_CODEC -> "NO_COMMON_CODEC"
        RESOLUTION_UNSUPPORTED -> "RESOLUTION_UNSUPPORTED"
        BANDWIDTH_INSUFFICIENT -> "BANDWIDTH_INSUFFICIENT"
        CAPS_MALFORMED -> "CAPS_MALFORMED"
        UNSUPPORTED_VERSION -> "UNSUPPORTED_VERSION"
        else -> "UNKNOWN(0x%02X)".format(status)
    }
}

/**
 * `CAPS_ACK.RESULT` — 24 bytes of decided session parameters (`03-session.md` §4).
 *
 * @property status one of [ResultStatus].
 * @property codec the **chosen** codec; the watch must use this one.
 * @property widthPx encoded width. v0.1 always equals the panel width (no non-integer scaling).
 * @property heightPx encoded height.
 * @property targetFps negotiated frame rate, `1..30`.
 * @property quality JPEG quality for MJPEG, `0` for H.264.
 * @property maxChunkBytes per-chunk `payload_len` ceiling **including** the 20-byte video sub-header.
 * @property initialCredits the one and only initial credit grant, default 64.
 * @property targetBitrateBps H.264 target bitrate, `0` for MJPEG.
 * @property gop H.264 keyframe interval in frames; `0` means "keyframes only".
 * @property h264Profile H.264 profile idc (66/77/100).
 * @property colorFormat pixel format the phone will output.
 * @property sessionId random session id, for log correlation.
 * @property rawCodec the `codec` byte as received, kept so unknown values are not silently lost.
 */
data class CapsResult(
    val status: Int,
    val codec: VideoCodec,
    val widthPx: Int,
    val heightPx: Int,
    val targetFps: Int,
    val quality: Int,
    val maxChunkBytes: Int,
    val initialCredits: Int,
    val targetBitrateBps: Long = 0L,
    val gop: Int = 0,
    val h264Profile: Int = H264Caps.PROFILE_BASELINE,
    val colorFormat: ColorFormat = ColorFormat.RGB565_BE,
    val sessionId: Long = 0L,
    val rawCodec: Int = codec.code,
) {

    init {
        require(widthPx in 0..0xFFFF) { "widthPx out of u16 range: $widthPx" }
        require(heightPx in 0..0xFFFF) { "heightPx out of u16 range: $heightPx" }
        require(targetFps in 0..0xFF) { "targetFps out of u8 range: $targetFps" }
        require(quality in 0..0xFF) { "quality out of u8 range: $quality" }
        require(maxChunkBytes in 0..0xFFFF) { "maxChunkBytes out of u16 range: $maxChunkBytes" }
        require(initialCredits in 0..0xFFFF) { "initialCredits out of u16 range: $initialCredits" }
        require(targetBitrateBps in 0L..0xFFFFFFFFL) { "targetBitrateBps out of u32 range: $targetBitrateBps" }
        require(gop in 0..0xFFFF) { "gop out of u16 range: $gop" }
        require(sessionId in 0L..0xFFFFFFFFL) { "sessionId out of u32 range: $sessionId" }
    }

    /** `true` when the session may proceed. */
    val isOk: Boolean
        get() = status == ResultStatus.OK

    /** Media bytes one chunk can carry: `max_chunk_bytes - 20` (`04-video.md` §3.1). */
    val mediaBytesPerChunk: Int
        get() = maxChunkBytes - ProtocolLimits.VIDEO_SUBHEADER_SIZE

    /** Serialises the 24-byte value. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU8(out, 0, status)
        LittleEndian.writeU8(out, 1, codec.code)
        LittleEndian.writeU16(out, 2, widthPx)
        LittleEndian.writeU16(out, 4, heightPx)
        LittleEndian.writeU8(out, 6, targetFps)
        LittleEndian.writeU8(out, 7, quality)
        LittleEndian.writeU16(out, 8, maxChunkBytes)
        LittleEndian.writeU16(out, 10, initialCredits)
        LittleEndian.writeU32(out, 12, targetBitrateBps)
        LittleEndian.writeU16(out, 16, gop)
        LittleEndian.writeU8(out, 18, h264Profile)
        LittleEndian.writeU8(out, 19, colorFormat.code)
        LittleEndian.writeU32(out, 20, sessionId)
        return out
    }

    override fun toString(): String =
        "CapsResult(${ResultStatus.describe(status)}, ${codec.displayName} ${widthPx}x$heightPx " +
            "@${targetFps}fps, q=$quality, chunk=$maxChunkBytes, credits=$initialCredits, " +
            "bitrate=${targetBitrateBps}bps, gop=$gop)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_RESULT_SIZE

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): CapsResult {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            val rawCodec = LittleEndian.readU8(data, offset + 1)
            return CapsResult(
                status = LittleEndian.readU8(data, offset),
                // An unrecognised codec id leaves the session unusable; keep MJPEG as a placeholder
                // and expose the raw byte so the caller can reject with NO_COMMON_CODEC.
                codec = VideoCodec.fromCode(rawCodec) ?: VideoCodec.MJPEG,
                widthPx = LittleEndian.readU16(data, offset + 2),
                heightPx = LittleEndian.readU16(data, offset + 4),
                targetFps = LittleEndian.readU8(data, offset + 6),
                quality = LittleEndian.readU8(data, offset + 7),
                maxChunkBytes = LittleEndian.readU16(data, offset + 8),
                initialCredits = LittleEndian.readU16(data, offset + 10),
                targetBitrateBps = LittleEndian.readU32(data, offset + 12),
                gop = LittleEndian.readU16(data, offset + 16),
                h264Profile = LittleEndian.readU8(data, offset + 18),
                colorFormat = ColorFormat.fromCode(LittleEndian.readU8(data, offset + 19)),
                sessionId = LittleEndian.readU32(data, offset + 20),
                rawCodec = rawCodec,
            )
        }

        /** Builds an `OK` result for tests and for the phone-side negotiator. */
        fun ok(
            codec: VideoCodec,
            widthPx: Int,
            heightPx: Int,
            targetFps: Int,
            quality: Int,
            maxChunkBytes: Int,
            initialCredits: Int,
            targetBitrateBps: Long = 0L,
            gop: Int = 0,
            h264Profile: Int = H264Caps.PROFILE_BASELINE,
            colorFormat: ColorFormat = ColorFormat.RGB565_BE,
            sessionId: Long = 0L,
        ): CapsResult = CapsResult(
            status = ResultStatus.OK,
            codec = codec,
            widthPx = widthPx,
            heightPx = heightPx,
            targetFps = targetFps,
            quality = quality,
            maxChunkBytes = maxChunkBytes,
            initialCredits = initialCredits,
            targetBitrateBps = targetBitrateBps,
            gop = gop,
            h264Profile = h264Profile,
            colorFormat = colorFormat,
            sessionId = sessionId,
        )
    }
}

/**
 * `CAPS_ACK.STREAM` — 12 bytes (`03-session.md` §4.1).
 *
 * @property maxFrameBytes the phone guarantees no encoded frame exceeds this; the watch sizes its
 *   reassembly buffer from it once, up front (`04-video.md` §3.3 forbids per-frame allocation).
 * @property deadlineMs target latency budget for one frame, capture to display.
 * @property heartbeatMs `PING` interval when idle, default 2000.
 * @property drainTimeoutMs how long `DRAINING` may last before adaptive downshifting, default 2000.
 */
data class CapsStream(
    val maxFrameBytes: Long,
    val deadlineMs: Long,
    val heartbeatMs: Int = ProtocolLimits.DEFAULT_HEARTBEAT_MS,
    val drainTimeoutMs: Int = ProtocolLimits.DRAIN_TIMEOUT_MS,
) {

    init {
        require(maxFrameBytes in 1L..0xFFFFFFFFL) { "maxFrameBytes out of u32 range: $maxFrameBytes" }
        require(deadlineMs in 0L..0xFFFFFFFFL) { "deadlineMs out of u32 range: $deadlineMs" }
        require(heartbeatMs in 0..0xFFFF) { "heartbeatMs out of u16 range: $heartbeatMs" }
        require(drainTimeoutMs in 0..0xFFFF) { "drainTimeoutMs out of u16 range: $drainTimeoutMs" }
    }

    /** Serialises the 12-byte value. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU32(out, 0, maxFrameBytes)
        LittleEndian.writeU32(out, 4, deadlineMs)
        LittleEndian.writeU16(out, 8, heartbeatMs)
        LittleEndian.writeU16(out, 10, drainTimeoutMs)
        return out
    }

    override fun toString(): String =
        "CapsStream(maxFrame=$maxFrameBytes B, deadline=${deadlineMs}ms, " +
            "heartbeat=${heartbeatMs}ms, drain=${drainTimeoutMs}ms)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_STREAM_SIZE

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): CapsStream {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return CapsStream(
                maxFrameBytes = LittleEndian.readU32(data, offset),
                deadlineMs = LittleEndian.readU32(data, offset + 4),
                heartbeatMs = LittleEndian.readU16(data, offset + 8),
                drainTimeoutMs = LittleEndian.readU16(data, offset + 10),
            )
        }
    }
}
