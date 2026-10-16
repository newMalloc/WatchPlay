package com.watchplay.sdk.touch

import com.watchplay.sdk.protocol.LittleEndian
import com.watchplay.sdk.protocol.ProtocolException
import com.watchplay.sdk.protocol.ProtocolLimits
import com.watchplay.sdk.protocol.TouchFlags

/**
 * What the finger did — `05-input.md` §1.1.
 *
 * `LONG_PRESS` / `SWIPE_START` / `SWIPE_END` are optional: the protocol prefers raw
 * `DOWN`/`MOVE`/`UP` and lets the phone's WebView do gesture recognition, because reimplementing
 * browser gesture semantics on a watch MCU produces inconsistencies. A phone implementation must still
 * decode them.
 */
enum class TouchAction(
    /** Wire value in the `TOUCH` payload. */
    val code: Int,
) {
    /** `0x00` — a new touch sequence begins. */
    DOWN(0x00),

    /** `0x01` — movement between down and up. Idempotent, so losing one is harmless. */
    MOVE(0x01),

    /** `0x02` — the sequence ends. */
    UP(0x02),

    /** `0x03` — the sequence was cancelled; the phone must treat it as `UP` and drop any click. */
    CANCEL(0x03),

    /** `0x04` — optional watch-side long press (>= 500 ms). */
    LONG_PRESS(0x04),

    /** `0x05` — optional watch-side swipe start. */
    SWIPE_START(0x05),

    /** `0x06` — optional watch-side swipe end. */
    SWIPE_END(0x06);

    companion object {
        /** Resolves a wire value, or `null` when the action is unknown to this SDK version. */
        fun fromCode(code: Int): TouchAction? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A `TOUCH` event exactly as it arrives: **watch physical display pixels**, origin top-left
 * (`05-input.md` §1, §2.1).
 *
 * Field layout (14 bytes, little-endian):
 *
 * ```
 * 0  action      u8
 * 1  pointer_id  u8
 * 2  x           u16   physical pixels, 0..W-1
 * 4  y           u16
 * 6  pressure    u8    0 = unsupported, 1..255 increasing
 * 7  flags       u8    TouchFlags
 * 8  reserved    u16   MUST be 0
 * 10 device_ts   u32   watch monotonic clock, low 32 bits
 * ```
 *
 * @property action what the finger did.
 * @property pointerId contact id; always `0` for single-touch panels.
 * @property x horizontal position in watch physical pixels.
 * @property y vertical position in watch physical pixels.
 * @property pressure raw `0..255`; use [pressureFraction] for a normalised value.
 * @property flags [TouchFlags] bits.
 * @property timestampMs the **watch's** monotonic clock, not Unix time and not comparable with the
 *   phone's clock. Only differences within one gesture are meaningful (`05-input.md` §6).
 */
data class TouchEvent(
    val action: TouchAction,
    val pointerId: Int = 0,
    val x: Int,
    val y: Int,
    val pressure: Int = 0,
    val flags: Int = 0,
    val timestampMs: Long = 0L,
) {

    init {
        require(x in 0..0xFFFF) { "x out of u16 range: $x" }
        require(y in 0..0xFFFF) { "y out of u16 range: $y" }
        require(pointerId in 0..0xFF) { "pointerId out of u8 range: $pointerId" }
        require(pressure in 0..0xFF) { "pressure out of u8 range: $pressure" }
        require(flags in 0..0xFF) { "flags out of u8 range: $flags" }
    }

    /** Pressure normalised to `0.0..1.0`; `0.0` when the panel does not sense pressure. */
    val pressureFraction: Float
        get() = pressure / 255f

    /** `true` when the watch marked the point as outside the inscribed circle. */
    val isMarkedOutsideCircle: Boolean
        get() = flags and TouchFlags.OUTSIDE_CIRCLE != 0

    /** `true` when the watch marked the point as within 5% of the panel edge. */
    val isNearEdge: Boolean
        get() = flags and TouchFlags.NEAR_EDGE != 0

    /** Serialises the fixed 14-byte payload. */
    fun encode(): ByteArray {
        val out = ByteArray(ProtocolLimits.TOUCH_PAYLOAD_SIZE)
        LittleEndian.writeU8(out, 0, action.code)
        LittleEndian.writeU8(out, 1, pointerId)
        LittleEndian.writeU16(out, 2, x)
        LittleEndian.writeU16(out, 4, y)
        LittleEndian.writeU8(out, 6, pressure)
        LittleEndian.writeU8(out, 7, flags)
        LittleEndian.writeU16(out, 8, 0) // reserved, MUST be 0
        LittleEndian.writeU32(out, 10, timestampMs and 0xFFFFFFFFL)
        return out
    }

    override fun toString(): String =
        "TouchEvent(${action.name}, $x,$y, pointer=$pointerId, pressure=$pressure, " +
            "flags=0x%02X, deviceTs=$timestampMs)".format(flags)

    companion object {
        /** Parses a `TOUCH` payload. */
        fun decode(payload: ByteArray, offset: Int = 0): TouchEvent {
            if (offset < 0 || offset + ProtocolLimits.TOUCH_PAYLOAD_SIZE > payload.size) {
                throw ProtocolException(
                    "TOUCH needs exactly ${ProtocolLimits.TOUCH_PAYLOAD_SIZE} bytes, got ${payload.size - offset}",
                )
            }
            val actionCode = LittleEndian.readU8(payload, offset)
            val action = TouchAction.fromCode(actionCode)
                ?: throw ProtocolException("unknown touch action 0x%02X".format(actionCode))
            return TouchEvent(
                action = action,
                pointerId = LittleEndian.readU8(payload, offset + 1),
                x = LittleEndian.readU16(payload, offset + 2),
                y = LittleEndian.readU16(payload, offset + 4),
                pressure = LittleEndian.readU8(payload, offset + 6),
                flags = LittleEndian.readU8(payload, offset + 7),
                timestampMs = LittleEndian.readU32(payload, offset + 10),
            )
        }

        /** Builds a `DOWN` event, mostly for tests. */
        fun down(x: Int, y: Int, flags: Int = 0, timestampMs: Long = 0L): TouchEvent =
            TouchEvent(TouchAction.DOWN, x = x, y = y, flags = flags, timestampMs = timestampMs)

        /** Builds a `MOVE` event, mostly for tests. */
        fun move(x: Int, y: Int, flags: Int = 0, timestampMs: Long = 0L): TouchEvent =
            TouchEvent(TouchAction.MOVE, x = x, y = y, flags = flags, timestampMs = timestampMs)

        /** Builds an `UP` event, mostly for tests. */
        fun up(x: Int, y: Int, flags: Int = 0, timestampMs: Long = 0L): TouchEvent =
            TouchEvent(TouchAction.UP, x = x, y = y, flags = flags, timestampMs = timestampMs)
    }
}

/**
 * A touch translated into the scene's CSS pixel space, ready to become a `MotionEvent`.
 *
 * @property action what the finger did.
 * @property cssX horizontal CSS coordinate; **float**, because `05-input.md` §2.3 forbids rounding
 *   before hit-testing (rounding produces a systematic half-pixel error at the rim of a round screen).
 * @property cssY vertical CSS coordinate.
 * @property pressure normalised `0.0..1.0`.
 * @property pointerId contact id, passed through.
 * @property timestampMs watch clock, passed through for gesture timing.
 * @property outsideCircle `true` when the raw point was geometrically outside the circle.
 * @property nearEdge `true` when the point is within 5% of the edge (`TouchFlags.NEAR_EDGE`).
 * @property clamped `true` when the coordinate was projected onto the circle's tolerance band or the
 *   viewport bounds.
 */
data class MappedTouch(
    val action: TouchAction,
    val cssX: Float,
    val cssY: Float,
    val pressure: Float,
    val pointerId: Int,
    val timestampMs: Long,
    val outsideCircle: Boolean = false,
    val nearEdge: Boolean = false,
    val clamped: Boolean = false,
)

/** Why a raw event never reached the WebView. */
enum class TouchDropReason {

    /** A `DOWN` landed outside the inscribed circle of a round panel; the whole sequence is ignored. */
    DOWN_OUTSIDE_CIRCLE,

    /** The event belongs to a sequence that was suppressed by an out-of-circle `DOWN`. */
    SEQUENCE_SUPPRESSED,

    /** A `MOVE`/`UP`/etc. arrived with no preceding `DOWN` (`05-input.md` §3). */
    SEQUENCE_NOT_STARTED,

    /** The watch marked the `DOWN` as `OUTSIDE_CIRCLE`. */
    MARKED_OUTSIDE_CIRCLE,
}

/** Result of feeding one [TouchEvent] into a [TouchHandler]. */
sealed interface TouchOutcome {

    /** Hand [touch] to the WebView. */
    data class Dispatch(val touch: MappedTouch) : TouchOutcome

    /** Discard the event; [reason] is for statistics, never for control flow. */
    data class Dropped(val reason: TouchDropReason) : TouchOutcome
}
