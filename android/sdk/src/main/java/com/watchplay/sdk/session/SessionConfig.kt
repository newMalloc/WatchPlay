package com.watchplay.sdk.session

import com.watchplay.sdk.protocol.ProtocolLimits

/**
 * Phone-side tunables for one session.
 *
 * Defaults follow the reference pairing (`00-overview.md` §6): MJPEG at 10 fps, 244-byte chunks,
 * 247-byte ATT MTU, 64 initial credits — the combination the specification targets for
 * "< 200 ms" and "150–300 KB/s" on an ESP32-S3 watch.
 *
 * @property deviceName name the phone announces in `HELLO_ACK`/`CAPS_ACK`, for the watch's UI.
 * @property sdkVersion version string reported in `HELLO_ACK.sdk_version` (at most 8 ASCII bytes).
 * @property maxFps phone-side frame-rate ceiling, applied on top of the watch's own ceiling.
 * @property preferredQuality JPEG quality the phone would like; clamped into `[40, 85]` and below the
 *   watch's `max_quality` (`04-video.md` §6).
 * @property allowH264 when `false`, Profile B is never selected even if the watch advertises it. The
 *   default is `false` because [com.watchplay.sdk.encoder.H264Encoder] is still a stub, and because
 *   `03-session.md` §4.2 step 2a only selects H.264 when the watch reports **hardware** decoding.
 * @property maxChunkBytesCeiling phone-side ceiling for `max_chunk_bytes`, before the watch's and the
 *   bearer's limits are applied.
 * @property bearerTuBytes configured fallback budget for `max_chunk_bytes`. The live value comes from
 *   [com.watchplay.sdk.transport.TransportModule.maxChunkPayloadBytes] (`MTU − 3` on GATT); this is
 *   the ceiling used when a bearer reports nothing.
 * @property freeQueueSlots receiver queue slots the phone is willing to keep in flight; becomes
 *   `RESULT.initial_credits`, clamped to `16..128` (`03-session.md` §4.2 step 7).
 * @property maxFrameBytes the phone guarantees no encoded frame will exceed this, reported in
 *   `STREAM. max_frame_bytes`; the watch allocates its reassembly buffer once from it
 *   (`04-video.md` §3.3).
 * @property deadlineMs target latency budget for one frame, reported in `STREAM.deadline_ms`.
 * @property heartbeatMs idle `PING` interval, reported in `STREAM.heartbeat_ms`.
 * @property drainTimeoutMs how long `DRAINING` may last before adaptive downshifting.
 * @property pongTimeoutMs how long to wait for a `PONG` before counting it missing.
 * @property keepaliveGraceMs how long video traffic suppresses heartbeats (`03-session.md` §6.2).
 */
data class SessionConfig(
    val deviceName: String = "WatchPlay Phone",
    val sdkVersion: String = DEFAULT_SDK_VERSION,
    val maxFps: Int = 10,
    val preferredQuality: Int = 70,
    val allowH264: Boolean = false,
    val maxChunkBytesCeiling: Int = ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES,
    val bearerTuBytes: Int = ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES,
    val freeQueueSlots: Int = ProtocolLimits.DEFAULT_INITIAL_CREDITS,
    val maxFrameBytes: Long = DEFAULT_MAX_FRAME_BYTES,
    val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    val heartbeatMs: Int = ProtocolLimits.DEFAULT_HEARTBEAT_MS,
    val drainTimeoutMs: Int = ProtocolLimits.DRAIN_TIMEOUT_MS,
    val pongTimeoutMs: Int = ProtocolLimits.DEFAULT_PONG_TIMEOUT_MS,
    val keepaliveGraceMs: Int = ProtocolLimits.DEFAULT_KEEPALIVE_GRACE_MS,
) {

    init {
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
        require(sdkVersion.length <= 8) { "sdkVersion must fit HELLO_ACK's 8-byte field" }
        require(maxFps in ProtocolLimits.MIN_FPS..ProtocolLimits.MAX_FPS) { "maxFps out of range: $maxFps" }
        require(preferredQuality in 1..100) { "preferredQuality out of range: $preferredQuality" }
        require(freeQueueSlots in ProtocolLimits.MIN_INITIAL_CREDITS..ProtocolLimits.MAX_INITIAL_CREDITS) {
            "freeQueueSlots must be in ${ProtocolLimits.MIN_INITIAL_CREDITS}..${ProtocolLimits.MAX_INITIAL_CREDITS}"
        }
        require(maxChunkBytesCeiling >= ProtocolLimits.MIN_MAX_CHUNK_BYTES) {
            "maxChunkBytesCeiling must be at least ${ProtocolLimits.MIN_MAX_CHUNK_BYTES}"
        }
        require(bearerTuBytes > 0) { "bearerTuBytes must be positive" }
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }

    companion object {
        /** Version string reported to the watch. */
        const val DEFAULT_SDK_VERSION: String = "0.1.0"

        /** Frame-reassembly budget promised to the watch: 1 MiB covers a quality-85 466×466 JPEG. */
        const val DEFAULT_MAX_FRAME_BYTES: Long = 1024L * 1024L

        /** Latency budget reported in `STREAM.deadline_ms`, matching the spec's < 200 ms goal. */
        const val DEFAULT_DEADLINE_MS: Long = 200L
    }
}
