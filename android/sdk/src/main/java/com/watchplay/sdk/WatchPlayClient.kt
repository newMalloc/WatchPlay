package com.watchplay.sdk

import com.watchplay.sdk.apps.MirrorApp
import com.watchplay.sdk.capture.CaptureModule
import com.watchplay.sdk.capture.GlTextureCapture
import com.watchplay.sdk.encoder.EncoderModule
import com.watchplay.sdk.encoder.MjpegEncoder
import com.watchplay.sdk.pipeline.MirrorPipeline
import com.watchplay.sdk.pipeline.PipelineConfig
import com.watchplay.sdk.pipeline.PipelineFrame
import com.watchplay.sdk.pipeline.PipelineStats
import com.watchplay.sdk.protocol.CapsStream
import com.watchplay.sdk.protocol.ControlCommand
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameType
import com.watchplay.sdk.protocol.Feedback
import com.watchplay.sdk.protocol.LittleEndian
import com.watchplay.sdk.render.SceneUpdate
import com.watchplay.sdk.render.UIRenderModule
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.session.NegotiationOutcome
import com.watchplay.sdk.session.SessionConfig
import com.watchplay.sdk.session.SessionManager
import com.watchplay.sdk.session.SessionState
import com.watchplay.sdk.touch.MappedTouch
import com.watchplay.sdk.touch.TouchEvent
import com.watchplay.sdk.touch.TouchHandler
import com.watchplay.sdk.touch.TouchOutcome
import com.watchplay.sdk.transport.TransportModule
import com.watchplay.sdk.util.MonotonicClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The single public entry point of the WatchPlay SDK.
 *
 * ```
 *   val client = WatchPlayClient.builder(LoopbackTransport())
 *       .captureModule(PixelCopyCapture(...))
 *       .listener(myListener)
 *       .build()
 *   client.setApp(MusicPlayerApp())
 *   client.connect()   // handshake + negotiation, driven by the watch
 *   client.start()     // capture -> encode -> send
 *   ...
 *   client.stop()
 * ```
 *
 * ## What it owns
 *
 *  * a [SessionManager] (handshake, negotiation, heartbeats, credit),
 *  * a [MirrorPipeline] (capture → encode → chunk → send),
 *  * a [TouchHandler] (watch pixels → CSS pixels, with the round-screen rules), and
 *  * a control thread that ticks the session and polls the active [MirrorApp] for updates.
 *
 * The client is the only place that knows how those four fit together, which is why apps get a narrow
 * [MirrorApp.Host] instead of the pipeline.
 *
 * ## Lifecycle
 *
 * `connect()` may be called again after a failure, but a reconnect always performs a **full** handshake:
 * `03-session.md` §8 forbids reusing the previous session's parameters, and the SDK enforces that by
 * clearing the negotiated result on every [SessionManager.open].
 *
 * ## Honest limitations of this skeleton
 *
 *  * Video only starts once the watch has sent `CAPS` **and** granted credit through `FEEDBACK`; with
 *    [GlTextureCapture] (a stub) no frames are produced at all, so the pipeline reports them as skipped.
 *  * `DISCONTINUITY`, drop accounting and the `04-video.md` §6 quality/frame-rate loop are implemented;
 *    the loop is a heuristic (§6 says SHOULD), not a bandwidth model.
 *  * Reconnect backoff (`03-session.md` §8) is left to the host app.
 *
 * @property config resolved dependencies; see [Builder].
 */
class WatchPlayClient private constructor(
    private val config: ClientConfig,
) : SessionManager.Listener, MirrorApp.Host {

    /** Observers of client-level events. */
    interface Listener {

        /** The session state changed; [state] is the new value. */
        fun onClientStateChanged(state: SessionState)

        /** Fresh pipeline statistics, delivered on the control tick (about once per second). */
        fun onStats(stats: PipelineStats)

        /** Something went wrong; the client keeps running unless the state became `FAILED`. */
        fun onClientError(error: Throwable)
    }

    private val transport: TransportModule = config.transport
    private val session = SessionManager(transport, config.sessionConfig)
    private val pipeline = MirrorPipeline(config.capture, config.encoder, transport, config.pipelineConfig)
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var touchHandler: TouchHandler = TouchHandler(WatchLayoutSpec())
    private var app: MirrorApp? = null
    private var controlThread: Thread? = null

    @Volatile
    private var running = false

    /** Session state, mirrored from [SessionManager]. */
    val state: SessionState
        get() = session.state

    /** Panel geometry; updated from the watch's `CAPS` during negotiation. */
    override val layout: WatchLayoutSpec
        get() = session.layout

    /** The active app, or `null` before [setApp]. */
    val currentApp: MirrorApp?
        get() = app

    /** Negotiated parameters once negotiation succeeded, else `null`. */
    val negotiated: com.watchplay.sdk.protocol.CapsResult?
        get() = session.negotiated

    init {
        session.addListener(this)
        config.listener?.let { listeners += it }
        pipeline.seqProvider = { session.allocateSeq() }
        pipeline.frameObserver = config.frameObserver
    }

    /** Starts the handshake. The watch initiates `HELLO`, so this only opens the bearer. */
    fun connect() {
        session.open()
    }

    /**
     * Sets the active watch-screen app.
     *
     * Safe to call before or after [connect]; the scene is loaded as soon as the panel geometry is known
     * (immediately if a session is already negotiated, otherwise on `CAPS_ACK`).
     */
    fun setApp(app: MirrorApp) {
        this.app?.onStop()
        this.app = app
        app.onStart(this)
        loadSceneIfPossible()
    }

    /** Starts the pipeline thread and the control tick. */
    fun start() {
        if (running) return
        running = true
        pipeline.start()
        val thread = Thread({
            while (running) {
                try {
                    session.tick()
                    session.reportDrainIfNeeded()
                    pollApp()
                    publishStats()
                    Thread.sleep(CONTROL_TICK_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (error: RuntimeException) {
                    notifyListeners { it.onClientError(error) }
                }
            }
        }, "watchplay-control")
        thread.isDaemon = true
        controlThread = thread
        thread.start()
    }

    /** Stops streaming and closes the session with `BYE(USER_REQUEST)`. */
    fun stop() {
        running = false
        controlThread?.interrupt()
        controlThread = null
        pipeline.stop()
        app?.onStop()
        session.close()
    }

    /** Current pipeline statistics. */
    fun stats(): PipelineStats = pipeline.stats()

    /** Registers a listener. */
    fun addListener(listener: Listener) {
        listeners += listener
    }

    /** Unregisters a listener. */
    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Accesses the session, for advanced host integration (reconnect policy, RTT, diagnostics). */
    fun sessionManager(): SessionManager = session

    // ---------------------------------------------------------------- MirrorApp.Host

    override fun postUpdate(update: SceneUpdate) {
        config.render?.applyUpdate(update)
    }

    override fun requestFrame() {
        config.render?.requestFrame()
    }

    override fun sendControl(opcode: Int, vararg parameters: Int) {
        if (!transport.state.isOpen) return
        session.send(
            Frame.of(
                FrameType.CONTROL,
                ControlCommand.payload(opcode, *parameters),
                seq = session.allocateSeq(),
            ),
        )
    }

    // ---------------------------------------------------------------- SessionManager.Listener

    override fun onSessionStateChanged(from: SessionState, to: SessionState) {
        // `VIDEO` may only flow in STREAMING (`03-session.md` §1.1); DRAINING is backpressure.
        pipeline.streamingEnabled = to == SessionState.STREAMING
        notifyListeners { it.onClientStateChanged(to) }
    }

    override fun onNegotiated(outcome: NegotiationOutcome) {
        val result = outcome.result ?: return
        val stream: CapsStream? = outcome.ack.stream
        val maxFrameBytes = stream?.maxFrameBytes ?: config.sessionConfig.maxFrameBytes
        pipeline.configure(result, maxFrameBytes, result.initialCredits.toLong())
        touchHandler = TouchHandler(
            layout = session.layout,
            cssViewportWidthPx = result.widthPx.toFloat(),
            cssViewportHeightPx = result.heightPx.toFloat(),
        )
        loadSceneIfPossible()
    }

    override fun onSessionFailed(reason: Int, message: String) {
        pipeline.streamingEnabled = false
        notifyListeners { it.onClientError(IllegalStateException("session failed ($reason): $message")) }
    }

    override fun onTouchReceived(event: TouchEvent) {
        val outcome = touchHandler.handle(event)
        if (outcome !is TouchOutcome.Dispatch) return
        val touch = outcome.touch
        val handledByApp = app?.onTouch(touch) ?: false
        if (!handledByApp) config.render?.dispatchTouch(touch)
    }

    override fun onControlReceived(opcode: Int, payload: ByteArray) {
        when (opcode) {
            // The watch's decoder wants a fresh random-access point (`03-session.md` §5).
            ControlCommand.REQUEST_KEYFRAME -> config.encoder.requestKeyframe()
            ControlCommand.PAUSE -> pipeline.streamingEnabled = false
            ControlCommand.RESUME ->
                if (session.state == SessionState.STREAMING) pipeline.streamingEnabled = true

            // `03-session.md` §5.3: `0` hands the dimension back to the §6 loop.
            ControlCommand.SET_QUALITY -> pipeline.setPinnedQuality(controlParameter(payload))
            ControlCommand.SET_FPS -> pipeline.setPinnedFps(controlParameter(payload))

            else -> Unit
        }
    }

    /** First `u8` parameter of a `CONTROL` payload; `0` when the watch omitted it. */
    private fun controlParameter(payload: ByteArray): Int =
        if (payload.size >= 2) LittleEndian.readU8(payload, 1) else 0

    override fun onFeedbackReceived(feedback: Feedback) {
        if (feedback.requestsKeyframe) config.encoder.requestKeyframe()
        // `04-video.md` §6: the loop lives in the pipeline so quality and cadence change together.
        pipeline.onFeedback(feedback, session.roundTripTimeMs.toLong())
    }

    override fun onDrainTimeout(drainingMs: Long) {
        notifyListeners {
            it.onClientError(IllegalStateException("draining for ${drainingMs}ms; consider downshifting quality"))
        }
    }

    override fun onUnknownFrame(frame: Frame) {
        // Unknown types are skipped and counted, never fatal (`02-wire-format.md` §3).
    }

    // ---------------------------------------------------------------- internals

    private fun loadSceneIfPossible() {
        val render = config.render ?: return
        val activeApp = app ?: return
        render.loadScene(activeApp.createScene(session.layout), session.layout)
    }

    private fun pollApp() {
        val activeApp = app ?: return
        val update = activeApp.pollUpdate(MonotonicClock.nowMs()) ?: return
        // The scene the update targets must exist; loading is deferred until geometry is known.
        if (config.render?.currentScene?.id != update.sceneId) {
            loadSceneIfPossible()
            return
        }
        config.render?.applyUpdate(update)
    }

    private fun publishStats() {
        val snapshot = pipeline.stats()
        notifyListeners { it.onStats(snapshot) }
    }

    private fun notifyListeners(action: (Listener) -> Unit) {
        for (listener in listeners) {
            try {
                action(listener)
            } catch (error: RuntimeException) {
                // A listener must never break the control loop.
                continue
            }
        }
    }

    /** Resolved dependencies of a [WatchPlayClient]. */
    internal data class ClientConfig(
        val transport: TransportModule,
        val sessionConfig: SessionConfig,
        val pipelineConfig: PipelineConfig,
        val capture: CaptureModule,
        val encoder: EncoderModule,
        val render: UIRenderModule?,
        val listener: Listener?,
        val frameObserver: ((PipelineFrame) -> Unit)?,
    )

    /**
     * Builds a [WatchPlayClient].
     *
     * Only the transport is mandatory; everything else has an honest default:
     *
     *  * capture: [GlTextureCapture], which is still a stub and produces no frames,
     *  * encoder: [MjpegEncoder], which is fully working on the CPU path,
     *  * render: none, which means no WebView is driven by the SDK.
     *
     * @property transport bearer to run the session over. Use a loopback pair for tests and the demo;
     *   [com.watchplay.sdk.transport.BleGattTransport] for real hardware.
     */
    class Builder(private val transport: TransportModule) {

        private var sessionConfig: SessionConfig = SessionConfig()
        private var pipelineConfig: PipelineConfig = PipelineConfig()
        private var capture: CaptureModule = GlTextureCapture()
        private var encoder: EncoderModule = MjpegEncoder()
        private var render: UIRenderModule? = null
        private var listener: Listener? = null
        private var frameObserver: ((PipelineFrame) -> Unit)? = null

        /** Overrides the phone-side session tunables. */
        fun sessionConfig(config: SessionConfig): Builder = apply { this.sessionConfig = config }

        /** Overrides the pipeline tunables (profile, frame rate, queue depth). */
        fun pipelineConfig(config: PipelineConfig): Builder = apply { this.pipelineConfig = config }

        /** Supplies the capture implementation; required for real pixels. */
        fun captureModule(module: CaptureModule): Builder = apply { this.capture = module }

        /** Supplies the encoder implementation. */
        fun encoderModule(module: EncoderModule): Builder = apply { this.encoder = module }

        /** Supplies the UI render module, which owns the WebView. */
        fun uiRenderModule(module: UIRenderModule): Builder = apply { this.render = module }

        /** Registers the first listener. */
        fun listener(listener: Listener): Builder = apply { this.listener = listener }

        /**
         * Observes the frames that leave the encoder, exactly as the watch will receive them.
         *
         * For diagnostics: callbacks arrive on the pipeline thread, so decode or display elsewhere.
         */
        fun frameObserver(observer: (PipelineFrame) -> Unit): Builder = apply { this.frameObserver = observer }

        /** Builds the client. Does not connect. */
        fun build(): WatchPlayClient = WatchPlayClient(
            ClientConfig(
                transport = transport,
                sessionConfig = sessionConfig,
                pipelineConfig = pipelineConfig,
                capture = capture,
                encoder = encoder,
                render = render,
                listener = listener,
                frameObserver = frameObserver,
            ),
        )
    }

    companion object {
        /** Control-tick period: session timeouts, heartbeats and stats publication. */
        const val CONTROL_TICK_MS: Long = 250L

        /** Starts building a client around [transport]. */
        fun builder(transport: TransportModule): Builder = Builder(transport)
    }
}
