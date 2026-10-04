package com.example.ecgmonitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ecgmonitor.ble.BleEcgClient
import com.example.ecgmonitor.ble.EcgPacket
import com.example.ecgmonitor.data.EcgSession
import com.example.ecgmonitor.data.EcgSessionDao
import com.example.ecgmonitor.domain.EcgAnalysis
import com.example.ecgmonitor.domain.EcgAssessment
import com.example.ecgmonitor.domain.RealtimeEcgFilter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BleDeviceOption(
    val name: String,
    val address: String,
    val isRecommended: Boolean = false
)

class EcgViewModel(
    private val ble: BleEcgClient,
    private val dao: EcgSessionDao
) : ViewModel() {

    private val rawSamples = mutableListOf<Float>()
    private val espFilteredSamples = mutableListOf<Float>()
    private val androidFilteredSamples = mutableListOf<Float>()
    private val androidFilter = RealtimeEcgFilter()

    private var measurementStartedAt: Long? = null
    private var latestEspBpm: Float? = null

    // Waveform buffers for UI (last 5 seconds at 125 Hz = 625 samples)
    private val _rawWaveform = MutableStateFlow<List<Float>>(emptyList())
    val rawWaveform = _rawWaveform.asStateFlow()

    private val _espFilteredWaveform = MutableStateFlow<List<Float>>(emptyList())
    val espFilteredWaveform = _espFilteredWaveform.asStateFlow()

    private val _androidFilteredWaveform = MutableStateFlow<List<Float>>(emptyList())
    val androidFilteredWaveform = _androidFilteredWaveform.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount = _sampleCount.asStateFlow()

    // Biometrics from ESP32 & sensors
    private val _spo2 = MutableStateFlow<Float?>(null)
    val spo2 = _spo2.asStateFlow()

    private val _ppgBpm = MutableStateFlow<Float?>(null)
    val ppgBpm = _ppgBpm.asStateFlow()

    private val _aiClassIndex = MutableStateFlow<Int?>(null)
    val aiClassIndex = _aiClassIndex.asStateFlow()

    private val _aiClassName = MutableStateFlow<String?>("Normal (N)")
    val aiClassName = _aiClassName.asStateFlow()

    private val _aiClassPercentages = MutableStateFlow<FloatArray?>(null)
    val aiClassPercentages = _aiClassPercentages.asStateFlow()

    private val _isPeak = MutableStateFlow(false)
    val isPeak = _isPeak.asStateFlow()

    val status = ble.status

    private val _assessment = MutableStateFlow(EcgAnalysis.assess(emptyList()))
    val assessment = _assessment.asStateFlow()

    // Dynamic Ami mascot asset based on connection and health status
    private val _currentGifAsset = MutableStateFlow("ami_sleep.gif")
    val currentGifAsset = _currentGifAsset.asStateFlow()

    private val _devices = MutableStateFlow<List<BleDeviceOption>>(emptyList())
    val devices = _devices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<BleDeviceOption?>(null)
    val selectedDevice = _selectedDevice.asStateFlow()

    val isScanning = ble.isScanning

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectionEvent = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val connectionEvent = _connectionEvent.asSharedFlow()

    val sessions: StateFlow<List<EcgSession>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Collect BLE status to track connection state and Mascot emotion
        viewModelScope.launch {
            status.collect { s ->
                val connected = s.contains("Đã kết nối") || s.contains("Đang nhận")
                if (connected && !_isConnected.value) {
                    _isConnected.value = true
                    val devName = _selectedDevice.value?.name ?: "ESP32"
                    _connectionEvent.tryEmit("Đã kết nối thành công với $devName!")
                    _currentGifAsset.value = "ami_happy.gif"
                } else if (!connected && _isConnected.value) {
                    _isConnected.value = false
                }
                updateMascotEmotion(s)
            }
        }

        // Collect incoming ECG & Biometrics packets
        viewModelScope.launch {
            ble.packets.collect { packet ->
                if (espFilteredSamples.isEmpty()) {
                    measurementStartedAt = System.currentTimeMillis()
                }

                rawSamples += packet.raw
                espFilteredSamples += packet.filtered

                val androidCleaned = androidFilter.process(packet.raw)
                androidFilteredSamples += androidCleaned

                _sampleCount.value = espFilteredSamples.size

                // Update charts with sliding window (take last 625 samples = 5s @ 125Hz)
                _rawWaveform.value = rawSamples.takeLast(625)
                _espFilteredWaveform.value = espFilteredSamples.takeLast(625)
                _androidFilteredWaveform.value = androidFilteredSamples.takeLast(625)

                // Update biometrics
                packet.spo2?.takeIf { it > 0 }?.let { _spo2.value = it }
                packet.ppgBpm?.takeIf { it > 0 }?.let { _ppgBpm.value = it }
                packet.bpm?.takeIf { it > 0 }?.let { latestEspBpm = it }
                packet.aiClass?.let { _aiClassIndex.value = it }
                packet.aiClassName?.let { _aiClassName.value = it }
                packet.classPercentages?.let { _aiClassPercentages.value = it }
                _isPeak.value = packet.isPeak

                // Re-evaluate assessment every 25 samples (0.2s)
                if (espFilteredSamples.size % 25 == 0) {
                    val newAssessment = EcgAnalysis.assess(
                        samples = espFilteredSamples.takeLast(1250),
                        espBpm = latestEspBpm,
                        aiClass = _aiClassIndex.value,
                        aiClassName = _aiClassName.value,
                        spo2 = _spo2.value,
                        ppgBpm = _ppgBpm.value
                    )
                    _assessment.value = newAssessment
                    updateMascotEmotion(status.value, newAssessment)
                }
            }
        }
    }

    private fun updateMascotEmotion(currentStatus: String, currentAssessment: EcgAssessment? = assessment.value) {
        if (!currentStatus.contains("Đang nhận") && !currentStatus.contains("Đã kết nối")) {
            _currentGifAsset.value = if (currentStatus.contains("Đang quét") || currentStatus.contains("Đang kết nối")) {
                "ami_question.gif"
            } else {
                "ami_sleep.gif"
            }
            return
        }

        val aiCls = _aiClassIndex.value
        val bpm = currentAssessment?.bpm

        when {
            // Arrhythmia or PVC detected
            aiCls != null && aiCls in 1..4 -> _currentGifAsset.value = "ami_crying.gif"
            // Abnormal heart rate
            bpm != null && (bpm < 50 || bpm > 100) -> _currentGifAsset.value = "ami_crying.gif"
            // Low oxygen level
            _spo2.value != null && (_spo2.value ?: 100f) < 95f -> _currentGifAsset.value = "ami_question.gif"
            // Normal & healthy
            bpm != null && bpm in 55..95 -> _currentGifAsset.value = "ami_happy.gif"
            // Idle / Waiting signal
            else -> _currentGifAsset.value = "ami_nod.gif"
        }
    }

    fun scanDevices(reset: Boolean = true) {
        if (reset) _devices.value = emptyList()
        ble.startScan { device ->
            val devName = device.name ?: "Thiết bị không tên"
            val isTarget = devName.contains("ESP32", ignoreCase = true) ||
                    devName.contains("ECG", ignoreCase = true)
            val option = BleDeviceOption(devName, device.address, isRecommended = isTarget)
            if (_devices.value.none { it.address == option.address }) {
                _devices.value = if (isTarget) {
                    listOf(option) + _devices.value
                } else {
                    _devices.value + option
                }
            }
        }
    }

    fun connectTo(option: BleDeviceOption) {
        _selectedDevice.value = option
        ble.connect(option.address)
    }

    fun disconnect() {
        ble.disconnect()
        _currentGifAsset.value = "ami_sleep.gif"
    }

    fun saveCurrentMeasurement() {
        if (espFilteredSamples.isEmpty()) return
        val copy = espFilteredSamples.toList()
        val result = EcgAnalysis.assess(
            samples = copy,
            espBpm = latestEspBpm,
            aiClass = _aiClassIndex.value,
            aiClassName = _aiClassName.value,
            spo2 = _spo2.value,
            ppgBpm = _ppgBpm.value
        )

        // Store samples as Short array in Little-Endian ByteArray (preserving Room schema)
        val shortValues = copy.map {
            it.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }

        viewModelScope.launch {
            dao.insert(
                EcgSession(
                    startedAt = measurementStartedAt ?: System.currentTimeMillis(),
                    durationSeconds = copy.size / 125,
                    samples = toBytes(shortValues),
                    heartRateBpm = result.bpm,
                    note = "AI: ${_aiClassName.value ?: "N/A"} | SpO2: ${_spo2.value ?: "--"}%"
                )
            )

            // Reset buffers
            rawSamples.clear()
            espFilteredSamples.clear()
            androidFilteredSamples.clear()
            androidFilter.reset()
            measurementStartedAt = null
            latestEspBpm = null
            _rawWaveform.value = emptyList()
            _espFilteredWaveform.value = emptyList()
            _androidFilteredWaveform.value = emptyList()
            _sampleCount.value = 0
            _assessment.value = EcgAnalysis.assess(emptyList())
            _currentGifAsset.value = "ami_nod.gif"
        }
    }

    private fun toBytes(values: List<Short>) =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b ->
            values.forEach(b::putShort)
        }.array()

    class Factory(
        private val ble: BleEcgClient,
        private val dao: EcgSessionDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EcgViewModel(ble, dao) as T
    }
}
