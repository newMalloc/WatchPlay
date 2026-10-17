package com.watchplay.sdk.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin implementation against the bytes the Python reference implementation actually produced.
 *
 * Every other test in this module builds its own frames with `FrameCodec.encode`, so it can only prove
 * the Kotlin codec agrees with itself. These two files are written by `tools/gen_traces.py` and
 * committed, which makes them the only cross-language evidence: if the CRC coverage, a field offset or
 * an endianness changed on either side, this test fails while both suites stay green.
 *
 * The expected values below come from the spec's field tables and the trace `README.md`, never from
 * running the Kotlin encoder first.
 */
class CommittedTracesTest {

    private val tracesDir: File = File(
        System.getProperty("watchplay.traces")
            ?: error("watchplay.traces is not set; see the testOptions block of :sdk/build.gradle.kts"),
    )

    private fun trace(name: String): ByteArray {
        val file = File(tracesDir, name)
        assertTrue("committed trace is missing: $file", file.isFile)
        return file.readBytes()
    }

    private fun frames(name: String): List<Frame> {
        val data = trace(name)
        val parser = FrameParser()
        val parsed = mutableListOf<Frame>()
        // Fed the way a bearer delivers it — one maximum-size write at a time — because the parser is
        // required to bound its buffer (`02-wire-format.md` §7.2); 9455 bytes in a single call would
        // exercise that bound instead of the trace.
        var offset = 0
        while (offset < data.size) {
            val length = minOf(BEARER_BURST_BYTES, data.size - offset)
            parsed += parser.offer(data, offset, length)
            offset += length
        }
        assertEquals("no CRC errors allowed in a committed trace", 0L, parser.headerCrcErrors)
        assertEquals("no resyncs allowed in a committed trace", 0L, parser.resyncCount)
        assertEquals("no overflow drops allowed in a committed trace", 0L, parser.discardedOverflowBytes)
        return parsed
    }

    @Test
    fun `the MJPEG trace is one frame of 37 chunks and 9455 bytes`() {
        val data = trace(MJPEG_TRACE)
        assertEquals(9455, data.size)

        val frames = frames(MJPEG_TRACE)
        assertEquals(37, frames.size)
        assertTrue("all 37 frames are VIDEO", frames.all { it.isType(FrameType.VIDEO) })
        assertTrue("every chunk carries seq 42", frames.all { it.seq == 42 })
    }

    @Test
    fun `the first chunk matches the documented headers byte for byte`() {
        val first = frames(MJPEG_TRACE).first()

        assertEquals(244, first.payload.size)
        assertEquals(FrameFlags.Video.FIRST_CHUNK or FrameFlags.Video.KEYFRAME, first.flags)

        val header = VideoFrameHeader.decodeOrNull(first.payload)
        assertNotNull(header)
        header!!
        assertEquals(7L, header.frameId)
        assertEquals(0, header.chunkIndex)
        assertEquals(37, header.chunkTotal)
        assertEquals(VideoCodec.MJPEG, header.codec)
        assertEquals(466, header.widthPx)
        assertEquals(466, header.heightPx)
        assertEquals(72, header.quality)
        assertEquals(8123L, header.frameBytes)
    }

    @Test
    fun `the last chunk is 79 bytes and marked LAST_CHUNK`() {
        val last = frames(MJPEG_TRACE).last()

        assertEquals(79, last.payload.size)
        assertEquals(FrameFlags.Video.LAST_CHUNK or FrameFlags.Video.KEYFRAME, last.flags)
        assertEquals(36, VideoFrameHeader.decodeOrNull(last.payload)!!.chunkIndex)
    }

    @Test
    fun `the chunks reassemble into one 8123-byte frame`() {
        val chunks = frames(MJPEG_TRACE)
        val assembler = VideoFrameAssembler()
        var assembled: AssembledVideoFrame? = null

        for (chunk in chunks) {
            val outcome = assembler.onChunk(chunk, nowMs = 1_000L)
            if (outcome is AssemblyResult.Complete) assembled = outcome.frame
        }

        val frame = assembled
        assertNotNull("37 chunks must produce exactly one frame", frame)
        frame!!
        assertEquals(8123, frame.sizeBytes)

        // The media is every payload minus its 20-byte video sub-header, concatenated in chunk order.
        // The trace's media bytes are synthetic, so there is no JPEG marker to look for — the ordering
        // and the sub-header size are the conformance statement here.
        val expected = chunks
            .sortedBy { VideoFrameHeader.decodeOrNull(it.payload)!!.chunkIndex }
            .map { it.payload.copyOfRange(VIDEO_SUBHEADER_BYTES, it.payload.size) }
            .reduce { acc, part -> acc + part }
        assertArrayEquals(expected, frame.codedData.copyOf(frame.sizeBytes))
    }

    @Test
    fun `every chunk re-encodes to the committed bytes`() {
        val data = trace(MJPEG_TRACE)
        var offset = 0

        for (frame in frames(MJPEG_TRACE)) {
            val encoded = FrameCodec.encode(frame)
            assertArrayEquals(
                "chunk seq=${frame.seq} does not survive a decode/encode round trip",
                data.copyOfRange(offset, offset + encoded.size),
                encoded,
            )
            offset += encoded.size
        }

        assertEquals("the round trip must consume the file exactly", data.size, offset)
    }

    @Test
    fun `fragmented delivery parses the same chunks`() {
        val data = trace(MJPEG_TRACE)
        val parser = FrameParser()
        val parsed = mutableListOf<Frame>()

        // A BLE bearer hands over arbitrary fragments; 7 bytes is deliberately not a frame boundary.
        var offset = 0
        while (offset < data.size) {
            val length = minOf(7, data.size - offset)
            parsed += parser.offer(data, offset, length)
            offset += length
        }

        assertEquals(37, parsed.size)
        assertEquals(0L, parser.headerCrcErrors)
        assertEquals(0L, parser.resyncCount)
    }

    @Test
    fun `the handshake trace is HELLO, HELLO_ACK, CAPS, CAPS_ACK, PING, PONG`() {
        val data = trace(HANDSHAKE_TRACE)
        assertEquals(229, data.size)

        val frames = frames(HANDSHAKE_TRACE)
        assertEquals(
            listOf(
                FrameType.HELLO,
                FrameType.HELLO_ACK,
                FrameType.CAPS,
                FrameType.CAPS_ACK,
                FrameType.PING,
                FrameType.PONG,
            ),
            frames.map { it.type },
        )
        // Each direction keeps its own seq space, so the watch's HELLO/CAPS/PING are 1, 2, 3 and the
        // phone's HELLO_ACK/CAPS_ACK/PONG walk the same numbers independently.
        assertEquals(listOf(1, 1, 2, 2, 3, 3), frames.map { it.seq })
        assertEquals("the PONG echoes the PING payload verbatim", frames[4].payload.size, frames[5].payload.size)
    }

    private companion object {
        const val MJPEG_TRACE = "mjpeg-frame-466x466-3c.bin"
        const val HANDSHAKE_TRACE = "session-handshake.bin"

        /** One `VIDEO` chunk on the wire: 16-byte header plus a full 244-byte payload. */
        const val BEARER_BURST_BYTES = 16 + 244

        /** The video sub-header that precedes the media data in every `VIDEO` payload. */
        const val VIDEO_SUBHEADER_BYTES = 20
    }
}
