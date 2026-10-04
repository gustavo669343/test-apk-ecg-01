package com.example.ecgmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Movie
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ecgmonitor.ble.BleEcgClient
import com.example.ecgmonitor.data.EcgDatabase
import com.example.ecgmonitor.data.EcgSession
import com.example.ecgmonitor.domain.EcgAssessment
import com.example.ecgmonitor.domain.TfliteModelMeta
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private val database by lazy { EcgDatabase.create(this) }
    private val vm by viewModels<EcgViewModel> {
        EcgViewModel.Factory(this, BleEcgClient(this), database.sessionDao())
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) vm.scanDevices()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { EcgApp(vm, ::scan) }
        scan()
    }

    private fun scan() {
        val wanted = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (wanted.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissions.launch(wanted)
        } else {
            vm.scanDevices()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EcgApp(vm: EcgViewModel, scan: () -> Unit) {
    val raw by vm.rawWaveform.collectAsState()
    val espFiltered by vm.espFilteredWaveform.collectAsState()
    val androidFiltered by vm.androidFilteredWaveform.collectAsState()

    val status by vm.status.collectAsState()
    val isConnected by vm.isConnected.collectAsState()
    val isScanning by vm.isScanning.collectAsState()
    val assessment by vm.assessment.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val devices by vm.devices.collectAsState()
    val selected by vm.selectedDevice.collectAsState()
    val samples by vm.sampleCount.collectAsState()

    val spo2 by vm.spo2.collectAsState()
    val ppgBpm by vm.ppgBpm.collectAsState()
    val activeAiClassName by vm.activeAiClassName.collectAsState()
    val activeAiClassPercentages by vm.activeAiClassPercentages.collectAsState()
    val isPeak by vm.isPeak.collectAsState()
    val gifAsset by vm.currentGifAsset.collectAsState()

    // Dual AI Source and TFLite Model State
    val aiSource by vm.aiSource.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val isDemoMode by vm.isDemoMode.collectAsState()
    val onDeviceConfidence by vm.onDeviceConfidence.collectAsState()
    val onDeviceInferenceTimeMs by vm.onDeviceInferenceTimeMs.collectAsState()

    var page by rememberSaveable { mutableIntStateOf(0) }
    var dark by rememberSaveable { mutableStateOf(true) }
    var english by rememberSaveable { mutableStateOf(false) }
    var showConnectSheet by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.connectionEvent.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    val labels = if (english) {
        listOf("Home", "ECG Charts", "Trends", "Settings")
    } else {
        listOf("Trang chủ", "Đồ thị ECG", "Xu hướng", "Cài đặt")
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF00E5A8),
            secondary = Color(0xFF4DD0E1),
            surface = Color(0xFF111B25),
            background = Color(0xFF07121C)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF00796B),
            secondary = Color(0xFF006C8F),
            surface = Color(0xFFF7FFFC),
            background = Color(0xFFEDF7F5)
        )
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (english) "ECG Companion" else "ECG Sinh Hiệu AI",
                                fontWeight = FontWeight.Bold
                            )
                            if (isPeak) {
                                Spacer(Modifier.width(8.dp))
                                Text("❤️", fontSize = 16.sp)
                            }
                        }
                    },
                    actions = {
                        // Demo Mode Quick Toggle
                        FilterChip(
                            selected = isDemoMode,
                            onClick = vm::toggleDemoMode,
                            label = {
                                Text(
                                    if (isDemoMode) (if (english) "🧪 Demo: ON" else "🧪 Demo: BẬT")
                                    else (if (english) "🧪 Demo" else "🧪 Chạy thử"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0x3300E5A8),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.padding(end = 6.dp)
                        )

                        // Bluetooth Quick Button in TopBar
                        FilledTonalButton(
                            onClick = {
                                scan()
                                showConnectSheet = true
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isConnected || isDemoMode) Color(0x3300E5A8) else Color(0x33EF5350)
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isConnected || isDemoMode) Color(0xFF00E5A8) else Color(0xFFEF5350),
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isDemoMode) (if (english) "Demo Mode" else "Chế độ Demo")
                                else if (isConnected) (selected?.name ?: "Đã kết nối")
                                else (if (english) "Connect" else "Kết nối"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected || isDemoMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    labels.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = page == i,
                            onClick = { page = i },
                            icon = { Text(listOf("⌂", "⌁", "📈", "⚙")[i]) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { pad ->
            when (page) {
                0 -> HomeScreen(
                    modifier = Modifier.padding(pad),
                    assessment = assessment,
                    spo2 = spo2,
                    ppgBpm = ppgBpm,
                    activeAiClassName = activeAiClassName,
                    activeAiClassPercentages = activeAiClassPercentages,
                    aiSource = aiSource,
                    onSelectAiSource = vm::setAiSource,
                    availableModels = availableModels,
                    selectedModel = selectedModel,
                    onSelectModel = vm::selectModel,
                    onDeviceConfidence = onDeviceConfidence,
                    onDeviceInferenceTimeMs = onDeviceInferenceTimeMs,
                    samples = samples,
                    gifAsset = gifAsset,
                    isPeak = isPeak,
                    isConnected = isConnected || isDemoMode,
                    isDemoMode = isDemoMode,
                    onToggleDemo = vm::toggleDemoMode,
                    onOpenConnect = {
                        scan()
                        showConnectSheet = true
                    },
                    english = english
                )
                1 -> DetailScreen(
                    modifier = Modifier.padding(pad),
                    raw = raw,
                    espFiltered = espFiltered,
                    androidFiltered = androidFiltered,
                    status = if (isDemoMode) "Chế độ mô phỏng Demo ECG 125Hz" else status,
                    assessment = assessment,
                    spo2 = spo2,
                    ppgBpm = ppgBpm,
                    aiClassName = activeAiClassName,
                    selected = selected,
                    isConnected = isConnected || isDemoMode,
                    samples = samples,
                    onOpenConnect = {
                        scan()
                        showConnectSheet = true
                    },
                    disconnect = vm::disconnect,
                    save = vm::saveCurrentMeasurement,
                    english = english
                )
                2 -> TrendsScreen(
                    modifier = Modifier.padding(pad),
                    sessions = sessions,
                    english = english
                )
                else -> SettingsScreen(
                    modifier = Modifier.padding(pad),
                    dark = dark,
                    onDark = { dark = it },
                    english = english,
                    onEnglish = { english = it },
                    isDemoMode = isDemoMode,
                    onToggleDemo = vm::toggleDemoMode,
                    selectedModel = selectedModel,
                    availableModels = availableModels,
                    onSelectModel = vm::selectModel
                )
            }

            if (showConnectSheet) {
                BleConnectBottomSheet(
                    devices = devices,
                    selected = selected,
                    isConnected = isConnected,
                    isScanning = isScanning,
                    status = status,
                    onDismiss = { showConnectSheet = false },
                    onScan = scan,
                    onConnect = { dev ->
                        vm.connectTo(dev)
                        showConnectSheet = false
                    },
                    onDisconnect = vm::disconnect,
                    english = english
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    modifier: Modifier,
    assessment: EcgAssessment,
    spo2: Float?,
    ppgBpm: Float?,
    activeAiClassName: String?,
    activeAiClassPercentages: FloatArray?,
    aiSource: AiSource,
    onSelectAiSource: (AiSource) -> Unit,
    availableModels: List<TfliteModelMeta>,
    selectedModel: TfliteModelMeta,
    onSelectModel: (TfliteModelMeta) -> Unit,
    onDeviceConfidence: Float?,
    onDeviceInferenceTimeMs: Long?,
    samples: Int,
    gifAsset: String,
    isPeak: Boolean,
    isConnected: Boolean,
    isDemoMode: Boolean,
    onToggleDemo: () -> Unit,
    onOpenConnect: () -> Unit,
    english: Boolean
) {
    val advice = if (!isConnected || assessment.bpm == null) {
        if (english) "Connect your ESP32-S3 ECG device or start Demo Mode to begin."
        else "Hãy kết nối thiết bị ESP32 ECG hoặc bật Chế độ chạy thử (Demo) để bắt đầu nhé."
    } else {
        assessment.advice
    }

    var modelDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Quick Connect Suggestion Card when not connected
        if (!isConnected) {
            Card(
                onClick = onOpenConnect,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (english) "Connect ECG Sensor" else "Kết nối thiết bị ECG",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                if (english) "Tap to pair with ESP32-S3 or run Demo" else "Chạm để kết nối Bluetooth hoặc bật Demo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onToggleDemo) {
                            Text(if (english) "Demo" else "Thử")
                        }
                        Button(onClick = onOpenConnect) {
                            Text(if (english) "Pair" else "Kết nối")
                        }
                    }
                }
            }
        }

        // Mascot Card with Advice
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (english) "AMI'S HEALTH INSIGHT" else "LỜI KHUYÊN TỪ AMI",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(advice, style = MaterialTheme.typography.bodyMedium)
                }
            }
            GifCompanion(assetName = gifAsset, modifier = Modifier.size(116.dp))
        }

        // ==========================================
        // DUAL AI SOURCE & MODEL SELECTION CONTROLS
        // ==========================================
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            if (english) "AI INFERENCE ENGINE" else "NGUỒN SUY LUẬN AI",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (english) "Toggle between ESP32 Chip & Android On-Device"
                            else "Chuyển đổi giữa chip ESP32 và máy Android",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    // Source Switcher Toggle Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = aiSource == AiSource.ESP32,
                            onClick = { onSelectAiSource(AiSource.ESP32) },
                            label = { Text("ESP32 AI", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                        FilterChip(
                            selected = aiSource == AiSource.ON_DEVICE,
                            onClick = { onSelectAiSource(AiSource.ON_DEVICE) },
                            label = { Text("Android AI", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                // If On-Device AI is active: Show model selector and metadata
                if (aiSource == AiSource.ON_DEVICE) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        if (english) "Select On-Device TFLite Model:" else "Chọn mô hình TFLite trên điện thoại:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )

                    ExposedDropdownMenuBox(
                        expanded = modelDropdownExpanded,
                        onExpandedChange = { modelDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedModel.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        ExposedDropdownMenu(
                            expanded = modelDropdownExpanded,
                            onDismissRequest = { modelDropdownExpanded = false }
                        ) {
                            availableModels.forEach { meta ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(meta.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text(
                                                "${meta.architecture} • ${meta.windowSize} mẫu • Acc: ${meta.accuracyPercent}%",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSelectModel(meta)
                                        modelDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Model Badge Details
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Độ dài cửa sổ: ${selectedModel.windowSize} mẫu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "Độ trễ: ${onDeviceInferenceTimeMs ?: 4} ms",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        selectedModel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Text(
            if (english) "Vital Signs & Edge AI Diagnostics"
            else "Chỉ số sinh hiệu & Chẩn đoán Edge AI",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Card 1: ECG & AI Classification
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (english) "Heart & CNN AI" else "Điện tim & AI Chẩn đoán",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE95454)
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (aiSource == AiSource.ON_DEVICE) Color(0x3300E5A8) else Color(0x334DD0E1)
                        ) {
                            Text(
                                if (aiSource == AiSource.ON_DEVICE) "Android AI" else "ESP32 AI",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (aiSource == AiSource.ON_DEVICE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    if (isPeak) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0x33E95454)
                        ) {
                            Text(
                                "BEAT",
                                color = Color(0xFFE95454),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (english) "Heart Rate" else "Nhịp tim ECG", style = MaterialTheme.typography.labelMedium)
                        Text(
                            assessment.bpm?.let { "$it BPM" } ?: "-- BPM",
                            color = Color(0xFFE95454),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Column(Modifier.weight(1.5f)) {
                        Text(if (english) "AAMI AI Rhythm" else "Phân loại nhịp AI", style = MaterialTheme.typography.labelMedium)
                        Text(
                            activeAiClassName ?: (if (assessment.bpm == null) "--" else assessment.title),
                            color = Color(0xFFE95454),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                // AI Probability distribution bars (N, S, V, F, Q)
                if (activeAiClassPercentages != null && activeAiClassPercentages.size >= 5) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (english) "AAMI EC57 Class Probabilities:" else "Xác suất 5 lớp AAMI EC57:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (aiSource == AiSource.ON_DEVICE && onDeviceConfidence != null) {
                            Text(
                                "Tin cậy: ${String.format(Locale.US, "%.1f", onDeviceConfidence)}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    val classKeys = listOf("N", "S", "V", "F", "Q")
                    val classLabels = listOf("Bình thường", "Sớm trên thất", "Ngoại tâm thất", "Kết hợp", "Không xác định")
                    val classColors = listOf(Color(0xFF00E5A8), Color(0xFFFFB74D), Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF90A4AE))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until 5) {
                            val rawProb = activeAiClassPercentages.getOrNull(i) ?: 0f
                            // Normalized percentage 0..100
                            val pct = if (rawProb in 0f..1f) rawProb * 100f else rawProb
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = classKeys[i],
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(22.dp)
                                )
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(8.dp),
                                    color = classColors[i],
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", pct)}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(46.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    if (aiSource == AiSource.ON_DEVICE)
                        "Mô hình TFLite: ${selectedModel.displayName}. Thuật toán Pan-Tompkins & chuẩn hóa nhịp 1:1 từ MATLAB."
                    else
                        "Mô hình CNN 3-nhánh chạy trên vi điều khiển ESP32-S3 qua luồng truyền tin BLE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // Card 2: SpO2 & PPG from MAX30102
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (english) "Oxygen & PPG Pulse (MAX30102)" else "Oxy & Mạch (MAX30102)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF29B6F6)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("SpO₂", style = MaterialTheme.typography.labelMedium)
                        Text(
                            spo2?.let { "${String.format(Locale.US, "%.1f", it)}%" } ?: "-- %",
                            color = Color(0xFF29B6F6),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (english) "PPG Pulse" else "Mạch PPG", style = MaterialTheme.typography.labelMedium)
                        Text(
                            ppgBpm?.let { "${it.toInt()} BPM" } ?: "-- BPM",
                            color = Color(0xFF29B6F6),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
                Text(
                    if (english) "Optical reflectance telemetry on ESP32-S3 (MAX30102 I2C)."
                    else "Dữ liệu quang học thời gian thực đo từ cảm biến MAX30102.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DetailScreen(
    modifier: Modifier,
    raw: List<Float>,
    espFiltered: List<Float>,
    androidFiltered: List<Float>,
    status: String,
    assessment: EcgAssessment,
    spo2: Float?,
    ppgBpm: Float?,
    aiClassName: String?,
    selected: BleDeviceOption?,
    isConnected: Boolean,
    samples: Int,
    onOpenConnect: () -> Unit,
    disconnect: () -> Unit,
    save: () -> Unit,
    english: Boolean
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Streamlined Connection Header Card
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isConnected) Color(0xFF00E5A8) else Color(0xFFEF5350),
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            selected?.name ?: if (english) "Device Active" else "Thiết bị",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = onOpenConnect) {
                        Text(
                            if (isConnected) (if (english) "Change" else "Đổi thiết bị")
                            else (if (english) "Connect" else "Kết nối")
                        )
                    }
                    if (isConnected) {
                        OutlinedButton(onClick = disconnect) {
                            Text(if (english) "Disconnect" else "Ngắt", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Text(
            if (english) "Multi-Waveform Analysis & Comparison"
            else "Phân tích & So sánh Đa Đồ thị ECG (3 Kênh)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Waveform 1: Raw Signal from AD8232
        ChartPanel(
            title = if (english) "1. RAW ECG SIGNAL (AD8232 ADC)" else "1. TÍN HIỆU ĐIỆN TIM THÔ (AD8232 ADC)",
            subtitle = if (english) "Direct ADC samples with 50Hz mains & baseline wander"
            else "Tín hiệu thô từ cảm biến AD8232, bao gồm nhiễu điện lưới 50Hz & trôi nền",
            samples = raw,
            color = Color(0xFFEF5350)
        )

        // Waveform 2: Filtered on ESP32
        ChartPanel(
            title = if (english) "2. ESP32 HARDWARE FILTERED (NOTCH 50Hz)" else "2. TÍN HIỆU LỌC TỪ ESP32 (NOTCH 50Hz + BASELINE)",
            subtitle = if (english) "Realtime DSP on ESP32-S3 Core: IIR Notch 50Hz + wander removal + smoothing"
            else "Xử lý số tín hiệu trực tiếp trên vi điều khiển: Notch 50Hz + chống trôi nền",
            samples = espFiltered,
            color = Color(0xFF00E5A8)
        )

        // Waveform 3: Filtered on Android Device
        ChartPanel(
            title = if (english) "3. ANDROID LOCAL FILTERED (COMPARISON)" else "3. TÍN HIỆU LỌC TRÊN ANDROID (ĐỐI CHIẾU)",
            subtitle = if (english) "Computed in Kotlin on Android for filter validation & cross-check"
            else "Xử lý bằng bộ lọc RealtimeEcgFilter cục bộ trên máy Android để kiểm chứng",
            samples = androidFiltered,
            color = Color(0xFF4DD0E1)
        )

        // Summary Indicators Card
        Card {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (english) "Live Telemetry Snapshot" else "Thông số kỹ thuật phiên đo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "ECG: ${assessment.bpm?.let { "$it BPM" } ?: "--"}  •  SpO₂: ${spo2?.let { "$it%" } ?: "--"}  •  PPG: ${ppgBpm?.let { "${it.toInt()} BPM" } ?: "--"}"
                )
                Text("AI Class: ${aiClassName ?: "--"}  •  125 Hz  •  ${samples / 125} giây")
            }
        }

        Button(
            onClick = save,
            modifier = Modifier.fillMaxWidth(),
            enabled = espFiltered.isNotEmpty()
        ) {
            Text(if (english) "Save current session to local database" else "Lưu phiên đo vào cơ sở dữ liệu")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BleConnectBottomSheet(
    devices: List<BleDeviceOption>,
    selected: BleDeviceOption?,
    isConnected: Boolean,
    isScanning: Boolean,
    status: String,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onConnect: (BleDeviceOption) -> Unit,
    onDisconnect: () -> Unit,
    english: Boolean
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        if (english) "Bluetooth Devices" else "Kết nối Thiết bị Bluetooth",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                } else {
                    TextButton(onClick = onScan) {
                        Text(if (english) "Scan again" else "Quét lại")
                    }
                }
            }

            if (devices.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🔍", fontSize = 32.sp)
                        Text(
                            if (english) "Searching for ECG devices nearby..."
                            else "Đang tìm thiết bị ECG xung quanh...",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (english) "Make sure your ESP32-S3 is powered on."
                            else "Hãy đảm bảo ESP32-S3 đã bật nguồn và đặt gần điện thoại.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = onScan) {
                            Text(if (english) "Start scan" else "Bắt đầu quét")
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { dev ->
                        val isTarget = selected?.address == dev.address && isConnected
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isTarget) MaterialTheme.colorScheme.primaryContainer
                                else if (dev.isRecommended) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = if (dev.isRecommended) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (dev.isRecommended) "🩺" else "📶", fontSize = 22.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(dev.name, fontWeight = FontWeight.Bold)
                                            if (dev.isRecommended) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.primary
                                                ) {
                                                    Text(
                                                        "Khuyên dùng",
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimary
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            dev.address,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }

                                if (isTarget) {
                                    OutlinedButton(onClick = onDisconnect) {
                                        Text(if (english) "Disconnect" else "Ngắt", color = MaterialTheme.colorScheme.error)
                                    }
                                } else {
                                    Button(onClick = { onConnect(dev) }) {
                                        Text(if (english) "Connect" else "Kết nối")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChartPanel(title: String, subtitle: String, samples: List<Float>, color: Color) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = color)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(6.dp))
            EcgWaveform(samples, Modifier.fillMaxWidth().height(180.dp), color)
        }
    }
}

@Composable
private fun TrendsScreen(modifier: Modifier, sessions: List<EcgSession>, english: Boolean) {
    val formatter = remember { SimpleDateFormat("dd/MM", Locale.getDefault()) }
    val dates = remember {
        (0..6).map { Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -it) }.time }.reversed()
    }
    val byDay = sessions.groupBy { formatter.format(Date(it.startedAt)) }
    val values = dates.map { byDay[formatter.format(it)]?.mapNotNull { s -> s.heartRateBpm }?.average() }
    val recorded = values.filterNotNull()

    val comment = when {
        recorded.isEmpty() -> if (english) {
            "No measurements in the last 7 days. Measure daily to establish your baseline trend."
        } else {
            "Chưa có phiên đo trong 7 ngày gần đây. Hãy đo mỗi ngày vào cùng khung giờ để theo dõi xu hướng."
        }
        recorded.any { it < 50 || it > 100 } -> if (english) {
            "Some recorded daily averages are outside reference range. Check symptoms and consult a doctor if needed."
        } else {
            "Có ngày có nhịp tim trung bình ngoài khoảng tham khảo lúc nghỉ. Hãy theo dõi triệu chứng và đi khám nếu cảm thấy không khoẻ."
        }
        else -> if (english) {
            "Recorded daily averages are within resting reference range. Keep up regular wellness tracking."
        } else {
            "Các ngày đã đo có nhịp trung bình trong khoảng tham khảo. Hãy tiếp tục đo đều đặn mỗi ngày."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (english) "7-Day Heart Rate Trends" else "Xu hướng nhịp tim 7 ngày",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (english) "Weekly Health Assessment" else "Nhận xét sức khoẻ tuần",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(comment)
            }
        }

        dates.forEachIndexed { index, date ->
            val bpm = values[index]
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatter.format(date), fontWeight = FontWeight.Bold)
                    Text(
                        bpm?.let { "${it.toInt()} BPM" } ?: if (english) "No data" else "Chưa đo",
                        color = if (bpm == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            if (english) "Trend evaluation is for wellness tracking only; it cannot replace a clinical diagnosis."
            else "Nhận xét xu hướng chỉ mang tính tham khảo theo dõi sức khoẻ; không có giá trị chẩn đoán bệnh.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    modifier: Modifier,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    english: Boolean,
    onEnglish: (Boolean) -> Unit,
    isDemoMode: Boolean,
    onToggleDemo: () -> Unit,
    selectedModel: TfliteModelMeta,
    availableModels: List<TfliteModelMeta>,
    onSelectModel: (TfliteModelMeta) -> Unit
) {
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (english) "Settings & Configuration" else "Cài đặt & Cấu hình",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Demo Simulation Mode Switch
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (english) "Demo Simulation Mode" else "Chế độ Chạy thử (Demo Mode)", fontWeight = FontWeight.Bold)
                    Text(
                        if (english) "Simulate 125 Hz ECG with Pan-Tompkins & On-Device AI without hardware"
                        else "Giả lập tín hiệu ECG 125Hz, chạy Pan-Tompkins & AI TFLite không cần phần cứng",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = isDemoMode, onCheckedChange = { onToggleDemo() })
            }
        }

        // On-Device Model Switcher Card
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (english) "Default On-Device AI Model" else "Mô hình AI On-Device mặc định",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (english) "Select lightweight quantized INT8 model for on-device inference"
                    else "Chọn mô hình lượng tử hóa INT8 tối ưu suy luận trực tiếp trên điện thoại",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedModel.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        availableModels.forEach { meta ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(meta.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(
                                            "${meta.architecture} • ${meta.windowSize} mẫu • Acc: ${meta.accuracyPercent}%",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectModel(meta)
                                    modelDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (english) "Dark Theme" else "Giao diện tối", fontWeight = FontWeight.Bold)
                    Text(if (english) "Use cybernetic dark dashboard style" else "Dùng phong cách nền tối giám sát y sinh")
                }
                Switch(checked = dark, onCheckedChange = onDark)
            }
        }

        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (english) "Language" else "Ngôn ngữ", fontWeight = FontWeight.Bold)
                    Text(if (english) "English" else "Tiếng Việt")
                }
                TextButton(onClick = { onEnglish(!english) }) {
                    Text(if (english) "Tiếng Việt" else "English")
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (english) "Biomedical Hardware & AI Guide" else "Hướng dẫn phần cứng & AI y sinh",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (english) "Target Device: ESP32-S3 (Service UUID: 4fafc201-1fb5-459e-8fcc-c5c9c331914b)\n" +
                            "Sensors: AD8232 (ECG 125Hz) & MAX30102 (SpO2 & PPG 100Hz)\n" +
                            "Dual AI: ESP32 Hardware CNN + Android On-Device TFLite (Kachuee ResNet / Inception 3/4-Branch)."
                    else "Thiết bị kết nối: ESP32-S3 (Service UUID: 4fafc201-1fb5-459e-8fcc-c5c9c331914b)\n" +
                            "Cảm biến: AD8232 (Điện tim 125Hz) & MAX30102 (Oxy & Mạch PPG 100Hz)\n" +
                            "Kiến trúc Dual AI: Kết hợp đồng thời CNN trên chip ESP32 và TensorFlow Lite On-Device trên điện thoại Android.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun EcgWaveform(samples: List<Float>, modifier: Modifier, color: Color) {
    Canvas(modifier = modifier.background(Color(0xFF081820))) {
        val w = size.width
        val h = size.height

        for (x in 0..10) {
            drawLine(Color(0x224DD0E1), Offset(x * w / 10f, 0f), Offset(x * w / 10f, h))
        }
        for (y in 0..8) {
            drawLine(Color(0x224DD0E1), Offset(0f, y * h / 8f), Offset(w, y * h / 8f))
        }

        if (samples.size < 2) return@Canvas

        val lo = samples.minOrNull() ?: 0f
        val hi = samples.maxOrNull() ?: 1f
        val span = max(1f, hi - lo)
        val path = Path()

        samples.forEachIndexed { i, s ->
            val x = i * w / (samples.size - 1)
            val normalized = (s - lo) / span
            val y = h - (normalized * (h * 0.80f) + (h * 0.10f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun GifCompanion(assetName: String, modifier: Modifier) {
    AndroidView(
        factory = { ctx -> GifAssetView(ctx).apply { setAsset(assetName) } },
        update = { view -> view.setAsset(assetName) },
        modifier = modifier
    )
}

private class GifAssetView(context: Context) : View(context) {
    private var currentAssetName = ""
    private var movie: Movie? = null
    private var started = SystemClock.uptimeMillis()

    fun setAsset(assetName: String) {
        if (currentAssetName == assetName && movie != null) return
        currentAssetName = assetName
        movie = try {
            context.assets.open(assetName).use { Movie.decodeStream(it) }
        } catch (_: Exception) {
            try {
                context.assets.open("companion.gif").use { Movie.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
        }
        started = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: AndroidCanvas) {
        val gif = movie ?: return
        val duration = gif.duration().takeIf { it > 0 } ?: 1000
        gif.setTime(((SystemClock.uptimeMillis() - started) % duration).toInt())
        val scale = minOf(
            width.toFloat() / gif.width().coerceAtLeast(1),
            height.toFloat() / gif.height().coerceAtLeast(1)
        )
        canvas.save()
        canvas.translate((width - gif.width() * scale) / 2f, (height - gif.height() * scale) / 2f)
        canvas.scale(scale, scale)
        gif.draw(canvas, 0f, 0f)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
