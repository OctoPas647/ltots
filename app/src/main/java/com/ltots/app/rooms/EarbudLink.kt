package com.ltots.app.rooms

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.ltots.app.CrashReport

/**
 * How this phone's link to the earbuds is doing (Earbuds mode).
 *
 * Without root, Android doesn't tell an app the signal strength of a Bluetooth audio link. Two
 * ordinary ways get close, and both are tried:
 *  - The WF-1000XM6 also speak Bluetooth Low Energy (for LE Audio and the Sony app). Opening a
 *    plain LE connection to them lets the phone read the signal strength of that connection
 *    once a second ([BluetoothGatt.readRemoteRssi]) — same earbuds, same radios, same walls.
 *  - Failing that, the earbuds' LE announcements, when they make any, carry a strength too.
 * When neither works on a phone, the room falls back on what the playing phone can always see:
 * its Bluetooth stream stalling, and the earbuds disconnecting.
 */
class EarbudLink(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private var a2dp: BluetoothA2dp? = null

    /** The earbuds the room follows (Bluetooth address). */
    @Volatile var target: String? = null
        set(v) {
            if (field != v) { field = v; resetRssi() }
        }

    /** The earbuds are connected to this phone for music. */
    @Volatile var connected: Boolean = false; private set
    /** Signal strength (dBm) if measured in the last few seconds. */
    val rssi: Int? get() = lastRssi.takeIf { System.currentTimeMillis() - rssiAtMs < 4_000 }
    /** Where the strength comes from, for the status line. */
    @Volatile var rssiSource: String = ""; private set
    /** Music devices connected to this phone now: name to address. */
    @Volatile var connectedDevices: List<Pair<String, String>> = emptyList(); private set

    @Volatile private var lastRssi: Int? = null
    @Volatile private var rssiAtMs = 0L
    private var gatt: BluetoothGatt? = null
    private var gattFailures = 0
    private var gattRetryAtMs = 0L
    private var scanning = false
    private var running = false

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun canScan(): Boolean =
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running) return
        running = true
        if (hasPermission()) runCatching {
            adapter?.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy as BluetoothA2dp }
                override fun onServiceDisconnected(profile: Int) { a2dp = null }
            }, BluetoothProfile.A2DP)
        }
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        closeGatt()
        stopScan()
        a2dp?.let { p -> runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, p) } }
        a2dp = null
        connected = false
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { poll() }.onFailure { CrashReport.handled("EarbudLink.poll", it) }
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("MissingPermission") // checked in hasPermission()
    private fun poll() {
        if (!hasPermission()) { connected = false; return }
        val devices = a2dp?.connectedDevices.orEmpty()
        connectedDevices = devices.map { (runCatching { it.name }.getOrNull() ?: it.address) to it.address }
        val t = target
        val dev = devices.firstOrNull { it.address.equals(t, ignoreCase = true) }
        connected = dev != null
        if (dev == null) {
            closeGatt()
            stopScan()
            return
        }
        // Signal strength over LE.
        val g = gatt
        if (g != null) runCatching { g.readRemoteRssi() }
        else if (System.currentTimeMillis() >= gattRetryAtMs && gattFailures < 4) openGatt(dev)
        if (rssi == null && canScan()) startScan(dev.address) else if (rssi != null && rssiSource == "LE link") stopScan()
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(dev: BluetoothDevice) {
        gatt = runCatching {
            dev.connectGatt(app, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gattFailures = 0
                        runCatching { g.readRemoteRssi() }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        handler.post {
                            if (gatt === g) {
                                closeGatt()
                                gattFailures++
                                gattRetryAtMs = System.currentTimeMillis() + 15_000L * gattFailures
                            }
                        }
                    }
                }

                override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && rssi < 0) {
                        lastRssi = rssi
                        rssiAtMs = System.currentTimeMillis()
                        rssiSource = "LE link"
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            gattFailures++
            gattRetryAtMs = System.currentTimeMillis() + 30_000L
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!result.device.address.equals(target, ignoreCase = true)) return
            // The LE link's reading is better when both exist.
            if (rssiSource == "LE link" && System.currentTimeMillis() - rssiAtMs < 3000) return
            lastRssi = result.rssi
            rssiAtMs = System.currentTimeMillis()
            rssiSource = "LE announcements"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(address: String) {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(address.uppercase()).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                scanCallback,
            )
            scanning = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun resetRssi() {
        lastRssi = null
        rssiAtMs = 0
        gattFailures = 0
        gattRetryAtMs = 0
        handler.post { closeGatt(); stopScan() }
    }

    /** Whether the earbuds are receiving this phone's music right now. */
    @SuppressLint("MissingPermission")
    fun streaming(): Boolean {
        if (!hasPermission()) return false
        val dev = a2dp?.connectedDevices?.firstOrNull { it.address.equals(target, ignoreCase = true) } ?: return false
        return runCatching { a2dp?.isA2dpPlaying(dev) == true }.getOrDefault(false)
    }

    /** The earbuds as an audio output, to send the music to them specifically. */
    fun audioDevice(): AudioDeviceInfo? {
        val t = target ?: return null
        val am = app.getSystemService(AudioManager::class.java) ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                it.address.equals(t, ignoreCase = true)
        }
    }

    /** What this phone plays through now (for the room's list). */
    fun outputName(): String {
        val am = app.getSystemService(AudioManager::class.java) ?: return "This phone"
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        val wired = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        return (bt ?: wired)?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Phone speaker"
    }

    companion object {
        /** Earbuds or headphones by their name (the room's first guess for which to follow). */
        fun looksLikeEarbuds(name: String): Boolean {
            val n = name.lowercase()
            return listOf("wf-", "wh-", "xm", "buds", "airpods", "earbud", "pods", "headphone", "momentum").any { it in n } &&
                listOf("jbl", "flip", "charge", "go ", "boombox", "speaker", "soundbar").none { it in n }
        }
    }
}
