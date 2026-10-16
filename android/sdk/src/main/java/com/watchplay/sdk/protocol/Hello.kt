package com.watchplay.sdk.protocol

/**
 * `HELLO` — the first frame of a session, always sent by the **watch** (`03-session.md` §1.2, §2.1).
 *
 * Fixed 52-byte payload; the phone answers with [HelloAck]. The `nonce` is what makes re-handshakes
 * safe: a stale `HELLO` from a previous connection produces a `HELLO_ACK` whose `peer_nonce` does not
 * match the watch's current nonce, and the watch then ignores it and re-sends `HELLO`.
 *
 * `proto_version` is duplicated from the frame header on purpose: the header copy lets a frame parser
 * reject an incompatible frame before touching the payload, while the payload copy keeps a packet
 * capture self-describing. The two must match; the header wins if they do not.
 *
 * @property protoVersion protocol version the watch is running.
 * @property minVersion lowest version the watch can accept.
 * @property flags [HelloFlags] bits.
 * @property nonce per-boot random value, echoed by `HELLO_ACK.peer_nonce`.
 * @property deviceId 16-byte chip id / MAC; all zero means "not provided".
 * @property model ASCII model name, at most 12 bytes, NUL-padded on the wire.
 * @property firmwareVersion ASCII firmware version, at most 8 bytes, NUL-padded.
 */
data class Hello(
    val protoVersion: Int,
    val minVersion: Int,
    val flags: Int,
    val nonce: Long,
    val deviceId: ByteArray,
    val model: String,
    val firmwareVersion: String,
) {

    init {
        require(deviceId.size == DEVICE_ID_BYTES) { "deviceId must be exactly $DEVICE_ID_BYTES bytes" }
        require(nonce in 0L..0xFFFFFFFFL) { "nonce out of u32 range: $nonce" }
    }

    /** `true` when the watch has a touch panel, i.e. `TOUCH` frames may be expected. */
    val hasTouch: Boolean
        get() = FrameFlags.isSet(flags, HelloFlags.HAS_TOUCH)

    /** `true` when the watch has a display (`CAPS.DISPLAY` should then be present). */
    val hasDisplay: Boolean
        get() = FrameFlags.isSet(flags, HelloFlags.HAS_DISPLAY)

    /** Serialises the 52-byte payload. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU8(out, 0, protoVersion)
        LittleEndian.writeU8(out, 1, minVersion)
        LittleEndian.writeU16(out, 2, flags)
        LittleEndian.writeU32(out, 4, nonce)
        deviceId.copyInto(out, destinationOffset = 8, endIndex = minOf(deviceId.size, DEVICE_ID_BYTES))
        writeFixedAscii(out, 24, MODEL_BYTES, model)
        writeFixedAscii(out, 36, FIRMWARE_BYTES, firmwareVersion)
        // 44..51 stays zero: reserved, MUST be zero.
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Hello) return false
        return protoVersion == other.protoVersion && minVersion == other.minVersion &&
            flags == other.flags && nonce == other.nonce && model == other.model &&
            firmwareVersion == other.firmwareVersion && deviceId.contentEquals(other.deviceId)
    }

    override fun hashCode(): Int {
        var result = protoVersion
        result = 31 * result + minVersion
        result = 31 * result + flags
        result = 31 * result + nonce.hashCode()
        result = 31 * result + deviceId.contentHashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + firmwareVersion.hashCode()
        return result
    }

    override fun toString(): String =
        "Hello(v$protoVersion, min=$minVersion, flags=${FrameFlags.describe(null, flags)}, " +
            "model='$model', fw='$firmwareVersion')"

    companion object {
        /** Fixed payload size (`03-session.md` §2.1). */
        const val SIZE_BYTES: Int = ProtocolLimits.HELLO_PAYLOAD_SIZE

        /** Width of the `device_id` field. */
        const val DEVICE_ID_BYTES: Int = 16

        /** Width of the `model` field. */
        const val MODEL_BYTES: Int = 12

        /** Width of the `fw_version` field. */
        const val FIRMWARE_BYTES: Int = 8

        /** Parses a `HELLO` payload. */
        fun decode(data: ByteArray, offset: Int = 0): Hello {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return Hello(
                protoVersion = LittleEndian.readU8(data, offset),
                minVersion = LittleEndian.readU8(data, offset + 1),
                flags = LittleEndian.readU16(data, offset + 2),
                nonce = LittleEndian.readU32(data, offset + 4),
                deviceId = data.copyOfRange(offset + 8, offset + 24),
                model = readFixedAscii(data, offset + 24, MODEL_BYTES),
                firmwareVersion = readFixedAscii(data, offset + 36, FIRMWARE_BYTES),
            )
        }
    }
}

/**
 * `HELLO_ACK` — the phone's answer to [Hello] (`03-session.md` §2.2), fixed 24-byte payload.
 *
 * @property protoVersion version the phone selected; must be the highest both sides support.
 * @property status one of [HelloAckStatus].
 * @property flags [HelloFlags] bits describing the phone.
 * @property peerNonce echo of `HELLO.nonce`; a mismatched echo makes the watch re-handshake.
 * @property sdkVersion ASCII version string, at most 8 bytes.
 */
data class HelloAck(
    val protoVersion: Int,
    val status: Int,
    val flags: Int,
    val peerNonce: Long,
    val sdkVersion: String,
) {

    init {
        require(peerNonce in 0L..0xFFFFFFFFL) { "peerNonce out of u32 range: $peerNonce" }
    }

    /** `true` when the phone accepted the session and is waiting for `CAPS`. */
    val isOk: Boolean
        get() = status == HelloAckStatus.OK

    /** Serialises the 24-byte payload. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU8(out, 0, protoVersion)
        LittleEndian.writeU8(out, 1, status)
        LittleEndian.writeU16(out, 2, flags)
        LittleEndian.writeU32(out, 4, peerNonce)
        writeFixedAscii(out, 8, SDK_VERSION_BYTES, sdkVersion)
        // 16..23 reserved, MUST be zero.
        return out
    }

    override fun toString(): String =
        "HelloAck(v$protoVersion, ${HelloAckStatus.describe(status)}, peerNonce=$peerNonce, " +
            "sdk='$sdkVersion')"

    companion object {
        /** Fixed payload size (`03-session.md` §2.2). */
        const val SIZE_BYTES: Int = ProtocolLimits.HELLO_ACK_PAYLOAD_SIZE

        /** Width of the `sdk_version` field. */
        const val SDK_VERSION_BYTES: Int = 8

        /** Parses a `HELLO_ACK` payload. */
        fun decode(data: ByteArray, offset: Int = 0): HelloAck {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return HelloAck(
                protoVersion = LittleEndian.readU8(data, offset),
                status = LittleEndian.readU8(data, offset + 1),
                flags = LittleEndian.readU16(data, offset + 2),
                peerNonce = LittleEndian.readU32(data, offset + 4),
                sdkVersion = readFixedAscii(data, offset + 8, SDK_VERSION_BYTES),
            )
        }
    }
}

/** `HELLO_ACK.status` values (`03-session.md` §2.4). */
object HelloAckStatus {

    /** Accepted; the phone now waits for `CAPS`. */
    const val OK: Int = 0x00

    /** The versions cannot be reconciled; the watch must not retry the same version. */
    const val UNSUPPORTED_VERSION: Int = 0x01

    /** The phone already has its maximum number of watches. */
    const val BUSY: Int = 0x02

    /** Policy rejection (not authorised, blocked device); the watch must not auto-retry. */
    const val REJECTED: Int = 0x03

    /** Human-readable name, for logs. */
    fun describe(status: Int): String = when (status) {
        OK -> "OK"
        UNSUPPORTED_VERSION -> "UNSUPPORTED_VERSION"
        BUSY -> "BUSY"
        REJECTED -> "REJECTED"
        else -> "UNKNOWN(0x%02X)".format(status)
    }
}

/**
 * `PING` / `PONG` payload (`03-session.md` §6.1), 8 bytes.
 *
 * A `PONG` **MUST** echo the `PING` payload byte for byte; the sender then computes
 * `RTT = now - echo`. Because the two clocks are unrelated, only the round trip is meaningful — never
 * subtract two different devices' clocks.
 *
 * @property echo sender's monotonic clock, low 32 bits, in milliseconds.
 * @property txSeq highest `seq` the sender had emitted when it built the ping, used to align
 *   `FEEDBACK` accounting.
 */
data class PingPayload(
    val echo: Long,
    val txSeq: Long,
) {

    init {
        require(echo in 0L..0xFFFFFFFFL) { "echo out of u32 range: $echo" }
        require(txSeq in 0L..0xFFFFFFFFL) { "txSeq out of u32 range: $txSeq" }
    }

    /** Serialises the 8-byte payload. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU32(out, 0, echo)
        LittleEndian.writeU32(out, 4, txSeq)
        return out
    }

    override fun toString(): String = "PingPayload(echo=$echo, txSeq=$txSeq)"

    companion object {
        /** Payload size (`03-session.md` §6.1). */
        const val SIZE_BYTES: Int = ProtocolLimits.PING_PAYLOAD_SIZE

        /** Parses a ping/pong payload. */
        fun decode(data: ByteArray, offset: Int = 0): PingPayload {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return PingPayload(
                echo = LittleEndian.readU32(data, offset),
                txSeq = LittleEndian.readU32(data, offset + 4),
            )
        }
    }
}

/** Writes an ASCII string into a fixed-width NUL-padded field, truncating when too long. */
private fun writeFixedAscii(target: ByteArray, offset: Int, width: Int, value: String) {
    val bytes = value.toByteArray(Charsets.US_ASCII)
    val length = minOf(bytes.size, width)
    bytes.copyInto(target, destinationOffset = offset, startIndex = 0, endIndex = length)
    for (index in length until width) target[offset + index] = 0
}

/** Reads a fixed-width NUL-padded ASCII field, stopping at the first NUL. */
private fun readFixedAscii(source: ByteArray, offset: Int, width: Int): String {
    var end = offset
    val limit = offset + width
    while (end < limit && source[end].toInt() != 0) end++
    return String(source, offset, end - offset, Charsets.US_ASCII)
}
