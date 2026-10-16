package com.watchplay.sdk.encoder

import com.watchplay.sdk.capture.CaptureResult
import com.watchplay.sdk.protocol.VideoCodec

/**
 * **Not implemented yet — Profile B (`MediaCodec` H.264) is a stub.**
 *
 * ## What it is supposed to do
 *
 * Profile B is the bandwidth-friendly mode for watches with an H.264 decoder:
 *
 *  1. `prepare(profile, w, h)` — `MediaCodec.createEncoderByType("video/avc")` and configure it with
 *     `MediaFormat.KEY_COLOR_FORMAT = COLOR_FormatSurface`,
 *     `KEY_BITRATE_MODE = BITRATE_MODE_CBR`, `KEY_BIT_RATE`, `KEY_FRAME_RATE`,
 *     `KEY_I_FRAME_INTERVAL = gopFrames / fps`, and for [EncodeProfile.H264.baseline]
 *     `KEY_PROFILE = AVCProfileBaseline` plus `KEY_LEVEL`. Then `createInputSurface()`, which is the
 *     zero-copy path: the GL capture renders straight into the encoder.
 *  2. `encode(...)` — signal end-of-input-frame on the surface, then drain output buffers. Convert the
 *     `BUFFER_FLAG_KEY_FRAME` codec flag into [EncodedFrame.isKeyframe] and the buffer's
 *     `presentationTimeUs` into `ptsUs`. Do not return until the access unit is complete; a partial
 *     access unit would look like corruption to the watch.
 *  3. `requestKeyframe()` — `setParameters(Bundle with KEY_REQUEST_SYNCHRONIZE = 1)`.
 *  4. `release()` — `stop()`/`release()` the codec and the input surface.
 *
 * ## Why it cannot be finished yet
 *
 * Step 1 needs a `Surface` to render into, which only exists once
 * [com.watchplay.sdk.capture.GlTextureCapture] is real. Feeding RGBA buffers from `PixelCopy` instead
 * would work (`COLOR_FormatYUV420Flexible` plus a colour conversion), but that conversion costs more
 * CPU than the MJPEG path it would replace, so it is deliberately not written: it would be slower than
 * Profile A while pretending to be Profile B.
 *
 * ## Behaviour today
 *
 * [prepare] returns `false` with a clear [lastError] for every input; [encode] returns `null`. The
 * session negotiator therefore falls back to MJPEG, which is also what the reference hardware wants.
 *
 * ## Threading
 *
 * Placeholder holds no state beyond the failure string; safe from any thread.
 */
class H264Encoder : EncoderModule {

    override val name: String = NAME

    override var isReady: Boolean = false
        private set

    override var lastError: String? = NOT_IMPLEMENTED
        private set

    override var width: Int = 0
        private set

    override var height: Int = 0
        private set

    override val codec: VideoCodec?
        get() = null

    override fun prepare(profile: EncodeProfile, width: Int, height: Int): Boolean {
        require(width > 0 && height > 0) { "encoder size must be positive: ${width}x$height" }
        if (profile !is EncodeProfile.H264) {
            lastError = "$NAME only encodes EncodeProfile.H264, was ${profile::class.simpleName}"
            return false
        }
        this.width = width
        this.height = height
        lastError = NOT_IMPLEMENTED
        isReady = false
        return false
    }

    override fun encode(capture: CaptureResult, ptsUs: Long): EncodedFrame? {
        lastError = NOT_IMPLEMENTED
        return null
    }

    override fun requestKeyframe() = Unit

    override fun release() {
        isReady = false
        width = 0
        height = 0
        lastError = NOT_IMPLEMENTED
    }

    override fun toString(): String = "H264Encoder(stub, ready=$isReady)"

    companion object {
        /** Name used in logs. */
        const val NAME: String = "h264-mediacodec"

        /** Error reported until the MediaCodec path exists. */
        const val NOT_IMPLEMENTED: String =
            "H264Encoder is not implemented in this skeleton: it needs MediaCodec with an input " +
                "Surface from the (also unimplemented) GlTextureCapture. MJPEG is the reference " +
                "profile; see docs/spec/00-overview.md section 2."
    }
}
