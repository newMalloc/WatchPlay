package com.watchplay.sdk.protocol

/**
 * One WatchPlay protocol frame: a 16-byte header (see [FrameCodec]) plus an opaque payload.
 *
 * `typeCode` is a raw [Int] rather than a [FrameType] on purpose. The specification requires a
 * receiver to *skip* frames whose type it does not know and keep the session alive, so an unknown
 * type must be representable — [type] therefore returns `null` instead of throwing. Inside the SDK
 * you usually want `frame.type == FrameType.VIDEO` or [requireType].
 *
 * The header CRC is **not** stored here: it is recomputed by [FrameCodec] on serialisation, which
 * removes a whole class of "I forgot to update the CRC after mutating the payload" bugs.
 *
 * `payload` is a shared reference, never defensively copied; call [copy] before handing a frame to a
 * component that may mutate the array.
 *
 * @property typeCode wire value of the `type` byte, kept as-is even when reserved or vendor-private.
 * @property flags `flags` bit field; its meaning depends on [typeCode] (see [FrameFlags]).
 * @property seq per-direction monotonic sequence number, `0` meaning "uninitialised" (spec §5).
 * @property payload frame payload; empty for zero-length frames such as `PING` or `VIDEO_END`.
 * @property version protocol version byte; defaults to [ProtocolVersion.CURRENT].
 */
data class Frame(
    val typeCode: Int,
    val flags: Int = 0,
    val seq: Int = 0,
    val payload: ByteArray = EMPTY_PAYLOAD,
    val version: Int = ProtocolVersion.CURRENT,
) {

    /** The decoded frame type, or `null` for reserved/vendor-private types. */
    val type: FrameType?
        get() = FrameType.fromCode(typeCode)

    /** Payload size in bytes; also the value written into `payload_len`. */
    val payloadLength: Int
        get() = payload.size

    /** `true` when this frame's [typeCode] equals [other]'s code. */
    fun isType(other: FrameType): Boolean = typeCode == other.code

    /** Like [type], but throws [ProtocolException] when the type is unknown. Use inside known paths. */
    fun requireType(): FrameType =
        type ?: throw ProtocolException("unknown frame type 0x%02X".format(typeCode))

    /** A shallow copy with a different sequence number — handy when retransmitting (spec §5). */
    fun withSeq(newSeq: Int): Frame = copy(seq = newSeq)

    /** A shallow copy flagged as a retransmission, which must reuse the original `seq`. */
    fun asRetransmit(): Frame = copy(flags = FrameFlags.withFlag(flags, FrameFlags.RETRANSMIT))

    /** Deep copy: duplicates [payload] so the result shares no mutable state with this frame. */
    fun deepCopy(): Frame = Frame(typeCode, flags, seq, payload.copyOf(), version)

    /**
     * Structural equality that compares payload **contents**.
     *
     * `data class` would otherwise generate `ByteArray.equals`, which is identity — a trap for the
     * unit tests and for any dedup logic built on frames.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return typeCode == other.typeCode &&
            flags == other.flags &&
            seq == other.seq &&
            version == other.version &&
            payload.contentEquals(other.payload)
    }

    /** Consistent with [equals]: hashes the payload contents, not the array identity. */
    override fun hashCode(): Int {
        var result = typeCode
        result = 31 * result + flags
        result = 31 * result + seq
        result = 31 * result + version
        result = 31 * result + payload.contentHashCode()
        return result
    }

    /** Human-readable form for logs and traces; payload bytes are summarised, never dumped. */
    override fun toString(): String {
        val name = type?.name ?: "UNKNOWN(0x%02X)".format(typeCode)
        return "Frame($name, ${FrameFlags.describe(type, flags)}, seq=$seq, payload=${payload.size}B)"
    }

    companion object {
        /** Shared empty payload so zero-length frames allocate nothing. */
        val EMPTY_PAYLOAD: ByteArray = ByteArray(0)

        /** Creates a frame for a known [FrameType]. */
        fun of(
            type: FrameType,
            payload: ByteArray = EMPTY_PAYLOAD,
            flags: Int = 0,
            seq: Int = 0,
        ): Frame = Frame(typeCode = type.code, flags = flags, seq = seq, payload = payload)
    }
}
