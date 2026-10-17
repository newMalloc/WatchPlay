package com.watchplay.demo

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.watchplay.sdk.transport.WatchPlayGattProfile

/**
 * Finds watch sinks that advertise the WatchPlay service.
 *
 * The filter is the service UUID, not a name: `01-transport.md` §2.1 fixes the UUID, while the
 * advertised name is a diagnostic that any vendor may change. Results are deduplicated by address so
 * one watch that advertises twice does not appear twice.
 */
class BleWatchScanner(private val context: Context) {

    /** One discovered sink. */
    data class Found(
        val name: String,
        val address: String,
        val device: BluetoothDevice,
        val rssi: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()
    private var scanner: BluetoothLeScanner? = null
    private var onDone: ((List<Found>) -> Unit)? = null

    /** `true` when this device can scan at all (a phone with a radio and Bluetooth on). */
    fun isAvailable(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return false
        return adapter.isEnabled && adapter.bluetoothLeScanner != null
    }

    /** Permissions the caller must hold before [scan]; asking for them is the Activity's job. */
    fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** `true` when every permission in [requiredPermissions] is granted. */
    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Scans for [timeoutMs] and reports every sink seen, newest RSSI first.
     *
     * A previously running scan is stopped first, so a repeated tap cannot leave two callbacks alive.
     */
    fun scan(timeoutMs: Long, onDone: (List<Found>) -> Unit) {
        stop()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bleScanner = manager?.adapter?.bluetoothLeScanner
        if (bleScanner == null) {
            onDone(emptyList())
            return
        }
        this.onDone = onDone
        scanner = bleScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(WatchPlayGattProfile.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner.startScan(listOf(filter), settings, callback)
        } catch (_: SecurityException) {
            scanner = null
            this.onDone = null
            onDone(emptyList())
            return
        }
        handler.postDelayed({ finish() }, timeoutMs)
    }

    /** Stops the scan without reporting; safe to call at any time. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        scanner?.let { active ->
            try {
                active.stopScan(callback)
            } catch (_: SecurityException) {
                // The radio is going away; nothing to stop.
            }
        }
        scanner = null
        onDone = null
        found.clear()
    }

    private fun finish() {
        val callback = onDone ?: return
        onDone = null
        scanner?.let { active ->
            try {
                active.stopScan(this.callback)
            } catch (_: SecurityException) {
                // Same as stop().
            }
        }
        scanner = null
        val results = found.values.sortedByDescending { it.rssi }
        found.clear()
        callback(results)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = try {
                result.scanRecord?.deviceName ?: device.name
            } catch (_: SecurityException) {
                null
            }
            val address = try {
                device.address
            } catch (_: SecurityException) {
                return
            }
            found[address] = Found(
                name = name ?: "WatchPlay sink",
                address = address,
                device = device,
                rssi = result.rssi,
            )
        }

        override fun onScanFailed(errorCode: Int) {
            finish()
        }
    }
}
