package com.example.ecgmonitor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ecgmonitor.ble.BleEcgClient
import com.example.ecgmonitor.ble.EcgPacket
import com.example.ecgmonitor.data.EcgSession
import com.example.ecgmonitor.data.EcgSessionDao
import com.example.ecgmonitor.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BleDeviceOption(
    val name: String,
    val address: String,
    val isRecommended: Boolean = false
)

enum class AiSource {
    ESP32,
    ON_DEVICE
}

data class TelemetrySample(
    val raw: Float,
    val filtered: Float,
    val isPeak: Boolean,
    val bpm: Float,
    val classId: Int,
    val className: String,
    val p0: Float,
    val p1: Float,
    val p2: Float,
    val p3: Float,
    val p4: Float
)

class EcgViewModel(
    private val context: Context,
    private val ble: BleEcgClient,
    private val dao: EcgSessionDao
) : ViewModel() {

    private val rawSamples = mutableListOf<Float>()
    private val espFilteredSamples = mutableListOf<Float>()
    private val androidFilteredSamples = mutableListOf<Float>()
    private val telemetryHistory = mutableListOf<TelemetrySample>()
    private val androidFilter = RealtimeEcgFilter()
    private val panTompkinsDetector = PanTompkinsDetector()
    private val classifier by lazy { TfliteEcgClassifier(context) }

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

    // ESP32 AI Output
    private val _espAiClassIndex = MutableStateFlow<Int?>(null)
    val espAiClassIndex = _espAiClassIndex.asStateFlow()

    private val _espAiClassName = MutableStateFlow<String?>("Normal (N)")
    val espAiClassName = _espAiClassName.asStateFlow()

    private val _espAiClassPercentages = MutableStateFlow<FloatArray?>(null)
    val espAiClassPercentages = _espAiClassPercentages.asStateFlow()

    // On-Device AI Architecture & Model Management
    private val _aiSource = MutableStateFlow(AiSource.ESP32)
    val aiSource = _aiSource.asStateFlow()

    private val _availableModels = MutableStateFlow(TfliteModelMeta.ALL_MODELS)
    val availableModels = _availableModels.asStateFlow()

    private val _selectedModel = MutableStateFlow(TfliteModelMeta.DEFAULT_MODEL)
    val selectedModel = _selectedModel.asStateFlow()

    private val _onDeviceClassIndex = MutableStateFlow<Int?>(0)
    val onDeviceClassIndex = _onDeviceClassIndex.asStateFlow()

    private val _onDeviceClassName = MutableStateFlow<String?>("Bình thường (Normal N)")
    val onDeviceClassName = _onDeviceClassName.asStateFlow()

    private val _onDeviceClassPercentages = MutableStateFlow<FloatArray?>(floatArrayOf(0.98f, 0.005f, 0.005f, 0.005f, 0.005f))
    val onDeviceClassPercentages = _onDeviceClassPercentages.asStateFlow()

    private val _onDeviceConfidence = MutableStateFlow<Float?>(98.0f)
    val onDeviceConfidence = _onDeviceConfidence.asStateFlow()

    private val _onDeviceInferenceTimeMs = MutableStateFlow<Long?>(4L)
    val onDeviceInferenceTimeMs = _onDeviceInferenceTimeMs.asStateFlow()

    private val _onDeviceBpm = MutableStateFlow<Float?>(null)
    val onDeviceBpm = _onDeviceBpm.asStateFlow()

    // Active Display Outputs (dynamically switched based on aiSource)
    val activeAiClassName: StateFlow<String?> = combine(_aiSource, _espAiClassName, _onDeviceClassName) { source, esp, onDev ->
        if (source == AiSource.ON_DEVICE) onDev else esp
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "Normal (N)")

    val activeAiClassPercentages: StateFlow<FloatArray?> = combine(_aiSource, _espAiClassPercentages, _onDeviceClassPercentages) { source, esp, onDev ->
        if (source == AiSource.ON_DEVICE) onDev else esp
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode = _isDemoMode.asStateFlow()

    private var demoJob: Job? = null

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
                if (!_isDemoMode.value) {
                    updateMascotEmotion(s)
                }
            }
        }

        // Collect incoming ECG & Biometrics packets from BLE
        viewModelScope.launch {
            ble.packets.collect { packet ->
                if (_isDemoMode.value) return@collect // Ignore BLE while demo is active

                processIncomingSample(
                    raw = packet.raw,
                    espFiltered = packet.filtered,
                    espBpm = packet.bpm,
                    espAiClass = packet.aiClass,
                    espAiName = packet.aiClassName,
                    espPercentages = packet.classPercentages,
                    spo2 = packet.spo2,
                    ppgBpm = packet.ppgBpm,
                    espIsPeak = packet.isPeak
                )
            }
        }
    }

    private fun processIncomingSample(
        raw: Float,
        espFiltered: Float?,
        espBpm: Float?,
        espAiClass: Int?,
        espAiName: String?,
        espPercentages: FloatArray?,
        spo2: Float?,
        ppgBpm: Float?,
        espIsPeak: Boolean
    ) {
        if (rawSamples.isEmpty()) {
            measurementStartedAt = System.currentTimeMillis()
        }

        rawSamples += raw
        val androidCleaned = androidFilter.process(raw)
        androidFilteredSamples += androidCleaned
        val chosenFiltered = espFiltered ?: androidCleaned
        espFilteredSamples += chosenFiltered

        _sampleCount.value = espFilteredSamples.size

        // Update charts with sliding window (take last 625 samples = 5s @ 125Hz)
        _rawWaveform.value = rawSamples.takeLast(625)
        _espFilteredWaveform.value = espFilteredSamples.takeLast(625)
        _androidFilteredWaveform.value = androidFilteredSamples.takeLast(625)

        // Update ESP32 biometrics
        spo2?.takeIf { it > 0 }?.let { _spo2.value = it }
        ppgBpm?.takeIf { it > 0 }?.let { _ppgBpm.value = it }
        espBpm?.takeIf { it > 0 }?.let { latestEspBpm = it }
        espAiClass?.let { _espAiClassIndex.value = it }
        espAiName?.let { _espAiClassName.value = it }
        espPercentages?.let { _espAiClassPercentages.value = it }

        // Run On-Device Pan-Tompkins QRS Detection
        val cleanHistory = androidFilteredSamples.takeLast(800)
        val ptResult = panTompkinsDetector.processSample(androidCleaned, cleanHistory)

        val isPeakDetected = if (_aiSource.value == AiSource.ON_DEVICE) ptResult.isPeak else espIsPeak
        _isPeak.value = isPeakDetected

        if (ptResult.isPeak) {
            if (ptResult.smoothedBpm > 0) {
                _onDeviceBpm.value = ptResult.smoothedBpm
            }

            // Extract beat and run On-Device TFLite inference
            val meta = _selectedModel.value
            val historyOffset = (ptResult.rPeakIndex - ptResult.refinedRPeakIndex)
            val rPeakPosInClean = cleanHistory.lastIndex - historyOffset

            val beat = EcgSegmenter.extractBeat(
                cleanHistory = cleanHistory,
                rPeakIndexInHistory = rPeakPosInClean,
                preSamples = meta.preSamples,
                postSamples = meta.postSamples
            )

            if (beat != null) {
                val inferResult = classifier.classify(beat)
                if (inferResult != null) {
                    _onDeviceClassIndex.value = inferResult.classIndex
                    _onDeviceClassName.value = inferResult.classFullName
                    _onDeviceClassPercentages.value = inferResult.probabilities
                    _onDeviceConfidence.value = inferResult.confidence
                    _onDeviceInferenceTimeMs.value = inferResult.inferenceTimeMs
                }
            }
        }

        // Record telemetry sample matching in/data CSV columns
        val isDeviceAi = (_aiSource.value == AiSource.ON_DEVICE)
        val pcts = if (isDeviceAi) _onDeviceClassPercentages.value else (espPercentages ?: _onDeviceClassPercentages.value)
        val p0 = pcts?.getOrNull(0)?.let { if (it in 0f..1f) it * 100f else it } ?: 96.0f
        val p1 = pcts?.getOrNull(1)?.let { if (it in 0f..1f) it * 100f else it } ?: 2.0f
        val p2 = pcts?.getOrNull(2)?.let { if (it in 0f..1f) it * 100f else it } ?: 0.0f
        val p3 = pcts?.getOrNull(3)?.let { if (it in 0f..1f) it * 100f else it } ?: 0.0f
        val p4 = pcts?.getOrNull(4)?.let { if (it in 0f..1f) it * 100f else it } ?: 1.0f

        val curClassId = (if (isDeviceAi) _onDeviceClassIndex.value else (espAiClass ?: _onDeviceClassIndex.value)) ?: 0
        val curClassName = when (curClassId) {
            0 -> "N"
            1 -> "S"
            2 -> "V"
            3 -> "F"
            4 -> "Q"
            else -> "N"
        }
        val curBpm = (if (isDeviceAi) _onDeviceBpm.value else (espBpm ?: _onDeviceBpm.value)) ?: 75.0f

        synchronized(telemetryHistory) {
            telemetryHistory += TelemetrySample(
                raw = raw,
                filtered = chosenFiltered,
                isPeak = isPeakDetected,
                bpm = curBpm,
                classId = curClassId,
                className = curClassName,
                p0 = p0,
                p1 = p1,
                p2 = p2,
                p3 = p3,
                p4 = p4
            )
        }

        // Re-evaluate assessment every 25 samples (0.2s)
        if (espFilteredSamples.size % 25 == 0) {
            val effectiveClassIndex = if (isDeviceAi) _onDeviceClassIndex.value else _espAiClassIndex.value
            val effectiveClassName = if (isDeviceAi) _onDeviceClassName.value else _espAiClassName.value
            val effectiveBpm = if (isDeviceAi) (_onDeviceBpm.value ?: latestEspBpm) else (latestEspBpm ?: _onDeviceBpm.value)

            val newAssessment = EcgAnalysis.assess(
                samples = espFilteredSamples.takeLast(1250),
                espBpm = effectiveBpm,
                aiClass = effectiveClassIndex,
                aiClassName = effectiveClassName,
                spo2 = _spo2.value,
                ppgBpm = _ppgBpm.value
            )
            _assessment.value = newAssessment

            val effectiveStatus = if (_isDemoMode.value) "Đang chạy Demo AI" else status.value
            updateMascotEmotion(effectiveStatus, newAssessment, effectiveClassIndex)
        }
    }

    fun setAiSource(source: AiSource) {
        _aiSource.value = source
        val isDeviceAi = (source == AiSource.ON_DEVICE)
        val effectiveClassIndex = if (isDeviceAi) _onDeviceClassIndex.value else _espAiClassIndex.value
        val effectiveClassName = if (isDeviceAi) _onDeviceClassName.value else _espAiClassName.value
        val effectiveBpm = if (isDeviceAi) (_onDeviceBpm.value ?: latestEspBpm) else (latestEspBpm ?: _onDeviceBpm.value)

        _assessment.value = EcgAnalysis.assess(
            samples = espFilteredSamples.takeLast(1250),
            espBpm = effectiveBpm,
            aiClass = effectiveClassIndex,
            aiClassName = effectiveClassName,
            spo2 = _spo2.value,
            ppgBpm = _ppgBpm.value
        )
        val effectiveStatus = if (_isDemoMode.value) "Đang chạy Demo AI" else status.value
        updateMascotEmotion(effectiveStatus, _assessment.value, effectiveClassIndex)
    }

    fun selectModel(meta: TfliteModelMeta) {
        _selectedModel.value = meta
        classifier.loadModel(meta)
    }

    fun toggleDemoMode() {
        if (_isDemoMode.value) {
            demoJob?.cancel()
            demoJob = null
            _isDemoMode.value = false
            updateMascotEmotion(status.value)
        } else {
            _isDemoMode.value = true
            _aiSource.value = AiSource.ON_DEVICE
            _isConnected.value = true
            _currentGifAsset.value = "ami_happy.gif"

            demoJob = viewModelScope.launch(Dispatchers.Default) {
                var time = 0.0
                val dt = 1.0 / EcgDemoProcessor.SAMPLE_RATE
                val batchSize = 5
                val sleepMs = 40L // 5 samples at 125 Hz = 40 ms interval
                while (isActive) {
                    for (i in 0 until batchSize) {
                        val sample = EcgDemoProcessor.syntheticEcgSample(time)
                        time += dt
                        val rawFloat = (sample * 1000.0).toFloat()
                        processIncomingSample(
                            raw = rawFloat,
                            espFiltered = null,
                            espBpm = 75f,
                            espAiClass = null,
                            espAiName = null,
                            espPercentages = null,
                            spo2 = 98.6f,
                            ppgBpm = 75f,
                            espIsPeak = false
                        )
                    }
                    delay(sleepMs)
                }
            }
        }
    }

    private fun updateMascotEmotion(
        currentStatus: String,
        currentAssessment: EcgAssessment? = assessment.value,
        activeClassIndex: Int? = if (_aiSource.value == AiSource.ON_DEVICE) _onDeviceClassIndex.value else _espAiClassIndex.value
    ) {
        val isRunning = currentStatus.contains("Đang nhận") || currentStatus.contains("Đã kết nối") || currentStatus.contains("Demo")
        if (!isRunning) {
            _currentGifAsset.value = if (currentStatus.contains("Đang quét") || currentStatus.contains("Đang kết nối")) {
                "ami_question.gif"
            } else {
                "ami_sleep.gif"
            }
            return
        }

        val aiCls = activeClassIndex
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
        if (_isDemoMode.value) toggleDemoMode()
        _selectedDevice.value = option
        ble.connect(option.address)
    }

    fun disconnect() {
        if (_isDemoMode.value) toggleDemoMode()
        ble.disconnect()
        _currentGifAsset.value = "ami_sleep.gif"
    }

    fun saveCurrentMeasurement() {
        if (espFilteredSamples.isEmpty()) return
        val copy = espFilteredSamples.toList()
        val isDeviceAi = (_aiSource.value == AiSource.ON_DEVICE)
        val effectiveClassIndex = if (isDeviceAi) _onDeviceClassIndex.value else _espAiClassIndex.value
        val effectiveClassName = if (isDeviceAi) _onDeviceClassName.value else _espAiClassName.value

        val result = EcgAnalysis.assess(
            samples = copy,
            espBpm = latestEspBpm,
            aiClass = effectiveClassIndex,
            aiClassName = effectiveClassName,
            spo2 = _spo2.value,
            ppgBpm = _ppgBpm.value
        )

        val shortValues = copy.map {
            it.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }

        val aiSourceLabel = if (isDeviceAi) "Android On-Device [${_selectedModel.value.displayName}]" else "ESP32"

        viewModelScope.launch {
            dao.insert(
                EcgSession(
                    startedAt = measurementStartedAt ?: System.currentTimeMillis(),
                    durationSeconds = copy.size / 125,
                    samples = toBytes(shortValues),
                    heartRateBpm = result.bpm,
                    note = "AI ($aiSourceLabel): ${effectiveClassName ?: "N/A"} | SpO2: ${_spo2.value ?: "--"}%"
                )
            )

            // Reset buffers
            rawSamples.clear()
            espFilteredSamples.clear()
            androidFilteredSamples.clear()
            synchronized(telemetryHistory) { telemetryHistory.clear() }
            androidFilter.reset()
            panTompkinsDetector.reset()
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

    // ==========================================
    // CSV EXPORT ENGINE (MATCHING IN/DATA FORMAT)
    // ==========================================
    fun generateCsvFilename(timestamp: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "ble_received_ecg_${sdf.format(Date(timestamp))}.csv"
    }

    fun hasCurrentRecording(): Boolean = espFilteredSamples.isNotEmpty()

    fun exportCurrentSessionToCsv(outputStream: OutputStream) {
        outputStream.bufferedWriter().use { writer ->
            writer.write("Index,Raw,Filtered,Peak,BPM,Class_ID,Class_Name,P0,P1,P2,P3,P4\n")
            val list = synchronized(telemetryHistory) { telemetryHistory.toList() }
            if (list.isNotEmpty()) {
                list.forEachIndexed { index, sample ->
                    writer.write(
                        String.format(
                            Locale.US,
                            "%d,%.1f,%.1f,%d,%.1f,%d,%s,%.1f,%.1f,%.1f,%.1f,%.1f\n",
                            index,
                            sample.raw,
                            sample.filtered,
                            if (sample.isPeak) 1 else 0,
                            sample.bpm,
                            sample.classId,
                            sample.className,
                            sample.p0,
                            sample.p1,
                            sample.p2,
                            sample.p3,
                            sample.p4
                        )
                    )
                }
            } else {
                // Fallback from rawSamples / espFilteredSamples
                val rawCopy = synchronized(rawSamples) { rawSamples.toList() }
                val filtCopy = synchronized(espFilteredSamples) { espFilteredSamples.toList() }
                val count = minOf(rawCopy.size, filtCopy.size)
                for (i in 0 until count) {
                    writer.write(
                        String.format(
                            Locale.US,
                            "%d,%.1f,%.1f,0,75.0,0,N,96.0,2.0,0.0,0.0,1.0\n",
                            i,
                            rawCopy[i],
                            filtCopy[i]
                        )
                    )
                }
            }
        }
    }

    fun exportSessionToCsv(session: EcgSession, outputStream: OutputStream) {
        outputStream.bufferedWriter().use { writer ->
            writer.write("Index,Raw,Filtered,Peak,BPM,Class_ID,Class_Name,P0,P1,P2,P3,P4\n")
            val shortBuffer = ByteBuffer.wrap(session.samples).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val totalSamples = shortBuffer.remaining()
            val bpm = session.heartRateBpm?.toFloat() ?: 75.0f
            for (i in 0 until totalSamples) {
                val s = shortBuffer.get(i).toFloat()
                val raw = 1900.0f + s
                val filtered = s / 10.0f
                writer.write(
                    String.format(
                        Locale.US,
                        "%d,%.1f,%.1f,0,%.1f,0,N,96.0,2.0,0.0,0.0,1.0\n",
                        i,
                        raw,
                        filtered,
                        bpm
                    )
                )
            }
        }
    }

    private fun toBytes(values: List<Short>) =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b ->
            values.forEach(b::putShort)
        }.array()

    override fun onCleared() {
        super.onCleared()
        demoJob?.cancel()
        classifier.close()
    }

    class Factory(
        private val context: Context,
        private val ble: BleEcgClient,
        private val dao: EcgSessionDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EcgViewModel(context, ble, dao) as T
    }
}
