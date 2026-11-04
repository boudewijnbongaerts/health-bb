package com.boudhealth.tape

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Renpho Smart Tape (ES-Tape) over Bluetooth LE, protocol taken from the Renpho Health app's own logs:
 *  - device name "ES-Tape", service 0783b03e-8535-b5a0-7140-a304d2495cb7
 *  - notify characteristic …cb8; no pairing, key or start command — just enable notifications
 *  - every press of the tape's button sends ASCII like "*09560;00000;0000PM\n"
 *      "09560" = 95.60 (hundredths), last letter = unit: M = cm, otherwise inches
 */
class TapeSession(context: Context) {

    sealed interface State {
        data object Idle : State
        data object Searching : State
        data object Connecting : State
        /** Connected and listening; [last] is the latest reading in cm. */
        data class Ready(val last: Double?) : State
        data class Failed(val message: String) : State
    }

    private val app = context.applicationContext
    private val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    @SuppressLint("MissingPermission") // checked by the caller (BLUETOOTH_SCAN / BLUETOOTH_CONNECT)
    fun start() {
        if (adapter == null || !adapter.isEnabled) { _state.value = State.Failed("Bluetooth is off. Turn it on and try again."); return }
        stop()
        _state.value = State.Searching
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scan) ?: run { _state.value = State.Failed("Bluetooth scanner not available."); return }
        scanning = true
        // the tape only advertises for a while after it's switched on
        main.postDelayed({
            if (_state.value == State.Searching) { stopScan(); _state.value = State.Failed("Tape not found. Press the tape's button to wake it, close the Renpho app, and try again.") }
        }, 30_000)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        _state.value = State.Idle
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
        scanning = false
    }

    private val scan = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith(NAME) || _state.value != State.Searching) return
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = State.Failed("Bluetooth scan failed ($errorCode). Try again.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        _state.value = State.Connecting
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    if (gatt === g) gatt = null
                    if (_state.value !is State.Idle) _state.value = State.Failed("The tape disconnected. Tap “Try again”.")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(NOTIFY)
            if (ch == null) { _state.value = State.Failed("This doesn't look like the Renpho tape."); return }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            if (cccd != null) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            _state.value = State.Ready(null)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != NOTIFY) return
            parse(value)?.let { _state.value = State.Ready(it) }
        }
    }

    companion object {
        const val NAME = "ES-Tape"
        val SERVICE: UUID = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb7")
        val NOTIFY: UUID = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb8")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val FORMAT = Regex("""^\*(\d{5});""")

        /** "*09560;00000;0000PM\n" → 95.6 (cm). Returns null for anything unexpected. */
        fun parse(bytes: ByteArray): Double? {
            val text = bytes.toString(Charsets.US_ASCII).trim()
            val raw = FORMAT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val value = raw / 100.0
            val metric = text.lastOrNull()?.uppercaseChar() == 'M'
            val cm = if (metric) value else value * 2.54
            return cm.takeIf { it in 10.0..250.0 }
        }
    }
}
