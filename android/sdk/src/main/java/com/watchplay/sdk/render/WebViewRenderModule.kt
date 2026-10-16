package com.watchplay.sdk.render

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.UiThread
import com.watchplay.sdk.touch.MappedTouch
import com.watchplay.sdk.touch.TouchAction

/**
 * A deliberately partial [UIRenderModule] built on `android.webkit.WebView`.
 *
 * ## What works today
 *
 *  * [loadScene] applies the WebView settings `06-rendering.md` §3.2 requires (no zoom, no overview
 *    mode, `textZoom = 100`, minimum font sizes pinned to 1 so a `rem`-based scene is not distorted,
 *    `blockNetworkLoads = true` because a scene must make **zero** network requests), then loads the
 *    document with `loadDataWithBaseURL`.
 *  * **Safe-area injection happens before the first paint.** `06-rendering.md` §2.3 R1/R2 are
 *    normative: the phone injects the watch's own `safe_inset_permille` **verbatim** as
 *    `--wp-safe-inset: (safe_inset_permille / 10)vmin` (note `vmin`, not `%`) together with
 *    `data-screen-shape`, and must not substitute a hard-coded margin. This implementation rewrites the
 *    scene's [INJECTION_MARKER] — the fallback the specification itself defines — because that path
 *    needs no extra dependency. The preferred `WebViewCompat.addDocumentStartJavaScript` path lives in
 *    `androidx.webkit`, which `:sdk` does not depend on yet; see the TODO below.
 *  * [dispatchTouch] injects native [MotionEvent]s, as `05-input.md` §2.4 requires.
 *  * [WebViewClient.onPageCommitVisible] drives readiness so the first capture waits for a painted
 *    document.
 *
 * ## What is a stub
 *
 *  * [requestFrame] reports readiness only. Real frame pacing needs a hand-off to the capture path
 *    (GL texture or `PixelCopy`) once the compositor has the new content.
 *  * [WatchScene.assets] are not served yet: a scene must inline its CSS or use `file:///android_asset/`
 *    via `baseUrl`.
 *  * The render-to-texture path of `06-rendering.md` §7 is not implemented; the capture side provides it.
 *
 * ## TODO for the next contributor
 *
 *  * Add `androidx.webkit:webkit` and prefer `WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*"))`
 *    when `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)`, keeping the string substitution as
 *    the fallback.
 *  * Create the WebView with a `densityDpi = 160` configuration context so `1 CSS px == 1 watch pixel`
 *    and `1vmin == 1%` of the shorter side; the demo module shows the pattern.
 *
 * @property layout geometry of the watch being mirrored; fixed at construction, because changing it
 *   requires a new layout pass and therefore a scene reload.
 * @property listener observer for scene lifecycle events; `null` is allowed.
 */
@UiThread
class WebViewRenderModule(
    override val layout: WatchLayoutSpec,
    private val listener: UIRenderModule.UIRenderListener? = null,
) : UIRenderModule {

    private var webView: WebView? = null
    private var pageCommitted = false
    private var downTimeMs = 0L

    override var currentScene: WatchScene? = null
        private set

    override var isReady: Boolean = false
        private set

    /**
     * Attaches the WebView this module drives. Must be called before [loadScene].
     *
     * The WebView is supplied by the app because only the app knows its view hierarchy and can create it
     * with the density configuration `06-rendering.md` §3.2 step 1 requires.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        this.webView = webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = false
            textZoom = 100
            minimumFontSize = 1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setSupportZoom(false)
            }
            builtInZoomControls = false
            displayZoomControls = false
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            // A 466x466 scene must not need a user gesture to play media (API 17+).
            mediaPlaybackRequiresUserGesture = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                pageCommitted = true
                isReady = true
                currentScene?.let { scene -> listener?.onSceneReady(scene.id, layout) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Some WebView versions do not deliver onPageCommitVisible; keep a fallback.
                if (!pageCommitted) {
                    pageCommitted = true
                    isReady = true
                    currentScene?.let { scene -> listener?.onSceneReady(scene.id, layout) }
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                currentScene?.let { scene -> listener?.onRenderError(scene.id, consoleMessage.message()) }
                return true
            }
        }
    }

    override fun loadScene(scene: WatchScene, layout: WatchLayoutSpec) {
        val view = webView
        if (view == null) {
            listener?.onRenderError(scene.id, "loadScene called before attach(webView)")
            return
        }
        if (layout != this.layout) {
            listener?.onRenderError(
                scene.id,
                "layout change needs a new WebViewRenderModule; ignoring ${layout.widthPx}x${layout.heightPx}",
            )
            return
        }
        pageCommitted = false
        isReady = false
        currentScene = scene
        view.setBackgroundColor(if (scene.transparentBackground) 0x00000000 else 0xFF000000.toInt())
        view.loadDataWithBaseURL(scene.baseUrl, injectNegotiatedCss(scene.html, layout), "text/html", "utf-8", null)
    }

    override fun applyUpdate(update: SceneUpdate) {
        val view = webView ?: return
        if (!isReady) return
        val scene = currentScene ?: return
        if (scene.id != update.sceneId) return

        if (update.data.isNotEmpty()) {
            val json = update.data.entries.joinToString(",") { (key, value) ->
                "\"${key.replace("\"", "\\\"")}\":$value"
            }
            view.evaluateJavascript(
                "window.watchplay=window.watchplay||{};" +
                    "window.watchplay.data=Object.assign(window.watchplay.data||{},{$json});",
                null,
            )
        }
        update.javascript?.let { snippet -> view.evaluateJavascript("(function(){ $snippet })();", null) }
        if (update.requestFrameAfterUpdate) {
            // TODO(next contributor): gate on postVisualStateCallback before signalling, otherwise the
            // capture may read the previous paint.
            listener?.onFrameRequested()
        }
    }

    override fun requestFrame(): Boolean = isReady

    override fun dispatchTouch(touch: MappedTouch): Boolean {
        val view = webView ?: return false
        val action = when (touch.action) {
            TouchAction.DOWN -> {
                downTimeMs = SystemClock.uptimeMillis()
                MotionEvent.ACTION_DOWN
            }

            TouchAction.MOVE -> MotionEvent.ACTION_MOVE
            TouchAction.UP -> MotionEvent.ACTION_UP
            TouchAction.CANCEL -> MotionEvent.ACTION_CANCEL
            // The watch's optional gestures are translated to the closest primitive browser event.
            TouchAction.LONG_PRESS -> MotionEvent.ACTION_MOVE
            TouchAction.SWIPE_START -> MotionEvent.ACTION_MOVE
            TouchAction.SWIPE_END -> MotionEvent.ACTION_UP
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTimeMs = 0L
        val eventTime = if (action == MotionEvent.ACTION_DOWN) downTimeMs else SystemClock.uptimeMillis()
        // `eventTime` is based on uptimeMillis, as `05-input.md` §2.4 requires; the watch's own clock is
        // a different time base and is only usable for gesture-internal deltas.
        val event = MotionEvent.obtain(
            downTimeMs,
            eventTime,
            action,
            touch.cssX,
            touch.cssY,
            touch.pressure,
            1f,
            0,
            1f,
            1f,
            0,
            0,
        )
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        return try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    override fun release() {
        val view = webView
        webView = null
        pageCommitted = false
        isReady = false
        currentScene = null
        if (view != null) {
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        }
    }

    /**
     * Injects the negotiated CSS custom properties into [html].
     *
     * `06-rendering.md` §2.3 R1 fixes the exact form:
     * `--wp-safe-inset: (safe_inset_permille / 10)vmin`, injected verbatim, plus
     * `data-screen-shape` on `<html>` so one document can serve round and square panels (§3.4).
     *
     * Scene authors place [INJECTION_MARKER] inside `:root{...}`; when the marker is absent the values
     * are prepended as a `style` attribute on the `html` element, which still applies before the first
     * paint.
     */
    internal fun injectNegotiatedCss(html: String, layout: WatchLayoutSpec): String {
        val declarations = "--wp-safe-inset:${layout.cssSafeInsetValue};"
        val shape = layout.cssScreenShape

        if (!html.contains("<html", ignoreCase = true)) {
            return "<html data-screen-shape=\"$shape\" style=\"$declarations\">$html</html>"
        }

        var result = if (html.contains(INJECTION_MARKER)) {
            html.replace(INJECTION_MARKER, declarations)
        } else {
            html.replaceFirst("<html", "<html style=\"$declarations\"")
        }
        if (!result.contains("data-screen-shape", ignoreCase = true)) {
            result = result.replaceFirst("<html", "<html data-screen-shape=\"$shape\"")
        }
        return result
    }

    companion object {
        /**
         * Marker a scene places inside `:root{}` so that the phone can substitute the negotiated
         * values with a plain string replace — the dependency-free fallback of `06-rendering.md` §3.2.
         */
        const val INJECTION_MARKER: String = "/*WP_INJECT*/"
    }
}
