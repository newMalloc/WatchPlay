package com.watchplay.sdk.render

import android.annotation.SuppressLint
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
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
 *    `<html>` tag: the declaration lands **inline** and `data-screen-shape` is **overwritten** with what
 *    the watch reported, so neither the scene's `:root` fallback nor its shape-keyed fallback can win.
 *    [injectNegotiatedCss] explains why the carrier matters. The preferred
 *    `WebViewCompat.addDocumentStartJavaScript` path lives in `androidx.webkit`, which `:sdk` does not
 *    depend on yet; see the TODO below.
 *  * [dispatchTouch] injects native [MotionEvent]s, as `05-input.md` §2.4 requires.
 *  * **Every entry point is thread-safe.** The session thread calls [loadScene] and [dispatchTouch] and
 *    the pipeline thread calls [applyUpdate], but a `WebView` only accepts work from the thread that
 *    created it ([attach]). All three hop to the WebView's handler, so callers never marshal.
 *  * **Frame requests wait for the paint.** An update with `requestFrameAfterUpdate` reports through
 *    `postVisualStateCallback`, i.e. once the compositor holds the new content, so a capture taken in
 *    response cannot read the previous frame.
 *  * [WebViewClient.onPageCommitVisible] drives readiness so the first capture waits for a painted
 *    document.
 *
 * ## What is a stub
 *
 *  * [WatchScene.assets] are not served yet: a scene must inline its CSS or use `file:///android_asset/`
 *    via `baseUrl`.
 *  * The render-to-texture path of `06-rendering.md` §7 is not implemented; the capture side provides it.
 *
 * ## TODO for the next contributor
 *
 *  * Add `androidx.webkit:webkit` and prefer `WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*"))`
 *    when `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)`, keeping the string substitution as
 *    the fallback.
 *
 * @property layout geometry of the watch being mirrored; fixed at construction, because changing it
 *   requires a new layout pass and therefore a scene reload.
 * @property listener observer for scene lifecycle events; `null` is allowed.
 */
class WebViewRenderModule(
    override val layout: WatchLayoutSpec,
    private val listener: UIRenderModule.UIRenderListener? = null,
) : UIRenderModule {

    @Volatile
    private var webView: WebView? = null

    private var pageCommitted = false
    private var downTimeMs = 0L
    private var paintTicket = 0L

    @Volatile
    override var currentScene: WatchScene? = null
        private set

    @Volatile
    override var isReady: Boolean = false
        private set

    /**
     * Attaches the WebView this module drives. Must be called before [loadScene].
     *
     * The WebView is supplied by the app because only the app knows its view hierarchy and can create it
     * with the density configuration `06-rendering.md` §3.2 step 1 requires. Call this on the UI thread:
     * it installs the clients, and every later call is posted to that thread.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @UiThread
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
                markReady()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Some WebView versions do not deliver onPageCommitVisible; keep a fallback.
                if (!pageCommitted) markReady()
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
        onUiThread(view) {
            if (webView !== view) return@onUiThread
            pageCommitted = false
            isReady = false
            currentScene = scene
            view.setBackgroundColor(if (scene.transparentBackground) 0x00000000 else 0xFF000000.toInt())
            view.loadDataWithBaseURL(
                scene.baseUrl,
                injectNegotiatedCss(scene.html, layout),
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    override fun applyUpdate(update: SceneUpdate) {
        val view = webView ?: return
        onUiThread(view) {
            if (webView !== view || !isReady) return@onUiThread
            val scene = currentScene ?: return@onUiThread
            if (scene.id != update.sceneId) return@onUiThread

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
            if (update.requestFrameAfterUpdate) awaitPaint(view)
        }
    }

    override fun requestFrame(): Boolean = isReady

    override fun dispatchTouch(touch: MappedTouch): Boolean {
        val view = webView ?: return false
        // The WebView's verdict is only known on its own thread; `true` means "queued", which is what the
        // touch pipeline needs — a dropped event would otherwise look like an unresponsive panel.
        onUiThread(view) {
            if (webView === view) dispatchTouchOnUiThread(view, touch)
        }
        return true
    }

    override fun release() {
        val view = webView
        webView = null
        pageCommitted = false
        isReady = false
        currentScene = null
        if (view == null) return
        // Inline when the caller is already on the UI thread (the usual `onDestroy` case), so `destroy()`
        // is not deferred to a post that a detached WebView may never run.
        onUiThread(view) {
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        }
    }

    // ---------------------------------------------------------------- internals

    private fun markReady() {
        pageCommitted = true
        isReady = true
        currentScene?.let { scene -> listener?.onSceneReady(scene.id, layout) }
    }

    /**
     * Runs [action] on the thread that owns [view].
     *
     * `loadScene` arrives from the session thread and `applyUpdate` from the pipeline thread; touching a
     * `WebView` from either is undefined behaviour.
     */
    private fun onUiThread(view: WebView, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else view.post(action)
    }

    private fun awaitPaint(view: WebView) {
        val ticket = ++paintTicket
        view.postVisualStateCallback(
            ticket,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    listener?.onFrameRequested()
                }
            },
        )
    }

    private fun dispatchTouchOnUiThread(view: WebView, touch: MappedTouch) {
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
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    companion object {

        /**
         * Marker a scene may place inside `:root{}` for the string-replacement fallback of
         * `06-rendering.md` §3.2. The replacement is a convenience, not the carrier that decides the
         * value — see [injectNegotiatedCss].
         */
        const val INJECTION_MARKER: String = "/*WP_INJECT*/"
    }
}

/**
 * Injects the negotiated safe area and panel shape into [html] before the first paint.
 *
 * `06-rendering.md` §2.3 R1 fixes the exact form: `--wp-safe-inset: (safe_inset_permille / 10)vmin`,
 * injected verbatim; R3 wants it in place before anything is drawn.
 *
 * ## Why the declaration goes inline on `<html>`
 *
 * Both carriers are written, and they are not equal:
 *
 *  1. **Inline `style` on `<html>` — this is the one that decides.** The document-start path of §3.2
 *     ends up in the same place (`documentElement.style.setProperty`). A scene's own fallbacks are
 *     stylesheet rules, and §3.3/§3.4 encourage keying them on shape:
 *     `html[data-screen-shape="square"]{ --wp-safe-inset:4vmin }` has specificity `0,1,1`, which outranks
 *     a `:root` declaration (`0,1,0`). Writing the negotiated value into `:root` alone therefore loses on
 *     a square panel, which is exactly what §8's checklist forbids ("未被任何本地默认值覆盖").
 *  2. [WebViewRenderModule.INJECTION_MARKER] inside `:root{}`, when the scene has one. This keeps the
 *     value visible where scenes historically expected it, and it is harmless because (1) wins anyway.
 *
 * The same reasoning applies to the shape attribute: a scene hard-codes a default so that the document
 * also looks right when opened in a browser, and the phone **overwrites** it with what the watch reported.
 * Adding the attribute only when it is missing would silently keep the scene's guess on a square watch.
 */
internal fun injectNegotiatedCss(html: String, layout: WatchLayoutSpec): String {
    val declarations = "--wp-safe-inset:${layout.cssSafeInsetValue};"
    val replaced = html.replace(WebViewRenderModule.INJECTION_MARKER, declarations)
    val openTag = HTML_OPEN_TAG.find(replaced)
        ?: return "<html data-screen-shape=\"${layout.cssScreenShape}\" style=\"$declarations\">$replaced</html>"

    var tag = withShape(openTag.value, layout.cssScreenShape)
    tag = withInset(tag, declarations)
    return if (tag == openTag.value) replaced else replaced.replaceRange(openTag.range, tag)
}

/** The opening `<html ...>` tag, if the document has one. */
private val HTML_OPEN_TAG = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)

/** Matches an opening-tag `data-screen-shape="..."` (or `'...'`). */
private val SHAPE_ATTRIBUTE = Regex("""data-screen-shape\s*=\s*(?:"[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)

/** Matches an opening-tag `style="..."` (or `'...'`) attribute and captures its value. */
private val STYLE_ATTRIBUTE = Regex("""\bstyle\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

/** Matches a single `--wp-safe-inset` declaration, so a stale value in the tag cannot survive. */
private val SAFE_INSET_DECLARATION = Regex("""--wp-safe-inset\s*:\s*[^;]*;?""", RegexOption.IGNORE_CASE)

/** Appoints the negotiated shape, replacing whatever default the scene declared. */
private fun withShape(tag: String, shape: String): String {
    val attribute = "data-screen-shape=\"$shape\""
    val existing = SHAPE_ATTRIBUTE.find(tag) ?: return withAttribute(tag, attribute)
    return tag.replaceRange(existing.range, attribute)
}

/** Prepends [declarations] to the tag's `style` attribute, creating one when absent. */
private fun withInset(tag: String, declarations: String): String {
    val existing = STYLE_ATTRIBUTE.find(tag) ?: return withAttribute(tag, "style=\"$declarations\"")
    val value = existing.groupValues[1].ifEmpty { existing.groupValues[2] }
    val cleaned = SAFE_INSET_DECLARATION.replace(value, "").trim().trim(';').trim()
    // Re-quote with `"` so the result has one predictable form; `declarations` already ends with `;`.
    val merged = if (cleaned.isEmpty()) declarations else "$declarations $cleaned"
    return tag.replaceRange(existing.range, "style=\"$merged\"")
}

/** Appends [attribute] to an opening tag, before its `>`. */
private fun withAttribute(tag: String, attribute: String): String {
    val end = tag.lastIndexOf('>')
    val head = tag.substring(0, end)
    val separator = if (head.last().isWhitespace()) "" else " "
    return head + separator + attribute + tag.substring(end)
}
