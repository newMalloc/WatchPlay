package com.watchplay.sdk.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.View
import android.view.Window
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CPU capture of an on-screen view or window via [PixelCopy] — the documented fallback path.
 *
 * ## When to use it
 *
 * [GlTextureCapture] is the intended path, but it does not exist yet, and a scene shown in an ordinary
 * view hierarchy has pixels that can simply be read back. `PixelCopy` reads the composited surface
 * directly:
 *
 *  * no permission is required (unlike `MediaProjection`),
 *  * it exists from API 24, i.e. on every device the SDK supports (`minSdk 26`), including the
 *    `View` overload used here,
 *  * it copies real, already-composited pixels, so there is no `glReadPixels` round trip.
 *
 * The cost is bandwidth: every frame lands in a CPU bitmap and is compressed in software, which is why
 * this path is documented as "lower fps" and is not the default for a production stream.
 *
 * ## Caveats, stated plainly
 *
 *  * **This implementation has not been run on a device.** It is written against the API 26
 *    `PixelCopy.request(View|Window, Rect, Bitmap, listener, handler)` contract and cannot be covered
 *    by JVM unit tests, so the first device run is the real test.
 *  * `PixelCopy` copies what the window composited. A `WebView` renders through a child surface in the
 *    renderer process, and on some devices/versions that content comes back **black**. If the mirrored
 *    image is black while the local view looks fine, that is the cause — not the encoder.
 *  * The source must be attached, laid out and at least partially visible; otherwise [captureFrame]
 *    returns `null` and sets [lastError].
 *
 * ## Threading
 *
 * [prepare] creates a private `HandlerThread` that `PixelCopy` uses as its callback looper;
 * [captureFrame] blocks the calling (pipeline) thread on a latch for at most `timeoutMs`, so the
 * callback must not run on that thread. One capture at a time is assumed, matching the pipeline's
 * single-threaded design.
 *
 * @property source what to copy: a [Source.FromView] or a [Source.FromWindow].
 */
class PixelCopyCapture(
    private val source: Source,
) : CaptureModule {

    /** What to capture pixels from. */
    sealed interface Source {
        /**
         * Copy the whole visible area of a view. Coordinates are handled by the `View` overload of
         * `PixelCopy`, which resolves the view's window and scroll offsets itself.
         */
        class FromView(val view: View) : Source

        /**
         * Copy an explicit rectangle of a window, in window coordinates.
         *
         * @property rect area to copy; `null` means "the whole decor view".
         */
        class FromWindow(val window: Window, val rect: Rect? = null) : Source
    }

    override val name: String = NAME

    override var isReady: Boolean = false
        private set

    override var lastError: String? = null
        private set

    override var width: Int = 0
        private set

    override var height: Int = 0
        private set

    private var bitmap: Bitmap? = null
    private var copyThread: HandlerThread? = null
    private var copyHandler: Handler? = null

    override fun prepare(width: Int, height: Int): Boolean {
        require(width > 0 && height > 0) { "capture size must be positive: ${width}x$height" }
        release()
        this.width = width
        this.height = height
        return try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val thread = HandlerThread("watchplay-pixelcopy")
            thread.start()
            copyThread = thread
            copyHandler = Handler(thread.looper)
            isReady = true
            lastError = null
            true
        } catch (error: OutOfMemoryError) {
            // A 466x466 ARGB_8888 bitmap is ~870 KiB; the budget still belongs to the app.
            lastError = "could not allocate a ${width}x$height capture bitmap: ${error.message}"
            isReady = false
            false
        }
    }

    override fun captureFrame(timeoutMs: Long): CaptureResult? {
        val target = bitmap
        val handler = copyHandler
        if (!isReady || target == null || handler == null) {
            lastError = "captureFrame called before a successful prepare()"
            return null
        }

        val latch = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val listener = PixelCopy.OnPixelCopyFinishedListener { copyResult ->
            succeeded.set(copyResult == PixelCopy.SUCCESS)
            latch.countDown()
        }

        try {
            when (val current = source) {
                is Source.FromView -> {
                    val view = current.view
                    if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                        lastError = "source view is not laid out yet (${view.width}x${view.height})"
                        return null
                    }
                    PixelCopy.request(
                        view,
                        Rect(0, 0, view.width, view.height),
                        target,
                        listener,
                        handler,
                    )
                }

                is Source.FromWindow -> {
                    val decor = current.window.decorView
                    val rect = current.rect ?: Rect(0, 0, decor.width, decor.height)
                    if (rect.isEmpty) {
                        lastError = "source rect is empty"
                        return null
                    }
                    PixelCopy.request(current.window, rect, target, listener, handler)
                }
            }
        } catch (error: IllegalArgumentException) {
            // Empty/out-of-bounds rect, or a window that is no longer visible.
            lastError = "PixelCopy rejected the request: ${error.message}"
            return null
        }

        val finished = try {
            latch.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            lastError = "capture interrupted"
            return null
        }
        if (!finished) {
            lastError = "PixelCopy did not finish within ${timeoutMs}ms"
            return null
        }
        if (!succeeded.get()) {
            lastError = "PixelCopy returned a failure code (no source data, or the window is gone)"
            return null
        }

        val pixels = ByteArray(width * height * 4)
        target.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
        lastError = null
        return CaptureResult(
            textureId = -1,
            width = width,
            height = height,
            timestampNs = System.nanoTime(),
            source = CaptureSource.PIXEL_COPY,
            rgbaBytes = pixels,
        )
    }

    override fun release() {
        isReady = false
        bitmap?.recycle()
        bitmap = null
        copyThread?.quitSafely()
        copyThread = null
        copyHandler = null
        width = 0
        height = 0
    }

    override fun toString(): String =
        "PixelCopyCapture(${source::class.simpleName}, ready=$isReady, ${width}x$height)"

    companion object {
        /** Name used in logs. */
        const val NAME: String = "pixel-copy"
    }
}
