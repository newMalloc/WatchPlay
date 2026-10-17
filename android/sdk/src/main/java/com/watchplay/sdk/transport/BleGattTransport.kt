package com.watchplay.sdk.transport

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameCodec
import com.watchplay.sdk.protocol.FrameParser
import com.watchplay.sdk.protocol.FrameType
import com.watchplay.sdk.protocol.ProtocolLimits
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Bearer A — the BLE GATT transport every WatchPlay implementation must provide
 * (`01-transport.md` §2, `09` T-01/T-02).
 *
 * ```
 *   phone (central)                         watch (peripheral)
 *   a7c0a701  Write Without Response  ---->  TX, ATT writes of bearer_tu bytes
 *   a7c0a702  Notify                  <----  RX, one protocol frame at a time
 *   a7c0a703  Write + Notify          <-->   CTRL (optional)
 *   a7c0a704  Read + Notify           <----  PSM for a later L2CAP switch (optional)
 * ```
 *
 * `bearer_tu` is `MTU − 3`, so the recommended MTU **247** yields 244 usable bytes — exactly the
 * default `max_chunk_bytes`. `01-transport.md` §2.2 explains why 247 is preferred over 517: a
 * protocol frame (16 + 244 = 260 bytes) then fragments into just `244 + 16`, and some Android stacks
 * report 517 while truncating at 512.
 *
 * ## Bring-up order (`01-transport.md` §2.2 steps 1–5)
 *
 * ```
 *   connectGatt  ->  requestMtu(247)  ->  discoverServices  ->  CCCD write  ->  READY
 *                          |                    |                  |
 *                     mtu < 247?           no TX/RX?          refused?
 *                     bearer_tu = mtu-3    FAILED             FAILED
 * ```
 *
 * On status 133 (Android's "device not found / out of resources") the transport retries with
 * `min(30 s, 1 s × 2^n) + jitter` before reporting the link dead (§2.5, §8.3).
 *
 * ## What lives here, and what does not
 *
 * All of it is protocol logic and therefore runs on a desktop JVM in the unit tests: the state
 * machine, the `bearer_tu` recomputation, fragmentation ([AttFraming]), the bounded write queue
 * ([AttWriteQueue]), credit accounting and the receive parser. Only [AndroidGattIo] touches
 * `BluetoothGatt`.
 *
 * @property io the radio, or a fake in tests.
 * @property requestMtu ATT MTU to request; the specification says 247.
 */
class BleGattTransport internal constructor(
    private val io: GattIo,
    private val requestMtu: Int = ProtocolLimits.DEFAULT_MTU,
    private val delay: Delay = ExecutorDelay(),
    maxQueuedFrames: Int = AttWriteQueue.DEFAULT_MAX_FRAMES,
) : TransportModule, GattIoCallback {

    /** Convenience for apps: the real Android radio, behind [AndroidGattIo]. */
    constructor(
        context: Context,
        device: BluetoothDevice,
        requestMtu: Int = ProtocolLimits.DEFAULT_MTU,
    ) : this(AndroidGattIo(context, device), requestMtu)

    private val credit = AtomicInteger(0)
    private val parser = FrameParser()
    private val writes = AttWriteQueue(maxQueuedFrames)

    /** Guards [parser], [writes] and the transition that flips a state on send. */
    private val lock = Any()

    private val pumpScheduled = AtomicBoolean(false)

    private val framesSent = AtomicLong(0L)
    private val framesReceived = AtomicLong(0L)
    private val bytesSent = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)
    private val droppedForCredit = AtomicLong(0L)

    @Volatile
    private var listener: TransportListener? = null

    @Volatile
    private var currentState: TransportState = TransportState.CLOSED

    @Volatile
    private var negotiatedMtu: Int = 0

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var userClosed: Boolean = false

    private var attempts: Int = 0

    /** Consecutive "stack full" answers for the same piece; keeps a dead controller from looping. */
    private var busyRetries: Int = 0

    override val id: String
        get() = "ble-gatt:${io.peerAddress}"

    override val state: TransportState
        get() = currentState

    override val mtu: Int
        get() = if (negotiatedMtu > 0) negotiatedMtu else requestMtu

    override val availableCreditChunks: Int
        get() = credit.get()

    /** Largest payload one ATT write can carry at the negotiated MTU: what becomes `max_chunk_bytes`. */
    val bearerTu: Int
        get() = if (negotiatedMtu >= ProtocolLimits.MIN_ATT_MTU) {
            AttFraming.bearerTu(negotiatedMtu)
        } else {
            AttFraming.bearerTu(requestMtu)
        }

    override val capabilities: TransportCapabilities
        get() = TransportCapabilities(
            mtu = mtu,
            isReliableOrdered = true, // BLE L2CAP guarantees in-order, error-checked delivery.
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
            chunksInFlight = writes.queuedWrites,
            creditAvailable = credit.get(),
            droppedForCredit = droppedForCredit.get(),
            roundTripTimeMs = -1, // GATT has no RTT primitive; PING/PONG measures it at the session.
            resyncCount = parser.resyncCount,
            headerCrcErrors = parser.headerCrcErrors,
            lastError = lastError,
        )

    override fun open(listener: TransportListener) {
        require(currentState == TransportState.CLOSED || currentState == TransportState.FAILED) {
            "open() from $currentState; close the transport first"
        }
        this.listener = listener
        userClosed = false
        attempts = 0
        busyRetries = 0
        lastError = null
        parser.reset()
        synchronized(lock) { writes.clear() }
        credit.set(0) // §6.1: no implicit initial window.
        transitionTo(TransportState.OPENING)
        io.connect(this)
    }

    override fun close(reason: String?) {
        if (currentState == TransportState.CLOSED) return
        userClosed = true
        transitionTo(TransportState.CLOSING)
        synchronized(lock) { writes.clear() }
        io.disconnect()
        io.close()
        delay.shutdown()
        transitionTo(TransportState.CLOSED)
        listener = null
    }

    override fun send(frame: Frame): SendResult {
        if (!currentState.isOpen) return SendResult.NotConnected
        if (frame.payload.size > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            return SendResult.TooLarge(frame.payload.size, ProtocolLimits.MAX_PAYLOAD_BYTES)
        }

        // One protocol frame, whatever its size: bearer fragmentation hides the split (§6.2).
        val encoded = FrameCodec.encode(frame)
        val isVideo = frame.typeCode == FrameType.VIDEO.code
        if (isVideo) {
            val previous = credit.getAndUpdate { current -> if (current > 0) current - 1 else 0 }
            if (previous <= 0) {
                droppedForCredit.incrementAndGet()
                if (currentState == TransportState.READY) transitionTo(TransportState.CONGESTED)
                return SendResult.NoCredit(needed = 1, available = 0)
            }
        }

        synchronized(lock) {
            // A full queue means the controller stopped draining: report backpressure, never grow.
            if (!writes.offer(AttFraming.split(encoded, bearerTu))) {
                if (isVideo) credit.incrementAndGet()
                droppedForCredit.incrementAndGet()
                if (currentState == TransportState.READY) transitionTo(TransportState.CONGESTED)
                return SendResult.NoCredit(needed = 1, available = credit.get())
            }
            framesSent.incrementAndGet()
            if (isVideo && credit.get() == 0 && currentState == TransportState.READY) {
                transitionTo(TransportState.CONGESTED)
            }
        }

        pump()
        // The write may have been rejected synchronously; do not claim a frame nobody received.
        if (currentState == TransportState.FAILED) {
            return SendResult.Failed(lastError ?: "link failed while writing", null)
        }
        return SendResult.Sent(encoded.size)
    }

    override fun setCreditWindow(credits: Int) {
        require(credits >= 0) { "a credit window must not be negative: $credits" }
        credit.set(credits)
        if (credits > 0 && currentState == TransportState.CONGESTED) transitionTo(TransportState.READY)
        listener?.onCreditWindowChanged(credits)
    }

    // ---------------------------------------------------------------- writes

    /**
     * Writes queued pieces until the controller refuses more.
     *
     * `Write Without Response` has no completion event, so "the stack accepted it" is the only
     * signal available; issuing the next piece immediately is what keeps one connection interval
     * from carrying a single 244-byte write.
     */
    private fun pump() {
        if (!pumpScheduled.compareAndSet(false, true)) return
        try {
            while (true) {
                val piece = synchronized(lock) { writes.peek() } ?: return
                when (val outcome = io.write(piece)) {
                    GattWriteOutcome.Accepted -> {
                        synchronized(lock) {
                            busyRetries = 0
                            writes.acknowledgeWrite()
                            bytesSent.addAndGet(piece.size.toLong())
                        }
                    }
                    GattWriteOutcome.StackBusy -> {
                        // Backpressure, not failure: retry the same bytes shortly (§2.5). Bounded, so a
                        // controller that never drains fails the link instead of spinning forever.
                        if (++busyRetries > MAX_BUSY_RETRIES) {
                            fail("controller refused $MAX_BUSY_RETRIES consecutive writes; link is wedged")
                            return
                        }
                        delay.after(RETRY_DELAY_MS) { if (!currentState.isTerminal) pump() }
                        return
                    }
                    is GattWriteOutcome.Rejected -> {
                        onWriteRejected(outcome.reason)
                        return
                    }
                }
            }
        } finally {
            pumpScheduled.set(false)
        }
    }

    private fun onWriteRejected(reason: String) {
        val dropped = synchronized(lock) {
            val remaining = writes.dropCurrentFrame()
            writes.clear()
            remaining
        }
        // The frame never reached the watch, so its credit must come back (§6.2: credit is returned
        // only when the receiver has actually taken the chunk).
        credit.incrementAndGet()
        droppedForCredit.incrementAndGet()
        fail("ATT write rejected: $reason (dropped $dropped piece(s))")
    }

    // ------------------------------------------------------------- callbacks

    override fun onConnected() {
        io.requestMtu(requestMtu)
    }

    override fun onMtuChanged(mtu: Int, status: Int) {
        if (status != GattStatus.SUCCESS) {
            fail("requestMtu($requestMtu) failed: status $status")
            return
        }
        if (mtu < ProtocolLimits.MIN_ATT_MTU) {
            fail("useless ATT MTU $mtu (the minimum is ${ProtocolLimits.MIN_ATT_MTU})")
            return
        }
        negotiatedMtu = mtu
        io.discoverServices()
    }

    override fun onServicesDiscovered(hasTx: Boolean, hasRx: Boolean, status: Int) {
        if (status != GattStatus.SUCCESS) {
            fail("service discovery failed: status $status")
            return
        }
        val missing = buildList {
            if (!hasTx) add("TX ${WatchPlayGattProfile.TX}")
            if (!hasRx) add("RX ${WatchPlayGattProfile.RX}")
        }
        if (missing.isNotEmpty()) {
            fail("peer does not expose the WatchPlay service: missing ${missing.joinToString()}")
            return
        }
        io.setNotificationsEnabled(true)
    }

    override fun onNotificationsEnabled(enabled: Boolean, status: Int) {
        if (!enabled) {
            fail("CCCD subscription was refused (status $status)")
            return
        }
        lastError = null
        attempts = 0
        transitionTo(TransportState.READY)
        // Report the (empty) window so the session does not have to guess.
        listener?.onCreditWindowChanged(credit.get())
    }

    override fun onBytesReceived(bytes: ByteArray) {
        synchronized(lock) {
            val frames = parser.offer(bytes)
            for (frame in frames) {
                framesReceived.incrementAndGet()
                bytesReceived.addAndGet((ProtocolLimits.HEADER_SIZE + frame.payload.size).toLong())
                val target = listener ?: return@synchronized
                try {
                    target.onFrameReceived(frame)
                } catch (error: RuntimeException) {
                    // A broken listener must not corrupt the link's accounting.
                    target.onTransportError(error)
                }
            }
        }
    }

    override fun onDisconnected(status: Int, reason: String?) {
        if (userClosed || currentState.isTerminal) return
        val detail = "status $status${reason?.let { ": $it" } ?: ""}"
        if (attempts >= MAX_RECONNECT_ATTEMPTS) {
            fail("link lost, gave up after $attempts reconnect attempt(s): $detail")
            return
        }
        attempts++
        val waitMs = backoffMs(attempts)
        lastError = "link lost ($detail); reconnecting in ${waitMs}ms"
        listener?.onTransportError(IOException(lastError))
        transitionTo(TransportState.OPENING)
        delay.after(waitMs) {
            if (!userClosed && currentState == TransportState.OPENING) io.connect(this)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun backoffMs(attempt: Int): Long {
        val exponential = RECONNECT_BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 5)
        return minOf(RECONNECT_MAX_DELAY_MS, exponential) + Random.nextLong(0L, RECONNECT_JITTER_MS)
    }

    private fun fail(message: String) {
        lastError = message
        synchronized(lock) { writes.clear() }
        transitionTo(TransportState.FAILED)
        listener?.onTransportError(IllegalStateException(message))
    }

    private fun transitionTo(next: TransportState) {
        if (currentState == next) return
        currentState = next
        listener?.onTransportStateChanged(next)
    }

    override fun toString(): String =
        "BleGattTransport(${io.peerAddress}, state=$currentState, mtu=$mtu, credit=${credit.get()}, " +
            "queued=${writes.queuedWrites})"

    companion object {
        /** Stack name reported in [TransportCapabilities.stackName]. */
        const val STACK_NAME: String = "ble-gatt"

        /** ATT write overhead: 1 byte opcode + 2 bytes attribute handle. */
        const val ATT_WRITE_OVERHEAD: Int = ProtocolLimits.ATT_WRITE_OVERHEAD

        /** How long to wait before retrying a piece the controller queue refused. */
        const val RETRY_DELAY_MS: Long = 8L

        /**
         * Consecutive "stack full" answers tolerated for one piece — roughly two seconds at
         * [RETRY_DELAY_MS]. Past that the controller is not draining and the link is declared dead.
         */
        const val MAX_BUSY_RETRIES: Int = 250

        /** Reconnect attempts before the link is declared dead; the spec allows the vendor to tune it. */
        const val MAX_RECONNECT_ATTEMPTS: Int = 3

        /** First reconnect delay; doubles per attempt, capped at [RECONNECT_MAX_DELAY_MS]. */
        const val RECONNECT_BASE_DELAY_MS: Long = 1_000L

        /** Ceiling for the reconnect backoff (`01-transport.md` §2.5). */
        const val RECONNECT_MAX_DELAY_MS: Long = 30_000L

        /** Upper bound of the random jitter added to every reconnect delay. */
        const val RECONNECT_JITTER_MS: Long = 250L
    }
}

/**
 * Delayed execution, injected so the retry and reconnect paths are testable without real timers.
 */
interface Delay {

    /** Runs [action] after [delayMs], off the caller's thread is not required. */
    fun after(delayMs: Long, action: () -> Unit)

    /** Cancels nothing in flight; just stops accepting new work and releases the thread. */
    fun shutdown()
}

/** Default [Delay]: a daemon scheduler that is created lazily, so a transport can be reopened. */
class ExecutorDelay : Delay {

    private val lock = Any()
    private var executor: ScheduledThreadPoolExecutor? = null

    override fun after(delayMs: Long, action: () -> Unit) {
        val target = synchronized(lock) {
            val current = executor?.takeIf { !it.isShutdown }
            current ?: ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "watchplay-gatt-delay").apply { isDaemon = true }
            }.apply {
                removeOnCancelPolicy = true
                executor = this
            }
        }
        try {
            target.schedule({ action() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The transport was closed while a retry was queued: nothing left to retry.
        }
    }

    override fun shutdown() {
        val target = synchronized(lock) {
            val current = executor
            executor = null
            current
        }
        target?.shutdownNow()
    }
}
