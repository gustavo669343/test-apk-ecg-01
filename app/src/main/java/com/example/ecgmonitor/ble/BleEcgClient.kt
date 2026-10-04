package com.example.ecgmonitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class EcgPacket(
    val raw: Float,
    val filtered: Float,
    val isPeak: Boolean = false,
    val bpm: Float? = null,
    val aiClass: Int? = null,
    val aiClassName: String? = null,
    val classPercentages: FloatArray? = null,
    val spo2: Float? = null,
    val ppgBpm: Float? = null,
    val rawIr: Long? = null
)

/**
 * BLE Client supporting real-time ECG, SpO2, PPG, and AI classification stream
 * from ESP32-S3 (ecg_monitor_ai).
 */
class BleEcgClient(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: android.bluetooth.BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val scanHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow("Chưa kết nối")
    val status = _status.asStateFlow()

    private val _packets = MutableSharedFlow<EcgPacket>(extraBufferCapacity = 1024)
    val packets = _packets.asSharedFlow()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        _status.value = "Đang kết nối: ${device.name ?: device.address}"
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, callback)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            _status.value = "Bluetooth chưa bật hoặc không khả dụng"
            return
        }
        connect(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        _status.value = "Đã ngắt kết nối"
    }

    @SuppressLint("MissingPermission")
    fun startScan(onDevice: (BluetoothDevice) -> Unit) {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _status.value = "Bluetooth chưa bật hoặc thiết bị không hỗ trợ BLE"
            return
        }
        scanning = true
        try {
            scanner.startScan(scanCallback.apply { found = onDevice })
            _status.value = "Đang quét thiết bị BLE..."
            scanHandler.postDelayed({ stopScan() }, 8_000)
        } catch (_: SecurityException) {
            scanning = false
            _status.value = "Thiếu quyền Bluetooth – hãy cấp quyền Thiết bị ở gần"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {}
        if (_status.value == "Đang quét thiết bị BLE...") {
            _status.value = "Đã quét xong – chọn thiết bị để kết nối"
        }
    }

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        var found: ((BluetoothDevice) -> Unit)? = null
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
            if (!scanning) return
            found?.invoke(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _status.value = "Quét BLE lỗi: $errorCode"
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                _status.value = "Đã kết nối, đang tìm kiếm dịch vụ..."
                gatt.discoverServices()
            } else {
                _status.value = "Mất kết nối (mã $status)"
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // Priority 1: ESP32 ecg_monitor_ai Service
            var characteristic = gatt.getService(ESP32_SERVICE_UUID)?.getCharacteristic(ESP32_CHARACTERISTIC_UUID)

            // Priority 2: Fallback to Legacy ECG Service if present
            if (characteristic == null) {
                characteristic = gatt.getService(LEGACY_SERVICE_UUID)?.getCharacteristic(LEGACY_NOTIFY_UUID)
            }

            if (characteristic == null) {
                _status.value = "Không tìm thấy dịch vụ ECG trên thiết bị"
                return
            }

            gatt.setCharacteristicNotification(characteristic, true)
            characteristic.getDescriptor(CCCD_UUID)?.let { descriptor ->
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
            _status.value = "Đang nhận dữ liệu ECG & AI (125 Hz)"
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            parsePacket(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parsePacket(value)
        }
    }

    /**
     * Parses incoming notifications:
     * 1. ESP32 ASCII String format:
     *    R:<raw>|F:<filtered>|P:<0|1>|B:<bpm>|C:<class>|CN:<name>|Q:<p0,p1,p2,p3,p4>|SPO2:<spo2>|PBPM:<ppg>|IR:<ir>
     * 2. Fallback Binary Int16 format for backwards compatibility.
     */
    private fun parsePacket(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return

        // Check if message is ASCII text protocol
        val firstChar = bytes[0].toInt().toChar()
        if (firstChar in 'A'..'Z' || firstChar in 'a'..'z') {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (text.startsWith("DBG:")) return // Ignore debug messages

            val lines = text.split('\n', '\r')
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                parseAsciiChunk(trimmed)
            }
        } else {
            // Binary fallback: 16-bit little-endian samples
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (data.remaining() >= 2) {
                val s = data.short.toFloat()
                _packets.tryEmit(EcgPacket(raw = s, filtered = s))
            }
        }
    }

    private fun parseAsciiChunk(chunk: String) {
        val tokens = chunk.split('|')
        var raw: Float? = null
        var filtered: Float? = null
        var isPeak = false
        var bpm: Float? = null
        var aiClass: Int? = null
        var aiClassName: String? = null
        var classPercentages: FloatArray? = null
        var spo2: Float? = null
        var ppgBpm: Float? = null
        var rawIr: Long? = null

        for (token in tokens) {
            val colonIdx = token.indexOf(':')
            if (colonIdx <= 0) continue
            val key = token.substring(0, colonIdx).trim()
            val value = token.substring(colonIdx + 1).trim()
            when (key) {
                "R" -> raw = value.toFloatOrNull()
                "F" -> filtered = value.toFloatOrNull()
                "P" -> isPeak = (value == "1")
                "B" -> bpm = value.toFloatOrNull()
                "C" -> aiClass = value.toIntOrNull()
                "CN" -> aiClassName = value
                "Q" -> {
                    val qList = value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (qList.isNotEmpty()) {
                        classPercentages = qList.toFloatArray()
                    }
                }
                "SPO2" -> spo2 = value.toFloatOrNull()
                "PBPM" -> ppgBpm = value.toFloatOrNull()
                "IR" -> rawIr = value.toLongOrNull()
            }
        }

        if (raw != null || filtered != null) {
            val r = raw ?: filtered ?: 0f
            val f = filtered ?: raw ?: 0f
            _packets.tryEmit(
                EcgPacket(
                    raw = r,
                    filtered = f,
                    isPeak = isPeak,
                    bpm = bpm,
                    aiClass = aiClass,
                    aiClassName = aiClassName,
                    classPercentages = classPercentages,
                    spo2 = spo2,
                    ppgBpm = ppgBpm,
                    rawIr = rawIr
                )
            )
        }
    }

    companion object {
        // ESP32 ecg_monitor_ai UUIDs
        val ESP32_SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val ESP32_CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

        // Legacy UUIDs (fallback)
        val LEGACY_SERVICE_UUID: UUID = UUID.fromString("0000EC00-0000-1000-8000-00805F9B34FB")
        val LEGACY_NOTIFY_UUID: UUID = UUID.fromString("0000EC01-0000-1000-8000-00805F9B34FB")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
