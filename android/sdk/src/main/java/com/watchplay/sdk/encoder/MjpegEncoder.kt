package com.watchplay.sdk.encoder

import android.graphics.Bitmap
import com.watchplay.sdk.capture.CaptureResult
import com.watchplay.sdk.capture.CaptureSource
import com.watchplay.sdk.protocol.VideoCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Profile A encoder: one complete JPEG per frame, compressed on the CPU.
 *
 * ## Why this implementation is real, while others are stubs
 *
 * MJPEG is the reference profile (`docs/spec/00-overview.md` §2) and it needs no `MediaCodec` state
 * machine: an RGBA buffer goes in, a JPEG comes out. That makes it the one encoder that can be
 * finished without the GL capture path, and it is what makes the demo module stream actual bytes over
 * `LoopbackTransport` today.
 *
 * On a device with a hardware JPEG encoder, a later revision should switch to `MediaCodec` with
 * `image/jpeg` (some SoCs expose it, some do not — the `image/jpeg` encoder is optional in the Android
 * codec list). Until then `Bitmap.compress` uses Skia's libjpeg-turbo, which is fast enough for 10–15
 * fps at 466×466 on any phone made in the last decade.
 *
 * ## Properties that matter for the protocol
 *
 *  * Every frame is a keyframe, so `isKeyframe` is always `true` — losing a frame costs exactly one
 *    frame and never confuses the watch's decoder.
 *  * The output size is whatever JPEG produces, so the chunk count varies per frame; the sender must
 *    read [EncodedFrame.sizeBytes] rather than assume a fixed chunk count.
 *  * A JPEG is self-delimiting (SOI/EOI markers), so the watch can validate a reassembled frame
 *    without any extra length field.
 *
 * ## Threading
 *
 * Mutable state ([bitmap], [profile], [quality]) is written by `prepare`/`setQuality` on the main or
 * session thread and read by `encode` on the pipeline thread. [quality] is `@Volatile` because §6
 * changes it while frames are in flight; the bitmap-swapping invariant is unchanged: the pipeline never
 * calls `encode` concurrently with `prepare`.
 */
class MjpegEncoder : EncoderModule {

    override val name: String = NAME

    override var isReady: Boolean = false
        private set

    override var lastError: String? = null
        private set

    override var width: Int = 0
        private set

    override var height: Int = 0
        private set

    private var profile: EncodeProfile.Mjpeg? = null
    private var bitmap: Bitmap? = null
    private var encodeBuffer: ByteArrayOutputStream? = null
    private var framesEncoded: Long = 0L

    /**
     * JPEG quality used for the next frame.
     *
     * Separate from [profile] because `04-video.md` §6 changes quality mid-session and a re-prepare
     * would recycle the bitmap and cost a frame. Resets to the profile's value on every [prepare].
     */
    @Volatile
    private var quality: Int = EncodeProfile.DEFAULT_JPEG_QUALITY

    override val codec: VideoCodec?
        get() = profile?.codec

    /** Number of frames successfully compressed since [prepare]. */
    val encodedFrameCount: Long
        get() = framesEncoded

    override fun prepare(profile: EncodeProfile, width: Int, height: Int): Boolean {
        require(width > 0 && height > 0) { "encoder size must be positive: ${width}x$height" }
        if (profile !is EncodeProfile.Mjpeg) {
            lastError = "$NAME can only encode EncodeProfile.Mjpeg, was ${profile::class.simpleName}"
            isReady = false
            return false
        }

        release()
        return try {
            this.profile = profile
            this.quality = profile.jpegQuality
            this.width = width
            this.height = height
            this.bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            this.encodeBuffer = ByteArrayOutputStream(width * height / 4)
            framesEncoded = 0L
            isReady = true
            lastError = null
            true
        } catch (error: OutOfMemoryError) {
            lastError = "could not allocate a ${width}x$height RGBA bitmap: ${error.message}"
            isReady = false
            false
        }
    }

    override fun encode(capture: CaptureResult, ptsUs: Long): EncodedFrame? {
        val profile = this.profile
        val bitmap = this.bitmap
        val buffer = this.encodeBuffer
        if (!isReady || profile == null || bitmap == null || buffer == null) {
            lastError = "encode called before a successful prepare()"
            return null
        }
        if (capture.source != CaptureSource.PIXEL_COPY || capture.rgbaBytes == null) {
            lastError = "$NAME needs CPU pixels (CaptureSource.PIXEL_COPY with rgbaBytes); " +
                "got ${capture.source} from the GL path, which requires the H.264/MediaCodec encoder"
            return null
        }
        if (capture.width != width || capture.height != height) {
            lastError = "capture is ${capture.width}x${capture.height} but the encoder is ${width}x$height"
            return null
        }
        val pixels = capture.rgbaBytes
        if (pixels.size < capture.expectedRgbaBytes) {
            lastError = "RGBA buffer is ${pixels.size} bytes, expected ${capture.expectedRgbaBytes}"
            return null
        }

        return try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            buffer.reset()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)) {
                lastError = "Bitmap.compress(JPEG, $quality) returned false"
                return null
            }
            val bytes = buffer.toByteArray()
            if (bytes.isEmpty()) {
                lastError = "JPEG compressor produced an empty frame"
                return null
            }
            framesEncoded++
            lastError = null
            EncodedFrame(
                data = bytes,
                codec = VideoCodec.MJPEG,
                // A JPEG has no inter-frame dependencies: every frame is independently decodable.
                isKeyframe = true,
                ptsUs = ptsUs,
                width = width,
                height = height,
                quality = quality,
            )
        } catch (error: IllegalArgumentException) {
            // Raised by Bitmap when the buffer geometry does not match the bitmap.
            lastError = "RGBA buffer geometry mismatch: ${error.message}"
            null
        } finally {
            // The bitmap is owned by this encoder and reused; the caller's array is never retained.
            buffer.reset()
        }
    }

    /** No-op: every MJPEG frame is already a keyframe. */
    override fun requestKeyframe() = Unit

    /**
     * Applies a §6 quality change to the next frame.
     *
     * No re-prepare, no bitmaps recycled, no keyframe: a JPEG is independent, so the switch is exact.
     */
    override fun setQuality(quality: Int): Boolean {
        if (quality !in 1..100) {
            lastError = "JPEG quality out of range: $quality"
            return false
        }
        if (!isReady) {
            lastError = "setQuality($quality) called before a successful prepare()"
            return false
        }
        this.quality = quality
        return true
    }

    /** JPEG quality in use for the next frame. */
    val currentQuality: Int
        get() = quality

    override fun release() {
        isReady = false
        bitmap?.recycle()
        bitmap = null
        encodeBuffer = null
        profile = null
        width = 0
        height = 0
    }

    override fun toString(): String =
        "MjpegEncoder(ready=$isReady, ${width}x$height, q=$quality, frames=$framesEncoded)"

    companion object {
        /** Name used in logs. */
        const val NAME: String = "mjpeg-cpu"
    }
}
