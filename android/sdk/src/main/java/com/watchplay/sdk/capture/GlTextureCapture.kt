package com.watchplay.sdk.capture

/**
 * **Not implemented yet — this is the GPU capture path the project still owes.**
 *
 * ## What it is supposed to do
 *
 * The reference design (`docs/spec/00-overview.md` §1) renders the watch scene with OpenGL into a
 * texture and then encodes that texture with `MediaCodec`, never touching the CPU:
 *
 * ```
 *   WebView (hardware layer)
 *        |  draws into an EGLSurface
 *        v
 *   EGLSurface backed by an OpenGL ES 2.0 texture   <- this class owns the texture id
 *        |  glFinish()/fence sync once the WebView's frame is committed
 *        v
 *   MediaCodec input Surface                         <- encoder path, zero copy
 * ```
 *
 * The intended implementation, for whoever picks this up:
 *
 *  1. `prepare(w, h)` — create an `EGLContext`/`EGLSurface` via `EGL14`, allocate a texture with
 *     `GLES20.glGenTextures`, wrap it in a `SurfaceTexture`, and hand that `SurfaceTexture` to the
 *     WebView (`WebView.setRenderToSurface` is not public; the practical route is to render the
 *     WebView's hardware layer through `View.draw(Canvas)` onto a `Canvas` from
 *     `surfaceTexture.lockCanvas`, or to use `SurfaceControl`/`GLSurfaceView` composition).
 *  2. On each capture, `updateTexImage()`, then `glFinish()` (or an `EGL_KHR_fence_sync` wait) so the
 *     texture really contains the committed frame.
 *  3. Return `CaptureResult(textureId, w, h, System.nanoTime(), CaptureSource.GL_TEXTURE)`.
 *  4. `release()` — delete the texture and destroy the EGL context, in the right thread with the right
 *     `EGLDisplay` current.
 *
 * ## Why it is a stub rather than a half-working implementation
 *
 * Step 1 has no single correct answer: the WebView's own rendering surface is not exposed to apps, so
 * the scene must be re-composited through a `Canvas` or a `SurfaceControl` transaction, and the choice
 * depends on whether the SDK supports API 26 only or API 29+ (`SurfaceControl` became usable for this
 * in Android 10). Guessing here would produce code that compiles, runs, and silently streams black
 * frames — worse than an explicit stub.
 *
 * ## Behaviour today
 *
 * [prepare] reports `false` with a clear [lastError]; [captureFrame] returns `null`. The pipeline
 * therefore counts every frame as *skipped* instead of crashing, which is what
 * `WatchPlayClient` documents when no capture module is supplied. Use [PixelCopyCapture] to stream
 * real pixels in the meantime.
 *
 * ## Threading
 *
 * Placeholder is trivially thread safe: no state except the reported dimensions and error string.
 */
class GlTextureCapture : CaptureModule {

    override val name: String = NAME

    override var isReady: Boolean = false
        private set

    override var lastError: String? = NOT_IMPLEMENTED
        private set

    override var width: Int = 0
        private set

    override var height: Int = 0
        private set

    /** The texture name once implemented; `-1` while the module is a stub. */
    val textureId: Int
        get() = -1

    override fun prepare(width: Int, height: Int): Boolean {
        require(width > 0 && height > 0) { "capture size must be positive: ${width}x$height" }
        this.width = width
        this.height = height
        lastError = NOT_IMPLEMENTED
        isReady = false
        return false
    }

    override fun captureFrame(timeoutMs: Long): CaptureResult? {
        lastError = NOT_IMPLEMENTED
        return null
    }

    override fun release() {
        width = 0
        height = 0
        isReady = false
        lastError = NOT_IMPLEMENTED
    }

    override fun toString(): String = "GlTextureCapture(stub, ready=$isReady)"

    companion object {
        /** Name used in logs. */
        const val NAME: String = "gl-texture"

        /** Error reported until the GL path exists. */
        const val NOT_IMPLEMENTED: String =
            "GlTextureCapture is not implemented in this skeleton: the WebView -> EGLSurface -> " +
                "texture hand-off is still to be written. Use PixelCopyCapture for a working, " +
                "slower CPU path."
    }
}
