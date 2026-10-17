package com.watchplay.demo

import android.util.Log
import com.watchplay.sdk.protocol.CapsAck
import com.watchplay.sdk.protocol.CapsAckCodec
import com.watchplay.sdk.protocol.CapsCodec
import com.watchplay.sdk.protocol.CapsResult
import com.watchplay.sdk.protocol.Capabilities
import com.watchplay.sdk.protocol.Feedback
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameType
import com.watchplay.sdk.protocol.Hello
import com.watchplay.sdk.protocol.HelloAck
import com.watchplay.sdk.protocol.HelloAckStatus
import com.watchplay.sdk.protocol.HelloFlags
import com.watchplay.sdk.protocol.LastFrameStatus
import com.watchplay.sdk.protocol.PingPayload
import com.watchplay.sdk.protocol.ProtocolVersion
import com.watchplay.sdk.protocol.VideoCodec
import com.watchplay.sdk.transport.LoopbackTransport
import com.watchplay.sdk.transport.TransportListener
import com.watchplay.sdk.transport.TransportState
import com.watchplay.sdk.protocol.AssembledVideoFrame
import com.watchplay.sdk.protocol.AssemblyResult
import com.watchplay.sdk.protocol.VideoFrameAssembler
import com.watchplay.sdk.util.MonotonicClock

/**
 * A **scripted watch** for the demo: it plays the Sink side of the protocol over a loopback link.
 *
 * Without it the demo could not do anything honest: `03-session.md` §1.2 makes the watch the initiator
 * (`HELLO`, then `CAPS`), and a phone that is never asked to handshake would sit in `HANDSHAKING` for
 * three seconds and then time out. Rather than fake the session, this class speaks the real protocol:
 *
 * ```
 *   HELLO            ->  HELLO_ACK(OK, peer_nonce)          then CAPS
 *   CAPS_ACK         ->  decode RESULT, set the phone's credit window via FEEDBACK(credits)
 *   VIDEO chunks     ->  reassemble with VideoFrameAssembler, then grant credit back
 *   PING             ->  PONG with the payload echoed byte for byte
 *   BYE              ->  close
 * ```
 *
 * It uses the same `:sdk` codecs the phone uses, so if the two ever disagree the demo stops working —
 * which is exactly the property the demo is meant to demonstrate.
 *
 * This is test scaffolding, not a watch firmware reference: it decodes nothing, keeps no display, and
 * grants credit immediately instead of after a successful decode (the real rule in `04-video.md` §4.1
 * is to grant after decoding).
 *
 * @property transport the watch-side endpoint of a [LoopbackTransport.pair].
 * @property capabilities what this "watch" claims; defaults to the reference hardware.
 */
class DemoWatchStub(
    private val transport: LoopbackTransport,
    private val capabilities: Capabilities = Capabilities.referenceWatch(),
) : TransportListener {

    private val assembler = VideoFrameAssembler()
    private var txSeq = 0

    /** Frames completely reassembled off the link. */
    var framesDecoded: Long = 0L
        private set

    /** `VIDEO` chunks accepted. */
    var chunksReceived: Long = 0L
        private set

    /** Chunks or frames the assembler rejected, with the reason. */
    var lastDropReason: String? = null
        private set

    /** Size of the most recently reassembled frame, in bytes. */
    var lastFrameBytes: Int = 0
        private set

    /** `true` once the phone's `HELLO_ACK` was accepted. */
    var sessionAccepted: Boolean = false
        private set

    /** Negotiated parameters the phone sent in `CAPS_ACK`. */
    var negotiated: CapsResult? = null
        private set

    /** Credit window this stub last granted the phone. */
    var lastGrantedCredits: Int = 0
        private set

    /** Opens the watch-side endpoint; it starts the handshake as soon as the link is up. */
    fun start() {
        transport.open(this)
    }

    /** Closes the watch-side endpoint. */
    fun stop() {
        transport.close("demo finished")
    }

    override fun onTransportStateChanged(state: TransportState) {
        if (state == TransportState.READY) sendHello()
        if (state == TransportState.CLOSED) Log.i(TAG, "watch stub: link closed")
    }

    override fun onTransportError(error: Throwable) {
        Log.w(TAG, "watch stub: transport error", error)
    }

    override fun onCreditWindowChanged(credits: Int) {
        // The phone does not grant the watch credit in this demo; nothing to do.
    }

    override fun onFrameReceived(frame: Frame) {
        when (frame.typeCode) {
            FrameType.HELLO_ACK.code -> handleHelloAck(frame)
            FrameType.CAPS_ACK.code -> handleCapsAck(frame)
            FrameType.PING.code -> sendEcho(FrameType.PONG, frame.payload)
            FrameType.VIDEO.code -> handleVideo(frame)
            FrameType.BYE.code -> transport.close("phone said goodbye")
            else -> Unit
        }
    }

    private fun sendHello() {
        val hello = Hello(
            protoVersion = ProtocolVersion.CURRENT,
            minVersion = ProtocolVersion.MIN_SUPPORTED,
            flags = HelloFlags.HAS_DISPLAY or HelloFlags.HAS_TOUCH or HelloFlags.HAS_PSRAM or
                HelloFlags.IS_BATTERY_POWERED,
            nonce = (MonotonicClock.nowMs() and 0xFFFFFFFFL),
            deviceId = ByteArray(16) { index -> (index + 1).toByte() },
            model = "T-Disp-S3",
            firmwareVersion = "0.1.0",
        )
        transport.send(Frame.of(FrameType.HELLO, hello.encode(), seq = nextSeq()))
        Log.i(TAG, "watch stub: sent HELLO (nonce=${hello.nonce})")
    }

    private fun handleHelloAck(frame: Frame) {
        val ack = try {
            HelloAck.decode(frame.payload)
        } catch (error: Exception) {
            Log.w(TAG, "watch stub: malformed HELLO_ACK", error)
            return
        }
        if (ack.status != HelloAckStatus.OK) {
            Log.w(TAG, "watch stub: phone rejected the session: ${HelloAckStatus.describe(ack.status)}")
            return
        }
        sessionAccepted = true
        Log.i(TAG, "watch stub: session accepted, sending CAPS")
        transport.send(Frame.of(FrameType.CAPS, CapsCodec.encode(capabilities), seq = nextSeq()))
    }

    private fun handleCapsAck(frame: Frame) {
        val ack: CapsAck = try {
            CapsAckCodec.decode(frame.payload)
        } catch (error: Exception) {
            Log.w(TAG, "watch stub: malformed CAPS_ACK", error)
            return
        }
        val result = ack.result
        if (result == null || !ack.isOk) {
            Log.w(TAG, "watch stub: negotiation refused: ${ack.rejectReason ?: "no reason"}")
            return
        }
        negotiated = result
        Log.i(
            TAG,
            "watch stub: negotiated ${result.codec.displayName} ${result.widthPx}x${result.heightPx} " +
                "@${result.targetFps}fps q=${result.quality} chunk=${result.maxChunkBytes}",
        )
        // `01-transport.md` §6.1: the initial window comes only from this explicit grant.
        lastGrantedCredits = initialWindow(result)
        transport.send(
            Frame.of(FrameType.FEEDBACK, Feedback(credits = lastGrantedCredits.toLong()).encode(), seq = nextSeq()),
        )
    }

    private fun handleVideo(frame: Frame) {
        chunksReceived++
        when (val outcome = assembler.onChunk(frame, MonotonicClock.nowMs())) {
            is AssemblyResult.Complete -> {
                framesDecoded++
                logFrame(outcome.frame)
                // Credit is granted after a frame is consumed, which is what a real watch does after
                // decoding (`04-video.md` §4.1, §8.1).
                lastGrantedCredits = windowFor(negotiated)
                transport.send(
                    Frame.of(FrameType.FEEDBACK, feedbackFor(lastGrantedCredits.toLong()).encode(), seq = nextSeq()),
                )
            }

            is AssemblyResult.Rejected -> {
                lastDropReason = "${outcome.reason}: ${outcome.detail}"
                Log.w(TAG, "watch stub: dropped frame chunk: $lastDropReason")
            }

            is AssemblyResult.Progress -> Unit
        }
    }

    /**
     * Initial window this scripted watch grants.
     *
     * `04-video.md` §4.1 defaults `initial_credits` to 64, but 1 credit is 1 chunk and a 466×466 JPEG at
     * quality 70 is 47–76 chunks once the 20-byte subheader is subtracted. A 64-chunk window therefore
     * cannot hold one frame: the phone runs out mid-frame, drops it, and the next frame cannot start
     * either — the stream stalls with a permanently empty window. A real receiver grants what its
     * reassembly buffer can hold, and nudges again when credit falls below a quarter (§4.4).
     */
    private fun initialWindow(result: CapsResult): Int =
        maxOf(result.initialCredits, MID_FRAME_HEADROOM_CHUNKS)

    /** Window granted after each decoded frame; the same headroom applies. */
    private fun windowFor(negotiated: CapsResult?): Int =
        maxOf(negotiated?.initialCredits ?: 0, MID_FRAME_HEADROOM_CHUNKS)

    private fun feedbackFor(window: Long): Feedback = Feedback(
        credits = window,
        framesDecoded = framesDecoded and 0xFFFFFFFFL,
        framesDropped = assembler.stats.framesRejected and 0xFFFFFFFFL,
        decodeMsAvg = 0,
        queueDepth = 0,
        lastFrameStatus = LastFrameStatus.OK,
        lastFrameId = if (framesDecoded == 0L) Feedback.NO_FRAME_ID else (framesDecoded - 1) and 0xFFFFFFFFL,
    )

    private fun logFrame(frame: AssembledVideoFrame) {
        lastFrameBytes = frame.sizeBytes
        if (framesDecoded % LOG_EVERY_N_FRAMES == 0L) {
            Log.i(
                TAG,
                "watch stub: frame ${frame.frameId} ${frame.codec.displayName} ${frame.sizeBytes}B " +
                    "(keyframe=${frame.isKeyframe}), decoded=$framesDecoded chunks=$chunksReceived",
            )
        }
    }

    private fun sendEcho(type: FrameType, payload: ByteArray) {
        // PONG must echo the PING payload verbatim (`03-session.md` §6.1).
        val echoed = if (payload.size == PingPayload.SIZE_BYTES) payload.copyOf() else ByteArray(0)
        transport.send(Frame.of(type, echoed, seq = nextSeq()))
    }

    private fun nextSeq(): Int {
        txSeq++
        if (txSeq <= 0) txSeq = 1
        return txSeq
    }

    override fun toString(): String =
        "DemoWatchStub(accepted=$sessionAccepted, frames=$framesDecoded, chunks=$chunksReceived, " +
            "lastFrame=${lastFrameBytes}B, negotiated=${negotiated?.codec ?: VideoCodec.MJPEG})"

    companion object {
        private const val TAG = "WatchPlayDemoWatch"

        /** Log a line every n-th frame so the logcat stays readable at 10 fps. */
        private const val LOG_EVERY_N_FRAMES: Long = 10L

        /** Chunk window large enough for one 466×466 q70 frame plus a second one in flight. */
        private const val MID_FRAME_HEADROOM_CHUNKS: Int = 256
    }
}
