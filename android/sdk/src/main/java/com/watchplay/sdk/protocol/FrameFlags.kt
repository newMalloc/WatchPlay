package com.watchplay.sdk.protocol

/**
 * Bit masks for the `flags` field (u16, offset 4) of the frame header — `02-wire-format.md` §4.
 *
 * The meaning of a bit depends on the frame type, so type-specific bits live in nested objects:
 * bit `0x0008` is `IDLE_HINT` on a control frame and `KEYFRAME` on a video chunk. The `HELLO` payload
 * carries its own 16-bit flag word with a completely different meaning — see [HelloFlags].
 */
object FrameFlags {

    /** `0x0001` — needs a `CONTROL(ACK)` reply. Video frames **MUST NOT** set it. */
    const val ACK_REQUIRED: Int = 0x0001

    /** `0x0002` — retransmission; `seq` must repeat the original (`02` §5). */
    const val RETRANSMIT: Int = 0x0002

    /** `0x0004` — the sender has nothing else queued. */
    const val LAST_IN_BURST: Int = 0x0004

    /** `0x0008` — the sender expects ~500 ms of silence (non-video frames only). */
    const val IDLE_HINT: Int = 0x0008

    /** `0x8000` — vendor-private semantics; ignore rather than disconnect. */
    const val VENDOR: Int = 0x8000

    /** Bits `4..14` are reserved: zero on send, ignored on receive. */
    const val RESERVED_MASK: Int = 0x7FF0

    /**
     * Video-specific bits (`02-wire-format.md` §4.2), valid only for [FrameType.VIDEO]. They
     * duplicate information from the video sub-header for the benefit of capture tools; when the two
     * disagree, the sub-header wins.
     */
    object Video {
        /** `0x0008` — the frame this chunk belongs to is a keyframe. */
        const val KEYFRAME: Int = 0x0008

        /** `0x0010` — this is chunk 0 of its frame. */
        const val FIRST_CHUNK: Int = 0x0010

        /** `0x0020` — this is the final chunk of its frame. */
        const val LAST_CHUNK: Int = 0x0020

        /** `0x0040` — frames were dropped before this one; reset decoder reference state. */
        const val DISCONTINUITY: Int = 0x0040
    }

    /** `true` when every bit in [mask] is set in [flags]. */
    fun isSet(flags: Int, mask: Int): Boolean = (flags and mask) == mask

    /** `true` when at least one bit of [mask] is set in [flags]. */
    fun anySet(flags: Int, mask: Int): Boolean = (flags and mask) != 0

    /** Returns [flags] with [mask] set or cleared. */
    fun withFlag(flags: Int, mask: Int, enabled: Boolean = true): Int =
        if (enabled) flags or mask else flags and mask.inv()

    /** Voids reserved bits, as a conforming sender must before transmitting. */
    fun sanitize(flags: Int): Int = flags and RESERVED_MASK.inv()

    /** Names the flags meaningful for [type], for logs. Unknown bits are shown numerically. */
    fun describe(type: FrameType?, flags: Int): String {
        val names = mutableListOf<String>()
        if (isSet(flags, ACK_REQUIRED)) names += "ACK_REQUIRED"
        if (isSet(flags, RETRANSMIT)) names += "RETRANSMIT"
        if (isSet(flags, LAST_IN_BURST)) names += "LAST_IN_BURST"
        if (type == FrameType.VIDEO) {
            if (isSet(flags, Video.KEYFRAME)) names += "KEYFRAME"
            if (isSet(flags, Video.FIRST_CHUNK)) names += "FIRST_CHUNK"
            if (isSet(flags, Video.LAST_CHUNK)) names += "LAST_CHUNK"
            if (isSet(flags, Video.DISCONTINUITY)) names += "DISCONTINUITY"
        } else if (isSet(flags, IDLE_HINT)) {
            names += "IDLE_HINT"
        }
        if (isSet(flags, VENDOR)) names += "VENDOR"
        return "0x%04X[%s]".format(flags and 0xFFFF, names.joinToString("|"))
    }
}

/**
 * The `flags` word **inside** the `HELLO` / `HELLO_ACK` payload (`03-session.md` §2.3).
 *
 * Not to be confused with [FrameFlags]: these bits describe the device, not the frame, and both
 * directions reuse the same bit assignments.
 */
object HelloFlags {

    /** `0x0001` — the device has a display (clear for pure-audio devices). */
    const val HAS_DISPLAY: Int = 0x0001

    /** `0x0002` — the device has a touch panel. If clear, the phone must expect no `TOUCH` frames. */
    const val HAS_TOUCH: Int = 0x0002

    /** `0x0004` — the device has external PSRAM (affects how many frame buffers it can hold). */
    const val HAS_PSRAM: Int = 0x0004

    /** `0x0008` — supports the L2CAP CoC bearer. */
    const val SUPPORTS_L2CAP_COC: Int = 0x0008

    /** `0x0010` — supports a Wi-Fi bearer (Phase 2). */
    const val SUPPORTS_WIFI: Int = 0x0010

    /** `0x0020` — supports classic-Bluetooth A2DP. The ESP32-S3 reference hardware cannot. */
    const val SUPPORTS_A2DP: Int = 0x0020

    /** `0x0040` — battery powered; the phone should stream more conservatively. */
    const val IS_BATTERY_POWERED: Int = 0x0040

    /** Bits `7..15` are reserved and must be zero. */
    const val RESERVED_MASK: Int = 0xFF80
}

/** Bit meanings of `FEEDBACK.flags` (`04-video.md` §4.3). */
object FeedbackFlags {

    /** `0x01` — the decoder needs an IDR; equivalent to `CONTROL(REQUEST_KEYFRAME)`. */
    const val REQUEST_KEYFRAME: Int = 0x01

    /** `0x02` — the receiver is overloaded: lower the frame rate or quality. */
    const val OVERLOADED: Int = 0x02

    /** `0x04` — thermal throttling is active. */
    const val THERMAL: Int = 0x04

    /** `0x08` — battery is low; reduce the frame rate. */
    const val LOW_BATTERY: Int = 0x08

    /** Bits `4..7` are reserved and must be zero. */
    const val RESERVED_MASK: Int = 0xF0
}

/** `FEEDBACK.last_frame_status` values (`04-video.md` §4.2). */
object LastFrameStatus {

    /** The last frame was decoded successfully. */
    const val OK: Int = 0

    /** CRC or structural error: the frame was discarded before decoding. */
    const val STRUCTURE_ERROR: Int = 1

    /** The decoder failed on the frame. */
    const val DECODE_ERROR: Int = 2

    /** The frame was dropped because the receiver's queue overflowed. */
    const val OVERFLOW: Int = 3

    /** Human-readable name of a `last_frame_status` value. */
    fun describe(status: Int): String = when (status) {
        OK -> "OK"
        STRUCTURE_ERROR -> "STRUCTURE_ERROR"
        DECODE_ERROR -> "DECODE_ERROR"
        OVERFLOW -> "OVERFLOW"
        else -> "UNKNOWN(0x%02X)".format(status)
    }
}

/** Bit meanings of `TOUCH.flags` (`05-input.md` §1.2). */
object TouchFlags {

    /** `0x01` — coordinates were rotated into the display coordinate system by `CONTROL(ROTATE)`. */
    const val COORDS_ROTATED: Int = 0x01

    /** `0x02` — the point is outside the inscribed circle (debug only; senders should drop it). */
    const val OUTSIDE_CIRCLE: Int = 0x02

    /** `0x04` — the point is within 5% of the panel edge. */
    const val NEAR_EDGE: Int = 0x04

    /** Bits `3..7` are reserved and must be zero. */
    const val RESERVED_MASK: Int = 0xF8
}
