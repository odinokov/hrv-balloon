package com.hrvballoon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hrvballoon.core.HrsPacket
import com.hrvballoon.core.parseHrm
import java.util.UUID

/** BLE Heart Rate Service client. All listener callbacks run on the main thread. */
@SuppressLint("MissingPermission")
class HrmClient(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onScanResult(address: String, name: String?)
        /** How many distinct Bluetooth devices of any kind the scan has heard so far: 0 means the radio or stack, not the strap. */
        fun onScanProgress(seen: Int)
        /** The system refused or dropped the scan ([code] is a ScanCallback.SCAN_FAILED_* value); nothing will be found. */
        fun onScanFailed(code: Int)
        fun onLink(connected: Boolean)
        /** Bluetooth was switched on while no strap was being tracked (e.g. from quick settings). */
        fun onBluetoothOn()
        /** One short line about the latest step of the connection (GATT status codes, retries), for the status band. */
        fun onBle(note: String)
        /** [nowMs] is SystemClock.elapsedRealtime() captured when the notification arrived. */
        fun onPacket(nowMs: Long, packet: HrsPacket)
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    // GATT callbacks arrive on binder threads; these are read there and written on main.
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile private var streamed = false   // notifications were enabled at least once on this target
    @Volatile private var streaming = false  // notifications are enabled on the current link
    @Volatile private var linked = false     // the link is up right now (CONNECTED … DISCONNECTED)
    private var target: BluetoothDevice? = null
    private var reopenAuto = false
    private val reopen = Runnable { if (!closed && gatt == null) open(reopenAuto) }
    private val heard = HashSet<String>()   // every address the current scan has reported, strap or not
    private var seeking: String? = null      // address we are scanning for before connecting to it
    private val seekTimeout = Runnable {
        if (seeking == null) return@Runnable
        seeking = null
        stopScan()
        note("NOT SEEN IN ${SEEK_MS / 1000}S, TRYING ANYWAY")
        open(false)
    }
    private var scanning = false
    private var tries = 0          // direct connect attempts since connect()
    private var refreshed = false  // the GATT cache was refreshed once for this target
    /** Devices seen by the scan: they carry the address type (the strap's is static random); getRemoteDevice(address) assumes public. */
    private val found = HashMap<String, BluetoothDevice>()
    /** A pending direct connect that never answered, or a link that never reached streaming: close it ourselves and retry — a cancelled connect does not reliably call back. */
    private fun stalled(what: String) = Runnable {
        val g = gatt ?: return@Runnable
        if (streaming) return@Runnable   // this link is fine; an earlier one having streamed says nothing about it
        g.close()
        gatt = null
        retryOrWait(what)
    }
    private val connectTimeout = stalled("NO ANSWER IN ${CONNECT_TIMEOUT_MS / 1000}S")
    private val setupTimeout = stalled("NO SERVICES IN ${SETUP_TIMEOUT_MS / 1000}S")

    val bluetoothEnabled get() = adapter?.isEnabled == true

    /** Bluetooth toggled off and on: connectGatt returned null while off, so nothing else would ever reconnect. */
    private val btState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON || closed || gatt != null) return
            if (target != null) open(false) else listener.onBluetoothOn()
        }
    }

    init {
        context.registerReceiver(btState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    }

    private fun note(s: String) = main.post { if (!closed) listener.onBle(s) }

    private fun scheduleOpen(autoConnect: Boolean) {
        reopenAuto = autoConnect
        main.removeCallbacks(reopen)
        main.postDelayed(reopen, RETRY_MS)
    }

    /** Drops a connect that has no live link yet, retries and timeouts included: a controller that is initiating does not deliver scan results. */
    private fun cancelPending() {
        main.removeCallbacks(reopen)
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(setupTimeout)
        if (!linked) { gatt?.close(); gatt = null }
    }

    /** On main, with [gatt] already null: a fresh link that never streamed gets a few quick direct attempts before autoConnect. */
    private fun retryOrWait(what: String) {
        listener.onLink(false)
        if (!streamed && tries < DIRECT_TRIES) {
            note("$what, RETRY $tries/$DIRECT_TRIES")
            scheduleOpen(false)
        } else {
            note("$what, WAITING FOR STRAP")
            open(true)
        }
    }

    /** The scan has no hardware filter (so it can count everything it hears); straps are picked out here. */
    private fun isStrap(r: ScanResult): Boolean {
        val rec = r.scanRecord ?: return false
        if (rec.serviceUuids?.any { it.uuid == HRS } == true) return true
        val name = rec.deviceName ?: r.device.name ?: return false
        return name.contains("HRM", ignoreCase = true)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val strap = isStrap(result)
            main.post {
                if (heard.add(d.address)) listener.onScanProgress(heard.size)
                if (!strap) return@post
                found[d.address] = d
                if (seeking == d.address) {   // the strap we were looking for: connect from the scan result itself
                    seeking = null
                    main.removeCallbacks(seekTimeout)
                    stopScan()
                    target = d
                    note("STRAP SEEN")
                    open(false)
                    return@post
                }
                listener.onScanResult(d.address, d.name ?: result.scanRecord?.deviceName)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            main.post { listener.onScanFailed(errorCode) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return   // a client we already replaced (never close here: a stale read must not kill a live client)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linked = true
                    main.removeCallbacks(connectTimeout)
                    if (g.discoverServices()) { note("LINKED, READING SERVICES"); main.postDelayed(setupTimeout, SETUP_TIMEOUT_MS) }
                    else { note("DISCOVER REFUSED"); g.disconnect() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    linked = false
                    streaming = false
                    g.close()
                    main.post {
                        if (closed || g !== gatt) return@post
                        main.removeCallbacks(connectTimeout)
                        main.removeCallbacks(setupTimeout)
                        gatt = null
                        retryOrWait("GATT $status")
                    }
                }
            }
        }

        /** Any setup failure goes through disconnect() so the one reconnect path handles it. */
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(HRS) else null
            val ch = service?.getCharacteristic(HRM)
            val cccd = ch?.getDescriptor(CCCD)
            if (ch == null || cccd == null) {
                note("SERVICES $status, HR ${if (service == null) "MISSING" else "INCOMPLETE"}")
                // A stale GATT cache hides the service; the hidden refresh() drops it before the next discovery.
                if (!refreshed) { refreshed = true; runCatching { g.javaClass.getMethod("refresh").invoke(g) } }
                g.disconnect()
                return
            }
            if (!g.setCharacteristicNotification(ch, true)) { note("NOTIFY REFUSED"); g.disconnect(); return }
            if (Build.VERSION.SDK_INT >= 33) {
                // Refused writes give no callback on 33+: the return code is the only word.
                val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (rc != BluetoothStatusCodes.SUCCESS) { note("CCCD WRITE $rc"); g.disconnect() }
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { note("NOTIFY FAILED $status"); g.disconnect(); return }
            streamed = true
            streaming = true
            note("STREAMING")
            main.post { main.removeCallbacks(setupTimeout); listener.onLink(true) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") deliver(ch.value ?: return)
        }

        private fun deliver(value: ByteArray) {
            val now = SystemClock.elapsedRealtime()
            val packet = try { parseHrm(value) } catch (e: IllegalArgumentException) { return }
            main.post { listener.onPacket(now, packet) }
        }
    }

    /** The picker's scan: drops any pending connect or seek first. */
    fun startScan() {
        seeking = null
        main.removeCallbacks(seekTimeout)
        cancelPending()
        beginScan()
    }

    private fun beginScan() {
        if (scanning) return
        scanning = true
        heard.clear()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    /**
     * Scan first, connect from what is heard: the scan result carries the right address type and proves the strap is
     * advertising at all. A strap seen earlier in this process is connected to at once; one not heard within [SEEK_MS]
     * gets a blind direct connect anyway. Never connect under a running scan.
     */
    fun connect(address: String) {
        stopScan()
        seeking = null
        main.removeCallbacks(seekTimeout)
        main.removeCallbacks(reopen)
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(setupTimeout)
        gatt?.close()
        gatt = null
        linked = false
        streaming = false
        target = found[address] ?: remote(address)
        tries = 0
        streamed = false
        refreshed = false
        if (found[address] != null) { open(false); return }
        seeking = address
        note("LOOKING FOR STRAP")
        beginScan()
        main.postDelayed(seekTimeout, SEEK_MS)
    }

    /**
     * A saved address with no scan result behind it. getRemoteDevice() assumes a public address unless the stack still has
     * the device cached from a recent scan (a Bluetooth restart empties that cache), and a random address connected as
     * public is never answered. The strap's is static random (top two bits 11; 01 is a resolvable one): say so on 33+.
     */
    private fun remote(address: String): BluetoothDevice {
        val top = address.substring(0, 2).toInt(16) and 0xC0
        return if (Build.VERSION.SDK_INT >= 33 && (top == 0xC0 || top == 0x40)) adapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        else adapter.getRemoteDevice(address)
    }

    /** connectGatt returns null while Bluetooth is off or the stack is restarting; retry until it takes. */
    private fun open(autoConnect: Boolean) {
        val device = target ?: return
        if (!autoConnect) tries++
        note(if (autoConnect) "WAITING FOR STRAP" else "CONNECTING $tries/$DIRECT_TRIES")
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) { note("NO GATT, RETRY"); scheduleOpen(autoConnect) }
        else if (!autoConnect) main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    fun close() {
        closed = true
        stopScan()
        main.removeCallbacks(seekTimeout)
        main.removeCallbacks(reopen)
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(setupTimeout)
        context.unregisterReceiver(btState)
        gatt?.close()
        gatt = null
    }

    companion object {
        val HRS: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HRM: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val RETRY_MS = 2000L
        const val DIRECT_TRIES = 4
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SETUP_TIMEOUT_MS = 10_000L
        const val SEEK_MS = 10_000L
    }
}
