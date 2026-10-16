package com.watchplay.sdk.transport

/**
 * Lifecycle state of a transport.
 *
 * Deliberately small: the transport knows nothing about WatchPlay sessions (that is
 * `com.watchplay.sdk.session.SessionManager`'s job), only whether bytes can flow right now.
 *
 * ```
 *   CLOSED --open()--> OPENING --link up--> READY <--> CONGESTED
 *        ^                                     |            |
 *        +------------- CLOSING <--------------+------------+
 *                          |
 *                          v
 *                        CLOSED            (or FAILED, from any state, on an unrecoverable error)
 * ```
 */
enum class TransportState {

    /** No link. [TransportModule.send] returns [SendResult.NotConnected]. */
    CLOSED,

    /** Connecting or waiting for the peer to finish the handshake. */
    OPENING,

    /** Byte flow is possible. */
    READY,

    /** Flow control has exhausted the outbound credit; frames will be dropped, not queued. */
    CONGESTED,

    /** Shutting down gracefully. */
    CLOSING,

    /** The transport hit an unrecoverable error. A new instance (or `open`) is required. */
    FAILED;

    /** `true` when frames can be handed to the transport. */
    val isOpen: Boolean
        get() = this == READY || this == CONGESTED

    /** `true` when no further progress is possible without reopening. */
    val isTerminal: Boolean
        get() = this == CLOSED || this == FAILED
}

/**
 * Outcome of a [TransportModule.send] attempt.
 *
 * The design rule is that **the transport never queues without a bound**: when credit is exhausted the
 * frame is refused and the pipeline drops it. A slow link therefore shows up as a lower frame rate
 * rather than as latency growth — the spec's intent for a live mirror (`docs/spec/04-video.md` on the
 * drop policy, not yet written).
 */
sealed interface SendResult {

    /** The frame was accepted; [bytes] is the number of bytes written (header + payload). */
    data class Sent(val bytes: Int) : SendResult

    /**
     * The frame was refused because the receiver has not granted enough credit.
     *
     * @property needed credit units required by this frame (usually its chunk count).
     * @property available credit units currently held.
     */
    data class NoCredit(val needed: Int, val available: Int) : SendResult

    /** The frame was refused because the transport is not open. */
    data object NotConnected : SendResult

    /** The frame exceeds what one transport unit can carry; the caller must chunk it first. */
    data class TooLarge(val sizeBytes: Int, val maxBytes: Int) : SendResult

    /** The link failed while writing; the session should treat the transport as dead. */
    data class Failed(val reason: String, val cause: Throwable? = null) : SendResult
}

/**
 * What a transport can do, so the session layer can adapt without knowing which stack is underneath.
 *
 * @property mtu negotiated maximum transmission unit in bytes (ATT MTU for GATT, CoC MPS for L2CAP).
 * @property isReliableOrdered `true` when units arrive exactly once, in order, without loss. BLE
 *   GATT/L2CAP both are; a future UDP-ish Wi-Fi transport would not be, and the reassembler would then
 *   need the per-chunk index the spec currently only puts in chunk 0.
 * @property supportsCreditFlowControl `true` when the receiver can grant/withhold credit.
 * @property maxFrameBytes largest single frame this transport can carry, including the 16-byte header.
 * @property stackName human-readable transport name for logs, e.g. `ble-l2cap-coc`.
 */
data class TransportCapabilities(
    val mtu: Int,
    val isReliableOrdered: Boolean,
    val supportsCreditFlowControl: Boolean,
    val maxFrameBytes: Int,
    val stackName: String,
) {

    init {
        require(mtu > 0) { "mtu must be positive, was $mtu" }
        require(maxFrameBytes >= mtu) { "maxFrameBytes must be at least one MTU" }
    }
}

/**
 * Running counters for a transport; surfaced in `PipelineStats` and in the demo's log pane.
 *
 * @property framesSent frames accepted by [TransportModule.send].
 * @property framesReceived frames delivered to the listener.
 * @property bytesSent total bytes written, including frame headers.
 * @property bytesReceived total bytes read, including frame headers.
 * @property chunksInFlight units handed to the stack but not yet acknowledged at the link layer.
 * @property creditAvailable current outbound credit in chunks.
 * @property droppedForCredit frames refused because credit ran out (the pipeline also counts these).
 * @property roundTripTimeMs measured RTT, or `-1` when the transport cannot measure it.
 * @property resyncCount parser resyncs observed on this link.
 * @property headerCrcErrors header checksum failures observed on this link.
 * @property lastError most recent recoverable error message, or `null`.
 */
data class TransportStats(
    val framesSent: Long = 0L,
    val framesReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val chunksInFlight: Int = 0,
    val creditAvailable: Int = 0,
    val droppedForCredit: Long = 0L,
    val roundTripTimeMs: Int = -1,
    val resyncCount: Long = 0L,
    val headerCrcErrors: Long = 0L,
    val lastError: String? = null,
) {

    /** Total bytes moved in both directions. */
    val totalBytes: Long
        get() = bytesSent + bytesReceived

    override fun toString(): String =
        "TransportStats(sent=${framesSent}f/${bytesSent}B, recv=${framesReceived}f/${bytesReceived}B, " +
            "credit=$creditAvailable, inFlight=$chunksInFlight, dropped=$droppedForCredit, " +
            "rtt=${if (roundTripTimeMs < 0) "n/a" else "${roundTripTimeMs}ms"}, " +
            "resync=$resyncCount, crcErrors=$headerCrcErrors" +
            (lastError?.let { ", lastError='$it'" } ?: "") + ")"
}
