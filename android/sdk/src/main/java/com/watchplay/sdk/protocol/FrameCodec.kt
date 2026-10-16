package com.watchplay.sdk.protocol

/**
 * Serialises and parses the WatchPlay v0.1 frame header.
 *
 * ## Wire layout (normative: `docs/spec/02-wire-format.md` §1)
 *
 * ```
 * offset size field        notes
 * 0      2    magic        always 57 50 ("WP")
 * 2      1    version      0x01 for protocol v0.1
 * 3      1    type         FrameType code
 * 4      2    flags        u16, meaning depends on `type`
 * 6      2    hdr_len      total header length, currently always 16
 * 8      4    seq          u32, per-direction, monotonic, never 0 after the first frame
 * 12     2    payload_len  u16
 * 14     2    hdr_crc      CRC-16/CCITT-FALSE over bytes 0..13
 * 16     ...  payload
 * ```
 *
 * All integers are little-endian and the structure is packed. `hdr_len` exists so a future version
 * can grow the header: a receiver must skip `hdr_len - 16` unknown extension bytes rather than
 * assume 16 (§1.1) — [decode] does exactly that when the two disagree.
 *
 * ## Deviations from the project brief that requested this SDK
 *
 * The brief described a *20-byte* header (its own field list: magic 2 + version 1 + type 1 +
 * flags 2 + reserved 2 + seq 4 + **u32** payload_len + **u32** crc32) while simultaneously calling it
 * "16-byte", and asked for a zlib CRC-32. The field widths cannot produce 16 bytes, and the
 * repository's normative specification settles it: `hdr_len` replaces "reserved", `payload_len` is
 * u16, and the checksum is CRC-16/CCITT-FALSE. This codec follows the specification so that it
 * interoperates with `watch/` and `tools/`; a zlib CRC-32 helper is still provided as [Crc32].
 *
 * ## Error handling
 *
 * Nothing here ever partially parses a frame: a bad magic, an out-of-range `hdr_len` or a failed
 * header CRC all produce [DecodeOutcome.Malformed], and [FrameParser] answers by resynchronising on
 * the next `"WP"` marker (spec §7).
 */
object FrameCodec {

    /** Fixed header size in bytes. */
    const val HEADER_SIZE: Int = ProtocolLimits.HEADER_SIZE

    /** First magic byte, ASCII `'W'`. */
    const val MAGIC_BYTE_0: Int = 0x57

    /** Second magic byte, ASCII `'P'`. */
    const val MAGIC_BYTE_1: Int = 0x50

    /** Protocol version this codec emits. */
    const val SUPPORTED_VERSION: Int = ProtocolVersion.CURRENT

    /** Offset of the `magic` field. */
    const val OFFSET_MAGIC: Int = 0

    /** Offset of the `version` field. */
    const val OFFSET_VERSION: Int = 2

    /** Offset of the `type` field. */
    const val OFFSET_TYPE: Int = 3

    /** Offset of the `flags` field. */
    const val OFFSET_FLAGS: Int = 4

    /** Offset of the `hdr_len` field. */
    const val OFFSET_HEADER_LENGTH: Int = 6

    /** Offset of the `seq` field. */
    const val OFFSET_SEQ: Int = 8

    /** Offset of the `payload_len` field. */
    const val OFFSET_PAYLOAD_LENGTH: Int = 12

    /** Offset of the `hdr_crc` field. */
    const val OFFSET_HEADER_CRC: Int = 14

    /** Number of header bytes covered by the checksum: offsets `0..13`. */
    const val CRC_INPUT_LENGTH: Int = 14

    /**
     * Parsed frame header, as returned by [decodeHeader] and [tryDecode].
     *
     * @property version protocol version byte.
     * @property typeCode raw `type` byte, possibly reserved or vendor-private.
     * @property flags raw `flags` word.
     * @property headerLength value of `hdr_len`; `> 16` means unknown extension bytes are present.
     * @property seq sender sequence number.
     * @property payloadLength value of `payload_len`.
     * @property headerCrc the `hdr_crc` value that was read (already verified by the parser).
     */
    data class FrameHeader(
        val version: Int,
        val typeCode: Int,
        val flags: Int,
        val headerLength: Int,
        val seq: Int,
        val payloadLength: Int,
        val headerCrc: Int,
    ) {
        /** Decoded [FrameType], or `null` when unknown. */
        val type: FrameType?
            get() = FrameType.fromCode(typeCode)

        /** Total bytes this frame occupies, `hdr_len + payload_len`. */
        val totalLength: Int
            get() = headerLength + payloadLength

        /** Unparsed extension bytes between the fixed header and the payload. */
        val extensionBytes: Int
            get() = headerLength - ProtocolLimits.HEADER_SIZE
    }

    /**
     * Result of a non-throwing parse attempt, used by [FrameParser] and by streaming receivers.
     *
     * The three cases map exactly onto the specification's parsing loop: wait for more bytes, resync
     * on garbage, or deliver a frame.
     */
    sealed interface DecodeOutcome {

        /**
         * A complete, CRC-verified frame was parsed.
         *
         * @property header validated header.
         * @property payload payload bytes, already offset past `hdr_len` (extension bytes skipped).
         * @property consumed bytes consumed from the input, `hdr_len + payload_len`.
         */
        data class Decoded(
            val header: FrameHeader,
            val payload: ByteArray,
            val consumed: Int,
        ) : DecodeOutcome

        /**
         * More bytes are required before a verdict is possible.
         *
         * @property header header if it was already parseable, else `null`.
         * @property expectedBytes total bytes the frame needs.
         * @property availableBytes bytes currently supplied.
         */
        data class Incomplete(
            val header: FrameHeader?,
            val expectedBytes: Int,
            val availableBytes: Int,
        ) : DecodeOutcome

        /**
         * The bytes cannot be a valid frame; the caller must resynchronise.
         *
         * @property reason human-readable cause, for traces and `hdr_crc_errors`-style counters.
         * @property header header when it was parseable but the payload was not authoritative.
         */
        data class Malformed(
            val reason: String,
            val header: FrameHeader? = null,
        ) : DecodeOutcome
    }

    /** `true` when `data[offset..offset+1]` is the `"WP"` magic. */
    fun hasMagic(data: ByteArray, offset: Int = 0): Boolean {
        if (offset < 0 || offset + 2 > data.size) return false
        return (data[offset].toInt() and 0xFF) == MAGIC_BYTE_0 &&
            (data[offset + 1].toInt() and 0xFF) == MAGIC_BYTE_1
    }

    /**
     * Computes `hdr_crc` over header bytes `0..13`.
     *
     * The specification defines the range as "offsets 0–13, with offsets 14–15 treated as zero", so
     * this method copies the first 14 bytes into a scratch buffer rather than requiring the caller to
     * have zeroed the CRC field. It is therefore safe to call on a fully-formed header.
     */
    fun computeHeaderCrc(headerBytes: ByteArray, offset: Int = 0): Int {
        LittleEndian.requireRange(headerBytes, offset, HEADER_SIZE)
        val scratch = ByteArray(CRC_INPUT_LENGTH)
        headerBytes.copyInto(scratch, destinationOffset = 0, startIndex = offset, endIndex = offset + CRC_INPUT_LENGTH)
        return Crc16.ccittFalse(scratch, 0, CRC_INPUT_LENGTH)
    }

    /** Serialises [frame] into a freshly allocated array of exactly `16 + payload.size` bytes. */
    fun encode(frame: Frame): ByteArray {
        require(frame.payload.size <= ProtocolLimits.MAX_PAYLOAD_BYTES) {
            "payload of ${frame.payload.size} bytes exceeds the u16 payload_len limit"
        }
        val out = ByteArray(HEADER_SIZE + frame.payload.size)
        encodeInto(frame, out, 0)
        return out
    }

    /**
     * Serialises [frame] into [destination] at [offset] and returns the number of bytes written.
     *
     * Exists so that a hot send path can reuse one buffer per connection instead of allocating per
     * frame; the caller must ensure [destination] has room for `16 + payload.size` bytes.
     */
    fun encodeInto(frame: Frame, destination: ByteArray, offset: Int = 0): Int {
        val payload = frame.payload
        require(payload.size <= ProtocolLimits.MAX_PAYLOAD_BYTES) {
            "payload of ${payload.size} bytes exceeds the u16 payload_len limit"
        }
        LittleEndian.requireRange(destination, offset, HEADER_SIZE + payload.size)

        LittleEndian.writeU8(destination, offset + OFFSET_MAGIC, MAGIC_BYTE_0)
        LittleEndian.writeU8(destination, offset + OFFSET_MAGIC + 1, MAGIC_BYTE_1)
        LittleEndian.writeU8(destination, offset + OFFSET_VERSION, frame.version)
        LittleEndian.writeU8(destination, offset + OFFSET_TYPE, frame.typeCode)
        LittleEndian.writeU16(destination, offset + OFFSET_FLAGS, frame.flags)
        LittleEndian.writeU16(destination, offset + OFFSET_HEADER_LENGTH, HEADER_SIZE)
        LittleEndian.writeU32(destination, offset + OFFSET_SEQ, frame.seq.toLong() and 0xFFFFFFFFL)
        LittleEndian.writeU16(destination, offset + OFFSET_PAYLOAD_LENGTH, payload.size)
        // The CRC field must read as zero while the CRC is being computed.
        LittleEndian.writeU16(destination, offset + OFFSET_HEADER_CRC, 0)
        LittleEndian.writeU16(destination, offset + OFFSET_HEADER_CRC, computeHeaderCrc(destination, offset))

        payload.copyInto(destination, destinationOffset = offset + HEADER_SIZE)
        return HEADER_SIZE + payload.size
    }

    /**
     * Parses and fully validates a header.
     *
     * @throws ProtocolException when the magic is wrong, `hdr_len` is out of range, or `hdr_crc`
     *   does not match. The specification is explicit that a failed `hdr_crc` means the frame must be
     *   discarded without partial parsing.
     */
    fun decodeHeader(data: ByteArray, offset: Int = 0): FrameHeader {
        LittleEndian.requireRange(data, offset, HEADER_SIZE)
        if (!hasMagic(data, offset)) {
            throw ProtocolException("bad magic: expected 57 50 at offset $offset")
        }
        val headerLength = LittleEndian.readU16(data, offset + OFFSET_HEADER_LENGTH)
        if (headerLength < ProtocolLimits.MIN_HEADER_SIZE || headerLength > ProtocolLimits.MAX_HEADER_SIZE) {
            throw ProtocolException("hdr_len $headerLength outside 16..256")
        }
        val declaredCrc = LittleEndian.readU16(data, offset + OFFSET_HEADER_CRC)
        val computedCrc = computeHeaderCrc(data, offset)
        if (declaredCrc != computedCrc) {
            throw ProtocolException(
                "hdr_crc mismatch: header says 0x%04X, computed 0x%04X".format(declaredCrc, computedCrc),
            )
        }
        return FrameHeader(
            version = LittleEndian.readU8(data, offset + OFFSET_VERSION),
            typeCode = LittleEndian.readU8(data, offset + OFFSET_TYPE),
            flags = LittleEndian.readU16(data, offset + OFFSET_FLAGS),
            headerLength = headerLength,
            seq = LittleEndian.readU32AsInt(data, offset + OFFSET_SEQ),
            payloadLength = LittleEndian.readU16(data, offset + OFFSET_PAYLOAD_LENGTH),
            headerCrc = declaredCrc,
        )
    }

    /**
     * Non-throwing parse of exactly one frame starting at [offset].
     *
     * Any header extension bytes (`hdr_len > 16`) are skipped and are **not** included in the
     * returned payload, which is what a forward-compatible receiver must do (spec §1.1).
     */
    fun tryDecode(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): DecodeOutcome {
        if (length < HEADER_SIZE) {
            return DecodeOutcome.Incomplete(header = null, expectedBytes = HEADER_SIZE, availableBytes = length)
        }
        if (!hasMagic(data, offset)) {
            return DecodeOutcome.Malformed("bad magic")
        }
        val headerLength = LittleEndian.readU16(data, offset + OFFSET_HEADER_LENGTH)
        if (headerLength < ProtocolLimits.MIN_HEADER_SIZE || headerLength > ProtocolLimits.MAX_HEADER_SIZE) {
            return DecodeOutcome.Malformed("hdr_len $headerLength outside 16..256")
        }
        val declaredCrc = LittleEndian.readU16(data, offset + OFFSET_HEADER_CRC)
        val computedCrc = computeHeaderCrc(data, offset)
        if (declaredCrc != computedCrc) {
            return DecodeOutcome.Malformed(
                "hdr_crc mismatch (declared 0x%04X, computed 0x%04X)".format(declaredCrc, computedCrc),
            )
        }
        val header = FrameHeader(
            version = LittleEndian.readU8(data, offset + OFFSET_VERSION),
            typeCode = LittleEndian.readU8(data, offset + OFFSET_TYPE),
            flags = LittleEndian.readU16(data, offset + OFFSET_FLAGS),
            headerLength = headerLength,
            seq = LittleEndian.readU32AsInt(data, offset + OFFSET_SEQ),
            payloadLength = LittleEndian.readU16(data, offset + OFFSET_PAYLOAD_LENGTH),
            headerCrc = declaredCrc,
        )
        if (length < header.totalLength) {
            return DecodeOutcome.Incomplete(header, header.totalLength, length)
        }
        val payload = data.copyOfRange(offset + header.headerLength, offset + header.totalLength)
        return DecodeOutcome.Decoded(header, payload, header.totalLength)
    }

    /**
     * Strictly parses one complete frame.
     *
     * @throws ProtocolException when the bytes are malformed or truncated — use [tryDecode] on a
     *   streaming path where "wait for more bytes" is a normal outcome.
     */
    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Frame {
        return when (val outcome = tryDecode(data, offset, length)) {
            is DecodeOutcome.Decoded -> Frame(
                typeCode = outcome.header.typeCode,
                flags = outcome.header.flags,
                seq = outcome.header.seq,
                payload = outcome.payload,
                version = outcome.header.version,
            )

            is DecodeOutcome.Incomplete -> throw ProtocolException(
                "incomplete frame: need ${outcome.expectedBytes} bytes, got ${outcome.availableBytes}",
            )

            is DecodeOutcome.Malformed -> throw ProtocolException("malformed frame: ${outcome.reason}")
        }
    }
}
