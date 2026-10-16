package com.watchplay.sdk.session

import com.watchplay.sdk.protocol.ByeReason
import com.watchplay.sdk.protocol.CapsCodec
import com.watchplay.sdk.protocol.CapsAckCodec
import com.watchplay.sdk.protocol.ByeReason.diagnosticOf
import com.watchplay.sdk.protocol.ByeReason.reasonOf
import com.watchplay.sdk.protocol.Capabilities
import com.watchplay.sdk.protocol.CapsResult
import com.watchplay.sdk.protocol.ControlCommand
import com.watchplay.sdk.protocol.Feedback
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameFlags
import com.watchplay.sdk.protocol.FrameType
import com.watchplay.sdk.protocol.Hello
import com.watchplay.sdk.protocol.HelloAck
import com.watchplay.sdk.protocol.HelloAckStatus
import com.watchplay.sdk.protocol.HelloFlags
import com.watchplay.sdk.protocol.PingPayload
import com.watchplay.sdk.protocol.ProtocolException
import com.watchplay.sdk.protocol.ProtocolLimits
import com.watchplay.sdk.protocol.ProtocolVersion
import com.watchplay.sdk.protocol.ResultStatus
import com.watchplay.sdk.protocol.TouchEvent
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.transport.SendResult
import com.watchplay.sdk.transport.TransportListener
import com.watchplay.sdk.transport.TransportModule
import com.watchplay.sdk.transport.TransportState
import com.watchplay.sdk.util.MonotonicClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives one WatchPlay session on the phone: the state machine, the handshake, capability negotiation,
 * heartbeats, credit accounting and `BYE` handling of `03-session.md`.
 *
 * ## Role
 *
 * The phone is the **Source**. The watch sends `HELLO` then `CAPS`; the phone answers `HELLO_ACK` then
 * `CAPS_ACK` and may only start sending `VIDEO` afterwards (`03-session.md` §1.2). Everything the
 * session sends goes through the transport it was constructed with, and every transmit consumes a
 * protocol sequence number.
 *
 * ## What is implemented
 *
 *  * the full handshake and negotiation, using the real codecs ([Hello], [HelloAck], [CapsCodec],
 *    [CapsAckCodec]) — so a scripted peer can complete a session with no hardware,
 *  * state timeouts (10 s connecting, 3 s handshaking, 3 s negotiating),
 *  * heartbeat with the specification's suppression rule: video traffic is itself proof of life, so
 *    `PING` is only sent after `keepaliveGraceMs` without a frame (`03-session.md` §6.2),
 *  * three missed `PONG`s cause `BYE(TIMEOUT)`,
 *  * the **absolute** credit window from `FEEDBACK` (`04-video.md` §4.2) — the window is replaced, not
 *    incremented, and it starts empty because `01-transport.md` §6.1 forbids an implicit initial grant,
 *  * `DRAINING` as backpressure: at zero credit the session stops `VIDEO` but keeps heartbeating, and
 *    returns to `STREAMING` when the watch grants credit again.
 *
 * ## What is deliberately not here
 *
 * Retransmission, the reconnect backoff state machine (`03-session.md` §8) and the rate-control loop
 * (`04-video.md` §6) are policy that belongs to the caller: [Listener] reports what happened
 * (`onFeedbackReceived`, `onDrainTimeout`) and `WatchPlayClient` decides. `BYE`-then-wait-500 ms
 * teardown is simplified to `BYE`-then-close, which the transport's own teardown covers.
 *
 * ## Threading
 *
 * `open`/`close`/`tick` are called from the app's or pipeline's control thread; [TransportListener]
 * callbacks arrive on the transport's reader thread. State transitions are guarded by a reentrant lock,
 * so a listener may call back into the session on the same thread; the lock is held while listeners are
 * notified, so listeners must not block or wait on another thread that needs the session.
 *
 * @property transport bearer to run the session over.
 * @property config phone-side tunables.
 */
class SessionManager(
    private val transport: TransportModule,
    private val config: SessionConfig = SessionConfig(),
) : TransportListener {

    /** Observers of session events. */
    interface Listener {

        /** The state machine moved. Called for every transition. */
        fun onSessionStateChanged(from: SessionState, to: SessionState)

        /** Negotiation finished successfully; [outcome] carries the decided parameters. */
        fun onNegotiated(outcome: NegotiationOutcome)

        /** The session failed; [reason] is a [ByeReason] value. */
        fun onSessionFailed(reason: Int, message: String)

        /** A `TOUCH` frame arrived and was decoded. */
        fun onTouchReceived(event: TouchEvent)

        /** A `CONTROL` opcode arrived; parameters are opaque to the session. */
        fun onControlReceived(opcode: Int, payload: ByteArray)

        /** A `FEEDBACK` frame arrived and was applied to the credit window. */
        fun onFeedbackReceived(feedback: Feedback)

        /**
         * `DRAINING` has lasted longer than the negotiated `drain_timeout_ms`. The session keeps the
         * link alive; the caller should downshift quality or frame rate (`04-video.md` §6).
         */
        fun onDrainTimeout(drainingMs: Long)

        /** A frame with an unregistered `type` arrived and was skipped (`02-wire-format.md` §3). */
        fun onUnknownFrame(frame: Frame)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val negotiator = Negotiator(config)
    private val lock = Any()

    private var currentState: SessionState = SessionState.IDLE
    private var stateSinceMs: Long = MonotonicClock.nowMs()
    private var txSeq: Int = 0
    private var pendingPingAtMs: Long = -1L
    private var lastPingAtMs: Long = 0L
    private var lastRxAtMs: Long = MonotonicClock.nowMs()
    private var lastVideoAtMs: Long = -1L
    private var missingPongs: Int = 0
    private var drainSinceMs: Long = -1L
    private var drainTimeoutReported: Boolean = false

    /** Current state of the session machine. */
    val state: SessionState
        get() = synchronized(lock) { currentState }

    /** Decided parameters once negotiation succeeded, else `null`. */
    var negotiated: CapsResult? = null
        private set

    /** Everything the watch reported in `CAPS`, once received. */
    var remoteCapabilities: Capabilities? = null
        private set

    /** Render layout derived from the watch's `DISPLAY` TLV; falls back to the reference panel. */
    var layout: WatchLayoutSpec = WatchLayoutSpec()
        private set

    /** Most recent RTT estimate from `PONG` echo subtraction, or `-1` when unmeasured. */
    var roundTripTimeMs: Int = -1
        private set

    /** Frames received with an unregistered type; skipped, never fatal. */
    var unknownFrameCount: Long = 0L
        private set

    /** `CAPS_ACK` actually sent, for tests and logs. */
    var lastCapsAck: com.watchplay.sdk.protocol.CapsAck? = null
        private set

    /** Registers a listener. */
    fun addListener(listener: Listener) {
        listeners += listener
    }

    /** Unregisters a listener. */
    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * Opens the transport and starts the session. Idempotent from [SessionState.IDLE] or
     * [SessionState.FAILED] only; a reconnect must run a complete handshake, never reuse old parameters
     * (`03-session.md` §8).
     */
    fun open() {
        synchronized(lock) {
            check(currentState == SessionState.IDLE || currentState == SessionState.FAILED) {
                "session already running in $currentState"
            }
            negotiated = null
            remoteCapabilities = null
            lastCapsAck = null
            missingPongs = 0
            pendingPingAtMs = -1L
            drainSinceMs = -1L
            drainTimeoutReported = false
            transitionLocked(SessionState.CONNECTING)
        }
        transport.open(this)
    }

    /**
     * Sends `BYE` and tears the link down.
     *
     * @param reason a [ByeReason] value.
     * @param diagnostic optional UTF-8 text for the peer's logs.
     */
    fun close(reason: Int = ByeReason.USER_REQUEST, diagnostic: String = "") {
        val wasRunning = synchronized(lock) {
            val running = currentState != SessionState.FAILED
            running
        }
        if (wasRunning && transport.state.isOpen) {
            send(Frame.of(FrameType.BYE, ByeReason.payload(reason, diagnostic), seq = allocateSeq()))
        }
        transport.close(diagnostic.ifEmpty { ByeReason.describe(reason) })
        synchronized(lock) { transitionLocked(SessionState.FAILED) }
        notify { it.onSessionFailed(reason, diagnostic.ifEmpty { ByeReason.describe(reason) }) }
    }

    /**
     * Allocates the protocol `seq` for one **video frame**.
     *
     * All chunks of a video frame share their frame's `seq` (`04-video.md` §9), so the pipeline calls
     * this once per encoded frame, not once per chunk. `0` is skipped because it means "uninitialised"
     * (`02-wire-format.md` §5).
     */
    fun allocateSeq(): Int = synchronized(lock) {
        txSeq++
        if (txSeq <= 0) txSeq = 1
        txSeq
    }

    /** Sends a frame through the transport. */
    fun send(frame: Frame): SendResult = transport.send(frame)

    /**
     * Drives timeouts and heartbeats. Call about every 100–500 ms from a control thread.
     *
     * @param nowMs monotonic milliseconds; injectable for tests.
     */
    fun tick(nowMs: Long = MonotonicClock.nowMs()) {
        val stateNow = synchronized(lock) { currentState }
        when (stateNow) {
            SessionState.CONNECTING -> if (nowMs - stateSinceMs > ProtocolLimits.CONNECTING_TIMEOUT_MS) {
                closeWithBye(ByeReason.TIMEOUT, "no bearer within ${ProtocolLimits.CONNECTING_TIMEOUT_MS} ms")
            }

            SessionState.HANDSHAKING -> if (nowMs - stateSinceMs > ProtocolLimits.HANDSHAKING_TIMEOUT_MS) {
                closeWithBye(ByeReason.TIMEOUT, "no HELLO within ${ProtocolLimits.HANDSHAKING_TIMEOUT_MS} ms")
            }

            SessionState.NEGOTIATING -> if (nowMs - stateSinceMs > ProtocolLimits.NEGOTIATING_TIMEOUT_MS) {
                closeWithBye(ByeReason.TIMEOUT, "no CAPS within ${ProtocolLimits.NEGOTIATING_TIMEOUT_MS} ms")
            }

            SessionState.STREAMING, SessionState.DRAINING -> tickKeepalive(nowMs)

            SessionState.IDLE, SessionState.FAILED -> Unit
        }
    }

    private fun tickKeepalive(nowMs: Long) {
        // A missing PONG is only meaningful once the pong timeout has elapsed.
        val pending = pendingPingAtMs
        if (pending >= 0 && nowMs - pending > config.pongTimeoutMs) {
            pendingPingAtMs = -1L
            missingPongs++
            if (missingPongs >= ProtocolLimits.DEFAULT_MISSING_PONGS_BEFORE_BYE) {
                closeWithBye(ByeReason.TIMEOUT, "$missingPongs consecutive PINGs went unanswered")
                return
            }
        }

        // Video frames are proof of life: suppress heartbeats while they flow (`03-session.md` §6.2).
        val videoAge = if (lastVideoAtMs < 0) Long.MAX_VALUE else nowMs - lastVideoAtMs
        val linkIdleMs = nowMs - lastRxAtMs
        val graceSatisfied = videoAge > config.keepaliveGraceMs || linkIdleMs > config.keepaliveGraceMs
        if (!graceSatisfied) return
        if (pendingPingAtMs >= 0) return
        if (nowMs - lastPingAtMs < config.heartbeatMs) return

        val payload = PingPayload(echo = nowMs and 0xFFFFFFFFL, txSeq = txSeq.toLong() and 0xFFFFFFFFL)
        val result = send(Frame.of(FrameType.PING, payload.encode(), seq = allocateSeq()))
        lastPingAtMs = nowMs
        pendingPingAtMs = nowMs
        if (result !is SendResult.Sent) pendingPingAtMs = -1L
    }

    /** Records that a video frame was emitted, which suppresses heartbeats for a while. */
    fun noteVideoFrameSent(nowMs: Long = MonotonicClock.nowMs()) {
        lastVideoAtMs = nowMs
        lastRxAtMs = nowMs
    }

    // ---------------------------------------------------------------- TransportListener

    override fun onTransportStateChanged(state: TransportState) {
        when (state) {
            TransportState.READY -> synchronized(lock) {
                if (currentState == SessionState.CONNECTING) transitionLocked(SessionState.HANDSHAKING)
            }

            TransportState.FAILED -> {
                synchronized(lock) { transitionLocked(SessionState.FAILED) }
                notify { it.onSessionFailed(ByeReason.PEER_GONE, "bearer failed") }
            }

            TransportState.CLOSED -> {
                val wasActive = synchronized(lock) {
                    val active = currentState != SessionState.FAILED && currentState != SessionState.IDLE
                    if (active) transitionLocked(SessionState.FAILED)
                    active
                }
                if (wasActive) notify { it.onSessionFailed(ByeReason.PEER_GONE, "bearer closed") }
            }

            TransportState.OPENING, TransportState.CONGESTED, TransportState.CLOSING -> Unit
        }
    }

    override fun onFrameReceived(frame: Frame) {
        lastRxAtMs = MonotonicClock.nowMs()
        when (frame.typeCode) {
            FrameType.HELLO.code -> handleHello(frame)
            FrameType.CAPS.code -> handleCaps(frame)
            FrameType.PING.code -> handlePing(frame)
            FrameType.PONG.code -> handlePong(frame)
            FrameType.FEEDBACK.code -> handleFeedback(frame)
            FrameType.BYE.code -> handleBye(frame)
            FrameType.TOUCH.code -> handleTouch(frame)
            FrameType.CONTROL.code -> handleControl(frame)
            FrameType.HELLO_ACK.code, FrameType.CAPS_ACK.code, FrameType.VIDEO.code,
            FrameType.VIDEO_END.code,
            -> Unit // Acknowledged frames and video are not expected inbound on the Source side.

            else -> {
                unknownFrameCount++
                notify { it.onUnknownFrame(frame) }
            }
        }
    }

    override fun onCreditWindowChanged(credits: Int) {
        synchronized(lock) {
            when {
                credits <= 0 && currentState == SessionState.STREAMING -> {
                    drainSinceMs = MonotonicClock.nowMs()
                    drainTimeoutReported = false
                    transitionLocked(SessionState.DRAINING)
                }

                credits > 0 && currentState == SessionState.DRAINING -> {
                    drainSinceMs = -1L
                    transitionLocked(SessionState.STREAMING)
                }
            }
        }
    }

    override fun onTransportError(error: Throwable) {
        notify { it.onSessionFailed(ByeReason.PROTOCOL_ERROR, error.message ?: error.toString()) }
    }

    // ---------------------------------------------------------------- frame handlers

    private fun handleHello(frame: Frame) {
        val hello = try {
            Hello.decode(frame.payload)
        } catch (exception: ProtocolException) {
            closeWithBye(ByeReason.PROTOCOL_ERROR, "malformed HELLO: ${exception.message}")
            return
        }

        if (hello.minVersion > ProtocolVersion.CURRENT) {
            // The watch cannot go as low as we can: answer and stop (`03-session.md` §2.5).
            send(
                Frame.of(
                    FrameType.HELLO_ACK,
                    HelloAck(
                        protoVersion = ProtocolVersion.CURRENT,
                        status = HelloAckStatus.UNSUPPORTED_VERSION,
                        flags = phoneHelloFlags(),
                        peerNonce = hello.nonce,
                        sdkVersion = config.sdkVersion,
                    ).encode(),
                    seq = allocateSeq(),
                ),
            )
            closeWithBye(ByeReason.UNSUPPORTED_VERSION, "watch min_version=${hello.minVersion}")
            return
        }

        val selectedVersion = minOf(hello.protoVersion, ProtocolVersion.CURRENT)
        val ack = HelloAck(
            protoVersion = selectedVersion,
            status = HelloAckStatus.OK,
            flags = phoneHelloFlags(),
            peerNonce = hello.nonce,
            sdkVersion = config.sdkVersion,
        )
        send(Frame.of(FrameType.HELLO_ACK, ack.encode(), seq = allocateSeq()))
        synchronized(lock) {
            if (currentState == SessionState.HANDSHAKING || currentState == SessionState.CONNECTING) {
                transitionLocked(SessionState.NEGOTIATING)
            }
        }
    }

    private fun handleCaps(frame: Frame) {
        val capabilities = try {
            CapsCodec.decode(frame.payload)
        } catch (exception: ProtocolException) {
            sendCapsAck(negotiator.negotiate(Capabilities(), sessionId()))
            closeWithBye(ByeReason.NEGOTIATION_FAILED, "malformed CAPS: ${exception.message}")
            return
        }

        remoteCapabilities = capabilities
        capabilities.display?.let { layout = WatchLayoutSpec.fromDisplay(it) }

        val outcome = negotiator.negotiate(capabilities, sessionId())
        sendCapsAck(outcome)

        if (!outcome.succeeded) {
            closeWithBye(
                ByeReason.NEGOTIATION_FAILED,
                outcome.rejectReason ?: ResultStatus.describe(outcome.status),
            )
            return
        }

        negotiated = outcome.result
        synchronized(lock) { transitionLocked(SessionState.STREAMING) }
        notify { it.onNegotiated(outcome) }
    }

    private fun sendCapsAck(outcome: NegotiationOutcome) {
        lastCapsAck = outcome.ack
        val payload = CapsAckCodec.encode(outcome.ack)
        send(Frame.of(FrameType.CAPS_ACK, payload, seq = allocateSeq()))
    }

    private fun handlePing(frame: Frame) {
        // PONG MUST echo the PING payload byte for byte (`03-session.md` §6.1).
        val payload = if (frame.payload.size == PingPayload.SIZE_BYTES) frame.payload.copyOf() else ByteArray(0)
        send(Frame.of(FrameType.PONG, payload, seq = allocateSeq()))
    }

    private fun handlePong(frame: Frame) {
        pendingPingAtMs = -1L
        missingPongs = 0
        if (frame.payload.size < PingPayload.SIZE_BYTES) return
        val payload = try {
            PingPayload.decode(frame.payload)
        } catch (exception: ProtocolException) {
            return
        }
        // `echo` was our own monotonic clock, so the subtraction is meaningful; two devices' clocks
        // never are (`03-session.md` §6, `05-input.md` §6).
        val elapsed = MonotonicClock.nowMs() - payload.echo
        if (elapsed in 0..0xFFFF) roundTripTimeMs = elapsed.toInt()
    }

    private fun handleFeedback(frame: Frame) {
        val feedback = try {
            Feedback.decode(frame.payload)
        } catch (exception: ProtocolException) {
            return
        }
        // Absolute semantics: replace the window, never add to it (`04-video.md` §4.2).
        transport.setCreditWindow(feedback.credits.toInt().coerceAtLeast(0))
        if (feedback.lastFrameId != Feedback.NO_FRAME_ID) lastAckedFrameId = feedback.lastFrameId
        notify { it.onFeedbackReceived(feedback) }
    }

    /** Highest `frame_id` the watch reported as successfully decoded. */
    var lastAckedFrameId: Long = Feedback.NO_FRAME_ID
        private set

    private fun handleBye(frame: Frame) {
        val reason = reasonOf(frame.payload) ?: ByeReason.PROTOCOL_ERROR
        val diagnostic = diagnosticOf(frame.payload)
        // Answer with the same reason so both logs record both perspectives (`03-session.md` §7).
        if (transport.state.isOpen) {
            send(Frame.of(FrameType.BYE, ByeReason.payload(reason, diagnostic), seq = allocateSeq()))
        }
        transport.close(diagnostic)
        synchronized(lock) { transitionLocked(SessionState.FAILED) }
        notify { it.onSessionFailed(reason, diagnostic.ifEmpty { ByeReason.describe(reason) }) }
    }

    private fun handleTouch(frame: Frame) {
        val event = try {
            TouchEvent.decode(frame.payload)
        } catch (exception: ProtocolException) {
            return
        }
        notify { it.onTouchReceived(event) }
    }

    private fun handleControl(frame: Frame) {
        val opcode = ControlCommand.opcodeOf(frame.payload) ?: return
        when (opcode) {
            ControlCommand.STOP -> synchronized(lock) {
                if (currentState == SessionState.STREAMING) {
                    drainSinceMs = MonotonicClock.nowMs()
                    transitionLocked(SessionState.DRAINING)
                }
            }

            ControlCommand.RESUME -> synchronized(lock) {
                if (currentState == SessionState.DRAINING) {
                    drainSinceMs = -1L
                    transitionLocked(SessionState.STREAMING)
                }
            }

            else -> Unit
        }
        notify { it.onControlReceived(opcode, frame.payload) }
    }

    // ---------------------------------------------------------------- helpers

    private fun phoneHelloFlags(): Int {
        var flags = FrameFlags.withFlag(0, HelloFlags.HAS_DISPLAY)
        // The phone does not consume touch on the watch's behalf: the watch's own HELLO states that.
        flags = FrameFlags.withFlag(flags, HelloFlags.HAS_TOUCH, false)
        if (config.allowH264) flags = FrameFlags.withFlag(flags, HelloFlags.SUPPORTS_L2CAP_COC)
        return flags
    }

    private fun sessionId(): Long = MonotonicClock.nowMs() and 0xFFFFFFFFL

    private fun closeWithBye(reason: Int, diagnostic: String) {
        if (transport.state.isOpen) {
            send(Frame.of(FrameType.BYE, ByeReason.payload(reason, diagnostic), seq = allocateSeq()))
        }
        transport.close(diagnostic)
        synchronized(lock) { transitionLocked(SessionState.FAILED) }
        notify { it.onSessionFailed(reason, diagnostic) }
    }

    private fun transitionLocked(next: SessionState) {
        val previous = currentState
        if (previous == next) return
        currentState = next
        stateSinceMs = MonotonicClock.nowMs()
        if (next == SessionState.DRAINING) {
            drainSinceMs = stateSinceMs
            drainTimeoutReported = false
        }
        if (next == SessionState.FAILED) drainSinceMs = -1L
        // Notified outside the lock by `notify`, so record the pair for the closure.
        notify { it.onSessionStateChanged(previous, next) }
    }

    private fun notify(action: (Listener) -> Unit) {
        for (listener in listeners) {
            try {
                action(listener)
            } catch (error: RuntimeException) {
                // A misbehaving listener must not break the session machine.
                continue
            }
        }
    }

    /** Reports a sustained `DRAINING` period; called from [tick] paths in the client. */
    fun reportDrainIfNeeded(nowMs: Long = MonotonicClock.nowMs()) {
        val (active, since, alreadyReported) = synchronized(lock) {
            Triple(currentState == SessionState.DRAINING, drainSinceMs, drainTimeoutReported)
        }
        if (!active || since < 0 || alreadyReported) return
        val elapsed = nowMs - since
        if (elapsed <= config.drainTimeoutMs) return
        synchronized(lock) { drainTimeoutReported = true }
        notify { it.onDrainTimeout(elapsed) }
    }

    override fun toString(): String =
        "SessionManager(state=$state, transport=${transport.id}, rtt=${roundTripTimeMs}ms, " +
            "credit=${transport.availableCreditChunks})"
}
