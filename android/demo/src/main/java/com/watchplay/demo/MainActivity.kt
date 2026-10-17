package com.watchplay.demo

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.watchplay.sdk.WatchPlayClient
import com.watchplay.sdk.capture.PixelCopyCapture
import com.watchplay.sdk.encoder.EncodeProfile
import com.watchplay.sdk.encoder.MjpegEncoder
import com.watchplay.sdk.pipeline.PipelineConfig
import com.watchplay.sdk.pipeline.PipelineFrame
import com.watchplay.sdk.pipeline.PipelineStats
import com.watchplay.sdk.protocol.Capabilities
import com.watchplay.sdk.protocol.ScreenShape
import com.watchplay.sdk.render.UIRenderModule
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.render.WatchScreenShape
import com.watchplay.sdk.render.WebViewRenderModule
import com.watchplay.sdk.session.SessionConfig
import com.watchplay.sdk.session.SessionState
import com.watchplay.sdk.transport.BleGattTransport
import com.watchplay.sdk.transport.LoopbackTransport
import com.watchplay.sdk.transport.TransportModule
import java.io.IOException
import java.util.concurrent.Executors

/**
 * WatchPlay demo: show a watch-sized WebView scene, then mirror it through the real SDK pipeline.
 *
 * ## What this demonstrates
 *
 * ```
 *   assets/demo-scene.html  -->  :sdk WebViewRenderModule  -->  WebView (densityDpi = 160)
 *            (examples/watch-ui/demo-scene.html)                     |
 *                                                                    v
 *   PixelCopyCapture --> MjpegEncoder --> VideoFrameChunker --> LoopbackTransport
 *                                                                  |
 *                                                       DemoWatchStub (scripted Sink)
 * ```
 *
 * A full handshake really happens: the stub sends `HELLO` and `CAPS`, the SDK negotiates, the stub grants
 * credit with `FEEDBACK`, and only then do `VIDEO` chunks flow. The stats pane shows the numbers the
 * pipeline actually produced — real JPEG sizes, real chunk counts, real credit consumption.
 *
 * The scene itself is the one from `examples/watch-ui/` — the same file the browser preview loads — and
 * it is loaded through [WebViewRenderModule], so the safe-area injection of `06-rendering.md` §2.3 R1 and
 * the native-`MotionEvent` touch path of `05-input.md` §2.4 are the ones actually exercised.
 *
 * ## What it deliberately does not do
 *
 *  * No Bluetooth unless you press *Connect to watch*: the default link is in-memory, so the demo runs
 *    anywhere and needs no permissions.
 *  * No `GlTextureCapture`: that path is still a stub, so the demo uses the documented CPU fallback.
 *    Note that `PixelCopy` copies what the window composited, and some devices return **black** for
 *    WebView content because it is composited in a separate surface. The stats stay honest either way;
 *    the picture is the only thing that may be blank.
 *  * No touch when the link is the scripted stub: there is no panel to touch. Tapping the phone's own
 *    WebView does work, and the `:sink` link does round-trip real `TOUCH` frames.
 */
class MainActivity :
    AppCompatActivity(),
    WatchPlayClient.Listener,
    UIRenderModule.UIRenderListener {

    private lateinit var sceneView: WebView
    private lateinit var render: WebViewRenderModule
    private lateinit var sceneApp: DemoSceneApp
    private lateinit var music: PhoneMusic

    /**
     * The panel being mirrored. Fixed for the whole activity: the SDK's render module refuses a layout
     * change without a new module, which means a scene reload.
     */
    private lateinit var layout: WatchLayoutSpec
    private lateinit var statsView: TextView
    private lateinit var toggleButton: TextView
    private lateinit var watchButton: TextView
    private lateinit var frameButton: TextView
    private lateinit var musicButton: TextView
    private lateinit var detailsToggle: TextView
    private lateinit var previewView: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var previewDetail: TextView
    private lateinit var chipSession: TextView
    private lateinit var chipFps: TextView
    private lateinit var chipLatency: TextView
    private lateinit var chipCredit: TextView

    private var detailsVisible = false

    private var client: WatchPlayClient? = null
    private var watchStub: DemoWatchStub? = null
    private var linkLabel: String = "none"
    private lateinit var scanner: BleWatchScanner

    private val handler = Handler(Looper.getMainLooper())

    /** Decodes the JPEGs that leave the encoder; never on the pipeline thread. */
    private val previewExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "watchplay-preview").apply { isDaemon = true }
    }

    /** Reused decode target, so a 4 fps preview does not allocate a bitmap per frame. */
    private var previewBitmap: Bitmap? = null
    private var lastPreviewAtMs = 0L

    @Volatile
    private var lastFrame: PipelineFrame? = null

    /** Written by the SDK's control thread, read by the UI thread: the visibility must be explicit. */
    @Volatile
    private var lastStats: PipelineStats = PipelineStats()

    @Volatile
    private var lastState: SessionState = SessionState.IDLE
    private var lastError: String? = null
    private var sceneStatus: String = "not loaded"
    private var paintRequests: Int = 0

    private val statsTicker = object : Runnable {
        override fun run() {
            renderStats()
            if (client != null) handler.postDelayed(this, STATS_PERIOD_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statsView = findViewById(R.id.stats)
        toggleButton = findViewById(R.id.toggle)
        watchButton = findViewById(R.id.watch)
        frameButton = findViewById(R.id.frame_toggle)
        musicButton = findViewById(R.id.music_toggle)
        detailsToggle = findViewById(R.id.details_toggle)
        previewView = findViewById(R.id.frame_preview)
        previewLabel = findViewById(R.id.frame_label)
        previewDetail = findViewById(R.id.frame_detail)
        chipSession = findViewById(R.id.chip_session)
        chipFps = findViewById(R.id.chip_fps)
        chipLatency = findViewById(R.id.chip_latency)
        chipCredit = findViewById(R.id.chip_credit)
        scanner = BleWatchScanner(this)
        music = PhoneMusic(this)

        sceneView = createSceneWebView()
        layout = panelFrom(intent)
        // The picture itself is a rectangle inside a padded card: without its own outline it keeps four
        // square corners inside whatever shape the card has.
        setPanelOutline(sceneView, layout)
        render = WebViewRenderModule(layout, this)
        render.attach(sceneView)
        // The scene turns a click on its controls into a command; see SceneBridge for why the touch path
        // itself stays native.
        sceneView.addJavascriptInterface(
            SceneBridge { control ->
                handler.post {
                    sceneApp.control(control)
                    syncMusicButton()
                }
            },
            SCENE_BRIDGE,
        )
        val sceneContainer = findViewById<FrameLayout>(R.id.scene_container)
        sceneContainer.addView(
            sceneView,
            // Laid out at the panel's pixel size, so the captured bitmap and the encoded frame are the
            // size the watch negotiated: a 466 px WebView under a density-160 context is a 466 CSS px
            // viewport, and `1vmin` in the scene is then exactly 1% of the panel (06-rendering.md §3.2).
            FrameLayout.LayoutParams(layout.widthPx, layout.heightPx, Gravity.CENTER),
        )
        // The card around the scene is the panel outline, not a rounded rectangle: on a round panel the
        // corners of the square picture do not exist on the watch.
        maskToPanel(
            sceneContainer,
            layout,
            fillColor = ContextCompat.getColor(this, R.color.wp_card),
            strokeWidthPx = (resources.displayMetrics.density).toInt(),
            strokeColor = ContextCompat.getColor(this, R.color.wp_line),
        )
        maskToPanel(previewView, layout, fillColor = ContextCompat.getColor(this, R.color.wp_card_soft))
        // A non-square panel must not be squeezed into a square thumbnail.
        previewView.fitPanelAspect(layout)
        sceneApp = DemoSceneApp(readSceneHtml(), music)
        registerBattery()

        toggleButton.setOnClickListener {
            if (client == null) startLoopbackMirroring() else stopMirroring()
        }
        watchButton.setOnClickListener { connectToWatch() }
        frameButton.setOnClickListener { showFrameDialog() }
        detailsToggle.setOnClickListener { toggleDetails() }
        musicButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED) {
                startMusic()
            } else {
                requestPermissions(arrayOf(audioPermission()), REQUEST_AUDIO)
            }
        }
        syncMusicButton()
        // Show the real track on the watch before anyone presses play; the permission prompt itself stays
        // on the button, so a first launch never asks for the library unprompted.
        if (ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED) {
            music.load()
        }

        // Start on the loopback link: the point of this APK is to show the picture that would be sent,
        // and that needs no second device and no permissions.
        startLoopbackMirroring()
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsTicker)
        unregisterReceiver(batteryReceiver)
        previewExecutor.shutdownNow()
        music.release()
        scanner.stop()
        stopMirroring()
        // The module owns the WebView; it destroys it on the handler.
        render.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED) {
                startMusic()
            } else {
                Toast.makeText(this, R.string.music_permission, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != REQUEST_BLUETOOTH) return
        if (scanner.hasPermissions()) {
            scanForWatches()
        } else {
            Toast.makeText(this, R.string.bluetooth_needed, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- music

    /** `READ_MEDIA_AUDIO` from API 33 on; before that the audio permission never existed separately. */
    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Loads the library (thread-safe to repeat) and toggles playback.
     *
     * Both calls land on the same single-thread executor inside [PhoneMusic], so the load always finishes
     * before the toggle runs.
     */
    private fun startMusic() {
        music.load()
        music.toggle()
        syncMusicButton()
    }

    private fun syncMusicButton() {
        if (!::musicButton.isInitialized) return
        musicButton.text = getString(
            if (music.isPlaying) R.string.music_pause else R.string.music_play,
        )
    }

    private fun toggleDetails() {
        detailsVisible = !detailsVisible
        statsView.visibility = if (detailsVisible) View.VISIBLE else View.GONE
        detailsToggle.setText(if (detailsVisible) R.string.details_hide else R.string.details_show)
    }

    /**
     * Short chip label for a session state.
     *
     * `STREAMING` does not survive a quarter-width chip at 10sp, and truncating it to `STREAMI…` is worse
     * than naming the one thing the user cares about. The full state name stays in the details block.
     */
    private fun sessionLabel(state: SessionState): String = when (state) {
        SessionState.STREAMING -> "LIVE"
        SessionState.DRAINING -> "DRAIN"
        SessionState.HANDSHAKING -> "HELLO"
        SessionState.NEGOTIATING -> "NEGO"
        SessionState.CONNECTING -> "LINK"
        SessionState.FAILED -> "FAILED"
        SessionState.IDLE -> "IDLE"
    }

    /**
     * Creates the WebView from a `densityDpi = 160` configuration context.
     *
     * `06-rendering.md` §3.2 step 1: this pins `1 CSS px == 1 physical px`, which is what makes every
     * relative unit in the scene line up with the watch's own geometry and keeps the touch mapping a pure
     * scale factor. Without it, the SDK would have to compensate with the real
     * `devicePixelRatio`, which this demo avoids on purpose. The WebView settings belong to
     * [WebViewRenderModule.attach].
     */
    private fun createSceneWebView(): WebView {
        val configuration = Configuration(resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_DEFAULT
        }
        return WebView(createConfigurationContext(configuration))
    }

    /**
     * Reads the scene the browser preview also renders.
     *
     * `demo/build.gradle.kts` stages `examples/watch-ui/demo-scene.html` into the asset directory, so there
     * is one copy of the document rather than a stale one per consumer.
     */
    private fun readSceneHtml(): String = try {
        assets.open(SCENE_ASSET).bufferedReader().use { it.readText() }
    } catch (error: IOException) {
        Log.e(TAG, "cannot read $SCENE_ASSET", error)
        lastError = "cannot read $SCENE_ASSET: ${error.message}"
        "<html><body style=\"background:#05070c\"></body></html>"
    }

    /**
     * Feeds the scene the phone's real battery level, since the watch face shows it.
     *
     * `ACTION_BATTERY_CHANGED` is sticky: registering delivers the current value immediately, so the first
     * pushed frame already carries a real number instead of the scene's placeholder.
     */
    private fun registerBattery() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(this, batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            sceneApp.batteryPercent = level * 100 / scale
        }
    }

    /**
     * The radio-free path: a loopback pair where `phone` is the SDK's link and `watch` is the scripted
     * sink. Useful because it needs no second device and no permissions.
     */
    private fun startLoopbackMirroring() {
        val (phone, watch) = LoopbackTransport.pair(phoneId = "demo-phone", watchId = "demo-watch")
        // The scripted watch claims exactly the panel this activity renders for: a session whose
        // `CAPS_ACK` disagreed with the render module's layout would be refused.
        val stub = DemoWatchStub(
            watch,
            Capabilities.referenceWatch(
                display = Capabilities.referenceDisplay(
                    shape = layout.shape,
                    widthPx = layout.widthPx,
                    heightPx = layout.heightPx,
                    safeInsetPermille = layout.safeInsetPermille,
                ),
            ),
        )
        linkLabel = "loopback (scripted watch)"
        startMirroring(phone, stub)
    }

    /**
     * The real path: [BleGattTransport] to a device that advertises the WatchPlay service — typically
     * the `:sink` app on a second Android device.
     */
    private fun startBleMirroring(watch: BleWatchScanner.Found) {
        val transport = BleGattTransport(this, watch.device)
        watchStub = null
        linkLabel = "BLE ${watch.name} (${watch.address})"
        startMirroring(transport)
    }

    private fun startMirroring(transport: TransportModule, stub: DemoWatchStub? = null) {
        stopMirroring()
        watchStub = stub
        val newClient = WatchPlayClient.builder(transport)
            .captureModule(PixelCopyCapture(PixelCopyCapture.Source.FromView(sceneView, window)))
            .encoderModule(MjpegEncoder())
            .uiRenderModule(render)
            .sessionConfig(
                SessionConfig(
                    deviceName = "WatchPlay demo phone",
                    maxFps = 10,
                    preferredQuality = 70,
                ),
            )
            .pipelineConfig(
                PipelineConfig(
                    profile = EncodeProfile.Mjpeg(70),
                    targetFps = 10,
                ),
            )
            .listener(this)
            .frameObserver { frame -> previewFrame(frame) }
            .build()

        client = newClient
        // The scene is loaded once negotiation reports the panel geometry (`CAPS_ACK`).
        newClient.setApp(sceneApp)
        newClient.connect()
        // The watch speaks first, so its `HELLO` must not be sent before the phone's endpoint is open:
        // `LoopbackTransport` drops a frame that arrives at a closed endpoint, and the session then times
        // out with "no HELLO within 3000 ms".
        stub?.start()
        newClient.start()

        toggleButton.text = getString(R.string.stop_mirroring)
        lastError = null
        sceneStatus = "waiting for CAPS_ACK"
        handler.removeCallbacks(statsTicker)
        handler.post(statsTicker)
    }

    // ---------------------------------------------------------------- frame preview

    /**
     * Draws the frame that just left the encoder — the picture the watch will decode.
     *
     * This is also the only honest check on the capture path: `PixelCopy` reads what the window
     * composited, and on some devices the WebView is composited elsewhere, so the frame arrives black.
     * The preview says so instead of leaving a puzzling blank screen on the watch.
     *
     * Decoding runs on [previewExecutor] because this callback arrives on the pipeline thread, where a
     * 466×466 JPEG decode would delay the next capture.
     */
    private fun previewFrame(frame: PipelineFrame) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewAtMs < PREVIEW_PERIOD_MS) return
        lastPreviewAtMs = now
        lastFrame = frame
        val jpeg = frame.encoded.data
        previewExecutor.execute {
            val decoded = decode(jpeg) ?: return@execute
            val blank = looksBlank(decoded)
            handler.post {
                if (isFinishing || isDestroyed) return@post
                previewView.setImageBitmap(decoded)
                previewView.invalidate()
                previewView.visibility = View.VISIBLE
                previewLabel.visibility = View.VISIBLE
                previewLabel.text = if (blank) {
                    getString(R.string.frame_black)
                } else {
                    getString(R.string.frame_label, frame.frameId, frame.encoded.width, frame.encoded.height)
                }
                previewDetail.text = if (blank) {
                    ""
                } else {
                    getString(
                        R.string.frame_detail,
                        "${jpeg.size / 1024} kB",
                        frame.chunkTotal,
                    )
                }
            }
        }
    }

    /**
     * Decodes into the bitmap from the previous frame when possible.
     *
     * At [PREVIEW_PERIOD_MS] a fresh 466×466 ARGB bitmap would be ~870 kB of garbage per update, which is
     * pointless churn on a phone that is already encoding 10 frames a second.
     */
    private fun decode(jpeg: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            previewBitmap?.takeIf { !it.isRecycled }?.let { inBitmap = it }
        }
        return try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)?.also { previewBitmap = it }
        } catch (error: IllegalArgumentException) {
            // Reuse is refused when the frame size changed; decode once without a target.
            previewBitmap = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options())
        }
    }

    /** `true` when the frame carries almost no light, which is what a broken capture produces. */
    private fun looksBlank(bitmap: Bitmap): Boolean {
        var total = 0L
        var samples = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                // Rec. 601 luma, integer arithmetic: 30/59/11 out of 100.
                val luma = ((pixel shr 16 and 0xFF) * 30 + (pixel shr 8 and 0xFF) * 59 + (pixel and 0xFF) * 11) / 100
                total += luma
                samples++
                x += LUMA_SAMPLE_STEP_PX
            }
            y += LUMA_SAMPLE_STEP_PX
        }
        return samples > 0 && total / samples < BLANK_LUMA_THRESHOLD
    }

    /**
     * Shows the last frame at 1:1 in a dialog.
     *
     * A dialog is its own window, which matters here: an `ImageView` laid over the scene would be
     * composited into the same window and `PixelCopy` would capture it, so the preview would end up
     * mirroring itself.
     */
    private fun showFrameDialog() {
        val frame = lastFrame
        val decoded = previewBitmap?.takeIf { !it.isRecycled }
        if (frame == null || decoded == null) {
            Toast.makeText(this, R.string.frame_none, Toast.LENGTH_SHORT).show()
            return
        }
        // A snapshot, because the preview bitmap is reused as the next frame is decoded.
        val snapshot = decoded.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val image = ImageView(this).apply {
            setImageBitmap(snapshot)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.frame_preview)
        }
        // Sized in pixels: 1:1 is the point, and an AlertDialog otherwise shrinks the view to its own
        // content width. The outer frame matches the dialog width so the square ends up centred.
        val stage = FrameLayout(this).apply {
            addView(
                image,
                FrameLayout.LayoutParams(layout.widthPx, layout.heightPx, Gravity.CENTER),
            )
        }
        maskToPanel(stage, layout, fillColor = ContextCompat.getColor(this, R.color.wp_bg))
        AlertDialog.Builder(this)
            .setTitle(
                getString(R.string.frame_label, frame.frameId, frame.encoded.width, frame.encoded.height) +
                    " · " +
                    getString(
                        R.string.frame_detail,
                        "${frame.encoded.sizeBytes / 1024} kB",
                        frame.chunkTotal,
                    ),
            )
            .setView(stage)
            .setPositiveButton(android.R.string.ok) { _, _ -> snapshot.recycle() }
            .setOnDismissListener { snapshot.recycle() }
            .show()
    }

    // ---------------------------------------------------------------- BLE discovery

    private fun connectToWatch() {
        if (!scanner.isAvailable()) {
            Toast.makeText(this, R.string.bluetooth_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val missing = scanner.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
            return
        }
        scanForWatches()
    }

    private fun scanForWatches() {
        watchButton.isEnabled = false
        watchButton.text = getString(R.string.scanning_watches)
        scanner.scan(SCAN_TIMEOUT_MS) { found ->
            watchButton.isEnabled = true
            watchButton.text = getString(R.string.connect_watch)
            if (found.isEmpty()) {
                Toast.makeText(this, R.string.no_watches, Toast.LENGTH_LONG).show()
                return@scan
            }
            val labels = found.map { "${it.name}  ${it.address}  ${it.rssi} dBm" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.pick_watch)
                .setItems(labels) { _, index -> startBleMirroring(found[index]) }
                .show()
        }
    }

    private fun stopMirroring() {
        handler.removeCallbacks(statsTicker)
        client?.stop()
        client = null
        watchStub?.stop()
        watchStub = null
        if (::toggleButton.isInitialized) toggleButton.text = getString(R.string.start_mirroring)
    }

    // ---------------------------------------------------------------- WatchPlayClient.Listener

    override fun onClientStateChanged(state: SessionState) {
        lastState = state
        // Called on the transport or control thread; hop to the UI thread before touching views.
        handler.post { renderStats() }
    }

    override fun onStats(stats: PipelineStats) {
        lastStats = stats
    }

    override fun onClientError(error: Throwable) {
        Log.w(TAG, "client error", error)
        lastError = error.message
        handler.post { renderStats() }
    }

    // ---------------------------------------------------------------- UIRenderModule.UIRenderListener

    override fun onSceneReady(sceneId: String, layout: WatchLayoutSpec) {
        sceneStatus = "$sceneId " + WatchScreenShape.describe(layout) + " inset=${layout.cssSafeInsetValue}"
        handler.post { renderStats() }
    }

    override fun onFrameRequested() {
        paintRequests++
    }

    override fun onRenderError(sceneId: String, message: String) {
        // WebView console output arrives here too; log it instead of painting it into the stats pane.
        Log.w(TAG, "render $sceneId: $message")
    }

    private fun renderStats() {
        val stub = watchStub
        val client = this.client
        val stats = lastStats

        chipSession.text = sessionLabel(lastState)
        chipFps.text = if (stats.framesSent > 0) getString(R.string.chip_fps, stats.fps) else getString(R.string.chip_dash)
        chipLatency.text = if (stats.framesSent > 0) {
            getString(R.string.chip_latency, stats.averageLatencyMs.toInt())
        } else {
            getString(R.string.chip_dash)
        }
        chipCredit.text = getString(R.string.chip_credit, client?.stats()?.creditRemaining ?: 0)
        syncMusicButton()

        val text = buildString {
            append("session: ").append(lastState.name).append('\n')
            append("link: ").append(linkLabel).append('\n')
            append("scene: ").append(sceneStatus).append('\n')
            append("paint requests: ").append(paintRequests).append('\n')
            lastFrame?.let { frame ->
                append("sent frame: #").append(frame.frameId)
                    .append(' ').append(frame.encoded.width).append('x').append(frame.encoded.height)
                    .append(' ').append(frame.encoded.sizeBytes).append(" B / ")
                    .append(frame.chunkTotal).append(" chunks\n")
            }
            append("negotiated: ")
            append(client?.negotiated?.let { "${it.codec.displayName} ${it.widthPx}x${it.heightPx} " +
                "@${it.targetFps}fps q=${it.quality} chunk=${it.maxChunkBytes} credits=${it.initialCredits}" }
                ?: "not yet").append('\n')
            append("credit window: ").append(client?.stats()?.creditRemaining ?: 0).append('\n')
            append("--- pipeline ---").append('\n')
            append(lastStats.summary()).append('\n')
            append("--- scripted watch ---").append('\n')
            if (stub == null) {
                append("not running")
            } else {
                append("handshake accepted: ").append(stub.sessionAccepted).append('\n')
                append("frames reassembled: ").append(stub.framesDecoded).append('\n')
                append("chunks received: ").append(stub.chunksReceived).append('\n')
                append("last frame: ").append(stub.lastFrameBytes).append(" B\n")
                append("last grant: ").append(stub.lastGrantedCredits).append(" credits")
                stub.lastDropReason?.let { append("\nlast drop: ").append(it) }
            }
            lastError?.let { append("\n--- last error ---\n").append(it) }
        }
        statsView.text = text
    }

    companion object {
        private const val TAG = "WatchPlayDemo"

        /** How often the stats pane refreshes. */
        private const val STATS_PERIOD_MS = 1_000L

        /**
         * The scene ships in the APK's assets, so it loads with zero network requests. The file is
         * `examples/watch-ui/demo-scene.html`, mapped into assets by `app/build.gradle.kts`.
         */
        private const val SCENE_ASSET = "demo-scene.html"

        /** How long the watch scan runs before the chooser appears. */
        private const val SCAN_TIMEOUT_MS = 8_000L

        private const val REQUEST_BLUETOOTH = 1

        /** Audio-library permission, requested once at start. */
        private const val REQUEST_AUDIO = 2

        /** Name the scene sees: `window.watchplayHost`. */
        private const val SCENE_BRIDGE = "watchplayHost"

        /** How often the preview decodes a frame; the stream itself runs at 10 fps. */
        private const val PREVIEW_PERIOD_MS = 250L

        /** Sampling stride for the blank-frame check: 466/16 is about 30×30 samples. */
        private const val LUMA_SAMPLE_STEP_PX = 16

        /** Mean luma below this (0..255) means the frame carries no visible content. */
        private const val BLANK_LUMA_THRESHOLD = 4

        /** Intent extra naming the panel shape: `round` (default), `square` or `sharp`. */
        private const val EXTRA_SHAPE = "shape"

        /** Side of the non-reference panels `--es shape` can ask for. */
        private const val SHAPE_PANEL_PX = 466
    }

    /**
     * The panel this run mirrors, defaulting to the reference watch.
     *
     * `WebViewRenderModule` refuses a layout that differs from the one it was built with and the session's
     * layout comes from `CAPS_ACK`, so the scripted watch is told the same geometry
     * ([startLoopbackMirroring]). Being able to start a square panel is what makes the round/square branch
     * of the scene observable on a phone at all:
     *
     * ```
     * adb shell am start -n com.watchplay.demo/.MainActivity --es shape square
     * ```
     */
    private fun panelFrom(intent: Intent): WatchLayoutSpec = when (intent.getStringExtra(EXTRA_SHAPE)) {
        "square" -> WatchLayoutSpec.rectangular(
            widthPx = SHAPE_PANEL_PX,
            heightPx = SHAPE_PANEL_PX,
            shape = ScreenShape.ROUNDED_RECT,
        )

        "sharp" -> WatchLayoutSpec.rectangular(
            widthPx = SHAPE_PANEL_PX,
            heightPx = SHAPE_PANEL_PX,
            shape = ScreenShape.RECTANGULAR,
        )

        else -> WatchLayoutSpec.round()
    }
}
