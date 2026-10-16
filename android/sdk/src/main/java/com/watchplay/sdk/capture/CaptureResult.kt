package com.watchplay.sdk.capture

/**
 * Where a captured frame's pixels came from.
 *
 * The distinction matters downstream: a GL texture can be handed to `MediaCodec` with
 * `createInputSurface` and never leaves the GPU, while a CPU bitmap must be copied and compressed in
 * software. The pipeline's achievable frame rate differs by an order of magnitude between the two.
 */
enum class CaptureSource {

    /** The frame lives in an OpenGL ES texture owned by [GlTextureCapture]. Zero-copy path. */
    GL_TEXTURE,

    /** The frame was copied into a CPU bitmap by [PixelCopyCapture]. Compatibility path. */
    PIXEL_COPY,
}

/**
 * One rendered watch frame, ready to be encoded.
 *
 * @property textureId OpenGL texture name holding the frame, or `-1` for a CPU capture. Only
 *   meaningful to an encoder that shares the capture's EGL context.
 * @property width frame width in pixels; equals the negotiated panel width.
 * @property height frame height in pixels.
 * @property timestampNs capture time from a monotonic clock (`System.nanoTime`-compatible), used for
 *   end-to-end latency accounting. Never wall-clock time — that can jump.
 * @property source which capture path produced this frame.
 * @property rgbaBytes optional tightly packed RGBA8888 pixels, row-major, `4 * width * height` bytes.
 *   Present on the [CaptureSource.PIXEL_COPY] path and consumed by CPU encoders such as
 *   [com.watchplay.sdk.encoder.MjpegEncoder]; `null` on the GPU path, where the pixels never leave the
 *   texture.
 */
data class CaptureResult(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val source: CaptureSource = CaptureSource.GL_TEXTURE,
    val rgbaBytes: ByteArray? = null,
) {

    /** `true` when the result carries something an encoder can actually use. */
    val isUsable: Boolean
        get() = width > 0 && height > 0 && (textureId >= 0 || rgbaBytes != null)

    /** Expected size of [rgbaBytes] for this frame, in bytes. */
    val expectedRgbaBytes: Int
        get() = width * height * 4

    override fun toString(): String =
        "CaptureResult(${source.name}, texture=$textureId, ${width}x$height, " +
            "ts=$timestampNs, rgba=${rgbaBytes?.size ?: 0}B)"
}
