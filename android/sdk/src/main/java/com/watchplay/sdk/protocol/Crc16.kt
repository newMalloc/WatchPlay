package com.watchplay.sdk.protocol

/**
 * CRC-16/CCITT-FALSE — the checksum algorithm used by the WatchPlay frame header.
 *
 * Parameters (normative, `docs/spec/02-wire-format.md` §2):
 *
 * | Parameter    | Value      |
 * | :----------- | :--------- |
 * | Polynomial   | `0x1021`   |
 * | Initial value| `0xFFFF`   |
 * | Input reflect| no         |
 * | Output reflect| no        |
 * | Xor-out      | `0x0000`   |
 * | Check (`"123456789"`) | `0x29B1` |
 *
 * The header CRC covers only bytes `0..13` of the frame header; the two CRC bytes themselves are
 * treated as zero while computing (see [FrameCodec.computeHeaderCrc]). The specification is explicit
 * that the payload is **not** covered: BLE's link-layer CRC24 plus the video chunk continuity checks
 * are considered sufficient, and per-chunk payload CRCs would burn ESP32-S3 CPU for no benefit.
 *
 * This is a table-driven implementation of the bitwise reference algorithm in the spec; both forms
 * produce identical results, and [CHECK_VALUE] pins that down in the unit tests.
 */
object Crc16 {
    /** CRC-16/CCITT-FALSE polynomial. */
    const val POLYNOMIAL: Int = 0x1021

    /** CRC-16/CCITT-FALSE initial register value. */
    const val INITIAL: Int = 0xFFFF

    /** Expected result for the ASCII input `"123456789"` (spec §2). */
    const val CHECK_VALUE: Int = 0x29B1

    /** The eight-bit-wide lookup table derived from [POLYNOMIAL]. */
    private val TABLE: IntArray = IntArray(256) { index ->
        var crc = index shl 8
        repeat(8) {
            crc = if (crc and 0x8000 != 0) {
                ((crc shl 1) xor POLYNOMIAL) and 0xFFFF
            } else {
                (crc shl 1) and 0xFFFF
            }
        }
        crc
    }

    /**
     * Folds [length] bytes of [data] starting at [offset] into the running register [crc].
     *
     * Exposed separately from [ccittFalse] so that a caller computing the header checksum over a
     * partially-filled buffer can stream the first and last regions independently.
     */
    fun update(crc: Int, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        LittleEndian.requireRange(data, offset, length)
        var register = crc and 0xFFFF
        for (index in offset until offset + length) {
            val tableIndex = ((register ushr 8) xor (data[index].toInt() and 0xFF)) and 0xFF
            register = ((register shl 8) xor TABLE[tableIndex]) and 0xFFFF
        }
        return register
    }

    /** Computes CRC-16/CCITT-FALSE over a byte range. Xor-out is `0x0000`, so no final step. */
    fun ccittFalse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int =
        update(INITIAL, data, offset, length)
}
