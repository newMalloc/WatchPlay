package com.watchplay.sdk.transport

import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameParser
import com.watchplay.sdk.protocol.FrameType
import com.watchplay.sdk.protocol.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GATT bring-up, fragmentation and flow-control rules of `01-transport.md` §2, §6 and §8.3.
 *
 * A real `BluetoothGatt` cannot be instantiated on a desktop JVM, so the radio is [FakeGattIo]; every
 * decision the transport makes is still exercised here, including the ones that only show up on a
 * device: the `bearer_tu` recomputation after a small MTU, one credit per video frame, the bounded
 * write queue, retry-on-133 and the give-up path.
 */
class BleGattTransportTest {

    private val states = mutableListOf<TransportState>()
    private val frames = mutableListOf<Frame>()
    private val credits = mutableListOf<Int>()
    private val errors = mutableListOf<Throwable>()

    private val listener = object : TransportListener {
        override fun onTransportStateChanged(state: TransportState) {
            states += state
        }

        override fun onFrameReceived(frame: Frame) {
            frames += frame
        }

        override fun onCreditWindowChanged(credits: Int) {
            this@BleGattTransportTest.credits += credits
        }

        override fun onTransportError(error: Throwable) {
            errors += error
        }
    }

    private fun bringUp(
        io: FakeGattIo = FakeGattIo(),
        delay: FakeDelay = FakeDelay(),
        mtu: Int = ProtocolLimits.DEFAULT_MTU,
    ): BleGattTransport {
        val transport = BleGattTransport(io, ProtocolLimits.DEFAULT_MTU, delay)
        transport.open(listener)
        io.callback!!.onMtuChanged(mtu, GattStatus.SUCCESS)
        io.callback!!.onServicesDiscovered(hasTx = true, hasRx = true, status = GattStatus.SUCCESS)
        io.callback!!.onNotificationsEnabled(enabled = true, status = GattStatus.SUCCESS)
        return transport
    }

    @Test
    fun `bring-up follows the documented order and ends READY`() {
        val io = FakeGattIo()
        val transport = bringUp(io)

        assertEquals(
            listOf("connect", "requestMtu:247", "discoverServices", "notifications:true"),
            io.log,
        )
        assertEquals(TransportState.READY, transport.state)
        assertEquals(247, transport.mtu)
        // §6.1: the window is announced empty, never implicitly granted.
        assertEquals(listOf(0), credits)
    }

    @Test
    fun `a 260 byte frame is written as 244 plus 16 and reassembles on the peer`() {
        val io = FakeGattIo()
        val transport = bringUp(io)
        transport.setCreditWindow(1)

        val video = Frame.of(FrameType.VIDEO, ByteArray(244) { 7 }, seq = 1)
        assertTrue(transport.send(video) is SendResult.Sent)

        assertEquals(listOf(244, 16), io.written.map { it.size })

        // The receiver's parser, not a length prefix, is what turns pieces back into frames (§6.3).
        val parser = FrameParser()
        val parsed = io.written.flatMap { parser.offer(it) }
        assertEquals(1, parsed.size)
        assertEquals(video, parsed[0])
    }

    @Test
    fun `a small MTU shrinks bearer_tu and the fragment count follows`() {
        val io = FakeGattIo()
        val transport = bringUp(io, mtu = 185)
        transport.setCreditWindow(1)

        assertEquals(182, transport.bearerTu)
        val video = Frame.of(FrameType.VIDEO, ByteArray(244), seq = 1)
        assertTrue(transport.send(video) is SendResult.Sent)

        assertEquals(listOf(182, 78), io.written.map { it.size })
    }

    @Test
    fun `video needs credit while a ping does not`() {
        val transport = bringUp()

        assertTrue(transport.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = 1)) is SendResult.NoCredit)
        assertTrue(transport.send(Frame.of(FrameType.PING, ByteArray(8), seq = 2)) is SendResult.Sent)
        assertEquals(TransportState.CONGESTED, transport.state)
    }

    @Test
    fun `one credit buys one video frame`() {
        val transport = bringUp()
        transport.setCreditWindow(2)

        assertTrue(transport.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = 1)) is SendResult.Sent)
        assertTrue(transport.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = 2)) is SendResult.Sent)
        assertTrue(transport.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = 3)) is SendResult.NoCredit)
    }

    @Test
    fun `a stalled controller shows up as backpressure, not as a growing queue`() {
        val io = FakeGattIo(writeOutcome = GattWriteOutcome.StackBusy)
        val transport = bringUp(io)
        transport.setCreditWindow(64)

        val accepted = (1..AttWriteQueue.DEFAULT_MAX_FRAMES).count {
            transport.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = it)) is SendResult.Sent
        }
        assertEquals(AttWriteQueue.DEFAULT_MAX_FRAMES, accepted)

        // Every frame is still waiting for the controller; the next one is refused (§4.3: drop, do not delay).
        val refused = transport.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = 99))
        assertTrue(refused is SendResult.NoCredit)
        assertEquals(AttWriteQueue.DEFAULT_MAX_FRAMES, transport.stats.chunksInFlight)
    }

    @Test
    fun `a wedged controller eventually fails the link instead of spinning`() {
        val io = FakeGattIo(writeOutcome = GattWriteOutcome.StackBusy)
        val delay = FakeDelay()
        val transport = bringUp(io, delay)
        transport.setCreditWindow(1)

        transport.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = 1))
        repeat(BleGattTransport.MAX_BUSY_RETRIES) {
            assertTrue(delay.tasks.isNotEmpty())
            delay.runNext()
        }

        assertEquals(TransportState.FAILED, transport.state)
        assertTrue(errors.last().message!!.contains("wedged"))
    }

    @Test
    fun `a rejected write returns the credit and kills the link`() {
        val io = FakeGattIo(writeOutcome = GattWriteOutcome.Rejected("binder died"))
        val transport = bringUp(io)
        transport.setCreditWindow(1)

        val result = transport.send(Frame.of(FrameType.VIDEO, ByteArray(244), seq = 1))

        assertTrue(result is SendResult.Failed)
        assertEquals(TransportState.FAILED, transport.state)
        // The frame never reached the watch, so its credit is back (§6.2).
        assertEquals(1, transport.availableCreditChunks)
    }

    @Test
    fun `notifications are reassembled from whatever sizes the stack delivers`() {
        val io = FakeGattIo()
        val transport = bringUp(io)
        transport.setCreditWindow(1)
        transport.send(Frame.of(FrameType.VIDEO, ByteArray(244), seq = 1))

        val encoded = io.written.reduce { acc, piece -> acc + piece }
        io.callback!!.onBytesReceived(encoded.copyOfRange(0, 100))
        assertTrue("a partial frame must not be delivered", frames.isEmpty())

        io.callback!!.onBytesReceived(encoded.copyOfRange(100, encoded.size))
        assertEquals(1, frames.size)
        assertEquals(FrameType.VIDEO, frames[0].type)
    }

    @Test
    fun `a missing TX characteristic fails the link with a named characteristic`() {
        val io = FakeGattIo()
        val transport = BleGattTransport(io, ProtocolLimits.DEFAULT_MTU, FakeDelay())
        transport.open(listener)
        io.callback!!.onMtuChanged(ProtocolLimits.DEFAULT_MTU, GattStatus.SUCCESS)
        io.callback!!.onServicesDiscovered(hasTx = false, hasRx = true, status = GattStatus.SUCCESS)

        assertEquals(TransportState.FAILED, transport.state)
        assertTrue(errors.last().message!!.contains("TX"))
    }

    @Test
    fun `a refused CCCD subscription fails the link`() {
        val io = FakeGattIo()
        val transport = BleGattTransport(io, ProtocolLimits.DEFAULT_MTU, FakeDelay())
        transport.open(listener)
        io.callback!!.onMtuChanged(ProtocolLimits.DEFAULT_MTU, GattStatus.SUCCESS)
        io.callback!!.onServicesDiscovered(true, true, GattStatus.SUCCESS)
        io.callback!!.onNotificationsEnabled(enabled = false, status = 4)

        assertEquals(TransportState.FAILED, transport.state)
        assertEquals(0, transport.availableCreditChunks)
    }

    @Test
    fun `status 133 is retried with backoff before the link is declared dead`() {
        val io = FakeGattIo()
        val delay = FakeDelay()
        val transport = BleGattTransport(io, ProtocolLimits.DEFAULT_MTU, delay)
        transport.open(listener)

        for (attempt in 1..BleGattTransport.MAX_RECONNECT_ATTEMPTS) {
            io.callback!!.onDisconnected(GattStatus.ERROR_133, "gatt error")
            assertEquals(TransportState.OPENING, transport.state)
            val scheduled = delay.lastDelayMs
            assertTrue("attempt $attempt waited ${scheduled}ms", scheduled in 1_000L..33_250L)
            delay.runNext()
            assertEquals(attempt + 1, io.connectCount)
        }

        // One more disconnect exhausts the budget: a fresh instance (or open()) is needed.
        io.callback!!.onDisconnected(GattStatus.ERROR_133, "gatt error")
        assertEquals(TransportState.FAILED, transport.state)
        assertEquals(BleGattTransport.MAX_RECONNECT_ATTEMPTS + 1, io.connectCount)
    }

    @Test
    fun `backoff grows per attempt, stays under the cap and carries jitter`() {
        val io = FakeGattIo()
        val delay = FakeDelay()
        val transport = BleGattTransport(io, ProtocolLimits.DEFAULT_MTU, delay)
        transport.open(listener)

        val delays = (1..6).map {
            io.callback!!.onDisconnected(GattStatus.ERROR_133, "gatt error")
            delay.lastDelayMs
        }

        assertTrue("first retry waited ${delays.first()}ms", delays.first() in 1_000L..1_250L)
        assertEquals(delays.sorted(), delays)
        assertTrue(
            "capped at ${BleGattTransport.RECONNECT_MAX_DELAY_MS}ms + jitter, was ${delays.last()}ms",
            delays.last() <= BleGattTransport.RECONNECT_MAX_DELAY_MS + BleGattTransport.RECONNECT_JITTER_MS,
        )
        assertTrue(delays.last() > delays.first())
    }

    @Test
    fun `close releases the radio and refuses further frames`() {
        val io = FakeGattIo()
        val transport = bringUp(io)
        transport.setCreditWindow(4)

        transport.close("done")

        assertEquals(TransportState.CLOSED, transport.state)
        assertTrue(io.disconnected)
        assertTrue(io.closed)
        assertTrue(transport.send(Frame.of(FrameType.PING, seq = 1)) is SendResult.NotConnected)
    }

    @Test
    fun `a closed transport can be opened again`() {
        val io = FakeGattIo()
        val transport = bringUp(io)
        transport.close("relink")

        transport.open(listener)

        assertEquals(TransportState.OPENING, transport.state)
        assertEquals(2, io.connectCount)
        assertNotNull(io.callback)
    }
}

/** Scripted radio: records what the transport asked for, and lets a test answer each event. */
private class FakeGattIo(
    private var writeOutcome: GattWriteOutcome = GattWriteOutcome.Accepted,
) : GattIo {

    override val peerAddress: String = "AA:BB:CC:DD:EE:FF"

    var callback: GattIoCallback? = null
        private set

    val log = mutableListOf<String>()
    val written = mutableListOf<ByteArray>()

    var connectCount: Int = 0
    var disconnected: Boolean = false
    var closed: Boolean = false

    override fun connect(callback: GattIoCallback) {
        this.callback = callback
        connectCount++
        log += "connect"
        callback.onConnected()
    }

    override fun requestMtu(mtu: Int) {
        log += "requestMtu:$mtu"
    }

    override fun discoverServices() {
        log += "discoverServices"
    }

    override fun setNotificationsEnabled(enabled: Boolean) {
        log += "notifications:$enabled"
    }

    override fun write(bytes: ByteArray): GattWriteOutcome {
        if (writeOutcome is GattWriteOutcome.Accepted) written += bytes
        return writeOutcome
    }

    override fun disconnect() {
        disconnected = true
    }

    override fun close() {
        closed = true
    }
}

/** Records scheduled work instead of waiting, so retry and backoff are observable and instant. */
private class FakeDelay : Delay {

    val tasks = ArrayDeque<Pair<Long, () -> Unit>>()

    val lastDelayMs: Long
        get() = tasks.lastOrNull()?.first ?: -1L

    override fun after(delayMs: Long, action: () -> Unit) {
        tasks.addLast(delayMs to action)
    }

    override fun shutdown() {
        tasks.clear()
    }

    fun runNext() {
        val (_, action) = tasks.removeFirst()
        action()
    }
}
