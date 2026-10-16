package com.watchplay.demo

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.watchplay.sdk.WatchPlayClient
import com.watchplay.sdk.capture.PixelCopyCapture
import com.watchplay.sdk.encoder.MjpegEncoder
import com.watchplay.sdk.encoder.EncodeProfile
import com.watchplay.sdk.pipeline.PipelineConfig
import com.watchplay.sdk.pipeline.PipelineStats
import com.watchplay.sdk.session.SessionConfig
import com.watchplay.sdk.session.SessionState
import com.watchplay.sdk.transport.LoopbackTransport

/**
 * WatchPlay demo: show a watch-sized WebView scene, then mirror it through the real SDK pipeline.
 *
 * ## What this demonstrates
 *
 * ```
 *   assets/demo_scene.html  -->  WebView (densityDpi = 160, animated CSS scene)
 *                                     |
 *                                     v
 *   PixelCopyCapture --> MjpegEncoder --> VideoFrameChunker --> LoopbackTransport
 *                                                                   |
 *                                                        DemoWatchStub (scripted Sink)
 * ```
 *
 * A full handshake really happens: the stub sends `HELLO` and `CAPS`, the SDK negotiates, the stub grants
 * credit with `FEEDBACK`, and only then do `VIDEO` chunks flow. The stats pane shows the numbers the
 * pipeline actually produced — real JPEG sizes, real chunk counts, real credit consumption.
 *
 * ## What it deliberately does not do
 *
 *  * No Bluetooth: the link is in-memory, so the demo runs anywhere and needs no permissions.
 *  * No `GlTextureCapture`: that path is still a stub, so the demo uses the documented CPU fallback.
 *    Note that `PixelCopy` copies what the window composited, and some devices return **black** for
 *    WebView content because it is composited in a separate surface. The stats stay honest either way;
 *    the picture is the only thing that may be blank.
 *  * No touch round trip: there is no real panel to touch, so `TOUCH` handling is not exercised here.
 */
class MainActivity : AppCompatActivity(), WatchPlayClient.Listener {

    private lateinit var sceneView: WebView
    private lateinit var statsView: TextView
    private lateinit var toggleButton: Button

    private var client: WatchPlayClient? = null
    private var watchStub: DemoWatchStub? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastStats: PipelineStats = PipelineStats()
    private var lastState: SessionState = SessionState.IDLE
    private var lastError: String? = null

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
        sceneView = createSceneWebView()
        findViewById<FrameLayout>(R.id.scene_container).addView(
            sceneView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        sceneView.loadUrl(SCENE_URL)

        toggleButton.setOnClickListener {
            if (client == null) startMirroring() else stopMirroring()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsTicker)
        stopMirroring()
        sceneView.destroy()
        super.onDestroy()
    }

    /**
     * Creates the WebView from a `densityDpi = 160` configuration context.
     *
     * `06-rendering.md` §3.2 step 1: this pins `1 CSS px == 1 physical px`, which is what makes every
     * relative unit in the scene line up with the watch's own geometry and keeps the touch mapping a pure
     * scale factor. Without it, the SDK would have to compensate with the real
     * `devicePixelRatio`, which this demo avoids on purpose.
     */
    private fun createSceneWebView(): WebView {
        val configuration = Configuration(resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_DEFAULT
        }
        val webView = WebView(createConfigurationContext(configuration))
        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = false
            textZoom = 100
            minimumFontSize = 1
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        return webView
    }

    private fun startMirroring() {
        // A loopback pair: `phone` is the SDK's link, `watch` is the scripted Sink.
        val (phone, watch) = LoopbackTransport.pair(phoneId = "demo-phone", watchId = "demo-watch")
        val stub = DemoWatchStub(watch)
        watchStub = stub
        stub.start()

        val newClient = WatchPlayClient.builder(phone)
            .captureModule(PixelCopyCapture(PixelCopyCapture.Source.FromView(sceneView, window)))
            .encoderModule(MjpegEncoder())
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
        newClient.connect()
        newClient.start()

        toggleButton.text = getString(R.string.stop_mirroring)
        lastError = null
        handler.removeCallbacks(statsTicker)
        handler.post(statsTicker)
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

    private fun renderStats() {
        val stub = watchStub
        val client = this.client
        val text = buildString {
            append("session: ").append(lastState.name).append('\n')
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

        /** The scene ships in the APK's assets, so it loads with zero network requests. */
        private const val SCENE_URL = "file:///android_asset/demo_scene.html"
    }
}
