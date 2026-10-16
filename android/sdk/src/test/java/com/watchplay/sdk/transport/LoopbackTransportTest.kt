package com.watchplay.sdk.transport

import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameType
import com.watchplay.sdk.protocol.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credit semantics of `01-transport.md` §6 and `04-video.md` §4.
 *
 * The rules pinned here are the ones that are easy to get subtly wrong: the window starts **empty**
 * (there is no implicit initial grant), `FEEDBACK.credits` **replaces** the window instead of adding to
 * it, and credit gates `VIDEO` frames only — `PING`, `CONTROL` and `BYE` must still flow at zero credit,
 * otherwise a session could never recover.
 */
class LoopbackTransportTest {

    private val received = mutableListOf<Frame>()
    private val creditUpdates = mutableListOf<Int>()

    private fun recordingListener() = object : TransportListener {
        override fun onTransportStateChanged(state: TransportState) = Unit
        override fun onFrameReceived(frame: Frame) {
            received += frame
        }

        override fun onCreditWindowChanged(credits: Int) {
            creditUpdates += credits
        }

        override fun onTransportError(error: Throwable) = Unit
    }

    @Test
    fun `the credit window starts empty`() {
        val (phone, watch) = LoopbackTransport.pair()
        phone.open(recordingListener())
        watch.open(recordingListener())

        assertEquals(0, phone.availableCreditChunks)
        // The empty window is announced, so a session never has to guess.
        assertEquals(listOf(0), creditUpdates.take(1))
    }

    @Test
    fun `video frames are refused at zero credit while control frames still flow`() {
        val (phone, watch) = LoopbackTransport.pair()
        val watchListener = recordingListener()
        watch.open(watchListener)
        phone.open(recordingListener())

        val video = Frame.of(FrameType.VIDEO, ByteArray(64), seq = 1)
        val noCredit = phone.send(video)
        assertTrue(noCredit is SendResult.NoCredit)

        // A PING needs no credit (`01-transport.md` §6.3 rule 3).
        val ping = Frame.of(FrameType.PING, ByteArray(8), seq = 2)
        assertTrue(phone.send(ping) is SendResult.Sent)
        assertEquals(1, received.size)
        assertEquals(FrameType.PING, received[0].type)
    }

    @Test
    fun `an explicit grant opens the window and is consumed per video chunk`() {
        val (phone, watch) = LoopbackTransport.pair()
        val watchListener = recordingListener()
        watch.open(watchListener)
        phone.open(recordingListener())

        phone.setCreditWindow(2)
        assertEquals(2, phone.availableCreditChunks)

        assertTrue(phone.send(Frame.of(FrameType.VIDEO, ByteArray(16), seq = 1)) is SendResult.Sent)
        assertTrue(phone.send(Frame.of(FrameType.VIDEO, ByteArray(16), seq = 1)) is SendResult.Sent)
        assertEquals(0, phone.availableCreditChunks)
        assertTrue(phone.send(Frame.of(FrameType.VIDEO, ByteArray(16), seq = 1)) is SendResult.NoCredit)
        assertEquals(2, received.count { it.type == FrameType.VIDEO })
    }

    @Test
    fun `credit is absolute and idempotent under a dropped notification`() {
        val (phone, _) = LoopbackTransport.pair()
        phone.open(recordingListener())

        phone.setCreditWindow(5)
        phone.send(Frame.of(FrameType.VIDEO, ByteArray(8), seq = 1))
        assertEquals(4, phone.availableCreditChunks)

        // A replayed FEEDBACK carrying the receiver's current window replaces ours; it must not add.
        phone.setCreditWindow(4)
        assertEquals(4, phone.availableCreditChunks)

        // A stale absolute value re-sent later is still a replacement, never a windfall.
        phone.setCreditWindow(1)
        assertEquals(1, phone.availableCreditChunks)
        assertTrue(phone.send(Frame.of(FrameType.VIDEO, ByteArray(8), seq = 2)) is SendResult.Sent)
        assertTrue(phone.send(Frame.of(FrameType.VIDEO, ByteArray(8), seq = 3)) is SendResult.NoCredit)
    }

    @Test
    fun `frames reach the peer and byte counters include the header`() {
        val (phone, watch) = LoopbackTransport.pair()
        val watchListener = recordingListener()
        watch.open(watchListener)
        phone.open(recordingListener())

        val payload = ByteArray(100) { 0x2A }
        phone.send(Frame.of(FrameType.CONTROL, payload, seq = 4))

        assertEquals(1, received.size)
        assertEquals(FrameType.CONTROL, received[0].type)
        assertEquals(payload.size, received[0].payload.size)
        assertEquals((ProtocolLimits.HEADER_SIZE + payload.size).toLong(), phone.stats.bytesSent)
        assertEquals((ProtocolLimits.HEADER_SIZE + payload.size).toLong(), watch.stats.bytesReceived)
        assertNotNull(watch.lastReceivedFrame)
    }

    @Test
    fun `a payload over the u16 ceiling is refused as too large`() {
        val (phone, _) = LoopbackTransport.pair()
        phone.open(recordingListener())
        phone.setCreditWindow(8)

        val oversized = Frame(typeCode = FrameType.VIDEO.code, payload = ByteArray(70_000))
        val result = phone.send(oversized)
        assertTrue(result is SendResult.TooLarge)
        assertEquals(70_000, (result as SendResult.TooLarge).sizeBytes)
    }

    @Test
    fun `sending without an open link reports not connected`() {
        val phone = LoopbackTransport()
        assertTrue(phone.send(Frame.of(FrameType.PING, ByteArray(0), seq = 1)) is SendResult.NotConnected)
    }

    @Test
    fun `closing one endpoint tears down the other`() {
        val (phone, watch) = LoopbackTransport.pair()
        val states = mutableListOf<TransportState>()
        watch.open(object : TransportListener {
            override fun onTransportStateChanged(state: TransportState) {
                states += state
            }

            override fun onFrameReceived(frame: Frame) = Unit
            override fun onCreditWindowChanged(credits: Int) = Unit
            override fun onTransportError(error: Throwable) = Unit
        })
        phone.open(recordingListener())
        phone.close("test")

        assertEquals(TransportState.CLOSED, phone.state)
        assertEquals(TransportState.CLOSED, watch.state)
        assertTrue(states.contains(TransportState.CLOSED))
    }
}
