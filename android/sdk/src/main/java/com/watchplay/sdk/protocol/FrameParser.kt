package com.watchplay.sdk.protocol

/**
 * Incremental frame parser for an unreliable byte stream.
 *
 * The stream a phone sees from a BLE transport is not framed: L2CAP CoC delivers a byte stream that
 * can split or coalesce protocol frames, and a GATT notification stream can lose bytes when a
 * connection hiccups. [FrameParser] implements the receive loop of `docs/spec/02-wire-format.md` §7:
 *
 *  1. wait for at least 16 bytes,
 *  2. verify the `"WP"` magic; on failure search forward for the next `"WP"` and count a resync,
 *  3. reject `hdr_len` outside `16..256`,
 *  4. verify `hdr_crc`; on failure count `hdr_crc_errors` and resync (never partial-parse),
 *  5. wait until `hdr_len + payload_len` bytes are buffered,
 *  6. skip `hdr_len - 16` extension bytes and deliver the payload.
 *
 * Frames of unsupported version or unknown type are still **delivered**: the session layer decides to
 * send `BYE(REASON_UNSUPPORTED_VERSION)`, and the spec forbids dropping the connection over an
 * unknown type. Both cases are counted in [unsupportedVersions] and [unknownFrameTypes].
 *
 * ## Threading
 *
 * Not thread safe. A transport implementation must confine one parser to a single reader thread —
 * which is also what the spec's back-pressure rule (§7.2) assumes.
 *
 * @property maxBufferedBytes hard ceiling for the input buffer. If a stream sends garbage that never
 *   forms a frame, the parser discards the oldest bytes and counts a resync rather than growing
 *   without bound (spec §7.2 forbids unbounded buffering). The spec suggests `2 x` the largest frame;
 *   the default of 8 KiB covers several 244-byte chunks with slack.
 */
class FrameParser(
    private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
) {

    init {
        require(maxBufferedBytes >= ProtocolLimits.HEADER_SIZE) {
            "maxBufferedBytes must be at least one header (${ProtocolLimits.HEADER_SIZE})"
        }
    }

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    /** Number of times the parser had to skip forward to the next `"WP"` magic. */
    var resyncCount: Long = 0L
        private set

    /** Number of frames dropped because `hdr_crc` did not match (spec §2). */
    var headerCrcErrors: Long = 0L
        private set

    /** Number of frames dropped because `hdr_len` was outside `16..256`. */
    var malformedHeaders: Long = 0L
        private set

    /** Number of delivered frames whose `type` byte is reserved or vendor-private (spec §3). */
    var unknownFrameTypes: Long = 0L
        private set

    /** Number of delivered frames carrying a protocol version this SDK cannot parse. */
    var unsupportedVersions: Long = 0L
        private set

    /** Bytes thrown away by the overflow guard, i.e. data that never became a frame. */
    var discardedOverflowBytes: Long = 0L
        private set

    /** Bytes currently held but not yet parsed. */
    val bufferedBytes: Int
        get() = size

    /**
     * Feeds [length] bytes from [data] into the parser and returns every frame that became complete.
     *
     * A single call can return several frames (coalesced stream) or none (partial frame). The
     * returned list is empty when only partial data arrived.
     */
    fun offer(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Frame> {
        LittleEndian.requireRange(data, offset, length)
        if (length == 0) return emptyList()
        append(data, offset, length)
        val frames = ArrayList<Frame>(4)
        drain(frames)
        return frames
    }

    /** Discards all buffered bytes and resets the counters. Use after a reconnect. */
    fun reset() {
        size = 0
        resyncCount = 0L
        headerCrcErrors = 0L
        malformedHeaders = 0L
        unknownFrameTypes = 0L
        unsupportedVersions = 0L
        discardedOverflowBytes = 0L
    }

    /** Drops only the buffered bytes, keeping the statistics (useful when handing off a link). */
    fun clearBuffer() {
        size = 0
    }

    private fun drain(out: MutableList<Frame>) {
        while (size >= ProtocolLimits.HEADER_SIZE) {
            if (!FrameCodec.hasMagic(buffer, 0)) {
                resynchronise()
                continue
            }
            val headerLength = LittleEndian.readU16(buffer, FrameCodec.OFFSET_HEADER_LENGTH)
            if (headerLength < ProtocolLimits.MIN_HEADER_SIZE || headerLength > ProtocolLimits.MAX_HEADER_SIZE) {
                malformedHeaders++
                drop(1)
                continue
            }
            val declaredCrc = LittleEndian.readU16(buffer, FrameCodec.OFFSET_HEADER_CRC)
            val computedCrc = FrameCodec.computeHeaderCrc(buffer, 0)
            if (declaredCrc != computedCrc) {
                headerCrcErrors++
                drop(1)
                continue
            }
            val payloadLength = LittleEndian.readU16(buffer, FrameCodec.OFFSET_PAYLOAD_LENGTH)
            val totalLength = headerLength + payloadLength
            if (size < totalLength) {
                // Wait for the rest of the frame; the buffer keeps what we already have.
                return
            }
            val version = LittleEndian.readU8(buffer, FrameCodec.OFFSET_VERSION)
            if (!ProtocolVersion.isSupported(version)) unsupportedVersions++
            val typeCode = LittleEndian.readU8(buffer, FrameCodec.OFFSET_TYPE)
            if (FrameType.fromCode(typeCode) == null) unknownFrameTypes++
            out += Frame(
                typeCode = typeCode,
                flags = LittleEndian.readU16(buffer, FrameCodec.OFFSET_FLAGS),
                seq = LittleEndian.readU32AsInt(buffer, FrameCodec.OFFSET_SEQ),
                payload = buffer.copyOfRange(headerLength, totalLength),
                version = version,
            )
            drop(totalLength)
        }
    }

    /**
     * Skips forward to the next `"WP"` sequence and counts a resync (spec §7.1). Always makes
     * progress: when no further magic exists, everything but a possible trailing `'W'` is dropped.
     */
    private fun resynchronise() {
        resyncCount++
        for (index in 1 until size) {
            if (buffer[index].toInt() and 0xFF == FrameCodec.MAGIC_BYTE_0 &&
                index + 1 < size &&
                buffer[index + 1].toInt() and 0xFF == FrameCodec.MAGIC_BYTE_1
            ) {
                drop(index)
                return
            }
        }
        // No further magic in the buffer: keep a trailing 0x57 in case the 'P' is in the next read.
        val keep = if (buffer[size - 1].toInt() and 0xFF == FrameCodec.MAGIC_BYTE_0) 1 else 0
        drop(size - keep)
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        ensureCapacity(size + length)
        data.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size += length
        if (size > maxBufferedBytes) {
            val excess = size - maxBufferedBytes
            drop(excess)
            discardedOverflowBytes += excess
            resyncCount++
        }
    }

    private fun drop(count: Int) {
        if (count <= 0) return
        val effective = count.coerceAtMost(size)
        buffer.copyInto(buffer, destinationOffset = 0, startIndex = effective, endIndex = size)
        size -= effective
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    companion object {
        /** Default ceiling for the input buffer. */
        const val DEFAULT_MAX_BUFFERED_BYTES: Int = 8 * 1024

        private const val INITIAL_CAPACITY: Int = 1024
    }
}
