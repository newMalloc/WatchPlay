package com.watchplay.sink

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchplay.sdk.protocol.Frame
import com.watchplay.sdk.protocol.FrameCodec
import com.watchplay.sdk.protocol.FrameParser
import com.watchplay.sdk.protocol.ProtocolLimits
import com.watchplay.sdk.transport.GattStatus
import com.watchplay.sdk.transport.SendResult
import com.watchplay.sdk.transport.TransportCapabilities
import com.watchplay.sdk.transport.TransportListener
import com.watchplay.sdk.transport.TransportModule
import com.watchplay.sdk.transport.TransportState
import com.watchplay.sdk.transport.TransportStats
import com.watchplay.sdk.transport.WatchPlayGattProfile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bearer A from the **watch** side: a GATT server that advertises the WatchPlay service.
 *
 * ```
 *   phone (central)                          this app (peripheral)
 *   a7c0a701  Write Without Response  ---->   onCharacteristicWriteRequest  -> FrameParser
 *   a7c0a702  Notify                  <----   notifyCharacteristicChanged   <- FrameCodec
 * ```
 *
 * ## Two things that make the server side different
 *
 *  * **Readiness needs the CCCD.** A central can connect and write TX before it has subscribed to
 *    RX; sending `HELLO` then would be wasted because the phone cannot answer. The link is only
 *    [TransportState.READY] after `onDescriptorWriteRequest` reports subscriptions enabled
 *    (`01-transport.md` §2.5).
 *  * **Credit is one-directional.** `FEEDBACK.credits` governs `VIDEO`, which only the phone sends,
 *    so this transport neither needs nor enforces an outbound window: it reports
 *    [UNBOUNDED_CREDITS] and [setCreditWindow] is recorded but unused. The sink's own frames
 *    (`HELLO`, `CAPS`, `FEEDBACK`, `PONG`) are tiny and are allowed at any credit (`§6.3`).
 *
 * Notifications are split at `MTU − 3`, the same `bearer_tu` the phone uses; a protocol frame never
 * relies on a notification boundary (`01-transport.md` §6.2).
 */
class GattServerTransport(
    private val context: Context,
    /** Advertised name, shown to whoever scans; purely diagnostic. */
    private val deviceName: String = "WatchPlay Sink",
) : TransportModule {

    private val parser = FrameParser()
    private val credit = AtomicInteger(UNBOUNDED_CREDITS)
    private val framesReceived = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)
    private val framesSent = AtomicLong(0L)
    private val bytesSent = AtomicLong(0L)
    private val lock = Any()

    /** Serialises whole frames onto the notify path; see [send]. */
    private val sendLock = Any()

    @Volatile
    private var listener: TransportListener? = null

    @Volatile
    private var currentState: TransportState = TransportState.CLOSED

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var server: BluetoothGattServer? = null

    @Volatile
    private var rx: BluetoothGattCharacteristic? = null

    @Volatile
    private var central: BluetoothDevice? = null

    @Volatile
    private var negotiatedMtu: Int = ProtocolLimits.MIN_ATT_MTU

    override val id: String
        get() = "ble-gatt-server"

    override val state: TransportState
        get() = currentState

    override val mtu: Int
        get() = negotiatedMtu

    override val availableCreditChunks: Int
        get() = credit.get()

    override val capabilities: TransportCapabilities
        get() = TransportCapabilities(
            mtu = mtu,
            isReliableOrdered = true,
            supportsCreditFlowControl = false, // the reverse channel carries no video
            maxFrameBytes = ProtocolLimits.HEADER_SIZE + ProtocolLimits.MAX_PAYLOAD_BYTES,
            stackName = STACK_NAME,
        )

    override val stats: TransportStats
        get() = TransportStats(
            framesSent = framesSent.get(),
            framesReceived = framesReceived.get(),
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            creditAvailable = credit.get(),
            roundTripTimeMs = -1,
            resyncCount = parser.resyncCount,
            headerCrcErrors = parser.headerCrcErrors,
            lastError = lastError,
        )

    override fun open(listener: TransportListener) {
        require(currentState == TransportState.CLOSED || currentState == TransportState.FAILED) {
            "open() from $currentState; close the transport first"
        }
        this.listener = listener
        lastError = null
        parser.reset()
        negotiatedMtu = ProtocolLimits.MIN_ATT_MTU
        transitionTo(TransportState.OPENING)

        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            fail("missing Bluetooth permission(s): ${missing.joinToString()}")
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is off or unavailable")
            return
        }

        val gattServer = try {
            manager.openGattServer(context, serverCallback)
        } catch (error: SecurityException) {
            fail("openGattServer denied: ${error.message}")
            return
        }
        if (gattServer == null) {
            fail("openGattServer returned null")
            return
        }
        server = gattServer
        if (!gattServer.addService(buildService())) {
            fail("addService(${WatchPlayGattProfile.SERVICE}) was refused")
            return
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            fail("this adapter cannot advertise; the sink must be the peripheral")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(WatchPlayGattProfile.SERVICE))
            .build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (error: SecurityException) {
            fail("startAdvertising denied: ${error.message}")
        }
    }

    override fun send(frame: Frame): SendResult {
        if (!currentState.isOpen) return SendResult.NotConnected
        if (frame.payload.size > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            return SendResult.TooLarge(frame.payload.size, ProtocolLimits.MAX_PAYLOAD_BYTES)
        }
        val device = central ?: return SendResult.Failed("no central connected", null)
        val characteristic = rx ?: return SendResult.Failed("RX characteristic is not published", null)

        val encoded = FrameCodec.encode(frame)
        val bearerTu = (negotiatedMtu - ProtocolLimits.ATT_WRITE_OVERHEAD).coerceAtLeast(20)
        // A frame's fragments must not interleave with another frame's: the decoder thread sends
        // FEEDBACK while the transport thread may be answering a PING, and RFCOMM/GATT preserve order
        // per sender only. One lock keeps the byte stream a stream.
        synchronized(sendLock) {
            var offset = 0
            while (offset < encoded.size) {
                val end = minOf(offset + bearerTu, encoded.size)
                val piece = encoded.copyOfRange(offset, end)
                val accepted = notify(device, characteristic, piece)
                if (!accepted) {
                    return SendResult.Failed("notification rejected at offset $offset/${encoded.size}", null)
                }
                offset = end
            }
        }
        framesSent.incrementAndGet()
        bytesSent.addAndGet(encoded.size.toLong())
        return SendResult.Sent(encoded.size)
    }

    override fun setCreditWindow(credits: Int) {
        require(credits >= 0) { "a credit window must not be negative: $credits" }
        // Recorded for stats only: the phone never sends VIDEO to the sink, so there is nothing to gate.
        credit.set(credits.coerceAtMost(UNBOUNDED_CREDITS))
        listener?.onCreditWindowChanged(credit.get())
    }

    override fun close(reason: String?) {
        if (currentState == TransportState.CLOSED) return
        transitionTo(TransportState.CLOSING)
        stopAdvertising()
        val device = central
        try {
            if (device != null) server?.cancelConnection(device)
        } catch (_: SecurityException) {
            // The server is being closed anyway.
        }
        try {
            server?.close()
        } catch (_: SecurityException) {
            // Same.
        }
        server = null
        rx = null
        central = null
        synchronized(lock) { parser.clearBuffer() }
        transitionTo(TransportState.CLOSED)
        listener = null
    }

    private fun notify(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        piece: ByteArray,
    ): Boolean {
        val gattServer = server ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(device, characteristic, false, piece) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = piece
                @Suppress("DEPRECATION")
                gattServer.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (error: SecurityException) {
            lastError = "notify denied: ${error.message}"
            false
        }
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(
            WatchPlayGattProfile.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val tx = BluetoothGattCharacteristic(
            WatchPlayGattProfile.TX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notifyCharacteristic = BluetoothGattCharacteristic(
            WatchPlayGattProfile.RX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        notifyCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                WatchPlayGattProfile.CLIENT_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(tx)
        service.addCharacteristic(notifyCharacteristic)
        return service
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                central = device
                Log.i(TAG, "central connected: ${device.address}")
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "central disconnected: ${device.address} (status $status)")
                if (device == central) {
                    central = null
                    if (!currentState.isTerminal) transitionTo(TransportState.OPENING)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (mtu >= ProtocolLimits.MIN_ATT_MTU) {
                negotiatedMtu = mtu
                Log.i(TAG, "MTU is now $mtu; bearer_tu = ${mtu - ProtocolLimits.ATT_WRITE_OVERHEAD}")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                respond(device, requestId, GattStatus.SUCCESS, offset, value)
            }
            if (descriptor.uuid != WatchPlayGattProfile.CLIENT_CONFIGURATION) return
            val enabled = value?.any { it.toInt() != 0 } ?: false
            Log.i(TAG, "CCCD ${if (enabled) "enabled" else "disabled"}")
            if (enabled) {
                central = device
                transitionTo(TransportState.READY)
                listener?.onCreditWindowChanged(credit.get())
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                respond(device, requestId, GattStatus.SUCCESS, offset, value)
            }
            if (characteristic.uuid != WatchPlayGattProfile.TX) return
            if (value == null || value.isEmpty()) return
            deliver(value)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != GattStatus.SUCCESS) {
                fail("service ${service.uuid} was not added: status $status")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising as $deviceName")
        }

        override fun onStartFailure(errorCode: Int) {
            fail("advertising failed: error $errorCode")
        }
    }

    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        try {
            server?.sendResponse(device, requestId, status, offset, value)
        } catch (error: SecurityException) {
            lastError = "sendResponse denied: ${error.message}"
        }
    }

    private fun deliver(bytes: ByteArray) {
        synchronized(lock) {
            val frames = parser.offer(bytes)
            for (frame in frames) {
                framesReceived.incrementAndGet()
                bytesReceived.addAndGet((ProtocolLimits.HEADER_SIZE + frame.payload.size).toLong())
                val target = listener ?: return@synchronized
                try {
                    target.onFrameReceived(frame)
                } catch (error: RuntimeException) {
                    target.onTransportError(error)
                }
            }
        }
    }

    private fun stopAdvertising() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        try {
            manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
            // Closing anyway.
        }
    }

    private fun missingPermissions(): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
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

    override fun toString(): String =
        "GattServerTransport(state=$currentState, mtu=$mtu, central=${central?.address ?: "none"})"

    companion object {
        private const val TAG = "WatchPlaySink"

        /** Stack name reported in [TransportCapabilities.stackName]. */
        const val STACK_NAME: String = "ble-gatt-server"

        /**
         * The sink never sends `VIDEO`, so it never needs a real outbound window. Reporting a large
         * number keeps `TransportStats` readable without pretending to have flow control.
         */
        const val UNBOUNDED_CREDITS: Int = 1_024
    }
}
