package com.watchplay.sdk.transport

import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameCodec
import com.watchplay.sdk.protocol.FrameType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bearer C against a real TCP socket on loopback.
 *
 * The part §4.2 calls out is the part worth testing: TCP preserves no message boundaries, so the same
 * byte stream must survive being cut anywhere (one frame across three writes) and coalesced (two frames
 * in one write). A transport that assumes "one read = one frame" passes a hand-written test and fails
 * here.
 */
class WifiTcpTransportTest {

    private var server: ServerSocket? = null
    private var peer: Socket? = null
    private var transport: WifiTcpTransport? = null

    @After
    fun tearDown() {
        transport?.close("test over")
        peer?.close()
        server?.close()
    }

    private class Recorder : TransportListener {
        val frames = CopyOnWriteArrayList<Frame>()
        val states = CopyOnWriteArrayList<TransportState>()
        val errors = CopyOnWriteArrayList<Throwable>()
        private val firstFrame = CountDownLatch(1)

        override fun onTransportStateChanged(state: TransportState) {
            states += state
        }

        override fun onFrameReceived(frame: Frame) {
            frames += frame
            firstFrame.countDown()
        }

        override fun onCreditWindowChanged(credits: Int) = Unit

        override fun onTransportError(error: Throwable) {
            errors += error
        }

        fun awaitFrames(count: Int, timeoutMs: Long = 2_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (frames.size < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
            return frames.size >= count
        }

        fun awaitFirstFrame(timeoutMs: Long = 2_000): Boolean = firstFrame.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private class Harness(val link: WifiTcpTransport, val peer: Socket, val listener: Recorder)

    private fun connect(): Harness {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server = socket
        val accepted = ArrayBlockingQueue<Socket>(1)
        Thread({ accepted.put(socket.accept()) }, "test-server").apply { isDaemon = true }.start()

        val listener = Recorder()
        val created = WifiTcpTransport("127.0.0.1", socket.localPort)
        created.open(listener)
        val deadline = System.currentTimeMillis() + 2_000
        while (created.state != TransportState.READY && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals("the transport must reach READY", TransportState.READY, created.state)

        transport = created
        peer = accepted.poll(2, TimeUnit.SECONDS) ?: error("the server never accepted the connection")
        return Harness(created, peer!!, listener)
    }

    private fun readFully(source: Socket, expectedBytes: Int): ByteArray {
        val data = ByteArray(expectedBytes)
        var offset = 0
        source.soTimeout = 2_000
        val input = source.getInputStream()
        while (offset < expectedBytes) {
            val read = input.read(data, offset, expectedBytes - offset)
            check(read > 0) { "peer closed after $offset of $expectedBytes bytes" }
            offset += read
        }
        return data
    }

    @Test
    fun `a sent frame arrives byte for byte`() {
        val harness = connect()

        val frame = Frame.of(FrameType.PING, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), seq = 7)
        val result = harness.link.send(frame)

        assertTrue("send must be accepted, was $result", result is SendResult.Sent)
        assertArrayEquals(FrameCodec.encode(frame), readFully(harness.peer, 16 + frame.payload.size))
    }

    @Test
    fun `one frame split across three writes is delivered once`() {
        val harness = connect()
        val encoded = FrameCodec.encode(Frame.of(FrameType.HELLO, ByteArray(30) { it.toByte() }, seq = 1))

        val output = harness.peer.getOutputStream()
        output.write(encoded, 0, 5)
        output.flush()
        Thread.sleep(20)
        output.write(encoded, 5, 20)
        output.flush()
        Thread.sleep(20)
        output.write(encoded, 25, encoded.size - 25)
        output.flush()

        assertTrue("the split frame must be reassembled", harness.listener.awaitFirstFrame())
        assertEquals(1, harness.listener.frames.size)
        assertArrayEquals(encoded, FrameCodec.encode(harness.listener.frames[0]))
    }

    @Test
    fun `two frames in one write are delivered separately`() {
        val harness = connect()
        val first = FrameCodec.encode(Frame.of(FrameType.PONG, byteArrayOf(9, 9), seq = 3))
        val second = FrameCodec.encode(Frame.of(FrameType.FEEDBACK, byteArrayOf(1, 1, 1), seq = 4))

        val output = harness.peer.getOutputStream()
        output.write(first + second)
        output.flush()

        assertTrue(harness.listener.awaitFrames(2))
        assertEquals(FrameType.PONG, harness.listener.frames[0].type)
        assertEquals(FrameType.FEEDBACK, harness.listener.frames[1].type)
    }

    @Test
    fun `TCP_NODELAY is applied before the link is used`() {
        val harness = connect()

        assertTrue("§4.3 requires TCP_NODELAY", harness.link.isNoDelayEnabled)
    }

    @Test
    fun `the peer closing the connection ends the transport cleanly`() {
        val harness = connect()

        harness.peer.close()

        val deadline = System.currentTimeMillis() + 2_000
        while (!harness.link.state.isTerminal && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals("an orderly peer close is CLOSED, not FAILED", TransportState.CLOSED, harness.link.state)
    }

    @Test
    fun `video is refused at zero credit while control frames still flow`() {
        val harness = connect()

        val video = harness.link.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = 1))
        assertTrue("no implicit initial window", video is SendResult.NoCredit)

        val ping = harness.link.send(Frame.of(FrameType.PING, ByteArray(8), seq = 2))
        assertTrue("PING is allowed at zero credit", ping is SendResult.Sent)
        readFully(harness.peer, 24)

        harness.link.setCreditWindow(2)
        val withCredit = harness.link.send(Frame.of(FrameType.VIDEO, ByteArray(64), seq = 3))
        assertTrue("credit opens the video path", withCredit is SendResult.Sent)
    }

    @Test
    fun `send before open is refused`() {
        val link = WifiTcpTransport("127.0.0.1", 9)

        assertEquals(SendResult.NotConnected, link.send(Frame.of(FrameType.PING, ByteArray(0))))
        assertEquals(TransportState.CLOSED, link.state)
    }

    @Test
    fun `an unreachable peer ends in FAILED with an error`() {
        // Port 1 on loopback: nothing listens, so the connect is refused quickly.
        val link = WifiTcpTransport("127.0.0.1", 1, connectTimeoutMs = 500)
        val listener = Recorder()
        link.open(listener)
        transport = link

        val deadline = System.currentTimeMillis() + 2_000
        while (!link.state.isTerminal && System.currentTimeMillis() < deadline) Thread.sleep(5)

        assertEquals(TransportState.FAILED, link.state)
        assertTrue("the failure must reach the listener", listener.errors.isNotEmpty())
    }
}
