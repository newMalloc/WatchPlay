package com.watchplay.demo

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.watchplay.sdk.WatchPlayClient
import com.watchplay.sdk.capture.PixelCopyCapture
import com.watchplay.sdk.encoder.EncodeProfile
import com.watchplay.sdk.encoder.MjpegEncoder
import com.watchplay.sdk.pipeline.PipelineConfig
import com.watchplay.sdk.pipeline.PipelineStats
import com.watchplay.sdk.render.UIRenderModule
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.render.WebViewRenderModule
import com.watchplay.sdk.session.SessionConfig
import com.watchplay.sdk.session.SessionState
import com.watchplay.sdk.transport.BleGattTransport
import com.watchplay.sdk.transport.LoopbackTransport
import com.watchplay.sdk.transport.TransportModule
import java.io.IOException

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
    private lateinit var statsView: TextView
    private lateinit var toggleButton: Button
    private lateinit var watchButton: Button

    private var client: WatchPlayClient? = null
    private var watchStub: DemoWatchStub? = null
    private var linkLabel: String = "none"
    private lateinit var scanner: BleWatchScanner

    private val handler = Handler(Looper.getMainLooper())
    private var lastStats: PipelineStats = PipelineStats()
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
        scanner = BleWatchScanner(this)

        sceneView = createSceneWebView()
        render = WebViewRenderModule(DEMO_LAYOUT, this)
        render.attach(sceneView)
        findViewById<FrameLayout>(R.id.scene_container).addView(
            sceneView,
            // Laid out at the panel's pixel size, so the captured bitmap and the encoded frame are the
            // size the watch negotiated: a 466 px WebView under a density-160 context is a 466 CSS px
            // viewport, and `1vmin` in the scene is then exactly 1% of the panel (06-rendering.md §3.2).
            FrameLayout.LayoutParams(DEMO_LAYOUT.widthPx, DEMO_LAYOUT.heightPx, Gravity.CENTER),
        )
        sceneApp = DemoSceneApp(readSceneHtml())

        toggleButton.setOnClickListener {
            if (client == null) startLoopbackMirroring() else stopMirroring()
        }
        watchButton.setOnClickListener { connectToWatch() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsTicker)
        scanner.stop()
        stopMirroring()
        // The module owns the WebView; it destroys it on the handler.
        render.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (scanner.hasPermissions()) {
            scanForWatches()
        } else {
            Toast.makeText(this, R.string.bluetooth_needed, Toast.LENGTH_LONG).show()
        }
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
     * The radio-free path: a loopback pair where `phone` is the SDK's link and `watch` is the scripted
     * sink. Useful because it needs no second device and no permissions.
     */
    private fun startLoopbackMirroring() {
        val (phone, watch) = LoopbackTransport.pair(phoneId = "demo-phone", watchId = "demo-watch")
        val stub = DemoWatchStub(watch)
        stub.start()
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
            .build()

        client = newClient
        // The scene is loaded once negotiation reports the panel geometry (`CAPS_ACK`).
        newClient.setApp(sceneApp)
        newClient.connect()
        newClient.start()

        toggleButton.text = getString(R.string.stop_mirroring)
        lastError = null
        sceneStatus = "waiting for CAPS_ACK"
        handler.removeCallbacks(statsTicker)
        handler.post(statsTicker)
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
        sceneStatus = "$sceneId ${layout.widthPx}x${layout.heightPx} " +
            "${layout.shape.name.lowercase()} inset=${layout.cssSafeInsetValue}"
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
        val text = buildString {
            append("session: ").append(lastState.name).append('\n')
            append("link: ").append(linkLabel).append('\n')
            append("scene: ").append(sceneStatus).append('\n')
            append("paint requests: ").append(paintRequests).append('\n')
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

        /**
         * The panel the demo renders for. `WebViewRenderModule` refuses a layout that differs from the one
         * it was built with, and the session's own layout comes from `CAPS_ACK`, so the demo assumes the
         * reference panel the scripted watch reports: 466×466 round, 140‰ (`Capabilities.referenceWatch`).
         */
        private val DEMO_LAYOUT: WatchLayoutSpec = WatchLayoutSpec.round()
    }
}
