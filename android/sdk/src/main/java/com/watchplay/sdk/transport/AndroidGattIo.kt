package com.watchplay.sdk.transport

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The only part of the BLE transport that touches `android.bluetooth`.
 *
 * Everything protocol-shaped lives in [BleGattTransport]; this class is a thin, literal translation
 * of `BluetoothGatt` callbacks into [GattIoCallback] events, plus the two Android-specific details
 * that are easy to get wrong:
 *
 *  * **API 33 split.** `writeCharacteristic(characteristic, value, writeType)` and
 *    `writeDescriptor(descriptor, value)` return a status code; the older overloads mutate the
 *    characteristic/descriptor and return a bare boolean that cannot distinguish "stack busy" from
 *    "not allowed". Both paths are implemented, and the legacy `false` is reported as
 *    [GattWriteOutcome.StackBusy] because a retry is the only recovery either way.
 *  * **Permissions.** The SDK manifest declares `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (API 31+) and the
 *    legacy pair below that, but *requesting* them is the app's job. Missing permissions are reported
 *    as a disconnected link with a message that names the missing permission.
 *
 * `BLUETOOTH_ADVERTISE` is never needed: the phone is the central.
 */
class AndroidGattIo(
    private val context: Context,
    private val device: BluetoothDevice,
) : GattIo {

    @Volatile
    private var callback: GattIoCallback? = null

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var tx: BluetoothGattCharacteristic? = null

    @Volatile
    private var rx: BluetoothGattCharacteristic? = null

    override val peerAddress: String
        get() = try {
            device.address
        } catch (_: SecurityException) {
            // API 31+ hides the address without BLUETOOTH_CONNECT; it is diagnostic only.
            "unavailable"
        }

    override fun connect(callback: GattIoCallback) {
        this.callback = callback
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            callback.onDisconnected(
                GattStatus.NOT_CONNECTED,
                "missing Bluetooth permission(s): ${missing.joinToString()} — request them in the app first",
            )
            return
        }

        gatt?.close()
        gatt = null
        tx = null
        rx = null
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: SecurityException) {
            callback.onDisconnected(GattStatus.NOT_CONNECTED, "connectGatt denied: ${error.message}")
            return
        }
        if (gatt == null) {
            callback.onDisconnected(GattStatus.NOT_CONNECTED, "connectGatt returned null")
        }
    }

    override fun requestMtu(mtu: Int) {
        val gatt = gatt ?: return failure("requestMtu without a connection")
        val started = try {
            gatt.requestMtu(mtu)
        } catch (error: SecurityException) {
            callback?.onDisconnected(GattStatus.NOT_CONNECTED, "requestMtu denied: ${error.message}")
            return
        }
        if (!started) failure("requestMtu($mtu) was refused by the stack")
    }

    override fun discoverServices() {
        val gatt = gatt ?: return failure("discoverServices without a connection")
        val started = try {
            gatt.discoverServices()
        } catch (error: SecurityException) {
            callback?.onDisconnected(GattStatus.NOT_CONNECTED, "discoverServices denied: ${error.message}")
            return
        }
        if (!started) callback?.onServicesDiscovered(false, false, GattStatus.NOT_CONNECTED)
    }

    override fun setNotificationsEnabled(enabled: Boolean) {
        val gatt = gatt ?: return failure("setNotificationsEnabled without a connection")
        val characteristic = rx
        if (characteristic == null) {
            callback?.onNotificationsEnabled(false, GattStatus.NOT_CONNECTED)
            return
        }
        val ok = try {
            gatt.setCharacteristicNotification(characteristic, enabled)
        } catch (error: SecurityException) {
            callback?.onNotificationsEnabled(false, GattStatus.NOT_CONNECTED)
            return
        }
        if (!ok) {
            callback?.onNotificationsEnabled(false, GattStatus.NOT_CONNECTED)
            return
        }
        val descriptor = characteristic.getDescriptor(WatchPlayGattProfile.CLIENT_CONFIGURATION)
        if (descriptor == null) {
            callback?.onNotificationsEnabled(false, GattStatus.NOT_CONNECTED)
            return
        }
        val value = if (enabled) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (gatt.writeDescriptor(descriptor, value) != BluetoothStatusCodes.SUCCESS) {
                callback?.onNotificationsEnabled(false, GattStatus.NOT_CONNECTED)
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            if (!gatt.writeDescriptor(descriptor)) {
                callback?.onNotificationsEnabled(false, GattStatus.NOT_CONNECTED)
            }
        }
        // The result arrives in onDescriptorWrite; nothing is reported here on success.
    }

    override fun write(bytes: ByteArray): GattWriteOutcome {
        val gatt = gatt ?: return GattWriteOutcome.Rejected("no connection")
        val characteristic = tx ?: return GattWriteOutcome.Rejected("TX characteristic not resolved")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
                when (status) {
                    BluetoothStatusCodes.SUCCESS -> GattWriteOutcome.Accepted
                    BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> GattWriteOutcome.StackBusy
                    else -> GattWriteOutcome.Rejected("writeCharacteristic status $status")
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                // `false` is undocumented: full stack queue or a dead link. Retrying is the only
                // recovery that is safe in both cases, and BleGattTransport bounds the retries.
                if (gatt.writeCharacteristic(characteristic)) GattWriteOutcome.Accepted else GattWriteOutcome.StackBusy
            }
        } catch (error: SecurityException) {
            GattWriteOutcome.Rejected("writeCharacteristic denied: ${error.message}")
        }
    }

    override fun disconnect() {
        try {
            gatt?.disconnect()
        } catch (_: SecurityException) {
            // Nothing to do: the link is going away either way.
        }
    }

    override fun close() {
        try {
            gatt?.close()
        } catch (_: SecurityException) {
            // Same as disconnect(): the handle is being released.
        }
        gatt = null
        tx = null
        rx = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (status == GattStatus.SUCCESS) {
                    callback?.onConnected()
                } else {
                    callback?.onDisconnected(status, "connected with status $status")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    tx = null
                    rx = null
                    callback?.onDisconnected(status, "disconnected")
                }
                else -> callback?.onDisconnected(status, "unexpected state $newState")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            callback?.onMtuChanged(mtu, status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = if (status == GattStatus.SUCCESS) {
                gatt.getService(WatchPlayGattProfile.SERVICE)
            } else {
                null
            }
            tx = service?.getCharacteristic(WatchPlayGattProfile.TX)
            rx = service?.getCharacteristic(WatchPlayGattProfile.RX)
            callback?.onServicesDiscovered(
                hasTx = tx != null,
                hasRx = rx != null,
                status = status,
            )
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != WatchPlayGattProfile.CLIENT_CONFIGURATION) return
            callback?.onNotificationsEnabled(true, status)
        }

        @Deprecated("Superseded by the API 33 overload, which also exists on this class.")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            deliver(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(characteristic, value)
        }

        private fun deliver(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != WatchPlayGattProfile.RX) return
            if (value.isEmpty()) return
            callback?.onBytesReceived(value)
        }
    }

    private fun failure(message: String) {
        callback?.onDisconnected(GattStatus.NOT_CONNECTED, message)
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

    override fun toString(): String = "AndroidGattIo($peerAddress)"
}
