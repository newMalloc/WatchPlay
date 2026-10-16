package com.watchplay.sdk.touch

import com.watchplay.sdk.protocol.ProtocolLimits
import com.watchplay.sdk.protocol.ScreenShape
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.util.MonotonicClock
import kotlin.math.sqrt

/**
 * A point in the scene's CSS pixel space.
 *
 * @property x horizontal position.
 * @property y vertical position.
 */
data class CssPoint(val x: Float, val y: Float)

/**
 * Maps `TOUCH` events from watch physical pixels to the WebView's CSS pixel space, enforcing the
 * sequence and geometry rules of `05-input.md` and `06-rendering.md` §4.
 *
 * ## The mapping
 *
 * `05-input.md` §2.3 gives the normative transform:
 *
 * ```
 * scale_x = css_viewport_width  / negotiated_width
 * scale_y = css_viewport_height / negotiated_height
 * css_x   = clamp(x * scale_x, 0, css_viewport_width  - 1)
 * css_y   = clamp(y * scale_y, 0, css_viewport_height - 1)
 * ```
 *
 * In the common case — the WebView created with `densityDpi = 160` and laid out at exactly
 * `width × height` CSS pixels, no scroll, no zoom — `scale_x = scale_y = 1` and the mapping is the
 * identity. `06-rendering.md` §3.2 requires the phone to pin that case, and this class makes it the
 * default so the fast path is also the tested path.
 *
 * ## Geometry rules
 *
 *  * A `DOWN` outside the inscribed circle of a round panel is dropped, and **the whole sequence is
 *    ignored until the next `DOWN`** (`05-input.md` §2.2, phone side). Touch panels report corner
 *    contacts that physically do not exist on a round display.
 *  * A `MOVE` that leaves the circle is *not* turned into an `UP` — that would end the user's drag. It
 *    is projected radially onto the 0.98·r tolerance band and marked `clamped`
 *    (`06-rendering.md` §4, `05-input.md` §2.2).
 *  * Raw coordinates at or beyond the panel edge are clamped into the viewport, because a WebView hit
 *    test with out-of-bounds coordinates can select an unrelated element (`05-input.md` §2.2).
 *  * Coordinates stay floating point all the way to the hit test (`06-rendering.md` §4).
 *
 * ## Sequence rules
 *
 * `05-input.md` §3: a `MOVE` without a preceding `DOWN` must be dropped; `UP`/`CANCEL` end a sequence;
 * a `DOWN` that never receives an `UP` must be recovered after
 * [ProtocolLimits.STUCK_TOUCH_TIMEOUT_MS] by synthesising a cancel — see [checkStuck], which the
 * session tick should call.
 *
 * ## Purity
 *
 * No Android types are referenced, so the whole thing is unit-testable on the JVM. That matters: an
 * off-by-one here is a UI bug that only shows up as "the wrong button sometimes activates".
 *
 * @property layout panel geometry.
 * @property cssViewportWidthPx WebView viewport width in CSS pixels; defaults to the panel width, the
 *   1:1 case required by `06-rendering.md` §3.2.
 * @property cssViewportHeightPx WebView viewport height in CSS pixels.
 */
class TouchHandler(
    val layout: WatchLayoutSpec,
    val cssViewportWidthPx: Float = layout.widthPx.toFloat(),
    val cssViewportHeightPx: Float = layout.heightPx.toFloat(),
) {

    init {
        require(cssViewportWidthPx > 0f) { "cssViewportWidthPx must be positive" }
        require(cssViewportHeightPx > 0f) { "cssViewportHeightPx must be positive" }
    }

    /** Horizontal CSS pixels per watch pixel. */
    val scaleX: Float
        get() = cssViewportWidthPx / layout.widthPx

    /** Vertical CSS pixels per watch pixel. */
    val scaleY: Float
        get() = cssViewportHeightPx / layout.heightPx

    /** `true` when the mapping is the identity, i.e. the 1:1 rendering case. */
    val isIdentityMapping: Boolean
        get() = layout.widthPx.toFloat() == cssViewportWidthPx && layout.heightPx.toFloat() == cssViewportHeightPx

    /** Circle centre used for round-panel validity: `((W-1)/2, (H-1)/2)` (`05-input.md` §2.2). */
    val circleCentreX: Float
        get() = (layout.widthPx - 1) / 2f

    /** Circle centre Y, see [circleCentreX]. */
    val circleCentreY: Float
        get() = (layout.heightPx - 1) / 2f

    /** Inscribed-circle radius: `min(W, H) / 2`. */
    val circleRadius: Float
        get() = layout.minSidePx / 2f

    /** Number of `DOWN`s dropped for falling outside the circle. */
    var outOfCircleDowns: Long = 0L
        private set

    /** Number of coordinates projected onto the tolerance band or the viewport bounds. */
    var clampedCoordinates: Long = 0L
        private set

    /** Number of events dropped for arriving without a `DOWN`. */
    var orphanEvents: Long = 0L
        private set

    /** Number of sequences ended by the stuck-touch guard. */
    var stuckTouchRecoveries: Long = 0L
        private set

    private var sequenceActive = false
    private var sequenceSuppressed = false
    private var sequenceStartedAtMs = 0L
    private var activePointerId = 0

    /** `true` while a `DOWN` has been dispatched and no `UP`/`CANCEL` has followed. */
    val isSequenceActive: Boolean
        get() = sequenceActive

    /** `true` when the current sequence is being ignored (`DOWN` landed outside the circle). */
    val isSequenceSuppressed: Boolean
        get() = sequenceSuppressed

    /** `true` when the watch pixel lies inside the inscribed circle, or the panel is not round. */
    fun isInsideCircle(x: Float, y: Float): Boolean {
        if (layout.shape != ScreenShape.ROUND) return true
        val dx = x - circleCentreX
        val dy = y - circleCentreY
        return dx * dx + dy * dy <= circleRadius * circleRadius
    }

    /**
     * Maps a watch pixel to a CSS pixel using the normative transform, clamping into the viewport.
     *
     * @param projectIntoCircle when `true` (the `MOVE` case) a point outside the circle is first
     *   projected radially onto the 0.98·r tolerance band; when `false` the raw coordinate is used.
     */
    fun mapToCss(x: Float, y: Float, projectIntoCircle: Boolean = false): CssPoint {
        var sourceX = x
        var sourceY = y
        if (projectIntoCircle && layout.shape == ScreenShape.ROUND) {
            val projected = projectOntoCircleBand(x, y)
            if (projected != null) {
                sourceX = projected.x
                sourceY = projected.y
                clampedCoordinates++
            }
        }
        val rawCssX = sourceX * scaleX
        val rawCssY = sourceY * scaleY
        val cssX = rawCssX.coerceIn(0f, cssViewportWidthPx - 1f)
        val cssY = rawCssY.coerceIn(0f, cssViewportHeightPx - 1f)
        if (cssX != rawCssX || cssY != rawCssY) clampedCoordinates++
        return CssPoint(cssX, cssY)
    }

    /** Inverse of [mapToCss] without clamping, for diagnostics and round-trip tests. */
    fun toWatchPx(cssX: Float, cssY: Float): CssPoint = CssPoint(cssX / scaleX, cssY / scaleY)

    /**
     * Feeds one raw event through the geometry and sequence rules.
     *
     * Stateless callers can ignore the sequence tracking; a `MirrorApp` should not, because the WebView
     * needs a coherent `DOWN → MOVE → UP` stream.
     *
     * @param receivedAtMs **phone-side** monotonic milliseconds at which the frame was received. The
     *   event's own `timestampMs` is the watch's clock and cannot be compared with the phone's, so it is
     *   used only for gesture-internal timing (`05-input.md` §6).
     */
    fun handle(event: TouchEvent, receivedAtMs: Long = MonotonicClock.nowMs()): TouchOutcome {
        when (event.action) {
            TouchAction.DOWN -> return handleDown(event, receivedAtMs)

            TouchAction.MOVE -> {
                if (sequenceSuppressed) return TouchOutcome.Dropped(TouchDropReason.SEQUENCE_SUPPRESSED)
                if (!sequenceActive) return dropOrphan()
                val outside = !isInsideCircle(event.x.toFloat(), event.y.toFloat())
                val point = mapToCss(event.x.toFloat(), event.y.toFloat(), projectIntoCircle = outside)
                return TouchOutcome.Dispatch(mapped(event, point, outsideCircle = outside, clamped = outside))
            }

            TouchAction.UP,
            TouchAction.CANCEL,
            TouchAction.LONG_PRESS,
            TouchAction.SWIPE_START,
            TouchAction.SWIPE_END,
            -> {
                if (sequenceSuppressed) {
                    if (endsSequence(event.action)) clearSequence()
                    return TouchOutcome.Dropped(TouchDropReason.SEQUENCE_SUPPRESSED)
                }
                if (!sequenceActive) return dropOrphan()
                val outside = !isInsideCircle(event.x.toFloat(), event.y.toFloat())
                val point = mapToCss(event.x.toFloat(), event.y.toFloat(), projectIntoCircle = outside)
                if (endsSequence(event.action)) clearSequence()
                return TouchOutcome.Dispatch(mapped(event, point, outsideCircle = outside, clamped = outside))
            }
        }
    }

    /**
     * Stuck-touch guard: `05-input.md` §3.1 requires the phone to synthesise a cancel if a `DOWN` has
     * not been followed by `UP`/`CANCEL` within
     * [ProtocolLimits.STUCK_TOUCH_TIMEOUT_MS] (10 s), otherwise the scene stays visually pressed.
     *
     * @return the cancel to dispatch, or `null` when nothing is stuck.
     */
    fun checkStuck(nowMs: Long): MappedTouch? {
        if (!sequenceActive) return null
        val elapsed = nowMs - sequenceStartedAtMs
        if (elapsed <= ProtocolLimits.STUCK_TOUCH_TIMEOUT_MS) return null
        stuckTouchRecoveries++
        val point = CssPoint(cssViewportWidthPx / 2f, cssViewportHeightPx / 2f)
        clearSequence()
        return MappedTouch(
            action = TouchAction.CANCEL,
            cssX = point.x,
            cssY = point.y,
            pressure = 0f,
            pointerId = activePointerId,
            timestampMs = nowMs,
        )
    }

    /** Forgets any in-flight sequence, e.g. after a disconnect or a scene change (`05-input.md` §5). */
    fun resetSequence() {
        clearSequence()
    }

    override fun toString(): String =
        "TouchHandler(${layout.shape.name} ${layout.widthPx}x${layout.heightPx}, " +
            "css=${cssViewportWidthPx}x$cssViewportHeightPx, scale=${scaleX}x$scaleY, " +
            "identity=$isIdentityMapping, sequenceActive=$sequenceActive)"

    private fun handleDown(event: TouchEvent, receivedAtMs: Long): TouchOutcome {
        // A DOWN always starts a new sequence, unless it is invalid.
        if (event.isMarkedOutsideCircle) {
            outOfCircleDowns++
            sequenceActive = false
            sequenceSuppressed = true
            return TouchOutcome.Dropped(TouchDropReason.MARKED_OUTSIDE_CIRCLE)
        }
        val x = event.x.toFloat()
        val y = event.y.toFloat()
        if (!isInsideCircle(x, y)) {
            outOfCircleDowns++
            sequenceActive = false
            sequenceSuppressed = true
            return TouchOutcome.Dropped(TouchDropReason.DOWN_OUTSIDE_CIRCLE)
        }
        sequenceActive = true
        sequenceSuppressed = false
        sequenceStartedAtMs = receivedAtMs
        activePointerId = event.pointerId
        return TouchOutcome.Dispatch(mapped(event, mapToCss(x, y), outsideCircle = false, clamped = false))
    }

    private fun dropOrphan(): TouchOutcome {
        orphanEvents++
        return TouchOutcome.Dropped(TouchDropReason.SEQUENCE_NOT_STARTED)
    }

    private fun mapped(
        event: TouchEvent,
        point: CssPoint,
        outsideCircle: Boolean,
        clamped: Boolean,
    ): MappedTouch = MappedTouch(
        action = event.action,
        cssX = point.x,
        cssY = point.y,
        pressure = event.pressureFraction,
        pointerId = event.pointerId,
        timestampMs = event.timestampMs,
        outsideCircle = outsideCircle,
        nearEdge = event.isNearEdge,
        clamped = clamped,
    )

    private fun endsSequence(action: TouchAction): Boolean =
        action == TouchAction.UP || action == TouchAction.CANCEL

    private fun clearSequence() {
        sequenceActive = false
        sequenceSuppressed = false
        sequenceStartedAtMs = 0L
        activePointerId = 0
    }

    /**
     * Projects an out-of-circle point radially onto the 0.98·r band (`06-rendering.md` §4).
     *
     * @return the projected point, or `null` when the point is already inside `0.98·r`.
     */
    private fun projectOntoCircleBand(x: Float, y: Float): CssPoint? {
        val dx = x - circleCentreX
        val dy = y - circleCentreY
        val distance = sqrt(dx * dx + dy * dy)
        val target = circleRadius * CIRCLE_TOLERANCE_FRACTION
        if (distance <= target) return null
        if (distance == 0f) return CssPoint(circleCentreX, circleCentreY)
        val factor = target / distance
        return CssPoint(circleCentreX + dx * factor, circleCentreY + dy * factor)
    }

    companion object {
        /**
         * Fraction of the radius inside which clamped touches are projected: `0.98`
         * (`06-rendering.md` §4, "boundary tolerance band").
         */
        const val CIRCLE_TOLERANCE_FRACTION: Float = 0.98f
    }
}
