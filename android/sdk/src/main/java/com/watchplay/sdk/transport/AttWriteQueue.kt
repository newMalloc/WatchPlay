package com.watchplay.sdk.transport

import com.watchplay.sdk.protocol.ProtocolLimits

/**
 * Bearer-level fragmentation for ATT (`01-transport.md` §6).
 *
 * A protocol frame is `16 + payload` bytes and may be larger than one ATT write. The bearer splits
 * it into `bearer_tu`-sized pieces; the receiver's `FrameParser` reassembles, so the fragmentation is
 * invisible above this layer — **there is no length prefix on the wire** (§6.3 forbids one) and the
 * pieces do not carry their own framing.
 *
 * ```
 *   encoded frame (260 B, MTU 247)  ->  [ 244 B ] [ 16 B ]     2 ATT writes
 *   encoded frame (600 B)           ->  [244] [244] [112]      3 ATT writes
 * ```
 *
 * Since a piece boundary carries no meaning, a piece stream that loses bytes is recovered by the
 * parser's `"WP"` resynchronisation (`02-wire-format.md` §7), not by a retransmission.
 */
object AttFraming {

    /** Smallest usable bearer unit: the 23-byte minimum ATT MTU minus the 3-byte write header. */
    const val MIN_BEARER_TU: Int = ProtocolLimits.MIN_ATT_MTU - ProtocolLimits.ATT_WRITE_OVERHEAD

    /**
     * Usable payload of one ATT write at [mtu].
     *
     * `MTU − 3` because an ATT write header is 1 byte opcode plus a 2-byte attribute handle
     * (`01-transport.md` §2.2). The quota is the channel's, not the protocol's: the negotiated value
     * also becomes `max_chunk_bytes` on the receiver side.
     */
    fun bearerTu(mtu: Int): Int {
        require(mtu >= ProtocolLimits.MIN_ATT_MTU) { "an ATT MTU below ${ProtocolLimits.MIN_ATT_MTU} is unusable: $mtu" }
        return mtu - ProtocolLimits.ATT_WRITE_OVERHEAD
    }

    /**
     * Splits one encoded frame into write-sized pieces, in order.
     *
     * The last piece is the remainder, so a frame that fits is returned unchanged as one piece.
     *
     * @throws IllegalArgumentException when [bearerTu] is below [MIN_BEARER_TU] or the array is empty.
     */
    fun split(encoded: ByteArray, bearerTu: Int): List<ByteArray> {
        require(bearerTu >= MIN_BEARER_TU) { "bearer_tu must be at least $MIN_BEARER_TU, was $bearerTu" }
        require(encoded.isNotEmpty()) { "a frame always has a 16-byte header, so it cannot be empty" }
        if (encoded.size <= bearerTu) return listOf(encoded)

        val pieces = ArrayList<ByteArray>((encoded.size + bearerTu - 1) / bearerTu)
        var offset = 0
        while (offset < encoded.size) {
            val end = minOf(offset + bearerTu, encoded.size)
            pieces += encoded.copyOfRange(offset, end)
            offset = end
        }
        return pieces
    }

    /** Number of ATT writes [split] would produce; the same arithmetic without allocating. */
    fun writeCount(encodedSize: Int, bearerTu: Int): Int {
        require(encodedSize > 0) { "a frame always has a 16-byte header" }
        require(bearerTu >= MIN_BEARER_TU) { "bearer_tu must be at least $MIN_BEARER_TU, was $bearerTu" }
        return (encodedSize + bearerTu - 1) / bearerTu
    }
}

/**
 * The outbound queue between `send()` and the ATT writes, bounded on purpose.
 *
 * Queuing here rather than in the session is what keeps a slow link honest: `TransportModule.send`
 * accepts at most [maxFrames] frames that the controller has not taken yet, and refuses the rest with
 * [SendResult.NoCredit]. Latency therefore cannot grow without bound behind a congested controller
 * (`04-video.md` §4.3 prefers dropping a frame over delaying it).
 *
 * Not thread safe: [BleGattTransport] guards it with its own lock, because a write can only be
 * acknowledged from the thread that issued it.
 *
 * @property maxFrames how many frames may wait for the controller at once.
 */
class AttWriteQueue(private val maxFrames: Int = DEFAULT_MAX_FRAMES) {

    init {
        require(maxFrames >= 1) { "maxFrames must be at least 1, was $maxFrames" }
    }

    private class PendingFrame(val pieces: List<ByteArray>) {
        var index: Int = 0
        val isDone: Boolean get() = index == pieces.size
    }

    private val frames = ArrayDeque<PendingFrame>()

    /** Frames currently waiting, including the one being written. */
    val queuedFrames: Int
        get() = frames.size

    /** ATT writes still owed, including the next one. */
    val queuedWrites: Int
        get() = frames.sumOf { it.pieces.size - it.index }

    /** `true` when nothing is waiting. */
    val isEmpty: Boolean
        get() = frames.isEmpty()

    /**
     * Adds one frame's pieces.
     *
     * @return `false` when the queue is full and the frame was **not** accepted; the caller must then
     *   report backpressure instead of growing the queue.
     */
    fun offer(pieces: List<ByteArray>): Boolean {
        require(pieces.isNotEmpty()) { "a frame has at least one piece" }
        if (frames.size >= maxFrames) return false
        frames.addLast(PendingFrame(pieces))
        return true
    }

    /** The next ATT payload, or `null` when the queue is empty. */
    fun peek(): ByteArray? {
        val head = frames.firstOrNull() ?: return null
        return head.pieces[head.index]
    }

    /** Marks [peek]'s piece as taken. A frame disappears once its last piece is acknowledged. */
    fun acknowledgeWrite() {
        val head = frames.firstOrNull() ?: error("acknowledgeWrite without a pending write")
        head.index++
        if (head.isDone) frames.removeFirst()
    }

    /**
     * Drops the frame currently being written, because one of its pieces was rejected.
     *
     * @return the number of un-written pieces of that frame, so the caller can restore its counters.
     */
    fun dropCurrentFrame(): Int {
        val head = frames.removeFirstOrNull() ?: return 0
        return head.pieces.size - head.index
    }

    /** Discards everything; used on close and on failure. */
    fun clear() {
        frames.clear()
    }

    override fun toString(): String = "AttWriteQueue(frames=$queuedFrames, writes=$queuedWrites)"

    companion object {
        /**
         * Four frames is about 1 KiB of JPEG at the default chunking — enough to keep the controller
         * fed across one 7.5 ms connection interval, small enough that a stalled link drops instead of
         * accumulating a second of video.
         */
        const val DEFAULT_MAX_FRAMES: Int = 4
    }
}
