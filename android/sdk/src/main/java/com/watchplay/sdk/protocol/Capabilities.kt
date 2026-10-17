package com.watchplay.sdk.protocol

/**
 * Physical shape of the watch display — `03-session.md` §3.3 (`DISPLAY.shape`).
 *
 * Wire values are fixed by the specification: `0` unknown, `1` round, `2` rectangular, `3`
 * rounded-rectangle. `07-capabilities.md` §4.1 requires unknown values to degrade gracefully rather
 * than fail, so [fromCode] maps anything unrecognised to [UNKNOWN] and callers fall back to
 * rectangular layout. Never let a new firmware value crash a phone.
 *
 * Round panels are the constrained case: content outside the inscribed square is physically clipped,
 * so a round shape carries a much larger safe-area inset than a rectangular one
 * (`06-rendering.md` §2.4: default 140‰ versus 40‰).
 */
enum class ScreenShape(
    /** Wire value used by `DISPLAY.shape` and by the advertisement's `shape` byte. */
    val code: Int,
) {
    /** `0` — the watch did not say. Treat as [RECTANGULAR] for layout, but do not assume a bezel. */
    UNKNOWN(0x00),

    /** `1` — circular panel, e.g. the 466×466 AMOLED of the reference watch. */
    ROUND(0x01),

    /** `2` — rectangular panel. */
    RECTANGULAR(0x02),

    /** `3` — rectangular panel with rounded corners (e.g. 410×502 with a 40 px radius). */
    ROUNDED_RECT(0x03);

    /** `true` for panels whose corners are clipped, i.e. [ROUND]. */
    val hasClippedCorners: Boolean
        get() = this == ROUND

    /** Style value the phone injects as `data-screen-shape` (`06-rendering.md` §3.2). */
    val cssScreenShape: String
        get() = if (this == ROUND) "round" else "square"

    companion object {
        /**
         * Resolves a wire value. Unknown values become [UNKNOWN] instead of throwing, as required by
         * `07-capabilities.md` §4.1 ("MUST NOT fail because an enum value is unrecognised").
         */
        fun fromCode(code: Int): ScreenShape = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Pixel format the panel wants (`03-session.md` §3.3.1).
 *
 * ESP32-S3 AMOLED drivers typically take **big-endian** RGB565 over QSPI while decoders produce
 * little-endian, so declaring [RGB565_BE] lets the phone do the swap once per frame instead of the
 * watch doing it per pixel (217,156 pixels at 466×466).
 */
enum class ColorFormat(
    /** Wire value used by `DISPLAY.color_format` and `RESULT.color_format`. */
    val code: Int,
) {
    /** `0` — RGB565 little-endian. */
    RGB565(0x00),

    /** `1` — RGB888. */
    RGB888(0x01),

    /** `2` — RGB565 big-endian (what most QSPI AMOLED panels want). */
    RGB565_BE(0x02);

    companion object {
        /** Resolves a wire value, defaulting to [RGB565] for anything unknown. */
        fun fromCode(code: Int): ColorFormat = entries.firstOrNull { it.code == code } ?: RGB565
    }
}

/**
 * One unparsed TLV, kept so that vendor extensions survive a decode/encode round trip and can be
 * inspected in logs (`07-capabilities.md` §1.1).
 *
 * @property type TLV type byte; `0x80..0xFF` is vendor-private.
 * @property value raw value bytes.
 */
class Tlv(val type: Int, val value: ByteArray) {

    /** Value length in bytes. */
    val length: Int
        get() = value.size

    /** Equality by type and value contents. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Tlv) return false
        return type == other.type && value.contentEquals(other.value)
    }

    /** Consistent with [equals]. */
    override fun hashCode(): Int = 31 * type + value.contentHashCode()

    override fun toString(): String = "Tlv(0x%02X, %dB)".format(type, value.size)
}

/** `CAPS.DEVICE_INFO` — 16 bytes (`03-session.md` §3.2). */
data class DeviceInfo(
    val protoVersion: Int,
    val fwMajor: Int,
    val fwMinor: Int,
    val fwPatch: Int,
    val chipId: ByteArray,
    val buildId: Long,
) {

    init {
        require(chipId.size == SIZE_BYTES_CHIP_ID) { "chipId must be $SIZE_BYTES_CHIP_ID bytes" }
        require(buildId in 0L..0xFFFFFFFFL) { "buildId out of u32 range: $buildId" }
    }

    /** Firmware version as `major.minor.patch`. */
    val firmwareVersion: String
        get() = "$fwMajor.$fwMinor.$fwPatch"

    /** Serialises the 16-byte value. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU8(out, 0, protoVersion)
        LittleEndian.writeU8(out, 1, fwMajor)
        LittleEndian.writeU8(out, 2, fwMinor)
        LittleEndian.writeU8(out, 3, fwPatch)
        chipId.copyInto(out, destinationOffset = 4, startIndex = 0, endIndex = SIZE_BYTES_CHIP_ID)
        LittleEndian.writeU32(out, 12, buildId)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceInfo) return false
        return protoVersion == other.protoVersion && fwMajor == other.fwMajor &&
            fwMinor == other.fwMinor && fwPatch == other.fwPatch &&
            buildId == other.buildId && chipId.contentEquals(other.chipId)
    }

    override fun hashCode(): Int {
        var result = protoVersion
        result = 31 * result + fwMajor
        result = 31 * result + fwMinor
        result = 31 * result + fwPatch
        result = 31 * result + chipId.contentHashCode()
        result = 31 * result + buildId.hashCode()
        return result
    }

    override fun toString(): String = "DeviceInfo(v$protoVersion, fw=$firmwareVersion, build=$buildId)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_DEVICE_INFO_SIZE

        /** Width of the `chip_id` field. */
        const val SIZE_BYTES_CHIP_ID: Int = 8

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): DeviceInfo {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return DeviceInfo(
                protoVersion = LittleEndian.readU8(data, offset),
                fwMajor = LittleEndian.readU8(data, offset + 1),
                fwMinor = LittleEndian.readU8(data, offset + 2),
                fwPatch = LittleEndian.readU8(data, offset + 3),
                chipId = data.copyOfRange(offset + 4, offset + 12),
                buildId = LittleEndian.readU32(data, offset + 12),
            )
        }
    }
}

/** `CAPS.DISPLAY` — 16 bytes (`03-session.md` §3.3). */
data class DisplayCaps(
    val widthPx: Int,
    val heightPx: Int,
    val shape: ScreenShape,
    val colorFormat: ColorFormat,
    val safeInsetPermille: Int,
    val refreshHz: Int = 0,
    val tearingMode: Int = 0,
    val brightnessLevels: Int = 0,
    val panelId: Long = 0L,
) {

    init {
        require(widthPx in 1..0xFFFF) { "widthPx out of range: $widthPx" }
        require(heightPx in 1..0xFFFF) { "heightPx out of range: $heightPx" }
        require(safeInsetPermille in 0..499) { "safeInsetPermille must be 0..499 (`06-rendering.md` §2.3)" }
        require(refreshHz in 0..0xFFFF) { "refreshHz out of u16 range: $refreshHz" }
    }

    /** Shorter side in pixels; safe-area insets are a fraction of this (`06-rendering.md` §2.3). */
    val minSidePx: Int
        get() = minOf(widthPx, heightPx)

    /** Serialises the 16-byte value. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU16(out, 0, widthPx)
        LittleEndian.writeU16(out, 2, heightPx)
        LittleEndian.writeU8(out, 4, shape.code)
        LittleEndian.writeU8(out, 5, colorFormat.code)
        LittleEndian.writeU16(out, 6, safeInsetPermille)
        LittleEndian.writeU16(out, 8, refreshHz)
        LittleEndian.writeU8(out, 10, tearingMode)
        LittleEndian.writeU8(out, 11, brightnessLevels)
        LittleEndian.writeU32(out, 12, panelId)
        return out
    }

    override fun toString(): String =
        "DisplayCaps(${widthPx}x$heightPx, ${shape.name}, ${colorFormat.name}, " +
            "inset=${safeInsetPermille}‰, ${refreshHz}Hz)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_DISPLAY_SIZE

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): DisplayCaps {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return DisplayCaps(
                widthPx = LittleEndian.readU16(data, offset),
                heightPx = LittleEndian.readU16(data, offset + 2),
                shape = ScreenShape.fromCode(LittleEndian.readU8(data, offset + 4)),
                colorFormat = ColorFormat.fromCode(LittleEndian.readU8(data, offset + 5)),
                safeInsetPermille = LittleEndian.readU16(data, offset + 6),
                refreshHz = LittleEndian.readU16(data, offset + 8),
                tearingMode = LittleEndian.readU8(data, offset + 10),
                brightnessLevels = LittleEndian.readU8(data, offset + 11),
                panelId = LittleEndian.readU32(data, offset + 12),
            )
        }
    }
}

/** `CAPS.CODEC_MJPEG` — 4 bytes (`03-session.md` §3.4). */
data class MjpegCaps(
    val subsampling: Int,
    val minQuality: Int,
    val maxQuality: Int,
    val maxFps: Int,
) {

    init {
        require(maxQuality in 1..100) { "maxQuality out of range: $maxQuality" }
    }

    /** Serialises the 4-byte value. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU8(out, 0, subsampling)
        LittleEndian.writeU8(out, 1, minQuality)
        LittleEndian.writeU8(out, 2, maxQuality)
        LittleEndian.writeU8(out, 3, maxFps)
        return out
    }

    override fun toString(): String =
        "MjpegCaps(subsampling=$subsampling, q=$minQuality..$maxQuality, maxFps=$maxFps)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_CODEC_MJPEG_SIZE

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): MjpegCaps {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return MjpegCaps(
                subsampling = LittleEndian.readU8(data, offset),
                minQuality = LittleEndian.readU8(data, offset + 1),
                maxQuality = LittleEndian.readU8(data, offset + 2),
                maxFps = LittleEndian.readU8(data, offset + 3),
            )
        }
    }
}

/** `CAPS.CODEC_H264` — 8 bytes (`03-session.md` §3.5). */
data class H264Caps(
    val profile: Int,
    val level: Int,
    val maxFps: Int,
    val hardwareDecode: Boolean,
    val maxBitrateKbps: Int,
    val maxWidthPx: Int,
) {

    init {
        require(profile in 0..0xFF) { "profile out of u8 range: $profile" }
    }

    /** Serialises the 8-byte value. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU8(out, 0, profile)
        LittleEndian.writeU8(out, 1, level)
        LittleEndian.writeU8(out, 2, maxFps)
        LittleEndian.writeU8(out, 3, if (hardwareDecode) 1 else 0)
        LittleEndian.writeU16(out, 4, maxBitrateKbps)
        LittleEndian.writeU16(out, 6, maxWidthPx)
        return out
    }

    override fun toString(): String =
        "H264Caps(profile=$profile, level=$level, maxFps=$maxFps, hw=$hardwareDecode, " +
            "maxBitrate=${maxBitrateKbps}kbps)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_CODEC_H264_SIZE

        /** H.264 Baseline profile idc, the only profile Profile B may use (`04-video.md` §1.1). */
        const val PROFILE_BASELINE: Int = 66

        /** H.264 Main profile idc. */
        const val PROFILE_MAIN: Int = 77

        /** H.264 High profile idc. */
        const val PROFILE_HIGH: Int = 100

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): H264Caps {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return H264Caps(
                profile = LittleEndian.readU8(data, offset),
                level = LittleEndian.readU8(data, offset + 1),
                maxFps = LittleEndian.readU8(data, offset + 2),
                hardwareDecode = LittleEndian.readU8(data, offset + 3) != 0,
                maxBitrateKbps = LittleEndian.readU16(data, offset + 4),
                maxWidthPx = LittleEndian.readU16(data, offset + 6),
            )
        }
    }
}

/** `CAPS.INPUT` — 4 bytes (`03-session.md` §3.6). */
data class InputCaps(
    val touchPoints: Int,
    val reportRateHz: Int,
    val hasHaptics: Boolean,
    val buttons: Int,
) {
    /** Serialises the 4-byte value. */
    fun encode(): ByteArray = byteArrayOf(
        touchPoints.toByte(),
        reportRateHz.toByte(),
        (if (hasHaptics) 1 else 0).toByte(),
        buttons.toByte(),
    )

    override fun toString(): String =
        "InputCaps(points=$touchPoints, ${reportRateHz}Hz, haptics=$hasHaptics, buttons=$buttons)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_INPUT_SIZE

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): InputCaps {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return InputCaps(
                touchPoints = LittleEndian.readU8(data, offset),
                reportRateHz = LittleEndian.readU8(data, offset + 1),
                hasHaptics = LittleEndian.readU8(data, offset + 2) != 0,
                buttons = LittleEndian.readU8(data, offset + 3),
            )
        }
    }
}

/** `CAPS.AUDIO` — 4 bytes (`03-session.md` §3.7). */
data class AudioCaps(
    val codecs: Int,
    val path: Int,
    val volumeLevels: Int,
) {
    /** Serialises the 4-byte value. */
    fun encode(): ByteArray = byteArrayOf(codecs.toByte(), path.toByte(), volumeLevels.toByte(), 0)

    override fun toString(): String = "AudioCaps(codecs=0x%02X, path=$path, volume=$volumeLevels)".format(codecs)

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_AUDIO_SIZE

        /** `path` value: no audio path (the reference hardware). */
        const val PATH_NONE: Int = 0

        /** `path` value: classic Bluetooth A2DP. */
        const val PATH_A2DP: Int = 1

        /** `path` value: BLE LC3 (LE Audio). */
        const val PATH_LE_AUDIO: Int = 2

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): AudioCaps {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return AudioCaps(
                codecs = LittleEndian.readU8(data, offset),
                path = LittleEndian.readU8(data, offset + 1),
                volumeLevels = LittleEndian.readU8(data, offset + 2),
            )
        }
    }
}

/** `CAPS.MEMORY` — 8 bytes (`03-session.md` §3.8). */
data class MemoryCaps(
    val psramBytes: Long,
    val freeHeapBytes: Long,
) {
    /** `true` when there is room for two RGB565 frame buffers at the given panel size. */
    fun canDoubleBuffer(widthPx: Int, heightPx: Int): Boolean =
        psramBytes >= 2L * widthPx * heightPx * 2L

    /** Serialises the 8-byte value. */
    fun encode(): ByteArray {
        val out = ByteArray(SIZE_BYTES)
        LittleEndian.writeU32(out, 0, psramBytes)
        LittleEndian.writeU32(out, 4, freeHeapBytes)
        return out
    }

    override fun toString(): String = "MemoryCaps(psram=$psramBytes B, heap=$freeHeapBytes B)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = ProtocolLimits.TLV_MEMORY_SIZE

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): MemoryCaps {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return MemoryCaps(
                psramBytes = LittleEndian.readU32(data, offset),
                freeHeapBytes = LittleEndian.readU32(data, offset + 4),
            )
        }
    }
}

/** `CAPS.POWER` — 4 bytes (`07-capabilities.md` §2.1). */
data class PowerCaps(
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalState: Int,
    val powerProfile: Int,
) {
    /** `true` when the battery reading is unavailable (`0xFF`). */
    val batteryUnknown: Boolean
        get() = batteryPercent == 0xFF

    /** Serialises the 4-byte value. */
    fun encode(): ByteArray = byteArrayOf(
        batteryPercent.toByte(),
        (if (charging) 1 else 0).toByte(),
        thermalState.toByte(),
        powerProfile.toByte(),
    )

    override fun toString(): String =
        "PowerCaps(battery=${if (batteryUnknown) "?" else "$batteryPercent%"}, charging=$charging, " +
            "thermal=$thermalState, profile=$powerProfile)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = 4

        /** Sentinel meaning "battery level unknown". */
        const val BATTERY_UNKNOWN: Int = 0xFF

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): PowerCaps {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return PowerCaps(
                batteryPercent = LittleEndian.readU8(data, offset),
                charging = LittleEndian.readU8(data, offset + 1) != 0,
                thermalState = LittleEndian.readU8(data, offset + 2),
                powerProfile = LittleEndian.readU8(data, offset + 3),
            )
        }
    }
}

/** `CAPS.TRANSPORT` — variable length, at least 4 bytes (`07-capabilities.md` §2.2). */
data class TransportCaps(
    val bearers: Int,
    val preferred: Int,
    val mtuPayload: Int,
    val ext: ByteArray = ByteArray(0),
) {

    /** `true` when the watch supports the GATT bearer (mandatory in v0.1). */
    val supportsGatt: Boolean
        get() = bearers and BEARER_GATT != 0

    /** `true` when the watch supports L2CAP CoC. */
    val supportsL2capCoc: Boolean
        get() = bearers and BEARER_L2CAP_COC != 0

    /** `true` when the watch supports the classic Bluetooth (RFCOMM/SPP) bearer. */
    val supportsBtSpp: Boolean
        get() = bearers and BEARER_BT_SPP != 0

    /**
     * Real `bearer_tu` of the classic-Bluetooth bearer, or `null` when bit3 is unset.
     *
     * `mtuPayload` is a u8 truncated at 255 because it was sized for BLE; an RFCOMM frame size of
     * 990 cannot fit, so the peer carries it as a little-endian u16 in the first two bytes of `ext`
     * (`01-transport.md` section 5.2).
     */
    val btBearerTu: Int?
        get() {
            if (!supportsBtSpp || ext.size < 2) return null
            return (ext[0].toInt() and 0xFF) or ((ext[1].toInt() and 0xFF) shl 8)
        }

    /** Serialises the value (`4 + ext.size` bytes). */
    fun encode(): ByteArray {
        val out = ByteArray(MIN_SIZE_BYTES + ext.size)
        LittleEndian.writeU8(out, 0, bearers)
        LittleEndian.writeU8(out, 1, preferred)
        LittleEndian.writeU8(out, 2, mtuPayload.coerceIn(0, 255))
        LittleEndian.writeU8(out, 3, 0)
        ext.copyInto(out, destinationOffset = 4)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransportCaps) return false
        return bearers == other.bearers && preferred == other.preferred &&
            mtuPayload == other.mtuPayload && ext.contentEquals(other.ext)
    }

    override fun hashCode(): Int {
        var result = bearers
        result = 31 * result + preferred
        result = 31 * result + mtuPayload
        result = 31 * result + ext.contentHashCode()
        return result
    }

    override fun toString(): String =
        "TransportCaps(bearers=0x%02X, preferred=0x%02X, mtuPayload=$mtuPayload)".format(bearers, preferred)

    companion object {
        /** Smallest valid value size. */
        const val MIN_SIZE_BYTES: Int = 4

        /** Bearer bit: BLE GATT. */
        const val BEARER_GATT: Int = 0x01

        /** Bearer bit: BLE L2CAP CoC. */
        const val BEARER_L2CAP_COC: Int = 0x02

        /** Bearer bit: Wi-Fi TCP (Phase 2). */
        const val BEARER_WIFI_TCP: Int = 0x04

        /** Bearer bit: classic Bluetooth RFCOMM / SPP (optional). */
        const val BEARER_BT_SPP: Int = 0x08

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): TransportCaps {
            LittleEndian.requireRange(data, offset, minOf(length, MIN_SIZE_BYTES))
            return TransportCaps(
                bearers = LittleEndian.readU8(data, offset),
                preferred = LittleEndian.readU8(data, offset + 1),
                mtuPayload = LittleEndian.readU8(data, offset + 2),
                ext = if (length > MIN_SIZE_BYTES) {
                    data.copyOfRange(offset + MIN_SIZE_BYTES, offset + length)
                } else {
                    ByteArray(0)
                },
            )
        }
    }
}

/** `CAPS.SENSORS` — 4 bytes (`07-capabilities.md` §2.3). Sensor *data* is out of v0.1 scope. */
data class SensorsCaps(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
    val heartRate: Boolean,
    val compass: Boolean,
) {
    /** Serialises the 4-byte value. */
    fun encode(): ByteArray = byteArrayOf(
        (if (accelerometer) 1 else 0).toByte(),
        (if (gyroscope) 1 else 0).toByte(),
        (if (heartRate) 1 else 0).toByte(),
        (if (compass) 1 else 0).toByte(),
    )

    override fun toString(): String =
        "SensorsCaps(accel=$accelerometer, gyro=$gyroscope, hr=$heartRate, compass=$compass)"

    companion object {
        /** Fixed value size. */
        const val SIZE_BYTES: Int = 4

        /** Parses the value. */
        fun decode(data: ByteArray, offset: Int = 0): SensorsCaps {
            LittleEndian.requireRange(data, offset, SIZE_BYTES)
            return SensorsCaps(
                accelerometer = LittleEndian.readU8(data, offset) != 0,
                gyroscope = LittleEndian.readU8(data, offset + 1) != 0,
                heartRate = LittleEndian.readU8(data, offset + 2) != 0,
                compass = LittleEndian.readU8(data, offset + 3) != 0,
            )
        }
    }
}

/**
 * Everything the watch reported in its `CAPS` frame (`03-session.md` §3, `07-capabilities.md` §2).
 *
 * The payload is a TLV list rather than a fixed struct, so this type simply collects the registered
 * TLVs that were present; anything absent stays `null` and anything unrecognised is preserved in
 * [vendorTlvs] (or dropped when it is in the reserved standard range).
 *
 * `CAPS` is the watch's **offer**, never the final word: the session runs on `CAPS_ACK` (see
 * [CapsAck] and [Negotiator]).
 *
 * @property deviceInfo `0x01`, mandatory.
 * @property display `0x02`, mandatory for devices with a screen.
 * @property mjpeg `0x03`, at least one of `mjpeg`/`h264` must be present.
 * @property h264 `0x04`, at least one of `mjpeg`/`h264` must be present.
 * @property input `0x05`, expected when the watch has touch.
 * @property audio `0x06`.
 * @property memory `0x07`.
 * @property power `0x08`.
 * @property transport `0x09`.
 * @property sensors `0x0A`.
 * @property vendorTlvs TLVs in `0x80..0xFF`, preserved verbatim.
 */
data class Capabilities(
    val deviceInfo: DeviceInfo? = null,
    val display: DisplayCaps? = null,
    val mjpeg: MjpegCaps? = null,
    val h264: H264Caps? = null,
    val input: InputCaps? = null,
    val audio: AudioCaps? = null,
    val memory: MemoryCaps? = null,
    val power: PowerCaps? = null,
    val transport: TransportCaps? = null,
    val sensors: SensorsCaps? = null,
    val vendorTlvs: List<Tlv> = emptyList(),
) {

    /** Codecs the watch declared, in preference order for the negotiator (MJPEG first). */
    val supportedCodecs: Set<VideoCodec>
        get() = buildSet {
            if (mjpeg != null) add(VideoCodec.MJPEG)
            if (h264 != null) add(VideoCodec.H264)
        }

    /** Highest frame rate the watch claims for [codec], or `0` when it does not support it. */
    fun maxFpsFor(codec: VideoCodec): Int = when (codec) {
        VideoCodec.MJPEG -> mjpeg?.maxFps ?: 0
        VideoCodec.H264 -> h264?.maxFps ?: 0
    }

    /** `true` when the mandatory TLVs of `07-capabilities.md` §3.1 are all present. */
    val isComplete: Boolean
        get() = deviceInfo != null && display != null && supportedCodecs.isNotEmpty()

    override fun toString(): String =
        "Capabilities(device=$deviceInfo, display=$display, mjpeg=$mjpeg, h264=$h264, " +
            "input=$input, audio=$audio, memory=$memory, power=$power, transport=$transport, " +
            "sensors=$sensors, vendor=${vendorTlvs.size} TLV(s))"

    companion object {
        /**
         * Capabilities of the reference watch from `docs/spec/00-overview.md` §6: a LilyGO
         * T-Display S3 AMOLED 1.43 (ESP32-S3R8, 8 MB PSRAM, 466×466 round AMOLED, FT3168 touch).
         *
         * Handy for tests and for driving the SDK with no hardware at all — the values follow
         * `06-rendering.md` §2.4 (140‰ safe inset) and `03-session.md` §3.4 (4:2:0, q≤90, 15 fps).
         */
        fun referenceWatch(
            deviceId: ByteArray = ByteArray(DeviceInfo.SIZE_BYTES_CHIP_ID),
            nonce: Long = 0L,
        ): Capabilities = Capabilities(
            deviceInfo = DeviceInfo(
                protoVersion = ProtocolVersion.CURRENT,
                fwMajor = 0,
                fwMinor = 1,
                fwPatch = 0,
                chipId = deviceId,
                buildId = 1L,
            ),
            display = DisplayCaps(
                widthPx = 466,
                heightPx = 466,
                shape = ScreenShape.ROUND,
                colorFormat = ColorFormat.RGB565_BE,
                safeInsetPermille = 140,
                refreshHz = 60,
                tearingMode = 1,
                brightnessLevels = 16,
                panelId = 0L,
            ),
            mjpeg = MjpegCaps(subsampling = 2, minQuality = 40, maxQuality = 90, maxFps = 15),
            input = InputCaps(touchPoints = 1, reportRateHz = 60, hasHaptics = true, buttons = 0),
            audio = AudioCaps(codecs = 0, path = AudioCaps.PATH_NONE, volumeLevels = 0),
            memory = MemoryCaps(psramBytes = 8L * 1024 * 1024, freeHeapBytes = 256L * 1024),
            power = PowerCaps(batteryPercent = 82, charging = false, thermalState = 0, powerProfile = 1),
            transport = TransportCaps(
                bearers = TransportCaps.BEARER_GATT or TransportCaps.BEARER_L2CAP_COC,
                preferred = TransportCaps.BEARER_GATT,
                mtuPayload = ProtocolLimits.DEFAULT_MAX_CHUNK_BYTES,
            ),
            sensors = SensorsCaps(accelerometer = true, gyroscope = false, heartRate = false, compass = true),
        ).also { require(nonce in 0L..0xFFFFFFFFL) { "nonce out of u32 range" } }
    }
}
