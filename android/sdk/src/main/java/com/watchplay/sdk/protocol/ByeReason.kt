package com.watchplay.sdk.protocol

/**
 * `BYE` reason codes (`03-session.md` §7).
 *
 * The payload is `reason u8` followed by optional UTF-8 diagnostic text. A receiver **SHOULD** answer
 * with a `BYE` carrying the same reason before closing, so both logs record both points of view, and
 * the sender waits up to 500 ms for that before forcing the link down.
 */
object ByeReason {

    /** `0x00` — the user stopped the session. */
    const val USER_REQUEST: Int = 0x00

    /** `0x01` — heartbeat timeout (three missed `PONG`s). */
    const val TIMEOUT: Int = 0x01

    /** `0x02` — frame structure error, or repeated resync failures. */
    const val PROTOCOL_ERROR: Int = 0x02

    /** `0x03` — capability negotiation failed. */
    const val NEGOTIATION_FAILED: Int = 0x03

    /** `0x04` — bandwidth stayed below the minimum acceptable level. */
    const val BANDWIDTH_INSUFFICIENT: Int = 0x04

    /** `0x05` — unrecoverable decoder error. */
    const val DECODER_ERROR: Int = 0x05

    /** `0x06` — the receiver ran out of memory. */
    const val OUT_OF_MEMORY: Int = 0x06

    /** `0x07` — battery too low, stopping voluntarily. */
    const val LOW_BATTERY: Int = 0x07

    /** `0x08` — about to switch bearer (`CONTROL(SWITCH_TRANSPORT)`). */
    const val SWITCHING_TRANSPORT: Int = 0x08

    /** `0x09` — thermal protection. */
    const val THERMAL_THROTTLE: Int = 0x09

    /** `0x0A` — the peer is no longer reachable. */
    const val PEER_GONE: Int = 0x0A

    /** `0x0B` — incompatible protocol version. */
    const val UNSUPPORTED_VERSION: Int = 0x0B

    /** Human-readable name of a reason code. */
    fun describe(reason: Int): String = when (reason) {
        USER_REQUEST -> "USER_REQUEST"
        TIMEOUT -> "TIMEOUT"
        PROTOCOL_ERROR -> "PROTOCOL_ERROR"
        NEGOTIATION_FAILED -> "NEGOTIATION_FAILED"
        BANDWIDTH_INSUFFICIENT -> "BANDWIDTH_INSUFFICIENT"
        DECODER_ERROR -> "DECODER_ERROR"
        OUT_OF_MEMORY -> "OUT_OF_MEMORY"
        LOW_BATTERY -> "LOW_BATTERY"
        SWITCHING_TRANSPORT -> "SWITCHING_TRANSPORT"
        THERMAL_THROTTLE -> "THERMAL_THROTTLE"
        PEER_GONE -> "PEER_GONE"
        UNSUPPORTED_VERSION -> "UNSUPPORTED_VERSION"
        else -> "UNKNOWN(0x%02X)".format(reason)
    }

    /** Builds a `BYE` payload: the reason byte plus truncated UTF-8 diagnostics. */
    fun payload(reason: Int, diagnostic: String = ""): ByteArray {
        val text = diagnostic.toByteArray(Charsets.UTF_8)
        val textLength = minOf(text.size, ProtocolLimits.CONTROL_MAX_PAYLOAD - 1)
        val out = ByteArray(1 + textLength)
        LittleEndian.writeU8(out, 0, reason)
        text.copyInto(out, destinationOffset = 1, startIndex = 0, endIndex = textLength)
        return out
    }

    /** Parses the reason byte out of a `BYE` payload; `null` when the payload is empty. */
    fun reasonOf(payload: ByteArray): Int? =
        if (payload.isEmpty()) null else LittleEndian.readU8(payload, 0)

    /** Parses the diagnostic text out of a `BYE` payload, if any. */
    fun diagnosticOf(payload: ByteArray): String =
        if (payload.size <= 1) "" else String(payload, 1, payload.size - 1, Charsets.UTF_8)
}

/**
 * `CONTROL` opcodes (`03-session.md` §5). The payload is `opcode u8` plus optional parameters.
 *
 * Parameter encodings, where they exist, are `u8` quality/fps/angle/brightness, `u32` acked `seq`, and
 * `u8` intensity + `u16` duration for [HAPTIC].
 */
object ControlCommand {

    /** `0x01` — phone → watch: start streaming (first control frame after `STREAMING`). */
    const val START: Int = 0x01

    /** `0x02` — phone → watch: stop streaming, enter `DRAINING`. */
    const val STOP: Int = 0x02

    /** `0x03` — both: pause decoding to save power. */
    const val PAUSE: Int = 0x03

    /** `0x04` — both: resume. */
    const val RESUME: Int = 0x04

    /** `0x05` — watch → phone: the decoder needs an IDR / a fresh JPEG start. */
    const val REQUEST_KEYFRAME: Int = 0x05

    /** `0x06` — watch → phone: `u8` quality; `0` means "you decide". */
    const val SET_QUALITY: Int = 0x06

    /** `0x07` — watch → phone: `u8` fps; `0` means "you decide". */
    const val SET_FPS: Int = 0x07

    /** `0x08` — phone → watch: `u8` rotation in degrees (0/90/180/270). */
    const val ROTATE: Int = 0x08

    /** `0x09` — phone → watch: `u8` brightness, 0–255. */
    const val SET_BRIGHTNESS: Int = 0x09

    /** `0x0A` — both: `u32` acknowledged `seq`, only for frames with `ACK_REQUIRED`. */
    const val ACK: Int = 0x0A

    /** `0x0B` — phone → watch: `u8` scene id, so the watch can change its refresh strategy. */
    const val SCENE_CHANGE: Int = 0x0B

    /** `0x0C` — phone → watch: `u8` intensity + `u16` duration, request haptic feedback. */
    const val HAPTIC: Int = 0x0C

    /** `0x0D` — phone → watch: `u8` bearer + parameters, request a bearer switch. */
    const val SWITCH_TRANSPORT: Int = 0x0D

    /** `0x0E` — both: send a `FEEDBACK` immediately. */
    const val REPORT_STATS: Int = 0x0E

    /** Human-readable name of an opcode. */
    fun describe(opcode: Int): String = when (opcode) {
        START -> "START"
        STOP -> "STOP"
        PAUSE -> "PAUSE"
        RESUME -> "RESUME"
        REQUEST_KEYFRAME -> "REQUEST_KEYFRAME"
        SET_QUALITY -> "SET_QUALITY"
        SET_FPS -> "SET_FPS"
        ROTATE -> "ROTATE"
        SET_BRIGHTNESS -> "SET_BRIGHTNESS"
        ACK -> "ACK"
        SCENE_CHANGE -> "SCENE_CHANGE"
        HAPTIC -> "HAPTIC"
        SWITCH_TRANSPORT -> "SWITCH_TRANSPORT"
        REPORT_STATS -> "REPORT_STATS"
        else -> "UNKNOWN(0x%02X)".format(opcode)
    }

    /** Builds a `CONTROL` payload from an opcode and raw parameters. */
    fun payload(opcode: Int, vararg parameters: Int): ByteArray {
        val out = ByteArray(1 + parameters.size)
        LittleEndian.writeU8(out, 0, opcode)
        for ((index, value) in parameters.withIndex()) LittleEndian.writeU8(out, index + 1, value)
        return out
    }

    /** Reads the opcode out of a `CONTROL` payload; `null` when the payload is empty. */
    fun opcodeOf(payload: ByteArray): Int? =
        if (payload.isEmpty()) null else LittleEndian.readU8(payload, 0)
}
