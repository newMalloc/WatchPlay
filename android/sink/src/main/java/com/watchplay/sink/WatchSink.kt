package com.watchplay.sink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.watchplay.sdk.protocol.AssemblyResult
import com.watchplay.sdk.protocol.AssembledVideoFrame
import com.watchplay.sdk.protocol.AudioCaps
import com.watchplay.sdk.protocol.Capabilities
import com.watchplay.sdk.protocol.CapsAck
import com.watchplay.sdk.protocol.CapsAckCodec
import com.watchplay.sdk.protocol.CapsCodec
import com.watchplay.sdk.protocol.CapsResult
import com.watchplay.sdk.protocol.ColorFormat
import com.watchplay.sdk.protocol.DeviceInfo
import com.watchplay.sdk.protocol.DisplayCaps
import com.watchplay.sdk.protocol.Feedback
import com.watchplay.sdk.protocol.FeedbackFlags
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameType
import com.watchplay.sdk.protocol.Hello
import com.watchplay.sdk.protocol.HelloAck
import com.watchplay.sdk.protocol.HelloAckStatus
import com.watchplay.sdk.protocol.HelloFlags
import com.watchplay.sdk.protocol.InputCaps
import com.watchplay.sdk.protocol.LastFrameStatus
import com.watchplay.sdk.protocol.MemoryCaps
import com.watchplay.sdk.protocol.MjpegCaps
import com.watchplay.sdk.protocol.PingPayload
import com.watchplay.sdk.protocol.PowerCaps
import com.watchplay.sdk.protocol.ProtocolLimits
import com.watchplay.sdk.protocol.ProtocolVersion
import com.watchplay.sdk.protocol.ScreenShape
import com.watchplay.sdk.protocol.SensorsCaps
import com.watchplay.sdk.protocol.TransportCaps
import com.watchplay.sdk.protocol.VideoCodec
import com.watchplay.sdk.protocol.VideoFrameAssembler
import com.watchplay.sdk.touch.TouchAction
import com.watchplay.sdk.touch.TouchEvent
import com.watchplay.sdk.transport.SendResult
import com.watchplay.sdk.transport.TransportListener
import com.watchplay.sdk.transport.TransportModule
import com.watchplay.sdk.transport.TransportState
import com.watchplay.sdk.util.MonotonicClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Sink half of the protocol on Android: the same transcript `DemoWatchStub` plays over loopback,
 * but over a real radio, and with the one step the stub skips — **decoding**.
 *
 * ```
 *   READY            --HELLO------------------------------------>  phone
 *   HELLO_ACK        <-------------------------------------------
 *   CAPS             --(TLVs: device, display, MJPEG, input)----->  phone
 *   CAPS_ACK         <-----------(codec, size, fps, chunk, credits)
 *   FEEDBACK(credits)--(the only way the window opens)----------->  phone
 *   VIDEO chunks     <-------------------------------------------
 *   FEEDBACK(credits)--(after each decode, §4.1)----------------->  phone
 *   TOUCH            --(14 bytes, watch pixels)----------------->  phone
 * ```
 *
 * ## Where it is deliberately simple
 *
 *  * JPEG only. `BitmapFactory` cannot decode H.264, so Profile B gets an explicit message instead of
 *    a blank frame (`04-video.md` §5: a software-decoding sink must not be selected for B).
 *  * Credit is re-granted in one shot after each decoded frame rather than as a sliding window; a
 *    64-chunk window with one grant per frame is what the spec's simplest compliant sender expects.
 *  * Touch is single-pointer and reported raw: no long-press or swipe synthesis
 *    (`05-input.md` §1.1 says the phone's WebView does gesture recognition).
 *
 * @property transport the watch-side link; [GattServerTransport] in the app.
 * @property panelSidePx side of the square, round panel this sink claims to have.
 * @property onFrame called on a decoder thread with each decoded frame.
 * @property onStatus called on any thread with a human-readable line for the log pane.
 */
class WatchSink(
    private val transport: TransportModule,
    private val panelSidePx: Int,
    private val onFrame: (Bitmap, Int) -> Unit,
    private val onStatus: (String) -> Unit,
) : TransportListener {

    private val assembler = VideoFrameAssembler()
    private val decoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "watchplay-sink-decode").apply { isDaemon = true }
    }

    private val framesDecoded = AtomicLong(0L)
    private val chunksReceived = AtomicLong(0L)
    private val decodeMsTotal = AtomicLong(0L)

    /**
     * `VIDEO` chunks of completed frames that are waiting for the decode thread.
     *
     * This is `FEEDBACK.queue_depth` in the spec's unit — chunks, not frames (`04-video.md` §4.2) — and
     * it is what makes `04-video.md` §6 row 1 visible to the sender. A sink that always reports 0 tells
     * the phone "I am fine" no matter how far behind it is, which is how a BLE link ends up with a
     * permanent multi-second lag instead of a lower frame rate.
     */
    private val queuedChunks = AtomicInteger(0)

    /** Frames waiting for the serial decode thread; more than one means the sink is behind. */
    private val queuedFrames = AtomicInteger(0)

    private val startedAtMs = AtomicLong(0L)

    @Volatile
    private var txSeq: Int = 0

    @Volatile
    private var negotiated: CapsResult? = null

    @Volatile
    private var windowCredits: Long = DEFAULT_WINDOW

    @Volatile
    private var sessionAccepted: Boolean = false

    @Volatile
    private var lastDropReason: String? = null

    /** Starts listening; the transcript begins as soon as the link is [TransportState.READY]. */
    fun start() {
        startedAtMs.set(MonotonicClock.nowMs())
        transport.open(this)
    }

    /** Closes the link and the decoder. */
    fun stop() {
        transport.close("sink stopped")
        decoder.shutdownNow()
    }

    /** Sends one touch sample in **watch physical pixels**, origin top-left (`05-input.md` §1). */
    fun sendTouch(action: TouchAction, xPx: Int, yPx: Int): Boolean {
        if (!transport.state.isOpen) return false
        val clampedX = xPx.coerceIn(0, panelSidePx - 1)
        val clampedY = yPx.coerceIn(0, panelSidePx - 1)
        val payload = TouchEvent(
            action = action,
            x = clampedX,
            y = clampedY,
            timestampMs = MonotonicClock.nowMs(),
        ).encode()
        val result = transport.send(Frame.of(FrameType.TOUCH, payload, seq = nextSeq()))
        return result is SendResult.Sent
    }

    /** One line per second for the log pane. */
    fun statusLine(): String {
        val elapsed = (MonotonicClock.nowMs() - startedAtMs.get()).coerceAtLeast(1L)
        val frames = framesDecoded.get()
        val fps = frames * 1_000.0 / elapsed
        val decodeAvg = if (frames == 0L) 0L else decodeMsTotal.get() / frames
        return buildString {
            append("link: ").append(transport.state.name)
            append("  mtu: ").append(transport.mtu)
            append("  panel: ").append(panelSidePx).append("px\n")
            append("session: ").append(if (sessionAccepted) "accepted" else "waiting")
            negotiated?.let {
                append("  negotiated: ").append(it.codec.displayName)
                append(' ').append(it.widthPx).append('x').append(it.heightPx)
                append(" @").append(it.targetFps).append("fps q").append(it.quality)
                append(" chunk=").append(it.maxChunkBytes).append(" credits=").append(it.initialCredits)
            }
            append('\n')
            append("frames: ").append(frames).append("  chunks: ").append(chunksReceived.get())
            append("  fps: ").append("%.1f".format(fps))
            append("  decode: ").append(decodeAvg).append("ms avg\n")
            append("credit window: ").append(windowCredits)
            transport.stats.lastError?.let { append("\nlast error: ").append(it) }
            lastDropReason?.let { append("\nlast drop: ").append(it) }
        }
    }

    // ------------------------------------------------------------- TransportListener

    override fun onTransportStateChanged(state: TransportState) {
        onStatus("link -> $state")
        if (state == TransportState.READY) sendHello()
    }

    override fun onTransportError(error: Throwable) {
        onStatus("transport error: ${error.message}")
    }

    override fun onCreditWindowChanged(credits: Int) {
        // The phone never sends VIDEO to the sink, so an inbound window has nothing to gate.
    }

    override fun onFrameReceived(frame: Frame) {
        when (frame.typeCode) {
            FrameType.HELLO_ACK.code -> handleHelloAck(frame)
            FrameType.CAPS_ACK.code -> handleCapsAck(frame)
            FrameType.PING.code -> sendEcho(FrameType.PONG, frame.payload)
            FrameType.VIDEO.code -> handleVideo(frame)
            FrameType.BYE.code -> {
                onStatus("phone said goodbye")
                transport.close("phone sent BYE")
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------- transcript

    private fun sendHello() {
        val hello = Hello(
            protoVersion = ProtocolVersion.CURRENT,
            minVersion = ProtocolVersion.MIN_SUPPORTED,
            flags = HelloFlags.HAS_DISPLAY or HelloFlags.HAS_TOUCH or
                HelloFlags.IS_BATTERY_POWERED,
            nonce = MonotonicClock.nowMs() and 0xFFFFFFFFL,
            deviceId = DEVICE_ID,
            model = "AndroidSink",
            firmwareVersion = "0.1.0",
        )
        send(FrameType.HELLO, hello.encode())
        onStatus("sent HELLO")
    }

    private fun handleHelloAck(frame: Frame) {
        val ack = try {
            HelloAck.decode(frame.payload)
        } catch (error: Exception) {
            onStatus("malformed HELLO_ACK: ${error.message}")
            return
        }
        if (ack.status != HelloAckStatus.OK) {
            onStatus("phone rejected the session: ${HelloAckStatus.describe(ack.status)}")
            return
        }
        sessionAccepted = true
        send(FrameType.CAPS, CapsCodec.encode(capabilities()))
        onStatus("session accepted, sent CAPS")
    }

    private fun handleCapsAck(frame: Frame) {
        val ack: CapsAck = try {
            CapsAckCodec.decode(frame.payload)
        } catch (error: Exception) {
            onStatus("malformed CAPS_ACK: ${error.message}")
            return
        }
        val result = ack.result
        if (result == null || !ack.isOk) {
            onStatus("negotiation refused: ${ack.rejectReason ?: "no reason"}")
            return
        }
        negotiated = result
        windowCredits = result.initialCredits.toLong().coerceAtLeast(1L)
        // §6.1: the sender's window opens only through this explicit absolute grant.
        sendFeedback()
        onStatus(
            "negotiated ${result.codec.displayName} ${result.widthPx}x${result.heightPx} " +
                "@${result.targetFps}fps q=${result.quality} chunk=${result.maxChunkBytes}",
        )
    }

    private fun handleVideo(frame: Frame) {
        chunksReceived.incrementAndGet()
        when (val outcome = assembler.onChunk(frame, MonotonicClock.nowMs())) {
            is AssemblyResult.Complete -> decode(outcome.frame)
            is AssemblyResult.Rejected -> {
                lastDropReason = "${outcome.reason}: ${outcome.detail}"
                onStatus("dropped chunk: $lastDropReason")
            }
            is AssemblyResult.Progress -> Unit
        }
    }

    private fun decode(frame: AssembledVideoFrame) {
        if (frame.codec != VideoCodec.MJPEG) {
            // No H.264 decoder in a BitmapFactory: tell the truth instead of showing nothing.
            onStatus("cannot decode ${frame.codec.displayName} without a hardware decoder")
            return
        }
        queuedChunks.addAndGet(frame.header.chunkTotal)
        queuedFrames.incrementAndGet()
        decoder.execute {
            queuedChunks.addAndGet(-frame.header.chunkTotal)
            queuedFrames.decrementAndGet()
            val startedAt = MonotonicClock.nowMs()
            val bitmap = BitmapFactory.decodeByteArray(frame.codedData, 0, frame.sizeBytes)
            val tookMs = MonotonicClock.nowMs() - startedAt
            if (bitmap == null) {
                onStatus("JPEG decode failed (${frame.sizeBytes} B)")
                lastDropReason = "decode failed"
                sendFeedback(failed = true)
                return@execute
            }
            decodeMsTotal.addAndGet(tookMs)
            val decoded = framesDecoded.incrementAndGet()
            onFrame(bitmap, frame.sizeBytes)
            // §4.1: credit comes back after the frame is decoded, not when it arrives.
            sendFeedback()
            if (decoded % LOG_EVERY_N_FRAMES == 0L) {
                Log.i(TAG, "decoded frame ${frame.frameId} ${frame.sizeBytes}B in ${tookMs}ms")
            }
        }
    }

    private fun sendFeedback(failed: Boolean = false) {
        val decoded = framesDecoded.get()
        val decodeMsAvg = if (decoded == 0L) 0 else (decodeMsTotal.get() / decoded).coerceAtMost(0xFFFF).toInt()
        val feedback = Feedback(
            credits = windowCredits,
            framesDecoded = decoded and 0xFFFFFFFFL,
            framesDropped = assembler.stats.framesRejected and 0xFFFFFFFFL,
            decodeMsAvg = decodeMsAvg,
            queueDepth = queuedChunks.get().coerceIn(0, 0xFFFF),
            lastFrameStatus = if (failed) LastFrameStatus.DECODE_ERROR else LastFrameStatus.OK,
            flags = if (isOverloaded(decodeMsAvg)) FeedbackFlags.OVERLOADED else 0,
            lastFrameId = if (decoded == 0L) {
                Feedback.NO_FRAME_ID
            } else {
                (decoded - 1) and 0xFFFFFFFFL
            },
        )
        send(FrameType.FEEDBACK, feedback.encode())
    }

    /**
     * Whether this sink is behind — `FEEDBACK.OVERLOADED` (`04-video.md` §4.3).
     *
     * Two independent signs: a frame already waits behind the serial decoder (the queue is not draining),
     * or decoding alone eats 80 % of the negotiated frame interval (the same ratio §6 uses on the sender
     * side, so both ends agree on what "too slow" means).
     */
    private fun isOverloaded(decodeMsAvg: Int): Boolean {
        if (queuedFrames.get() > 1) return true
        val frameIntervalMs = 1000.0 / (negotiated?.targetFps ?: DEFAULT_TARGET_FPS).coerceAtLeast(1)
        return decodeMsAvg > OVERLOAD_DECODE_RATIO * frameIntervalMs
    }

    private fun sendEcho(type: FrameType, payload: ByteArray) {
        // PONG echoes the PING payload verbatim (`03-session.md` §6.1).
        val echoed = if (payload.size == PingPayload.SIZE_BYTES) payload.copyOf() else ByteArray(0)
        send(type, echoed)
    }

    private fun send(type: FrameType, payload: ByteArray) {
        transport.send(Frame.of(type, payload, seq = nextSeq()))
    }

    private fun nextSeq(): Int {
        txSeq++
        if (txSeq <= 0) txSeq = 1
        return txSeq
    }

    private fun capabilities(): Capabilities = Capabilities(
        deviceInfo = DeviceInfo(
            protoVersion = ProtocolVersion.CURRENT,
            fwMajor = 0,
            fwMinor = 1,
            fwPatch = 0,
            chipId = DEVICE_ID,
            buildId = 1L,
        ),
        display = DisplayCaps(
            widthPx = panelSidePx,
            heightPx = panelSidePx,
            shape = ScreenShape.ROUND,
            colorFormat = ColorFormat.RGB565_BE,
            // 06-rendering.md §2.4: every element must stay inside the inscribed circle.
            safeInsetPermille = ROUND_SAFE_INSET_PERMILLE,
            refreshHz = 60,
            tearingMode = 1,
            brightnessLevels = 16,
            panelId = 0L,
        ),
        mjpeg = MjpegCaps(subsampling = 2, minQuality = 40, maxQuality = 90, maxFps = SINK_MAX_FPS),
        input = InputCaps(touchPoints = 1, reportRateHz = 60, hasHaptics = false, buttons = 0),
        audio = AudioCaps(codecs = 0, path = AudioCaps.PATH_NONE, volumeLevels = 0),
        memory = MemoryCaps(psramBytes = 0L, freeHeapBytes = 256L * 1024),
        power = PowerCaps(batteryPercent = 100, charging = true, thermalState = 0, powerProfile = 0),
        transport = TransportCaps(
            bearers = TransportCaps.BEARER_GATT,
            preferred = TransportCaps.BEARER_GATT,
            mtuPayload = ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES,
        ),
        sensors = SensorsCaps(accelerometer = false, gyroscope = false, heartRate = false, compass = false),
    )

    override fun toString(): String =
        "WatchSink(state=${transport.state}, frames=${framesDecoded.get()}, " +
            "chunks=${chunksReceived.get()}, negotiated=${negotiated?.codec ?: VideoCodec.MJPEG})"

    companion object {
        private const val TAG = "WatchPlaySink"

        /** How many frames to decode at most; the sink is a software JPEG decoder. */
        const val SINK_MAX_FPS: Int = 10

        /** 140‰ inset for a round panel (`06-rendering.md` §2.4). */
        const val ROUND_SAFE_INSET_PERMILLE: Int = 140

        /** Default credit window: the spec's suggested `initial_credits`. */
        const val DEFAULT_WINDOW: Long = 64L

        /** `max_chunk_bytes` this sink asks for; the phone's negotiator clamps it down if its MTU is smaller. */
        private const val LOG_EVERY_N_FRAMES: Long = 30L

        /** Cadence assumed before `CAPS_ACK` reports one; matches the reference pairing's 10 fps. */
        private const val DEFAULT_TARGET_FPS: Int = 10

        /** Decoding this share of a frame interval counts as overload — the same ratio §6 uses. */
        private const val OVERLOAD_DECODE_RATIO: Double = 0.8

        /** Stable device id; a real watch uses its chip id (`03-session.md` §2.1). */
        private val DEVICE_ID = ByteArray(16) { index -> (0x40 + index).toByte() }
    }
}
