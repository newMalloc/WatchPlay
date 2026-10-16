package com.watchplay.sdk.transport

import java.util.UUID

/**
 * The frozen BLE UUID allocation of WatchPlay v0.1 — `01-transport.md` §2.1.
 *
 * ## Derivation
 *
 * ```
 * WatchPlay base UUID: a7c00000-6cc1-4a51-8c1b-7b3f7ba7e0b2
 * layout:              XXXXXXXX-6CC1-4A51-8C1B-7B3F7BA7E0B2
 * first 32-bit group:   0xA7C0 (fixed high 16) ++ function code (low 16)
 * the remaining 96 bits never change
 * ```
 *
 * ## Why the 16-bit forms must never be used
 *
 * `0xA7C0` is **not** a Bluetooth SIG assignment; it is merely a constant inside WatchPlay's own
 * 128-bit space. `01-transport.md` §2.1 therefore forbids using the short form in any AD structure or
 * GATT definition — doing so would collide with whatever the SIG allocates later. Keep every UUID here
 * full-length.
 *
 * Function codes `0xA706`–`0xA7FF` are reserved for future standard extensions, and vendor UUIDs must
 * **not** live in `0xA7C0`–`0xA7FF`; vendors use their own base UUID (`07-capabilities.md` §1.1).
 *
 * ## Roles
 *
 * Directions are stated from the protocol's point of view: the phone is the **Source**, the watch the
 * **Sink**. `TX` is therefore phone → watch (written), `RX` is watch → phone (notified).
 */
object WatchPlayGattProfile {

    /** WatchPlay base UUID; every other value here is derived from it. */
    val BASE: UUID = UUID.fromString("a7c00000-6cc1-4a51-8c1b-7b3f7ba7e0b2")

    /** `A7C0` — the WPS primary service. */
    val SERVICE: UUID = UUID.fromString("a7c0a7c0-6cc1-4a51-8c1b-7b3f7ba7e0b2")

    /**
     * `A701` — **TX**, phone → watch. The watch declares Write Without Response (preferred) **and**
     * Write (fallback): a few stacks reject unacknowledged writes before bonding completes
     * (`01-transport.md` §2.3).
     */
    val TX: UUID = UUID.fromString("a7c0a701-6cc1-4a51-8c1b-7b3f7ba7e0b2")

    /** `A702` — **RX**, watch → phone, Notify only (Indicate would serialise the reverse channel). */
    val RX: UUID = UUID.fromString("a7c0a702-6cc1-4a51-8c1b-7b3f7ba7e0b2")

    /** `A703` — **CTRL**, bidirectional control channel, Write + Notify. Optional. */
    val CTRL: UUID = UUID.fromString("a7c0a703-6cc1-4a51-8c1b-7b3f7ba7e0b2")

    /**
     * `A704` — **PSM**, sink → source, Read + Notify. Optional: it publishes the dynamically allocated
     * L2CAP PSM as a little-endian u16 (`01-transport.md` §3.2 step 2).
     *
     * v0.1 **forbids** a fixed PSM, because it can collide with system services. The PSM is either read
     * from this characteristic or carried in `CAPS`/`CAPS_ACK` TLVs — never both
     * (`01-transport.md` §3.2 step 2).
     */
    val PSM: UUID = UUID.fromString("a7c0a704-6cc1-4a51-8c1b-7b3f7ba7e0b2")

    /** Standard Client Characteristic Configuration descriptor, needed to enable notifications. */
    val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** First reserved function code for future standard extensions. */
    const val FUNCTION_CODE_RESERVED_FIRST: Int = 0xA706

    /** Last reserved function code. */
    const val FUNCTION_CODE_RESERVED_LAST: Int = 0xA7FF

    /**
     * Builds a WatchPlay UUID from a 16-bit function code, for tools and tests.
     *
     * @throws IllegalArgumentException when the code lands in the reserved `0xA706..0xA7FF` range.
     */
    fun uuidFor(functionCode: Int): UUID {
        require(functionCode in 0x0000..0xFFFF) { "function code out of range: $functionCode" }
        require(functionCode < FUNCTION_CODE_RESERVED_FIRST || functionCode > FUNCTION_CODE_RESERVED_LAST) {
            "0x%04X is reserved for future standard extensions".format(functionCode)
        }
        val high = 0xA7C0
        return UUID.fromString(
            "%04x%04x-6cc1-4a51-8c1b-7b3f7ba7e0b2".format(high, functionCode),
        )
    }
}
