package com.watchplay.sdk.transport

import com.watchplay.sdk.protocol.ProtocolLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fragmentation arithmetic of `01-transport.md` §6.1 and the bounded outbound queue.
 *
 * The numbers are the ones a wrong implementation gets wrong quietly: `MTU − 3`, not `MTU`; and a
 * 260-byte frame is `244 + 16`, not one 260-byte write.
 */
class AttWriteQueueTest {

    @Test
    fun `bearer_tu is the MTU minus the three byte ATT write header`() {
        assertEquals(244, AttFraming.bearerTu(247))
        assertEquals(182, AttFraming.bearerTu(185))
        assertEquals(20, AttFraming.bearerTu(ProtocolLimits.MIN_ATT_MTU))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an ATT MTU below the minimum is rejected`() {
        AttFraming.bearerTu(ProtocolLimits.MIN_ATT_MTU - 1)
    }

    @Test
    fun `a frame that fits becomes a single write`() {
        val encoded = ByteArray(244)
        val pieces = AttFraming.split(encoded, 244)
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].contentEquals(encoded))
    }

    @Test
    fun `a 260 byte frame becomes 244 plus 16`() {
        val pieces = AttFraming.split(ByteArray(260), 244)
        assertEquals(listOf(244, 16), pieces.map { it.size })
        assertEquals(2, AttFraming.writeCount(260, 244))
    }

    @Test
    fun `the pieces concatenate back to the original frame`() {
        val encoded = ByteArray(1000) { (it % 251).toByte() }
        val pieces = AttFraming.split(encoded, 244)
        assertEquals(listOf(244, 244, 244, 244, 24), pieces.map { it.size })
        assertTrue(pieces.reduce { acc, piece -> acc + piece }.contentEquals(encoded))
    }

    @Test
    fun `writeCount agrees with split for every size at the default unit`() {
        for (size in 1..700) {
            assertEquals(size, AttFraming.split(ByteArray(size), 244).sumOf { it.size })
            assertEquals(
                "size $size",
                AttFraming.split(ByteArray(size), 244).size,
                AttFraming.writeCount(size, 244),
            )
        }
    }

    @Test
    fun `the queue hands pieces back in order and retires a frame after its last piece`() {
        val queue = AttWriteQueue(maxFrames = 2)
        assertTrue(queue.offer(AttFraming.split(ByteArray(260) { 1 }, 244)))
        assertEquals(1, queue.queuedFrames)
        assertEquals(2, queue.queuedWrites)

        assertEquals(244, queue.peek()!!.size)
        queue.acknowledgeWrite()
        assertEquals(1, queue.queuedFrames)
        assertEquals(1, queue.queuedWrites)

        assertEquals(16, queue.peek()!!.size)
        queue.acknowledgeWrite()
        assertTrue(queue.isEmpty)
        assertNull(queue.peek())
    }

    @Test
    fun `a full queue refuses the frame instead of growing`() {
        val queue = AttWriteQueue(maxFrames = 2)
        assertTrue(queue.offer(listOf(ByteArray(16))))
        assertTrue(queue.offer(listOf(ByteArray(16))))
        assertFalse(queue.offer(listOf(ByteArray(16))))
        assertEquals(2, queue.queuedFrames)
    }

    @Test
    fun `dropping the frame in flight reports how many pieces it still owed`() {
        val queue = AttWriteQueue(maxFrames = 4)
        queue.offer(AttFraming.split(ByteArray(600), 244))
        assertEquals(3, queue.queuedWrites)

        queue.acknowledgeWrite()
        assertEquals(2, queue.dropCurrentFrame())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `clear discards everything and keeps the queue usable`() {
        val queue = AttWriteQueue(maxFrames = 1)
        queue.offer(listOf(ByteArray(16), ByteArray(16)))
        queue.clear()
        assertTrue(queue.isEmpty)
        assertTrue(queue.offer(listOf(ByteArray(16))))
    }
}
