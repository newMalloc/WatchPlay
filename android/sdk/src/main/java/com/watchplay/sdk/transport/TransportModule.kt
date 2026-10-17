package com.watchplay.sdk.transport

import com.watchplay.sdk.protocol.Frame

/**
 * Callbacks a transport delivers to its owner (in practice `SessionManager`).
 *
 * ## Threading
 *
 * All callbacks arrive on the transport's reader thread, never on the caller's thread, and never
 * concurrently with each other. A listener must not block: the receive path is also the path that keeps
 * link-layer flow control honest.
 */
interface TransportListener {

    /** The transport changed state. Called once per transition. */
    fun onTransportStateChanged(state: TransportState)

    /**
     * A complete, CRC-verified protocol frame arrived.
     *
     * Framing (magic, header CRC, and reassembly of bearer-level fragments) is the **transport's**
     * responsibility, so this only ever fires with whole frames. Unknown frame types are delivered on
     * purpose: the session counts them and stays connected (`02-wire-format.md` §3).
     */
    fun onFrameReceived(frame: Frame)

    /**
     * The peer redefined the outbound credit window.
     *
     * @param credits the **absolute** number of chunks the sender may still send, taken verbatim from
     *   `FEEDBACK.credits` (`04-video.md` §4.2). This is not an increment: increments would drift
     *   permanently whenever a notification is dropped (`01-transport.md` §6.5).
     */
    fun onCreditWindowChanged(credits: Int)

    /**
     * A recoverable link error was observed. An unrecoverable failure is reported instead through
     * [onTransportStateChanged] with [TransportState.FAILED].
     */
    fun onTransportError(error: Throwable)
}

/**
 * The byte-moving layer of WatchPlay, and the seam that keeps the SDK testable.
 *
 * ## Contract
 *
 * ```
 *   open(listener)        -> start connecting; report progress through the listener
 *   send(frame)           -> hand one whole frame to the link, or refuse it (never queue unbounded)
 *   setCreditWindow(n)    -> replace the outbound credit window from FEEDBACK.credits
 *   close(reason)         -> graceful teardown; state ends at CLOSED
 * ```
 *
 * Two responsibilities live here rather than in the session:
 *
 *  1. **Framing.** [send] takes a [Frame]; the transport serialises it with `FrameCodec` and runs a
 *     `com.watchplay.sdk.protocol.FrameParser` on the receive path, so the listener only ever sees whole
 *     frames. On BLE GATT a protocol frame may need several writes (`bearer_tu` = `MTU − 3`), and on
 *     L2CAP CoC one SDU may carry several frames; both are the transport's problem, not the session's
 *     (`01-transport.md` §5.2, §5.3).
 *  2. **Flow control.** The outbound credit counter lives here because only the transport knows how many
 *     units the link has actually accepted. The watch's `FEEDBACK` sets it.
 *
 * ## Credit semantics
 *
 * There is **no implicit initial window** (`01-transport.md` §6.1): the window starts at `0` and the
 * only way to get credit is the explicit `CAPS_ACK.RESULT.initial_credits` grant, applied through
 * [setCreditWindow]. A sender must stop sending `VIDEO` entirely at zero credit — never
 * "drop and continue" — and may keep sending `PING`/`CONTROL`/`BYE` (`01-transport.md` §6.3).
 *
 * ## Implementations
 *
 *  * [LoopbackTransport] — fully working, in-memory; used by the demo and the tests.
 *  * [BleGattTransport] — the mandatory baseline bearer. Complete, unit-tested against a fake radio
 *    ([GattIo]); not yet exercised against hardware.
 *  * [BleL2capTransport] — API 29+ L2CAP CoC, higher throughput; skeleton (see its KDoc).
 */
interface TransportModule {

    /** Stable identifier for logs, e.g. `loopback` or `ble-l2cap-coc`. */
    val id: String

    /** Current lifecycle state. */
    val state: TransportState

    /** Negotiated MTU in bytes (ATT MTU, or CoC PDU budget); `0` before the link is up. */
    val mtu: Int

    /**
     * Chunks the sender may still send. `0` means "stop sending `VIDEO`"
     * (`01-transport.md` §6.1, §6.3).
     */
    val availableCreditChunks: Int

    /** Static description of what this transport can do. */
    val capabilities: TransportCapabilities

    /** Snapshot of the counters. */
    val stats: TransportStats

    /**
     * Opens the link and registers [listener]. Progress is reported through the listener; this call
     * must not block on the radio.
     */
    fun open(listener: TransportListener)

    /** Closes the link; [reason] is diagnostic only. */
    fun close(reason: String? = null)

    /**
     * Sends one whole protocol frame.
     *
     * The transport never buffers without a bound: when the credit window is empty or the stack queue is
     * full it returns [SendResult.NoCredit] and the caller drops the frame.
     */
    fun send(frame: Frame): SendResult

    /**
     * Replaces the outbound credit window with an absolute value taken from `FEEDBACK.credits`.
     *
     * @param credits the receiver's current willingness, in chunks. `0` is legal and means "stop".
     */
    fun setCreditWindow(credits: Int)
}
