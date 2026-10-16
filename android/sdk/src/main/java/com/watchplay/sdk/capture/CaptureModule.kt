package com.watchplay.sdk.capture

/**
 * Produces encodable frames from whatever is displaying the watch scene.
 *
 * ## Contract
 *
 * ```
 *   prepare(w, h)        -> allocate GPU/CPU resources for that panel size; false means "cannot"
 *   captureFrame(timeout)-> one frame, or null when nothing new was painted in time
 *   release()            -> free everything; the module must not be used again
 * ```
 *
 * `captureFrame` returning `null` is **normal**, not an error: the pipeline polls it once per target
 * frame interval, and a scene that has not repainted since the previous capture has nothing new to
 * send. Implementations therefore must never block longer than `timeout` and must never throw for a
 * recoverable condition — they set [lastError] and return `null`, because a thrown exception inside
 * the pipeline thread would kill the stream.
 *
 * ## Why not MediaProjection
 *
 * WatchPlay never records the phone's screen. The scene is drawn by the app itself, so it can be
 * captured directly from its own surface — no permission prompt, no privacy-sensitive API, and no
 * scaling from the phone's native resolution (spec `00-overview.md` §1).
 *
 * ## Implementations
 *
 *  * [GlTextureCapture] — the real path: the WebView paints into an `EGLSurface` backed by a texture.
 *  * [PixelCopyCapture] — compatibility path: copies the pixels of a `View`/`Window` on the CPU.
 *
 * ## Threading
 *
 * `prepare`/`release` are called from the main thread; `captureFrame` is called from the pipeline
 * thread. Implementations must make that safe (the shipped ones use only volatile state plus a latch).
 */
interface CaptureModule {

    /** Short implementation name for logs, e.g. `gl-texture`. */
    val name: String

    /** `true` once [prepare] succeeded and the module has not been released. */
    val isReady: Boolean

    /** Last recoverable failure, or `null`. Never used for control flow. */
    val lastError: String?

    /** Current capture width in pixels; `0` before [prepare]. */
    val width: Int

    /** Current capture height in pixels; `0` before [prepare]. */
    val height: Int

    /**
     * Allocates whatever [captureFrame] needs for a [width] x [height] frame.
     *
     * @return `true` on success. On failure, [lastError] explains why and [isReady] stays `false`; the
     *   pipeline then treats every frame as "skipped" instead of crashing.
     */
    fun prepare(width: Int, height: Int): Boolean

    /**
     * Captures one frame.
     *
     * @param timeoutMs how long to wait for a fresh paint; `0` means "return immediately with whatever
     *   is available, or `null`".
     * @return the captured frame, or `null` when nothing could be captured.
     */
    fun captureFrame(timeoutMs: Long = 0L): CaptureResult?

    /** Releases GPU/CPU resources. Safe to call more than once. */
    fun release()
}
