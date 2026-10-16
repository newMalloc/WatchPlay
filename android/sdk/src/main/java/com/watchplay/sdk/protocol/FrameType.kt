package com.watchplay.sdk.protocol

/**
 * Frame types of the WatchPlay protocol v0.1 — the `type` byte at offset 3 of every frame header.
 *
 * Normative table: `docs/spec/02-wire-format.md` §3.
 *
 * ```
 * HELLO    Sink -> Source   watch announces itself right after the BLE link comes up
 * HELLO_ACK Source -> Sink  phone accepts and answers with its own identity
 * CAPS     Sink -> Source   watch reports screen + decoder capabilities
 * CAPS_ACK Source -> Sink   phone answers with the negotiated session parameters
 * VIDEO    Source -> Sink   one chunk of one video frame (see VideoFrameHeader)
 * VIDEO_END Source -> Sink  optional explicit end-of-frame marker; v0.1 senders do not use it
 * TOUCH    Sink -> Source   fixed 14-byte touch event
 * CONTROL  both ways        session control commands
 * FEEDBACK Sink -> Source   fixed 20-byte credit grant / receiver status
 * PING/PONG both ways       heartbeat
 * BYE      both ways        graceful teardown with a reason code
 * ```
 *
 * Anything outside `0x00..0x0C` is either reserved or vendor-private. The specification requires
 * receivers to *skip* such frames using `hdr_len` + `payload_len` and to count them, never to
 * disconnect — [fromCode] returning `null` is therefore a normal, non-fatal outcome.
 */
enum class FrameType(val code: Int) {

    /** `0x01` — Sink → Source. First frame of a session, carries the watch identity. */
    HELLO(0x01),

    /** `0x02` — Source → Sink. Accepts the session and returns the phone identity. */
    HELLO_ACK(0x02),

    /** `0x03` — Sink → Source. Watch screen and decoder capabilities. */
    CAPS(0x03),

    /** `0x04` — Source → Sink. The negotiated result the rest of the session must obey. */
    CAPS_ACK(0x04),

    /** `0x05` — Source → Sink. One chunk of a video frame; payload starts with a video header. */
    VIDEO(0x05),

    /**
     * `0x06` — Source → Sink, zero-length payload. Optional optimisation that explicitly marks the
     * end of a frame. The reference implementation does **not** send it: a frame ends when
     * `VIDEO_END`'s sibling rule matches, i.e. the chunk whose `LAST_CHUNK` flag is set, or whose
     * video header says `chunk_index == chunk_total - 1` (spec §3.1).
     *
     * Note: the project brief that requested this SDK called this constant `VIDEO_FRAME`. The
     * normative specification calls it `VIDEO_END`, so that is the name used here.
     */
    VIDEO_END(0x06),

    /** `0x07` — Sink → Source. Exactly 14 bytes of touch data. */
    TOUCH(0x07),

    /** `0x08` — both directions. Session control command; at most 64 payload bytes. */
    CONTROL(0x08),

    /** `0x09` — Sink → Source. Exactly 20 bytes: credit grant and receiver status. */
    FEEDBACK(0x09),

    /** `0x0A` — both directions. Heartbeat request. */
    PING(0x0A),

    /** `0x0B` — both directions. Heartbeat response. */
    PONG(0x0B),

    /** `0x0C` — both directions. Graceful shutdown, payload carries a reason code. */
    BYE(0x0C);

    companion object {
        /** Reserved value: never transmitted (`0x00`). */
        const val RESERVED_ZERO: Int = 0x00

        /** First vendor-private type value (`0x80..0xFF`). */
        const val VENDOR_FIRST: Int = 0x80

        /** Last value reserved for future standard extension (`0x7F`). */
        const val STANDARD_RESERVED_LAST: Int = 0x7F

        /** First value reserved for future standard extension (`0x0D`). */
        const val STANDARD_RESERVED_FIRST: Int = 0x0D

        /**
         * Resolves a wire value, or `null` when the value is reserved or vendor-private.
         *
         * `null` is **not** an error: the caller must skip the frame and increment its
         * `unknownFrames` counter (spec §3).
         */
        fun fromCode(code: Int): FrameType? = entries.firstOrNull { it.code == code }

        /** `true` for `0x00` and `0x0D..0x7F`, which the spec forbids a conforming sender to use. */
        fun isReserved(code: Int): Boolean = code == RESERVED_ZERO ||
            code in STANDARD_RESERVED_FIRST..STANDARD_RESERVED_LAST

        /** `true` for `0x80..0xFF`, the vendor-private range. */
        fun isVendorPrivate(code: Int): Boolean = code in VENDOR_FIRST..0xFF
    }
}
