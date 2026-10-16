package com.watchplay.sdk.transport

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.ProtocolLimits
import java.util.concurrent.atomic.AtomicInteger

/**
 * **Skeleton** — Bearer B, BLE L2CAP Connection-oriented Channels (`01-transport.md` §3).
 *
 * ## Why and when
 *
 * CoC bypasses ATT entirely: one SDU per protocol frame, only a 4-byte L2CAP header, its own
 * link-layer credit, and a channel that does not compete with GATT service discovery. At an MTU of
 * 512 a 260-byte protocol frame fits in a single SDU, so no bearer-level fragmentation happens at all.
 *
 * Availability is the catch:
 *
 *  * Android exposes the client API from **API 29** (`BluetoothDevice.createL2capChannel`), which is
 *    why every method touching it is [RequiresApi]-annotated and [open] checks the runtime version
 *    first;
 *  * the PSM must be **dynamic** (`01-transport.md` §3.2 step 1: a fixed PSM is forbidden in v0.1
 *    because it can collide with system services) and is published through
 *    [WatchPlayGattProfile.PSM] as a little-endian u16, or carried in `CAPS`/`CAPS_ACK` TLVs — never
 *    both;
 *  * the GATT link **must stay up** as a fallback and heartbeat path (`01-transport.md` §3.2 step 4);
 *  * two credit systems coexist. Sending requires `WatchPlay credit >= 1` **and** `L2CAP credit >= 1`
 *    **and** no congestion. Treating them as one counter is the classic failure: the sender blocks
 *    inside `write()` and stops servicing heartbeats (`01-transport.md` §3.3).
 *
 * ## Implemented today
 *
 * The API-level guard, state machine, credit window and MTU bookkeeping. [open] reports a precise
 * failure instead of throwing.
 *
 * ## TODO for the next contributor
 *
 *  1. Read the PSM from `a7c0a704` (or from the CAPS TLV), then
 *     `device.createL2capChannel(psm)` / `createInsecureL2capChannel(psm)` and `connect()` on a worker.
 *  2. Reader thread: `socket.inputStream` → `FrameParser` (CoC is a **stream**; never assume one read is
 *     one frame).
 *  3. Writer thread: serialise with `FrameCodec`, split at the MTU, and **never** block the caller on
 *     `write()`; if L2CAP credit is exhausted, return the fragment to the queue and yield.
 *  4. Probe with `PING`/`PONG` within 2 s of link-up and fall back to GATT if silent
 *     (`01-transport.md` §3.5).
 *  5. On `IOException`, transition to `FAILED` and let the session send `BYE(TRANSPORT_FAILURE)`.
 *
 * @property context application context, for permission checks.
 * @property device the watch to connect to.
 * @property psm the dynamically discovered PSM; there is deliberately no default.
 */
class BleL2capTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val psm: Int,
) : TransportModule {

    init {
        require(psm in 1..0xFFFF) { "psm must be a 16-bit nonzero value, was $psm" }
    }

    /** Outbound credit window; `0` until the watch grants one. */
    private val credit = AtomicInteger(0)

    @Volatile
    private var listener: TransportListener? = null

    @Volatile
    private var currentState: TransportState = TransportState.CLOSED

    @Volatile
    private var negotiatedMtu: Int = 0

    @Volatile
    private var lastError: String? = null

    override val id: String
        get() = "${STACK_NAME}:${device.address}:$psm"

    override val state: TransportState
        get() = currentState

    override val mtu: Int
        get() = if (negotiatedMtu > 0) negotiatedMtu else DEFAULT_COC_MTU

    override val availableCreditChunks: Int
        get() = credit.get()

    override val capabilities: TransportCapabilities
        get() = TransportCapabilities(
            mtu = mtu,
            isReliableOrdered = true,
            supportsCreditFlowControl = true,
            maxFrameBytes = ProtocolLimits.HEADER_SIZE + ProtocolLimits.MAX_PAYLOAD_BYTES,
            stackName = STACK_NAME,
        )

    override val stats: TransportStats
        get() = TransportStats(
            creditAvailable = credit.get(),
            roundTripTimeMs = -1,
            lastError = lastError,
        )

    /** `true` when this Android version can use L2CAP CoC at all. */
    val isSupportedOnThisDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun open(listener: TransportListener) {
        require(!currentState.isOpen) { "already open: $currentState" }
        this.listener = listener
        transitionTo(TransportState.OPENING)

        if (!isSupportedOnThisDevice) {
            fail(
                "L2CAP CoC needs Android 10 (API 29); this device is API ${Build.VERSION.SDK_INT}. " +
                    "Use BleGattTransport instead.",
            )
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            fail("missing Bluetooth permission(s): ${missing.joinToString()}")
            return
        }
        openChannel()
    }

    /**
     * L2CAP half of [open], kept separate so the API-29 surface stays in one annotated place.
     *
     * TODO(next contributor): steps 1-5 of the class documentation.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openChannel() {
        fail(
            "BleL2capTransport is a skeleton: createL2capChannel($psm)/connect() and the reader/writer " +
                "threads are not implemented yet. Use BleGattTransport or LoopbackTransport meanwhile.",
        )
    }

    override fun send(frame: Frame): SendResult {
        if (!currentState.isOpen) return SendResult.NotConnected
        if (frame.payload.size > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            return SendResult.TooLarge(frame.payload.size, ProtocolLimits.MAX_PAYLOAD_BYTES)
        }
        val previous = credit.getAndUpdate { current -> if (current > 0) current - 1 else 0 }
        if (previous <= 0) return SendResult.NoCredit(needed = 1, available = 0)

        // TODO(next contributor): write to the socket output stream from the writer thread and only
        // then report Sent; on IOException report Failed and transition to FAILED.
        credit.incrementAndGet()
        return SendResult.Failed("write path not implemented", null)
    }

    override fun close(reason: String?) {
        if (currentState == TransportState.CLOSED) return
        transitionTo(TransportState.CLOSING)
        // TODO(next contributor): socket?.close() and join the reader/writer threads.
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

    override fun toString(): String = "BleL2capTransport(${device.address}, psm=$psm, state=$currentState, mtu=$mtu)"

    companion object {
        /** Stack name reported in [TransportCapabilities.stackName]. */
        const val STACK_NAME: String = "ble-l2cap-coc"

        /** MTU the phone requests on CoC (`01-transport.md` §3.4: 1024 desired, 512 the ESP32 limit). */
        const val DEFAULT_COC_MTU: Int = 1024

        /** Smallest CoC MTU worth using; below this CoC has no advantage over GATT. */
        const val MIN_USEFUL_COC_MTU: Int = 512
    }
}
