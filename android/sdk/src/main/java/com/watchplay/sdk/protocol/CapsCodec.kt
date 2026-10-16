package com.watchplay.sdk.protocol

import java.io.ByteArrayOutputStream

/**
 * Codec for the `CAPS` frame the **watch** sends (`03-session.md` §3, registry in
 * `07-capabilities.md` §2).
 *
 * ## TLV rules, implemented literally
 *
 * A TLV is `type u8 | len u8 | value[len]`, all fields little-endian. The specification is explicit
 * about three cases, and each one is handled exactly as written in `03-session.md` §3.1:
 *
 *  * **unknown `type`** → skip `len` bytes; never fail (forward compatibility, and it is how vendor
 *    extensions in `0x80..0xFF` stay harmless),
 *  * **`len` larger than the registered size** → read the known prefix, then skip the remainder,
 *  * **`len` smaller than the registered size** → ignore that TLV entirely, because a short value is
 *    corrupt rather than a newer variant.
 *
 * ## Direction matters
 *
 * `CAPS` (`0x01` = `DEVICE_INFO`) and `CAPS_ACK` (`0x01` = `RESULT`) use **different type tables** on
 * purpose, so a parser must know which direction it is reading. That is why this file handles only the
 * watch→phone direction and [CapsAckCodec] handles the other.
 *
 * A `CAPS` missing `DEVICE_INFO`, `DISPLAY`, or both codec TLVs is malformed; the phone must answer
 * `RESULT.status = CAPS_MALFORMED` (`07-capabilities.md` §3.1). [decode] therefore returns whatever it
 * found, and the caller checks [Capabilities.isComplete] — decoding does not throw for a merely
 * incomplete list, because the negotiation layer needs the reason, not an exception.
 */
object CapsCodec {

    /** Registered `CAPS` TLV type numbers (`07-capabilities.md` §2). */
    object Type {
        /** `0x01` — [DeviceInfo], 16 bytes, mandatory. */
        const val DEVICE_INFO: Int = 0x01

        /** `0x02` — [DisplayCaps], 16 bytes, mandatory when the device has a screen. */
        const val DISPLAY: Int = 0x02

        /** `0x03` — [MjpegCaps], 4 bytes. */
        const val CODEC_MJPEG: Int = 0x03

        /** `0x04` — [H264Caps], 8 bytes. */
        const val CODEC_H264: Int = 0x04

        /** `0x05` — [InputCaps], 4 bytes. */
        const val INPUT: Int = 0x05

        /** `0x06` — [AudioCaps], 4 bytes. */
        const val AUDIO: Int = 0x06

        /** `0x07` — [MemoryCaps], 8 bytes. */
        const val MEMORY: Int = 0x07

        /** `0x08` — [PowerCaps], 4 bytes. */
        const val POWER: Int = 0x08

        /** `0x09` — [TransportCaps], variable (>= 4). */
        const val TRANSPORT: Int = 0x09

        /** `0x0A` — [SensorsCaps], 4 bytes. */
        const val SENSORS: Int = 0x0A

        /** First vendor-private type. Vendor TLVs must stay in `0x80..0xFF` (`07` §1.1). */
        const val VENDOR_FIRST: Int = 0x80

        /** `true` for the vendor-private range. */
        fun isVendor(type: Int): Boolean = type >= VENDOR_FIRST
    }

    /**
     * Parses a `CAPS` payload.
     *
     * @throws ProtocolException only for a structurally impossible TLV list (a `len` that runs past the
     *   end of the payload). A short or unknown TLV is skipped, not fatal.
     */
    fun decode(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): Capabilities {
        LittleEndian.requireRange(payload, offset, length)

        var deviceInfo: DeviceInfo? = null
        var display: DisplayCaps? = null
        var mjpeg: MjpegCaps? = null
        var h264: H264Caps? = null
        var input: InputCaps? = null
        var audio: AudioCaps? = null
        var memory: MemoryCaps? = null
        var power: PowerCaps? = null
        var transport: TransportCaps? = null
        var sensors: SensorsCaps? = null
        val vendor = mutableListOf<Tlv>()

        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            if (cursor + 2 > end) throw ProtocolException("CAPS truncated in a TLV header at offset $cursor")
            val type = LittleEndian.readU8(payload, cursor)
            val valueLength = LittleEndian.readU8(payload, cursor + 1)
            cursor += 2
            if (cursor + valueLength > end) {
                throw ProtocolException("CAPS TLV 0x%02X claims $valueLength bytes past the payload".format(type))
            }

            when (type) {
                Type.DEVICE_INFO -> if (valueLength >= DeviceInfo.SIZE_BYTES) {
                    deviceInfo = DeviceInfo.decode(payload, cursor)
                }

                Type.DISPLAY -> if (valueLength >= DisplayCaps.SIZE_BYTES) {
                    display = DisplayCaps.decode(payload, cursor)
                }

                Type.CODEC_MJPEG -> if (valueLength >= MjpegCaps.SIZE_BYTES) {
                    mjpeg = MjpegCaps.decode(payload, cursor)
                }

                Type.CODEC_H264 -> if (valueLength >= H264Caps.SIZE_BYTES) {
                    h264 = H264Caps.decode(payload, cursor)
                }

                Type.INPUT -> if (valueLength >= InputCaps.SIZE_BYTES) {
                    input = InputCaps.decode(payload, cursor)
                }

                Type.AUDIO -> if (valueLength >= AudioCaps.SIZE_BYTES) {
                    audio = AudioCaps.decode(payload, cursor)
                }

                Type.MEMORY -> if (valueLength >= MemoryCaps.SIZE_BYTES) {
                    memory = MemoryCaps.decode(payload, cursor)
                }

                Type.POWER -> if (valueLength >= PowerCaps.SIZE_BYTES) {
                    power = PowerCaps.decode(payload, cursor)
                }

                Type.TRANSPORT -> if (valueLength >= TransportCaps.MIN_SIZE_BYTES) {
                    transport = TransportCaps.decode(payload, cursor, valueLength)
                }

                Type.SENSORS -> if (valueLength >= SensorsCaps.SIZE_BYTES) {
                    sensors = SensorsCaps.decode(payload, cursor)
                }

                else -> if (Type.isVendor(type)) {
                    vendor += Tlv(type, payload.copyOfRange(cursor, cursor + valueLength))
                }
                // Reserved standard types are skipped silently, exactly like unknown ones.
            }

            cursor += valueLength
        }

        return Capabilities(
            deviceInfo = deviceInfo,
            display = display,
            mjpeg = mjpeg,
            h264 = h264,
            input = input,
            audio = audio,
            memory = memory,
            power = power,
            transport = transport,
            sensors = sensors,
            vendorTlvs = vendor,
        )
    }

    /**
     * Serialises [capabilities] into a TLV list.
     *
     * Used by the tests and by any phone-side tooling that needs to replay a realistic `CAPS`; a real
     * phone never sends `CAPS` (it is the watch's frame). Vendor TLVs are appended last, unchanged.
     */
    fun encode(capabilities: Capabilities): ByteArray {
        val out = ByteArrayOutputStream(64)

        fun tlv(type: Int, value: ByteArray) {
            require(value.size <= 0xFF) { "TLV 0x%02X value is too long: ${value.size}".format(type) }
            out.write(type)
            out.write(value.size)
            out.write(value, 0, value.size)
        }

        capabilities.deviceInfo?.let { tlv(Type.DEVICE_INFO, it.encode()) }
        capabilities.display?.let { tlv(Type.DISPLAY, it.encode()) }
        capabilities.mjpeg?.let { tlv(Type.CODEC_MJPEG, it.encode()) }
        capabilities.h264?.let { tlv(Type.CODEC_H264, it.encode()) }
        capabilities.input?.let { tlv(Type.INPUT, it.encode()) }
        capabilities.audio?.let { tlv(Type.AUDIO, it.encode()) }
        capabilities.memory?.let { tlv(Type.MEMORY, it.encode()) }
        capabilities.power?.let { tlv(Type.POWER, it.encode()) }
        capabilities.transport?.let { tlv(Type.TRANSPORT, it.encode()) }
        capabilities.sensors?.let { tlv(Type.SENSORS, it.encode()) }
        capabilities.vendorTlvs.forEach { tlv(it.type, it.value) }

        val bytes = out.toByteArray()
        require(bytes.size <= ProtocolLimits.CAPS_MAX_PAYLOAD) {
            "CAPS payload would be ${bytes.size} bytes, over the ${ProtocolLimits.CAPS_MAX_PAYLOAD}-byte budget"
        }
        return bytes
    }
}
