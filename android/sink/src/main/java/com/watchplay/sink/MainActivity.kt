package com.watchplay.sink

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.watchplay.sdk.touch.TouchAction
import kotlin.math.min

/**
 * WatchPlay sink: the watch screen, faked convincingly on a second Android device.
 *
 * ```
 *   GattServerTransport (advertise, notify)  -->  WatchSink (HELLO/CAPS/VIDEO/FEEDBACK)
 *                                                      |
 *                                              BitmapFactory --> ImageView
 *   onTouch  -->  touch in panel pixels  -->  TOUCH frame   -->  phone
 * ```
 *
 * The whole UI is built in code: one square panel, one status pane. Nothing here is protocol
 * behaviour — that is [WatchSink] — so this file stays about views and permissions.
 */
class MainActivity : Activity() {

    private lateinit var panel: ImageView
    private lateinit var logView: TextView

    private var sink: WatchSink? = null
    private var transport: GattServerTransport? = null
    private var frame: Bitmap? = null
    private var panelSidePx: Int = DEFAULT_PANEL_SIDE_PX

    private val logLines = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panelSidePx = panelSideFromDisplay()
        setContentView(buildUi())

        if (hasBluetoothPermissions()) {
            startSink()
        } else {
            requestPermissions(requiredPermissions().toTypedArray(), REQUEST_BLUETOOTH)
        }
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        sink?.stop()
        sink = null
        transport = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (hasBluetoothPermissions()) {
            startSink()
        } else {
            appendLog("Bluetooth permissions are required: the sink advertises a GATT service")
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- wiring

    private fun startSink() {
        if (sink != null) return
        val serverTransport = GattServerTransport(this)
        val watchSink = WatchSink(
            transport = serverTransport,
            panelSidePx = panelSidePx,
            onFrame = { bitmap, _ ->
                runOnUiThread {
                    frame = bitmap
                    panel.setImageBitmap(bitmap)
                }
            },
            onStatus = { line ->
                Log.i(TAG, line)
                synchronized(logLines) {
                    logLines.addLast(line)
                    while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
                }
            },
        )
        transport = serverTransport
        sink = watchSink
        watchSink.start()
        appendLog("advertising as a ${panelSidePx}px round sink")
    }

    private fun renderStatus() {
        val text = buildString {
            append(sink?.statusLine() ?: "sink not started")
            append("\n--- log ---")
            synchronized(logLines) {
                for (line in logLines.toList().takeLast(MAX_LOG_LINES)) append('\n').append(line)
            }
        }
        logView.text = text
    }

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
    }

    // ---------------------------------------------------------------- touch

    private fun onPanelTouch(event: MotionEvent): Boolean {
        val sink = sink ?: return false
        val bitmap = frame
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            else -> return false
        }
        if (bitmap == null) {
            // Before the first frame there is no panel rect to map into; 05-input.md §2.1 needs pixels.
            return true
        }
        val scale = min(
            panel.width.toFloat() / bitmap.width,
            panel.height.toFloat() / bitmap.height,
        )
        val dx = (panel.width - bitmap.width * scale) / 2f
        val dy = (panel.height - bitmap.height * scale) / 2f
        val x = ((event.x - dx) / scale).toInt()
        val y = ((event.y - dy) / scale).toInt()
        sink.sendTouch(action, x, y)
        return true
    }

    // ---------------------------------------------------------------- views

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF05070C.toInt())
        }

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        panel = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            setOnTouchListener { _, event -> onPanelTouch(event) }
        }
        container.addView(panel)

        logView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.45f,
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0xFFB9C4D4.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.START
            setPadding(24, 16, 24, 24)
            text = "starting…"
        }

        root.addView(container)
        root.addView(logView)
        return root
    }

    private fun panelSideFromDisplay(): Int {
        val metrics = resources.displayMetrics
        val side = min(metrics.widthPixels, metrics.heightPixels)
        return min(side, DEFAULT_PANEL_SIDE_PX)
    }

    // ---------------------------------------------------------------- permissions

    private fun hasBluetoothPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    companion object {
        private const val TAG = "WatchPlaySink"

        /** Status refresh period. */
        private const val TICK_MS = 500L

        /** How many log lines the pane keeps. */
        private const val MAX_LOG_LINES = 12

        /** The panel is square; this caps the pixel side so JPEG encoding stays cheap. */
        private const val DEFAULT_PANEL_SIDE_PX = 480

        private const val REQUEST_BLUETOOTH = 1
    }
}
