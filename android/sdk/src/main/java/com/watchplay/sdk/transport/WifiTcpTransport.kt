package com.watchplay.sdk.transport

import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameCodec
import com.watchplay.sdk.protocol.FrameParser
import com.watchplay.sdk.protocol.FrameType
import com.watchplay.sdk.protocol.ProtocolLimits
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bearer C: the same protocol over a plain TCP stream (`01-transport.md` §4).
 *
 * ```
 *   [Frame] --FrameCodec.encode--> socket output --\
 *                                                   >-- one byte stream, no message boundaries
 *   [Frame] <--FrameParser.offer--- socket input  --/
 * ```
 *
 * ## Why TCP needs its own transport, not just a `Socket` wrapper
 *
 * A stream preserves no message boundaries, so §4.2 requires a resident incremental parser and
 * forbids "one read = one frame" as well as any mandatory length prefix. Both live here: the reader
 * thread feeds every read into a [FrameParser], and the writer thread serialises whole frames from a
 * bounded queue. `TCP_NODELAY` is set before the connection is used, because Nagle would hold a 14-byte
 * `TOUCH` or a 22-byte `FEEDBACK` for up to 40 ms — the §4.3 deadline is 200 ms for the whole round trip.
 *
 * ## What it deliberately does not do
 *
 *  * **Discovery.** §4.1 leaves the handover open (mDNS, SoftAP, or credentials carried over a BLE
 *    bootstrap link — still Q6). The host supplies `host`/`port` however it found them.
 *  * **Reconnect.** §9.3's backoff is a policy for the session, not the bearer; a failed `open` ends in
 *    [TransportState.FAILED] and the caller decides whether to try again.
 *  * **UDP.** §4.3 explains why a datagram bearer needs its own loss policy; v0.1 defines none.
 *
 * ## Credit
 *
 * Application-layer credit is unchanged: `FEEDBACK.credits` is applied through [setCreditWindow], and
 * `VIDEO` at zero credit is refused rather than queued (`01-transport.md` §6.1, §6.3). TCP's own window
 * is a second, invisible layer — the same two-layer situation §3.3 describes for L2CAP.
 *
 * @property host peer address, already resolved by whatever discovery the host used.
 * @property port peer TCP port.
 * @property id identifier reported to the session.
 * @property connectTimeoutMs how long [Socket.connect] may take.
 * @property writeQueueFrames how many whole frames may wait for the writer thread before [send] starts
 *   refusing work. The protocol's rule is "drop, never queue" (`04-video.md` §5.4); this queue exists
 *   only so a write does not block the caller, and it is deliberately shallow.
 */
class WifiTcpTransport(
    private val host: String,
    private val port: Int,
    override val id: String = "wifi-tcp",
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val writeQueueFrames: Int = WRITE_QUEUE_FRAMES,
    private val negotiateMtu: Int = BEARER_TU,
) : TransportModule {

    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(writeQueueFrames >= 1) { "writeQueueFrames must be at least 1" }
        require(negotiateMtu > ProtocolLimits.HEADER_SIZE) { "negotiateMtu must exceed one header" }
    }

    @Volatile
    private var listener: TransportListener? = null

    @Volatile
    private var currentState: TransportState = TransportState.CLOSED

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var noDelayApplied: Boolean = false

    private val parser = FrameParser()

    private val credit = AtomicLong(0L)

    private val framesSent = AtomicLong(0L)
    private val framesReceived = AtomicLong(0L)
    private val bytesSent = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)
    private val droppedForCredit = AtomicLong(0L)
    private val refusedForQueue = AtomicLong(0L)

    @Volatile
    private var lastError: String? = null

    private val writeLock = ReentrantLock()
    private val writeAvailable = writeLock.newCondition()
    private val writeQueue = ArrayDeque<ByteArray>()

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var writerThread: Thread? = null

    override val state: TransportState
        get() = currentState

    override val mtu: Int
        get() = if (currentState.isOpen) negotiateMtu else 0

    override val availableCreditChunks: Int
        get() = credit.get().toInt()

    override val maxChunkPayloadBytes: Int
        get() = if (currentState.isOpen) negotiateMtu else Int.MAX_VALUE

    override val capabilities: TransportCapabilities
        get() = TransportCapabilities(
            mtu = negotiateMtu,
            isReliableOrdered = true,
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
            chunksInFlight = writeLock.withLock { writeQueue.size },
            creditAvailable = credit.get().toInt(),
            droppedForCredit = droppedForCredit.get(),
            resyncCount = parser.resyncCount,
            headerCrcErrors = parser.headerCrcErrors,
            lastError = lastError,
        )

    /** `true` once `TCP_NODELAY` has been applied to the connected socket. */
    val isNoDelayEnabled: Boolean
        get() = noDelayApplied

    /** Frames refused because the writer queue was full; the pipeline counts these as dropped. */
    val refusedQueueDepth: Long
        get() = refusedForQueue.get()

    override fun open(listener: TransportListener) {
        require(currentState == TransportState.CLOSED) { "open() called in $currentState" }
        this.listener = listener
        transitionTo(TransportState.OPENING)
        // Connecting can block for seconds; the contract says open() must not.
        val starter = Thread({
            try {
                connect()
            } catch (error: IOException) {
                fail("could not connect to $host:$port: ${error.message}", error)
            }
        }, "watchplay-tcp-connect")
        starter.isDaemon = true
        starter.start()
    }

    override fun close(reason: String?) {
        if (currentState.isTerminal) return
        transitionTo(TransportState.CLOSING)
        shutdown()
        transitionTo(TransportState.CLOSED)
    }

    override fun send(frame: Frame): SendResult {
        if (!currentState.isOpen) return SendResult.NotConnected
        if (frame.payload.size > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            return SendResult.TooLarge(frame.payload.size, ProtocolLimits.MAX_PAYLOAD_BYTES)
        }

        // Credit gates `VIDEO` only: at zero credit a sender MUST stop video but MAY keep sending
        // PING/PONG/CONTROL/BYE (`01-transport.md` §6.3).
        if (frame.typeCode == FrameType.VIDEO.code) {
            val previous = credit.getAndUpdate { current -> if (current > 0) current - 1 else 0 }
            if (previous <= 0) {
                droppedForCredit.incrementAndGet()
                return SendResult.NoCredit(needed = 1, available = 0)
            }
        }

        val encoded = FrameCodec.encode(frame)
        val queued = enqueue(encoded)
        if (!queued) {
            refusedForQueue.incrementAndGet()
            return SendResult.Failed("write queue full (${writeQueue.size} frames waiting)")
        }
        framesSent.incrementAndGet()
        bytesSent.addAndGet(encoded.size.toLong())
        return SendResult.Sent(encoded.size)
    }

    override fun setCreditWindow(credits: Int) {
        require(credits >= 0) { "a credit window must not be negative: $credits" }
        credit.set(credits.toLong())
        if (credits > 0 && currentState == TransportState.CONGESTED) {
            transitionTo(TransportState.READY)
        }
        listener?.onCreditWindowChanged(credits)
    }

    // ---------------------------------------------------------------- connection

    private fun connect() {
        val created = Socket()
        created.tcpNoDelay = true
        noDelayApplied = created.tcpNoDelay
        created.connect(InetSocketAddress(host, port), connectTimeoutMs)
        created.soTimeout = READ_POLL_TIMEOUT_MS
        // close() may have run while the connect was in flight; a socket nobody owns must not linger.
        if (currentState != TransportState.OPENING) {
            created.close()
            return
        }
        socket = created
        transitionTo(TransportState.READY)
        listener?.onCreditWindowChanged(credit.get().toInt())
        startReader(created)
        startWriter(created)
    }

    private fun startReader(connection: Socket) {
        val thread = Thread({
            val buffer = ByteArray(READ_BUFFER_BYTES)
            try {
                val input = connection.getInputStream()
                while (!connection.isClosed) {
                    val read = try {
                        input.read(buffer)
                    } catch (timeout: java.net.SocketTimeoutException) {
                        continue
                    }
                    if (read < 0) {
                        // The peer closed: an orderly end, not an error (§9.2 keeps failure semantics
                        // for PING timeouts and socket errors).
                        closeFromPeer("peer closed the connection")
                        return@Thread
                    }
                    bytesReceived.addAndGet(read.toLong())
                    for (frame in parser.offer(buffer, 0, read)) deliver(frame)
                }
            } catch (error: IOException) {
                if (!currentState.isTerminal) {
                    fail("read failed: ${error.message}", error)
                }
            }
        }, "watchplay-tcp-reader")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    private fun startWriter(connection: Socket) {
        val thread = Thread({
            try {
                val output = connection.getOutputStream()
                while (!connection.isClosed) {
                    val next = takeFromQueue() ?: continue
                    output.write(next)
                    output.flush()
                }
            } catch (error: IOException) {
                if (!currentState.isTerminal) {
                    fail("write failed: ${error.message}", error)
                }
            }
        }, "watchplay-tcp-writer")
        thread.isDaemon = true
        writerThread = thread
        thread.start()
    }

    /** `true` when the frame was queued; `false` when the queue is at its bound. */
    private fun enqueue(encoded: ByteArray): Boolean = writeLock.withLock {
        if (writeQueue.size >= writeQueueFrames) return@withLock false
        writeQueue.addLast(encoded)
        writeAvailable.signalAll()
        true
    }

    /** Blocks up to the poll timeout for work; `null` means "nothing to do, check the loop". */
    private fun takeFromQueue(): ByteArray? = writeLock.withLock {
        if (writeQueue.isEmpty()) {
            writeAvailable.await(WRITE_POLL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        writeQueue.removeFirstOrNull()
    }

    private fun deliver(frame: Frame) {
        framesReceived.incrementAndGet()
        val target = listener ?: return
        try {
            target.onFrameReceived(frame)
        } catch (error: RuntimeException) {
            // A broken listener must not corrupt the link's accounting.
            target.onTransportError(error)
        }
    }

    private fun closeFromPeer(reason: String) {
        if (currentState.isTerminal) return
        transitionTo(TransportState.CLOSING)
        shutdown()
        transitionTo(TransportState.CLOSED)
    }

    private fun fail(reason: String, cause: Throwable) {
        if (currentState.isTerminal) return
        lastError = reason
        transitionTo(TransportState.FAILED)
        listener?.onTransportError(cause)
        shutdown()
    }

    private fun shutdown() {
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // Closing an already-broken socket is not an error worth reporting.
        }
        socket = null
        writeLock.withLock {
            writeQueue.clear()
            writeAvailable.signalAll()
        }
        readerThread?.interrupt()
        writerThread?.interrupt()
    }

    private fun transitionTo(next: TransportState) {
        if (currentState == next) return
        currentState = next
        listener?.onTransportStateChanged(next)
    }

    override fun toString(): String =
        "WifiTcpTransport($host:$port, state=$currentState, credit=${credit.get()}, " +
            "nodelay=$noDelayApplied, ${stats})"

    companion object {
        /** Stack name reported in [TransportCapabilities.stackName]. */
        const val STACK_NAME: String = "wifi-tcp"

        /** `bearer_tu` for Bearer C: 1500-byte Ethernet MTU minus IP/TCP headers (`01-transport.md` §2). */
        const val BEARER_TU: Int = 1448

        /** Default connect budget; a local-network peer that has not answered in this long is gone. */
        const val CONNECT_TIMEOUT_MS: Int = 5_000

        /**
         * Read poll timeout. A blocking `read` would make [close] wait for the next inbound byte; with a
         * timeout the reader wakes up, re-checks the state and exits.
         */
        const val READ_POLL_TIMEOUT_MS: Int = 200

        /** Writer wake-up period, so [close] does not have to interrupt a waiting writer. */
        const val WRITE_POLL_TIMEOUT_MS: Long = 200L

        /** Depth of the writer queue; deeper only adds `depth × frame period` of latency. */
        const val WRITE_QUEUE_FRAMES: Int = 32

        /** One read may carry several frames; 8 KiB covers a full video frame on Bearer C. */
        const val READ_BUFFER_BYTES: Int = 8 * 1024
    }
}
