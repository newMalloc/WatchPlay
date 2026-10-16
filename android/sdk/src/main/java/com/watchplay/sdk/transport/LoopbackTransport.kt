package com.watchplay.sdk.transport

import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameCodec
import com.watchplay.sdk.protocol.ProtocolLimits
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-memory transport: the whole protocol with no radio at all.
 *
 * Two endpoints are wired to each other and a frame sent on one is delivered to the other's listener
 * synchronously. This is the SDK's test double *and* the reason the demo can stream real JPEG bytes
 * without a watch on the desk.
 *
 * ## Fidelity, and what it deliberately does not model
 *
 *  * **Credit is exactly the real thing.** The window starts at **zero** and only a
 *    [setCreditWindow] call opens it, mirroring `01-transport.md` §6.1 ("there is no implicit initial
 *    window; zero is the only legal starting value"). A test can therefore exercise the drop-under-
 *    backpressure path by simply never granting credit.
 *  * **Framing is exact.** Every send goes through [FrameCodec.encode], so a frame the real wire would
 *    reject is rejected here, and [stats] counts real bytes including the 16-byte header.
 *  * **Delivery is synchronous**, on the caller's thread. Deterministic for tests; a blocking listener
 *    therefore blocks the sender, which is an honest approximation of a full link.
 *  * **Loss is opt-in** via [dropEveryNthFrame].
 *  * **No latency model**: measure real latency on real hardware.
 *
 * @property id identifier reported to the session; the halves of a pair are usually
 *   `loopback-phone` and `loopback-watch`.
 * @property negotiatedMtu advertised MTU. Nothing in memory is limited by it, but the value must look
 *   like a real link so negotiated chunk sizes stay realistic.
 * @property dropEveryNthFrame when greater than zero, every n-th sent frame is counted as transmitted
 *   but never delivered — a deterministic way to simulate loss.
 */
class LoopbackTransport(
    override val id: String = "loopback",
    private val negotiatedMtu: Int = ProtocolLimits.DEFAULT_MTU,
    private val dropEveryNthFrame: Int = 0,
) : TransportModule {

    init {
        require(dropEveryNthFrame >= 0) { "dropEveryNthFrame must not be negative" }
    }

    @Volatile
    private var listener: TransportListener? = null

    @Volatile
    private var currentState: TransportState = TransportState.CLOSED

    /** Outbound credit window; `0` until the peer grants some (`01-transport.md` §6.1). */
    private val credit = AtomicInteger(0)

    private val framesSent = AtomicLong(0L)
    private val framesReceived = AtomicLong(0L)
    private val bytesSent = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)
    private val droppedForCredit = AtomicLong(0L)
    private val framesDroppedInTransit = AtomicLong(0L)

    /** The peer endpoint, when this transport is half of a pair. */
    @Volatile
    var peer: LoopbackTransport? = null
        private set

    /** Last frame received here, for assertions and for a demo status line. */
    @Volatile
    var lastReceivedFrame: Frame? = null
        private set

    override val state: TransportState
        get() = currentState

    override val mtu: Int
        get() = negotiatedMtu

    override val availableCreditChunks: Int
        get() = credit.get()

    override val capabilities: TransportCapabilities
        get() = TransportCapabilities(
            mtu = negotiatedMtu,
            isReliableOrdered = dropEveryNthFrame == 0,
            supportsCreditFlowControl = true,
            maxFrameBytes = ProtocolLimits.HEADER_SIZE + ProtocolLimits.MAX_PAYLOAD_BYTES,
            stackName = STACK_NAME,
        )

    override val stats: TransportStats
        get() = TransportStats(
            framesSent = framesSent.get(),
            framesReceived = framesReceived.get(),
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            chunksInFlight = 0,
            creditAvailable = credit.get(),
            droppedForCredit = droppedForCredit.get(),
            roundTripTimeMs = 0, // A synchronous in-memory hand-off has no measurable RTT.
            resyncCount = 0L,
            headerCrcErrors = 0L,
            lastError = null,
        )

    /** Frames [send] accepted but intentionally did not deliver (see [dropEveryNthFrame]). */
    val droppedInTransit: Long
        get() = framesDroppedInTransit.get()

    override fun open(listener: TransportListener) {
        this.listener = listener
        transitionTo(TransportState.OPENING)
        transitionTo(TransportState.READY)
        // Report the (empty) window so a session does not have to guess. No implicit grant.
        listener.onCreditWindowChanged(credit.get())
    }

    override fun close(reason: String?) {
        if (currentState == TransportState.CLOSED) return
        transitionTo(TransportState.CLOSING)
        transitionTo(TransportState.CLOSED)
        peer?.onPeerClosed(reason)
        listener = null
    }

    override fun send(frame: Frame): SendResult {
        if (!currentState.isOpen) return SendResult.NotConnected

        val encodedSize = FrameCodec.HEADER_SIZE + frame.payload.size
        if (frame.payload.size > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            return SendResult.TooLarge(frame.payload.size, ProtocolLimits.MAX_PAYLOAD_BYTES)
        }

        // Decrement first: a credit-based link consumes the permission on hand-off, not on arrival.
        val previous = credit.getAndUpdate { current -> if (current > 0) current - 1 else 0 }
        if (previous <= 0) {
            droppedForCredit.incrementAndGet()
            if (currentState == TransportState.READY) transitionTo(TransportState.CONGESTED)
            return SendResult.NoCredit(needed = 1, available = 0)
        }

        framesSent.incrementAndGet()
        bytesSent.addAndGet(encodedSize.toLong())
        if (previous - 1 == 0 && currentState == TransportState.READY) {
            transitionTo(TransportState.CONGESTED)
        }

        val destination = peer
        if (destination == null) {
            framesDroppedInTransit.incrementAndGet()
            return SendResult.Sent(encodedSize)
        }

        val ordinal = framesSent.get()
        if (dropEveryNthFrame > 0 && ordinal % dropEveryNthFrame == 0L) {
            framesDroppedInTransit.incrementAndGet()
            return SendResult.Sent(encodedSize)
        }

        destination.deliver(frame, encodedSize)
        return SendResult.Sent(encodedSize)
    }

    override fun setCreditWindow(credits: Int) {
        require(credits >= 0) { "a credit window must not be negative: $credits" }
        credit.set(credits)
        if (credits > 0 && currentState == TransportState.CONGESTED) {
            transitionTo(TransportState.READY)
        }
        listener?.onCreditWindowChanged(credits)
    }

    /** Convenience for tests and scripted peers: open the window by [chunks] more. */
    fun grantCredits(chunks: Int) {
        require(chunks >= 0) { "credit grants must not be negative" }
        setCreditWindow(credit.get() + chunks)
    }

    /** Delivers a frame arriving from the peer; runs on the sender's thread. */
    private fun deliver(frame: Frame, encodedSize: Int) {
        framesReceived.incrementAndGet()
        bytesReceived.addAndGet(encodedSize.toLong())
        lastReceivedFrame = frame
        val target = listener
        if (target == null) {
            framesDroppedInTransit.incrementAndGet()
            return
        }
        try {
            target.onFrameReceived(frame)
        } catch (error: RuntimeException) {
            // A broken listener must not corrupt the link's accounting.
            target.onTransportError(error)
        }
    }

    /** Called by the peer when it closes, so this endpoint stops pretending to be connected. */
    private fun onPeerClosed(reason: String?) {
        if (currentState.isTerminal) return
        listener?.onTransportError(IllegalStateException("loopback peer closed: ${reason ?: "no reason"}"))
        transitionTo(TransportState.CLOSING)
        transitionTo(TransportState.CLOSED)
    }

    private fun transitionTo(next: TransportState) {
        if (currentState == next) return
        currentState = next
        listener?.onTransportStateChanged(next)
    }

    override fun toString(): String =
        "LoopbackTransport($id, state=$currentState, credit=${credit.get()}, peer=${peer?.id ?: "none"})"

    companion object {
        /** Stack name reported in [TransportCapabilities.stackName]. */
        const val STACK_NAME: String = "loopback"

        /**
         * Creates two endpoints wired to each other.
         *
         * The credit window of both starts empty; a scripted watch grants the phone's window with
         * [setCreditWindow] exactly as the real one would through `FEEDBACK`.
         */
        fun pair(
            phoneId: String = "loopback-phone",
            watchId: String = "loopback-watch",
            dropEveryNthFrame: Int = 0,
        ): Pair<LoopbackTransport, LoopbackTransport> {
            val phone = LoopbackTransport(phoneId, dropEveryNthFrame = dropEveryNthFrame)
            val watch = LoopbackTransport(watchId, dropEveryNthFrame = dropEveryNthFrame)
            phone.peer = watch
            watch.peer = phone
            return phone to watch
        }
    }
}
