package com.watchplay.sdk.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunking arithmetic and reassembly, including the specification's own worked examples
 * (`02-wire-format.md` §8, `04-video.md` §3.4, §9 T-06).
 */
class VideoFrameAssemblerTest {

    private fun media(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `8123 byte frame at 244 byte chunks produces 37 chunks with a 79 byte last payload`() {
        val chunker = VideoFrameChunker(244)
        assertEquals(224, chunker.mediaBytesPerChunk)

        val chunked = chunker.chunk(
            frameId = 0,
            codedData = media(8123),
            codec = VideoCodec.MJPEG,
            widthPx = 466,
            heightPx = 466,
            quality = 72,
            seq = 42,
            isKeyframe = true,
        )

        assertEquals(37, chunked.chunkTotal)
        assertEquals(37, chunked.frames.size)
        assertEquals(79, chunked.frames.last().payload.size)
        assertEquals(244, chunked.frames.first().payload.size)
        assertEquals(8123L, chunked.header.frameBytes)
        // Every chunk must fit the negotiated payload ceiling, sub-header included.
        chunked.frames.forEach { assertTrue(it.payload.size <= 244) }
    }

    @Test
    fun `reassembles a multi-chunk frame`() {
        val original = media(500)
        val chunker = VideoFrameChunker(244)
        val chunked = chunker.chunk(
            frameId = 7,
            codedData = original,
            codec = VideoCodec.MJPEG,
            widthPx = 466,
            heightPx = 466,
            quality = 70,
            seq = 11,
            isKeyframe = true,
        )
        assertEquals(3, chunked.chunkTotal)

        val assembler = VideoFrameAssembler(maxChunkBytes = 244)
        val first = assembler.onChunk(chunked.frames[0], nowMs = 0)
        assertTrue(first is AssemblyResult.Progress)
        assertEquals(1, (first as AssemblyResult.Progress).chunksReceived)

        assertTrue(assembler.onChunk(chunked.frames[1], nowMs = 1) is AssemblyResult.Progress)

        val last = assembler.onChunk(chunked.frames[2], nowMs = 2)
        assertTrue(last is AssemblyResult.Complete)
        val frame = (last as AssemblyResult.Complete).frame
        assertEquals(7L, frame.frameId)
        assertEquals(11, frame.seq)
        assertArrayEquals(original, frame.codedData)
        assertEquals(500, frame.sizeBytes)
        assertTrue(frame.isKeyframe)
        assertNull(frame.captureTsMs)
        assertEquals(1L, assembler.stats.framesCompleted)
        assertEquals(0L, assembler.stats.framesRejected)
    }

    @Test
    fun `carries an optional capture timestamp on chunk zero only`() {
        val original = media(300)
        val chunked = VideoFrameChunker(244).chunk(
            frameId = 1,
            codedData = original,
            codec = VideoCodec.MJPEG,
            widthPx = 100,
            heightPx = 100,
            quality = 60,
            seq = 3,
            isKeyframe = true,
            captureTsMs = 123456789L,
        )
        assertTrue(chunked.header.hasTimestamp)

        val assembler = VideoFrameAssembler(maxChunkBytes = 244)
        var result: AssemblyResult = assembler.onChunk(chunked.frames[0], nowMs = 0)
        for (index in 1 until chunked.frames.size) {
            result = assembler.onChunk(chunked.frames[index], nowMs = index.toLong())
        }
        assertTrue(
            "expected a complete frame but got $result (chunkTotal=${chunked.chunkTotal}, " +
                "paylods=${chunked.frames.map { it.payload.size }})",
            result is AssemblyResult.Complete,
        )
        val frame = (result as AssemblyResult.Complete).frame
        assertEquals(123456789L, frame.captureTsMs)
        // The timestamp must not leak into the reassembled media.
        assertArrayEquals(original, frame.codedData)
    }

    @Test
    fun `rejects an out of order chunk and drops the whole frame`() {
        val chunked = VideoFrameChunker(244).chunk(
            frameId = 2,
            codedData = media(500),
            codec = VideoCodec.MJPEG,
            widthPx = 10,
            heightPx = 10,
            quality = 50,
            seq = 5,
            isKeyframe = true,
        )
        val assembler = VideoFrameAssembler(maxChunkBytes = 244)
        assembler.onChunk(chunked.frames[0], nowMs = 0)
        val result = assembler.onChunk(chunked.frames[2], nowMs = 1)
        assertTrue(result is AssemblyResult.Rejected)
        assertEquals(DropReason.OUT_OF_ORDER, (result as AssemblyResult.Rejected).reason)
        assertTrue(!assembler.isAssembling)
        assertEquals(1L, assembler.stats.framesRejected)
    }

    @Test
    fun `rejects a chunk with no media byte`() {
        val subHeaderOnly = VideoFrameHeader(
            frameId = 1,
            chunkIndex = 0,
            chunkTotal = 1,
            codec = VideoCodec.MJPEG,
            vflags = VideoSubFlags.V_KEYFRAME or VideoSubFlags.V_END_OF_FRAME,
            widthPx = 10,
            heightPx = 10,
            quality = 50,
            frameBytes = 0,
        ).encode()
        val frame = Frame.of(FrameType.VIDEO, subHeaderOnly, flags = FrameFlags.Video.FIRST_CHUNK, seq = 1)

        val result = VideoFrameAssembler().onChunk(frame, nowMs = 0)
        assertTrue(result is AssemblyResult.Rejected)
        assertEquals(DropReason.CHUNK_SIZE_OUT_OF_RANGE, (result as AssemblyResult.Rejected).reason)
    }

    @Test
    fun `detects a short frame by comparing against frame_bytes`() {
        val chunked = VideoFrameChunker(244).chunk(
            frameId = 3,
            codedData = media(500),
            codec = VideoCodec.MJPEG,
            widthPx = 10,
            heightPx = 10,
            quality = 50,
            seq = 6,
            isKeyframe = true,
        )
        val assembler = VideoFrameAssembler(maxChunkBytes = 244)
        assembler.onChunk(chunked.frames[0], nowMs = 0)
        assembler.onChunk(chunked.frames[1], nowMs = 1)

        // Corrupt the final chunk by removing media bytes while keeping the declared frame size.
        val last = chunked.frames[2]
        val truncated = Frame.of(
            FrameType.VIDEO,
            last.payload.copyOfRange(0, last.payload.size - 20),
            flags = last.flags,
            seq = last.seq,
        )
        val result = assembler.onChunk(truncated, nowMs = 2)
        assertTrue(result is AssemblyResult.Rejected)
        assertEquals(DropReason.LENGTH_MISMATCH, (result as AssemblyResult.Rejected).reason)
    }

    @Test
    fun `abandons a stale frame`() {
        val chunked = VideoFrameChunker(244).chunk(
            frameId = 4,
            codedData = media(500),
            codec = VideoCodec.MJPEG,
            widthPx = 10,
            heightPx = 10,
            quality = 50,
            seq = 7,
            isKeyframe = true,
        )
        val assembler = VideoFrameAssembler(maxChunkBytes = 244, staleTimeoutMs = 100)
        assembler.onChunk(chunked.frames[0], nowMs = 0)
        val stale = assembler.resetStale(nowMs = 500)
        assertEquals(DropReason.STALE, stale?.reason)
        assertTrue(!assembler.isAssembling)
    }

    @Test
    fun `rejects frames over the reassembly budget`() {
        val chunked = VideoFrameChunker(244).chunk(
            frameId = 5,
            codedData = media(2000),
            codec = VideoCodec.MJPEG,
            widthPx = 10,
            heightPx = 10,
            quality = 50,
            seq = 8,
            isKeyframe = true,
        )
        val assembler = VideoFrameAssembler(maxFrameBytes = 1024, maxChunkBytes = 244)
        val result = assembler.onChunk(chunked.frames[0], nowMs = 0)
        assertTrue(result is AssemblyResult.Rejected)
        assertEquals(DropReason.FRAME_TOO_LARGE, (result as AssemblyResult.Rejected).reason)
    }

    @Test
    fun `chunk size floor is enforced by the chunker`() {
        try {
            VideoFrameChunker(ProtocolLimits.MIN_MAX_CHUNK_BYTES - 1)
            throw AssertionError("a chunk budget below 84 bytes must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("maxChunkBytes"))
        }
    }
}
