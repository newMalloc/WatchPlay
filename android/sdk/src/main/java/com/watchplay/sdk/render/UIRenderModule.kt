package com.watchplay.sdk.render

/**
 * A push of new data into an already-loaded scene.
 *
 * Updates are expressed as JavaScript because the mapping from "app state" to "DOM" belongs to the
 * scene author, not to the SDK: the SDK only guarantees that the snippet runs on the WebView's main
 * thread with the document loaded. Keeping the payload as a string also means an update can be
 * batched — many apps will build one `requestAnimationFrame` callback that repaints everything.
 *
 * @property sceneId the scene this update targets; updates for a non-current scene are dropped.
 * @property javascript snippet to evaluate; `null` means "only [data] changed".
 * @property data name/value pairs the snippet can read from the global `window.watchplay.data`
 *   object. Values must already be JSON-encoded strings.
 * @property requestFrameAfterUpdate when `true`, the module asks for a fresh capture once the WebView
 *   has painted, which is how the pipeline learns that there is something new to send.
 */
data class SceneUpdate(
    val sceneId: String,
    val javascript: String? = null,
    val data: Map<String, String> = emptyMap(),
    val requestFrameAfterUpdate: Boolean = true,
) {

    init {
        require(sceneId.isNotBlank()) { "sceneId must not be blank" }
        require(javascript != null || data.isNotEmpty()) { "update for $sceneId carries nothing" }
    }
}

/**
 * Renders watch UI scenes and turns them into something a capture module can read.
 *
 * ## Contract
 *
 * ```
 *   loadScene(scene)            -> scene is loaded and laid out at layout.safeArea
 *   applyUpdate(update)         -> the DOM reflects the new data (possibly asynchronously)
 *   requestFrame()              -> the WebView will have painted by the time the capture module reads
 *   release()                   -> resources freed; the module must not be used again
 * ```
 *
 * The module owns the *UI* side of the pipeline (`docs/spec/00-overview.md` diagram: HTML+CSS →
 * WebView → OpenGL texture) and knows nothing about capture, encoding or transport. That split is
 * what allows [com.watchplay.sdk.capture.GlTextureCapture] to be swapped for
 * [com.watchplay.sdk.capture.PixelCopyCapture] without touching a single scene.
 *
 * ## Threading
 *
 * Callers come from three places: the app's main thread, the session thread (`loadScene` after
 * `CAPS_ACK`, `dispatchTouch` when a `TOUCH` frame arrives) and the pipeline thread (`applyUpdate`).
 * Implementations must marshal to whatever thread they need — [WebViewRenderModule] posts to the
 * WebView's handler. [requestFrame] must be safe to call from any thread.
 *
 * ## Implementation status
 *
 * [WebViewRenderModule] is the only implementation shipped. Loading a scene, injecting the negotiated
 * safe area, forwarding touches as native events and gating frame requests on the compositor all work;
 * serving [WatchScene.assets] and the GL texture hand-off do not. See its KDoc for the exact gaps.
 */
interface UIRenderModule {

    /** Geometry the current scene is laid out for; available before the first [loadScene]. */
    val layout: WatchLayoutSpec

    /** The currently loaded scene, or `null` before the first [loadScene]. */
    val currentScene: WatchScene?

    /** `true` once a scene is loaded and the document reports itself ready. */
    val isReady: Boolean

    /**
     * Loads [scene] and lays it out for [layout], replacing any previous scene.
     *
     * Implementations must call [UIRenderListener.onSceneReady] exactly once per successful load;
     * a scene that fails to load calls [UIRenderListener.onRenderError] instead and leaves
     * [currentScene] unchanged.
     */
    fun loadScene(scene: WatchScene, layout: WatchLayoutSpec = this.layout)

    /** Applies [update] to the current scene; ignored when its `sceneId` is not the current scene. */
    fun applyUpdate(update: SceneUpdate)

    /**
     * Signals that a capture is about to happen.
     *
     * @return `true` when the scene is ready to be captured, `false` when the caller should skip this
     *   frame (no scene, document still loading, GL surface lost).
     */
    fun requestFrame(): Boolean

    /**
     * Injects a touch into the scene as a **native** input event.
     *
     * `05-input.md` §2.4 is explicit that the phone must synthesise real `MotionEvent`s
     * (`WebView.dispatchTouchEvent`) rather than synthetic JavaScript events: only trusted events get
     * browser gesture recognition, `:active` styling, scroll-into-view and the click-delay heuristics.
     *
     * @param touch a touch already mapped to CSS pixels by `com.watchplay.sdk.touch.TouchHandler`.
     * @return `true` when the WebView consumed it.
     */
    fun dispatchTouch(touch: com.watchplay.sdk.touch.MappedTouch): Boolean

    /** Releases WebView/GL resources. */
    fun release()

    /** Observer for scene lifecycle events. All callbacks arrive on the main thread. */
    interface UIRenderListener {
        /** The scene finished loading and is laid out for [layout]. */
        fun onSceneReady(sceneId: String, layout: WatchLayoutSpec)

        /** The WebView painted after an update; the pipeline may capture. */
        fun onFrameRequested()

        /** Loading or evaluation failed; the message is for logs, not for the user. */
        fun onRenderError(sceneId: String, message: String)
    }
}
