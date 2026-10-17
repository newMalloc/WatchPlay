package com.watchplay.sdk.pipeline

import com.watchplay.sdk.protocol.Feedback

/**
 * The sender-side control loop of `04-video.md` §6: it reads `FEEDBACK` and decides what quality and
 * frame rate the *next* frame should use.
 *
 * ```
 *   FEEDBACK.queue_depth / frames_dropped / decode_ms_avg / credits, plus the session's RTT
 *        |
 *        v
 *   [RateControl] --quality--> encoder (no re-prepare, so no stall)
 *                 --fps-----> pipeline cadence
 * ```
 *
 * ## Rules, straight from the spec table
 *
 * The steps (−8/−10/+4 quality, −2/+1 fps, 1 s/2 s/5 s cooldowns) are the spec's numbers, not tunables
 * invented here. Three things are this class's choice, because the spec says "降质量 −8 **或** 降帧率 −2":
 *
 *  1. **quality before frame rate.** Bandwidth is the scarce resource on a BLE link; a lower quality
 *     costs sharpness, a lower frame rate costs reactivity. Walking quality down to [minQuality] first
 *     keeps the panel reacting, and only then gives up cadence.
 *  2. **an extra cooldown where the spec has none.** `FEEDBACK` arrives once per decoded frame, up to
 *     10 Hz. The decode-time rule with no floor would fire ten times in a second and land at 1 fps in
 *     five frames. Each such floor is stated on its rule.
 *  3. **recovery is slow and only to the negotiated values.** +4 quality and +1 fps every 5 s, after
 *     10 s without a symptom, stopping exactly at `RESULT.quality` / `RESULT.target_fps`.
 *
 * Resolution is never touched: §6 forbids changing it mid-session.
 *
 * ## Threading
 *
 * [onFeedback] runs on the session's reader thread; [effectiveQuality] and [targetFps] are read by the
 * pipeline thread. Every field is written under one lock and read whole, so the pipeline never sees
 * half of a quality/fps pair.
 *
 * @property negotiatedQuality `RESULT.quality`, the ceiling recovery climbs back to.
 * @property negotiatedFps `RESULT.target_fps`, the ceiling for recovery.
 * @property initialCredits initial credit window (`RESULT.initial_credits`), the "credits 长期 < 初始值/4"
 *   reference point.
 * @property minQuality hard floor from §6 (`40`).
 * @property maxQuality hard ceiling from §6 (`85`).
 * @property startupFrames how many frames at the start use the reduced quality of §6.1.
 */
class RateControl(
    val negotiatedQuality: Int,
    val negotiatedFps: Int,
    private val initialCredits: Long = DEFAULT_INITIAL_CREDITS,
    val minQuality: Int = MIN_QUALITY,
    val maxQuality: Int = MAX_QUALITY,
    private val startupFrames: Int = STARTUP_FRAMES,
) {

    init {
        require(negotiatedFps >= 1) { "negotiatedFps must be at least 1, was $negotiatedFps" }
        require(minQuality in 1..100 && maxQuality in 1..100) { "quality bounds must be in 1..100" }
        require(minQuality <= maxQuality) { "minQuality $minQuality > maxQuality $maxQuality" }
        require(startupFrames >= 0) { "startupFrames must not be negative, was $startupFrames" }
    }

    private val lock = Any()

    private var steadyQuality: Int = negotiatedQuality.coerceIn(minQuality, maxQuality)
    private var steadyFps: Int = negotiatedFps.coerceAtLeast(1)
    private var framesSent: Long = 0L

    /** Last moment any symptom was seen; recovery counts from here. */
    private var lastSymptomMs: Long = NONE

    private var queueHighSinceMs: Long = NONE
    private var lastQueueShiftMs: Long = NONE

    private var dropWindowStartMs: Long = NONE
    private var dropWindowBaseline: Long = 0L

    private var lastDecodeShiftMs: Long = NONE
    private var lastOverloadShiftMs: Long = NONE

    private var creditLowSinceMs: Long = NONE
    private var lastRttShiftMs: Long = NONE

    private var lastRecoveryMs: Long = NONE

    /** Steady-state quality the loop has settled on; recovery climbs back toward the negotiated value. */
    val quality: Int
        get() = synchronized(lock) { steadyQuality }

    /** Cadence the pipeline should use; never above [negotiatedFps]. */
    val targetFps: Int
        get() = synchronized(lock) { steadyFps }

    /**
     * Quality to encode the next frame with.
     *
     * Differs from [quality] for the first [startupFrames] frames: §6.1 starts 20 % lower so the first
     * seconds cannot overshoot into a visible stall.
     */
    val effectiveQuality: Int
        get() = synchronized(lock) {
            if (framesSent < startupFrames) {
                (steadyQuality * STARTUP_NUMERATOR / STARTUP_DENOMINATOR).coerceIn(minQuality, maxQuality)
            } else {
                steadyQuality
            }
        }

    /** Counts a frame handed to the transport, ending the §6.1 startup window. */
    fun onFrameSent() {
        synchronized(lock) { framesSent++ }
    }

    /**
     * Feeds one `FEEDBACK` into the loop.
     *
     * @param feedback the decoded receiver report.
     * @param rttMs session RTT from `PING`/`PONG`, or a negative value when it is not measured.
     * @param nowMs monotonic arrival time.
     * @return `true` when [quality] or [targetFps] changed.
     */
    fun onFeedback(feedback: Feedback, rttMs: Long, nowMs: Long): Boolean = synchronized(lock) {
        val before = steadyQuality to steadyFps

        // §6 row 1 — queue_depth above 4, sustained for a second, at most once per second.
        if (feedback.queueDepth > QUEUE_HIGH_DEPTH) {
            if (queueHighSinceMs == NONE) queueHighSinceMs = nowMs
            if (nowMs - queueHighSinceMs >= QUEUE_SUSTAIN_MS && due(lastQueueShiftMs, nowMs, QUEUE_SHIFT_COOLDOWN_MS)) {
                shiftQualityDown(QUALITY_STEP, nowMs)
                lastQueueShiftMs = nowMs
                queueHighSinceMs = nowMs
            }
        } else {
            queueHighSinceMs = NONE
        }

        // §6 row 2 — more than 10 drops inside 5 s: immediate, no cooldown, window restarts at the shift.
        if (dropWindowStartMs == NONE) {
            dropWindowStartMs = nowMs
            dropWindowBaseline = feedback.framesDropped
        } else if (nowMs - dropWindowStartMs >= DROP_WINDOW_MS) {
            dropWindowStartMs = nowMs
            dropWindowBaseline = feedback.framesDropped
        }
        if (feedback.framesDropped - dropWindowBaseline > DROP_LIMIT) {
            shiftQualityDown(DROP_QUALITY_STEP, nowMs)
            dropWindowStartMs = nowMs
            dropWindowBaseline = feedback.framesDropped
        }

        // §6 row 3 — decoding takes more than 80 % of a frame interval: give up cadence, not quality.
        val frameIntervalMs = 1000.0 / steadyFps.coerceAtLeast(1)
        if (feedback.decodeMsAvg > DECODE_SLOW_RATIO * frameIntervalMs &&
            due(lastDecodeShiftMs, nowMs, DECODE_SHIFT_COOLDOWN_MS)
        ) {
            shiftFpsDown(FPS_STEP, nowMs)
            lastDecodeShiftMs = nowMs
        }

        // §6 row 4 — RTT above 100 ms, or credits below a quarter of the initial window.
        if (feedback.credits < initialCredits / 4) {
            if (creditLowSinceMs == NONE) creditLowSinceMs = nowMs
        } else {
            creditLowSinceMs = NONE
        }
        val creditsSustainedLow = creditLowSinceMs != NONE && nowMs - creditLowSinceMs >= CREDIT_LOW_SUSTAIN_MS
        val rttHigh = rttMs >= 0 && rttMs > RTT_LIMIT_MS
        if ((rttHigh || creditsSustainedLow) && due(lastRttShiftMs, nowMs, RTT_SHIFT_COOLDOWN_MS)) {
            shiftQualityDown(QUALITY_STEP, nowMs)
            lastRttShiftMs = nowMs
        }

        // §4.3 — OVERLOADED is the receiver's own verdict, so it is a symptom on arrival.
        if (feedback.isOverloaded && due(lastOverloadShiftMs, nowMs, OVERLOAD_SHIFT_COOLDOWN_MS)) {
            shiftQualityDown(QUALITY_STEP, nowMs)
            lastOverloadShiftMs = nowMs
        }

        // §6 recovery — 10 s quiet, then +4 quality and +1 fps, at most once per 5 s.
        if (nowMs - lastSymptomMs >= RECOVERY_IDLE_MS && due(lastRecoveryMs, nowMs, RECOVERY_COOLDOWN_MS)) {
            var recovered = false
            if (steadyQuality < negotiatedQuality) {
                steadyQuality = (steadyQuality + QUALITY_STEP_UP).coerceAtMost(negotiatedQuality)
                recovered = true
            }
            if (steadyFps < negotiatedFps) {
                steadyFps++
                recovered = true
            }
            if (recovered) lastRecoveryMs = nowMs
        }

        before != (steadyQuality to steadyFps)
    }

    /**
     * Cuts quality by another step because the encoded frame exceeded `STREAM.max_frame_bytes`.
     *
     * §6 requires re-encoding smaller rather than sending a frame the receiver will drop. Unlike the
     * symptom rules this fires immediately, because the alternative is losing the whole frame's worth of
     * bandwidth. It also counts as a symptom, so recovery does not climb straight back into the limit.
     *
     * @return the quality to retry with, or `null` when the floor is already reached.
     */
    fun shrinkForOversize(nowMs: Long): Int? = synchronized(lock) {
        if (steadyQuality <= minQuality) {
            null
        } else {
            steadyQuality = (steadyQuality - OVERSIZE_QUALITY_STEP).coerceAtLeast(minQuality)
            lastSymptomMs = nowMs
            steadyQuality
        }
    }

    /** Restores the negotiated values; used when a new session negotiates. */
    fun reset() {
        synchronized(lock) {
            steadyQuality = negotiatedQuality.coerceIn(minQuality, maxQuality)
            steadyFps = negotiatedFps.coerceAtLeast(1)
            framesSent = 0L
            lastSymptomMs = NONE
            queueHighSinceMs = NONE
            lastQueueShiftMs = NONE
            dropWindowStartMs = NONE
            dropWindowBaseline = 0L
            lastDecodeShiftMs = NONE
            lastOverloadShiftMs = NONE
            creditLowSinceMs = NONE
            lastRttShiftMs = NONE
            lastRecoveryMs = NONE
        }
    }

    // ---------------------------------------------------------------- internals

    /** `true` when a rule with a cooldown may fire; a rule that never fired is always due. */
    private fun due(lastMs: Long, nowMs: Long, cooldownMs: Long): Boolean =
        lastMs == NONE || nowMs - lastMs >= cooldownMs

    private fun shiftQualityDown(step: Int, nowMs: Long) {
        if (steadyQuality > minQuality) {
            steadyQuality = (steadyQuality - step).coerceAtLeast(minQuality)
            lastSymptomMs = nowMs
        } else {
            // Quality is at the floor: frame rate is the only lever left (§6 hard constraint).
            shiftFpsDown(FPS_STEP, nowMs)
        }
    }

    private fun shiftFpsDown(step: Int, nowMs: Long) {
        if (steadyFps > MIN_FPS) {
            steadyFps = (steadyFps - step).coerceAtLeast(MIN_FPS)
            lastSymptomMs = nowMs
        }
    }

    override fun toString(): String =
        "RateControl(quality=$quality/$negotiatedQuality, fps=$targetFps/$negotiatedFps, " +
            "effective=$effectiveQuality)"

    companion object {
        /** Sentinel for "this timer has never started". Monotonic time itself is never negative. */
        private const val NONE: Long = -1L

        /** Credited window when the caller does not know better; matches the reference pairing. */
        const val DEFAULT_INITIAL_CREDITS: Long = 64L

        /** §6: quality is hard-clamped to this range. */
        const val MIN_QUALITY: Int = 40

        /** §6: quality is hard-clamped to this range. */
        const val MAX_QUALITY: Int = 85

        /** §6 hard constraint: frame rate never drops below this. */
        const val MIN_FPS: Int = 1

        /** §6 row 1: a first reduction step of −8 quality. */
        const val QUALITY_STEP: Int = 8

        /** §6 row 2: a drop-driven reduction of −10 quality. */
        const val DROP_QUALITY_STEP: Int = 10

        /** §6 recovery: +4 quality. */
        const val QUALITY_STEP_UP: Int = 4

        /** §6: a frame-rate step of −2. */
        const val FPS_STEP: Int = 2

        /** Oversize re-encode granularity; the spec only says "lower the quality". */
        const val OVERSIZE_QUALITY_STEP: Int = 10

        /** §6 row 1: `queue_depth` above this counts as backing up. */
        const val QUEUE_HIGH_DEPTH: Int = 4

        /** §6 row 1: "持续" — one second of a backed-up queue before it costs anything. */
        const val QUEUE_SUSTAIN_MS: Long = 1_000L

        /** §6 row 1: "每 1 s 最多一次". */
        const val QUEUE_SHIFT_COOLDOWN_MS: Long = 1_000L

        /** §6 row 2: the drop-rate window. */
        const val DROP_WINDOW_MS: Long = 5_000L

        /** §6 row 2: more than this many drops in the window trips the rule. */
        const val DROP_LIMIT: Long = 10L

        /** §6 row 3: decoding this share of a frame interval is already too slow. */
        const val DECODE_SLOW_RATIO: Double = 0.8

        /** Floor for the decode-time rule; §6 has no cooldown there, see the class note. */
        const val DECODE_SHIFT_COOLDOWN_MS: Long = 1_000L

        /** §6 row 4: RTT above this means the link is congested. */
        const val RTT_LIMIT_MS: Long = 100L

        /** §6 row 4: "credits 长期低于初始值/4" — one second counts as sustained. */
        const val CREDIT_LOW_SUSTAIN_MS: Long = 1_000L

        /** §6 row 4: "每 2 s". */
        const val RTT_SHIFT_COOLDOWN_MS: Long = 2_000L

        /** §4.3: `OVERLOADED` gets its own floor so a repeated flag cannot compound. */
        const val OVERLOAD_SHIFT_COOLDOWN_MS: Long = 1_000L

        /** §6 recovery: 10 s without a symptom before anything climbs. */
        const val RECOVERY_IDLE_MS: Long = 10_000L

        /** §6 recovery: at most one climb per 5 s. */
        const val RECOVERY_COOLDOWN_MS: Long = 5_000L

        /** §6.1: the first frames run at 80 % of the steady quality. */
        const val STARTUP_NUMERATOR: Int = 4

        /** §6.1: the first frames run at 80 % of the steady quality. */
        const val STARTUP_DENOMINATOR: Int = 5

        /** §6.1: how many frames are "the first frames". */
        const val STARTUP_FRAMES: Int = 3
    }
}
