package com.watchplay.sdk.protocol

/**
 * Little-endian readers/writers for the packed WatchPlay wire structures.
 *
 * `docs/spec/02-wire-format.md` states that **all** multi-byte integers are little-endian and that
 * every structure is packed with no padding. Every multi-byte access in this SDK goes through this
 * object so the byte order can never drift between the encoder, the parser and the unit tests.
 *
 * Unsigned values are widened into a signed Kotlin type wide enough to hold them:
 *  * `u8` / `u16` -> [Int]
 *  * `u32` -> [Long]
 *
 * All methods validate their range and throw [IndexOutOfBoundsException] through [requireRange], so
 * a truncated buffer fails loudly instead of silently reading neighbouring memory.
 */
object LittleEndian {

    /** Reads an unsigned 8-bit value. */
    fun readU8(data: ByteArray, offset: Int): Int {
        requireRange(data, offset, 1)
        return data[offset].toInt() and 0xFF
    }

    /** Reads an unsigned 16-bit little-endian value into an [Int] in `0..65535`. */
    fun readU16(data: ByteArray, offset: Int): Int {
        requireRange(data, offset, 2)
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** Reads an unsigned 32-bit little-endian value into a [Long] in `0..4294967295`. */
    fun readU32(data: ByteArray, offset: Int): Long {
        requireRange(data, offset, 4)
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    /** Reads an unsigned 32-bit value as a raw [Int], for fields used as opaque bit patterns. */
    fun readU32AsInt(data: ByteArray, offset: Int): Int = readU32(data, offset).toInt()

    /** Writes the low 8 bits of [value]. */
    fun writeU8(data: ByteArray, offset: Int, value: Int) {
        requireRange(data, offset, 1)
        data[offset] = value.toByte()
    }

    /** Writes the low 16 bits of [value], little-endian. */
    fun writeU16(data: ByteArray, offset: Int, value: Int) {
        requireRange(data, offset, 2)
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    /** Writes the low 32 bits of [value], little-endian. */
    fun writeU32(data: ByteArray, offset: Int, value: Long) {
        requireRange(data, offset, 4)
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
        data[offset + 2] = (value ushr 16).toByte()
        data[offset + 3] = (value ushr 24).toByte()
    }

    /** Decodes UTF-8 without throwing on malformed input (device strings are attacker-controlled). */
    fun readUtf8(data: ByteArray, offset: Int, length: Int): String {
        requireRange(data, offset, length)
        return String(data, offset, length, Charsets.UTF_8)
    }

    /** Throws unless `data` holds at least `length` bytes starting at `offset`. */
    fun requireRange(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "buffer too small: need $length byte(s) at offset $offset but size is ${data.size}"
        }
    }

    /** `ceil(numerator / denominator)` for non-negative values; returns 0 when `numerator == 0`. */
    fun ceilDiv(numerator: Int, denominator: Int): Int {
        require(denominator > 0) { "denominator must be positive" }
        return if (numerator <= 0) 0 else (numerator + denominator - 1) / denominator
    }
}
