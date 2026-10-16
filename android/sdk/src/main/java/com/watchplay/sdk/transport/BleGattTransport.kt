package com.watchplay.sdk.transport

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.ProtocolLimits
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Skeleton** — Bearer A, the BLE GATT transport that every WatchPlay implementation must provide
 * (`01-transport.md` §2, §9 T-01/T-02).
 *
 * ## Shape of the real implementation
 *
 * ```
 *   phone (central)                         watch (peripheral)
 *   a7c0a701  Write Without Response  ---->  TX, ATT writes of bearer_tu bytes
 *   a7c0a702  Notify                  <----  RX, one protocol frame at a time
 *   a7c0a703  Write + Notify          <-->   CTRL (optional)
 *   a7c0a704  Read + Notify           <----  PSM for a later L2CAP switch (optional)
 * ```
 *
 * `bearer_tu` is `MTU − 3`, so the recommended MTU **247** yields 244 usable bytes — exactly the
 * default `max_chunk_bytes`. `01-transport.md` §2.2 explains why 247 is preferred over 517: a
 * protocol frame (16 + 244 = 260 bytes) then fragments into just `244 + 16`, and some Android stacks
 * report 517 while truncating notifications at 512, which produces a 2-byte-short frame and a
 * header-CRC resync storm.
 *
 * ## Implemented today
 *
 * State machine, credit window, MTU bookkeeping and the permission checks. [open] fails with a precise
 * message instead of throwing, and the pipeline treats the link as down.
 *
 * ## TODO for the next contributor
 *
 *  1. `connectGatt(context, autoConnect = false, callback, TRANSPORT_LE)`, wait for
 *     `onConnectionStateChange(CONNECTED)`; on `status == 133` close the instance and retry with
 *     `min(30 s, 1 s * 2^n) + jitter` (`01-transport.md` §2.5, §8.3).
 *  2. `discoverServices()`, then resolve [WatchPlayGattProfile.SERVICE], `TX` and `RX`; fail cleanly
 *     with `BYE(TRANSPORT_FAILURE)` when they are missing.
 *  3. `requestMtu(247)` **exactly once** and wait for `onMtuChanged`; if the result is below 247 set
 *     `max_chunk_bytes = MTU − 3`, recompute `chunk_total = ceil(frame_bytes / (max_chunk_bytes − 20))`
 *     and report it in `CAPS_ACK.RESULT` (`01-transport.md` §2.2 steps 1–4).
 *  4. Subscribe: `setCharacteristicNotification` **and** a CCCD write to `CLIENT_CONFIGURATION`,
 *     waiting for `onDescriptorWrite` before streaming (`01-transport.md` §2.5).
 *  5. `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` before entering `STREAMING`, and
 *     `CONNECTION_PRIORITY_BALANCED` when idle (`01-transport.md` §2.4).
 *  6. [send]: split the encoded frame into `bearer_tu` pieces, **one ATT write per piece**, never mixing
 *     bytes of two frames in one write (`01-transport.md` §5.2); respect credit **and** GATT congestion.
 *  7. Receive: feed notification bytes into a `FrameParser`. GATT preserves message boundaries, but the
 *     parser is still the only thing that defines frames (`01-transport.md` §5.3).
 *
 * @property context application context, for permission checks and `connectGatt`.
 * @property device the watch, resolved by the caller's own scan or `bondedDevices` lookup.
 * @property requestMtu ATT MTU to request; the specification says 247.
 */
class BleGattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val requestMtu: Int = ProtocolLimits.DEFAULT_MTU,
) : TransportModule {

    /** Outbound credit window; `0` until the watch grants one (`01-transport.md` §6.1). */
    private val credit = AtomicInteger(0)

    @Volatile
    private var listener: TransportListener? = null

    @Volatile
    private var currentState: TransportState = TransportState.CLOSED

    @Volatile
    private var negotiatedMtu: Int = 0

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var lastError: String? = null

    override val id: String
        get() = "${STACK_NAME}:${device.address}"

    override val state: TransportState
        get() = currentState

    override val mtu: Int
        get() = if (negotiatedMtu > 0) negotiatedMtu else requestMtu

    override val availableCreditChunks: Int
        get() = credit.get()

    override val capabilities: TransportCapabilities
        get() = TransportCapabilities(
            mtu = mtu,
            isReliableOrdered = true, // BLE L2CAP guarantees in-order, error-checked delivery.
            supportsCreditFlowControl = true,
            maxFrameBytes = ProtocolLimits.HEADER_SIZE + ProtocolLimits.MAX_PAYLOAD_BYTES,
            stackName = STACK_NAME,
        )

    override val stats: TransportStats
        get() = TransportStats(
            creditAvailable = credit.get(),
            roundTripTimeMs = -1, // GATT has no RTT primitive; measure it with PING/PONG.
            lastError = lastError,
        )

    /** Largest chunk payload this link can carry: `MTU − 3`, which becomes `max_chunk_bytes`. */
    val maxChunkPayloadBytes: Int
        get() = (mtu - ATT_WRITE_OVERHEAD).coerceAtLeast(ProtocolLimits.MIN_MAX_CHUNK_BYTES)

    override fun open(listener: TransportListener) {
        require(!currentState.isOpen) { "already open: $currentState" }
        this.listener = listener
        transitionTo(TransportState.OPENING)

        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            fail("missing Bluetooth permission(s): ${missing.joinToString()} — request them in the app before connecting")
            return
        }

        // TODO(next contributor): steps 1-5 of the class documentation. Until then the link reports
        // FAILED rather than pretending to be connected.
        fail(
            "BleGattTransport is a skeleton: connectGatt/discoverServices/requestMtu/CCCD wiring is not " +
                "implemented yet. Use LoopbackTransport (tests, demo) or BleL2capTransport meanwhile.",
        )
    }

    override fun send(frame: Frame): SendResult {
        if (!currentState.isOpen) return SendResult.NotConnected

        val encodedSize = ProtocolLimits.HEADER_SIZE + frame.payload.size
        if (frame.payload.size > maxChunkPayloadBytes) {
            // The protocol cap and the ATT cap are different numbers; chunking is the caller's job.
            return SendResult.TooLarge(frame.payload.size, maxChunkPayloadBytes)
        }
        val previous = credit.getAndUpdate { current -> if (current > 0) current - 1 else 0 }
        if (previous <= 0) return SendResult.NoCredit(needed = 1, available = 0)

        // TODO(next contributor): write `encodedSize` bytes to TX, splitting at bearer_tu; only report
        // Sent after the write is accepted, and give the credit back on failure.
        credit.incrementAndGet()
        return SendResult.Failed("write path not implemented", null)
    }

    override fun close(reason: String?) {
        if (currentState == TransportState.CLOSED) return
        transitionTo(TransportState.CLOSING)
        // TODO(next contributor): gatt?.disconnect() then close(), waiting for the state callback.
        gatt = null
        transitionTo(TransportState.CLOSED)
        listener = null
    }

    override fun setCreditWindow(credits: Int) {
        require(credits >= 0) { "a credit window must not be negative: $credits" }
        credit.set(credits)
        if (credits > 0 && currentState == TransportState.CONGESTED) transitionTo(TransportState.READY)
        listener?.onCreditWindowChanged(credits)
    }

    private fun missingPermissions(): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filterNot { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun fail(message: String) {
        lastError = message
        transitionTo(TransportState.FAILED)
        listener?.onTransportError(IllegalStateException(message))
    }

    private fun transitionTo(next: TransportState) {
        if (currentState == next) return
        currentState = next
        listener?.onTransportStateChanged(next)
    }

    override fun toString(): String = "BleGattTransport(${device.address}, state=$currentState, mtu=$mtu)"

    companion object {
        /** Stack name reported in [TransportCapabilities.stackName]. */
        const val STACK_NAME: String = "ble-gatt"

        /** ATT write overhead: 1 byte opcode + 2 bytes attribute handle. */
        const val ATT_WRITE_OVERHEAD: Int = 3
    }
}
