package com.watchplay.sdk.transport

/**
 * The GATT operations [BleGattTransport] needs, with `android.bluetooth` kept behind this seam.
 *
 * ## Why the seam exists
 *
 * `01-transport.md` §2.2 puts five steps between "connect" and "ready": `connectGatt`, service
 * discovery, one `requestMtu`, CCCD subscription and connection-priority changes. Everything that
 * makes those steps correct — the order, the retry on status 133, the `bearer_tu` recomputation after
 * a small MTU, the credit accounting — is protocol logic, and protocol logic is exactly what a JVM
 * unit test can check. `BluetoothGatt` cannot be instantiated on a desktop JVM, so it lives behind
 * this interface; [AndroidGattIo] is the only implementation that touches the radio.
 *
 * ## Threading
 *
 * Callbacks may arrive on any thread (in practice the binder thread). One callback per event, never
 * reentrant for the same event, and a callback must not block: the receive path also carries the
 * flow-control signalling.
 */
interface GattIo {

    /** Address of the peer, for logs and [TransportModule.id]. Never used for routing. */
    val peerAddress: String

    /**
     * Starts connecting. Every outcome, including an immediate failure, is reported through
     * [callback] — this method must not throw and must not block on the radio.
     */
    fun connect(callback: GattIoCallback)

    /** Requests an ATT MTU once; the result arrives at [GattIoCallback.onMtuChanged]. */
    fun requestMtu(mtu: Int)

    /** Starts service discovery; the result arrives at [GattIoCallback.onServicesDiscovered]. */
    fun discoverServices()

    /** Enables (or disables) notifications on RX, including the CCCD write. */
    fun setNotificationsEnabled(enabled: Boolean)

    /**
     * Writes one ATT unit. Returns immediately; this is not a completion callback.
     *
     * A `Write Without Response` write has no acknowledgement at the ATT layer, so "accepted" means
     * "handed to the controller's queue" (`01-transport.md` §2.3).
     */
    fun write(bytes: ByteArray): GattWriteOutcome

    /** Ends the connection, keeping the instance usable for a later [connect]. */
    fun disconnect()

    /** Releases the `BluetoothGatt` handle for good. */
    fun close()
}

/** What the controller did with one [GattIo.write]. */
sealed interface GattWriteOutcome {

    /** Handed to the controller. Ordering is the link's job from here on. */
    data object Accepted : GattWriteOutcome

    /**
     * The stack queue is full. Not an error: retry the same bytes later
     * (`01-transport.md` §2.5 — a full queue is backpressure, not a broken link).
     */
    data object StackBusy : GattWriteOutcome

    /** The write cannot be delivered; the link is unusable. */
    data class Rejected(val reason: String) : GattWriteOutcome
}

/** Callbacks from a [GattIo] to its owner. One callback per event, in link order. */
interface GattIoCallback {

    /** The link is up at the ATT layer; service discovery may start. */
    fun onConnected()

    /**
     * The link went down.
     *
     * @param status GATT status, or [GattStatus.NOT_CONNECTED] when no connection was ever made.
     *   `133` is Android's catch-all "device not found / out of resources" and is retried
     *   (`01-transport.md` §8.3).
     * @param reason diagnostic text, possibly `null`.
     */
    fun onDisconnected(status: Int, reason: String?)

    /** Result of [GattIo.requestMtu]; [mtu] is the ATT MTU actually in force. */
    fun onMtuChanged(mtu: Int, status: Int)

    /** Result of [GattIo.discoverServices], with the two characteristics the protocol needs. */
    fun onServicesDiscovered(hasTx: Boolean, hasRx: Boolean, status: Int)

    /** Result of [GattIo.setNotificationsEnabled], after the CCCD write completed. */
    fun onNotificationsEnabled(enabled: Boolean, status: Int)

    /** One ATT notification payload, exactly as received — possibly a bearer fragment. */
    fun onBytesReceived(bytes: ByteArray)
}

/** GATT status codes this SDK distinguishes. */
object GattStatus {

    /** `GATT_SUCCESS`. */
    const val SUCCESS: Int = 0

    /** No link exists (Android reports this for a failed `connectGatt`, not a GATT status). */
    const val NOT_CONNECTED: Int = -1

    /** Android's "error 133": connect failed or the controller ran out of resources. */
    const val ERROR_133: Int = 133
}
