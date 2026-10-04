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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private val database by lazy { EcgDatabase.create(this) }
    private val vm by viewModels<EcgViewModel> {
        EcgViewModel.Factory(BleEcgClient(this), database.sessionDao())
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
    val assessment by vm.assessment.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val devices by vm.devices.collectAsState()
    val selected by vm.selectedDevice.collectAsState()
    val samples by vm.sampleCount.collectAsState()

    val spo2 by vm.spo2.collectAsState()
    val ppgBpm by vm.ppgBpm.collectAsState()
    val aiClassName by vm.aiClassName.collectAsState()
    val aiClassPercentages by vm.aiClassPercentages.collectAsState()
    val isPeak by vm.isPeak.collectAsState()
    val gifAsset by vm.currentGifAsset.collectAsState()

    var page by rememberSaveable { mutableIntStateOf(0) }
    var dark by rememberSaveable { mutableStateOf(true) }
    var english by rememberSaveable { mutableStateOf(false) }

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
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (english) "ECG & Vital Signs Companion"
                                else "ECG & Sinh Hiệu • Ami AI"
                            )
                            if (isPeak) {
                                Spacer(Modifier.width(8.dp))
                                Text("❤️", fontSize = 16.sp)
                            }
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
                    aiClassName = aiClassName,
                    aiClassPercentages = aiClassPercentages,
                    samples = samples,
                    gifAsset = gifAsset,
                    isPeak = isPeak,
                    english = english
                )
                1 -> DetailScreen(
                    modifier = Modifier.padding(pad),
                    raw = raw,
                    espFiltered = espFiltered,
                    androidFiltered = androidFiltered,
                    status = status,
                    assessment = assessment,
                    spo2 = spo2,
                    ppgBpm = ppgBpm,
                    aiClassName = aiClassName,
                    devices = devices,
                    selected = selected,
                    samples = samples,
                    scan = scan,
                    connect = vm::connectTo,
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
                    onEnglish = { english = it }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    assessment: EcgAssessment,
    spo2: Float?,
    ppgBpm: Float?,
    aiClassName: String?,
    aiClassPercentages: FloatArray?,
    samples: Int,
    gifAsset: String,
    isPeak: Boolean,
    english: Boolean
) {
    val advice = if (assessment.bpm == null) {
        if (english) "Connect your ESP32-S3 ECG device to begin. Sit still and keep electrodes well contacted."
        else "Hãy kết nối thiết bị ESP32 ECG để bắt đầu. Ngồi yên và giữ điện cực tiếp xúc tốt nhé."
    } else {
        assessment.advice
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            if (english) "Hello! I am your AI ECG Companion"
            else "Xin chào, mình là Ami - Bạn đồng hành ECG",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

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
                    Text(
                        if (english) "Heart & CNN AI" else "Điện tim & AI Chẩn đoán",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE95454)
                    )
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
                            aiClassName ?: (if (assessment.bpm == null) "--" else assessment.title),
                            color = Color(0xFFE95454),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                // AI Probability distribution bars (N, S, V, F, Q)
                if (aiClassPercentages != null && aiClassPercentages.size >= 5) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        if (english) "MIT-BIH Class Probabilities:" else "Xác suất 5 lớp chuẩn MIT-BIH:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val names = listOf("N", "S", "V", "F", "Q")
                        for (i in 0 until 5) {
                            val pct = aiClassPercentages.getOrNull(i) ?: 0f
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(names[i], fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("${pct.toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                Text(
                    if (english) "Trained on MIT-BIH Arrhythmia Database. Screening support only, not a medical diagnosis."
                    else "Mô hình CNN 3-nhánh trên ESP32. Chỉ hỗ trợ sàng lọc sơ bộ, không thay thế chẩn đoán y khoa.",
                    style = MaterialTheme.typography.bodySmall
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
                    if (english) "Real-time optical reflectance sensor on ESP32-S3 (I2C SDA=5, SCL=6)."
                    else "Dữ liệu quang học thời gian thực truyền trực tiếp từ vi điều khiển ESP32-S3.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Card 3: Received Stream Details
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                if (english) "ECG stream: ${samples / 125} seconds received @ 125 Hz (8ms cycle)"
                else "Dữ liệu ECG: Đã nhận ${samples / 125} giây ở tần số 125 Hz (chu kỳ 8ms)",
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium
            )
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
    devices: List<BleDeviceOption>,
    selected: BleDeviceOption?,
    samples: Int,
    scan: () -> Unit,
    connect: (BleDeviceOption) -> Unit,
    disconnect: () -> Unit,
    save: () -> Unit,
    english: Boolean
) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            if (english) "Multi-Waveform Analysis & Comparison"
            else "Phân tích & So sánh Đa Đồ thị ECG",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

        // Waveform 1: Raw Signal from AD8232
        ChartPanel(
            title = if (english) "1. RAW ECG SIGNAL (AD8232)" else "1. TÍN HIỆU ĐIỆN TIM THÔ (AD8232 ADC)",
            subtitle = if (english) "Direct 0..4095 ADC samples with 50Hz mains & baseline wander"
            else "Tín hiệu thô từ chân ADC GPIO 1, bao gồm nhiễu điện lưới 50Hz & trôi nền",
            samples = raw,
            color = Color(0xFFEF5350)
        )

        // Waveform 2: Filtered on ESP32
        ChartPanel(
            title = if (english) "2. ESP32 HARDWARE FILTERED (NOTCH 50Hz)" else "2. TÍN HIỆU LỌC TỪ ESP32 (NOTCH 50Hz + BASELINE)",
            subtitle = if (english) "Realtime DSP on ESP32-S3 Core 1: IIR Notch 50Hz + wander removal + smoothing"
            else "Xử lý số tín hiệu trực tiếp trên Core 1 ESP32-S3: Lọc Notch 50Hz + chống trôi nền",
            samples = espFiltered,
            color = Color(0xFF00E5A8)
        )

        // Waveform 3: Filtered on Android Device (For direct comparison!)
        ChartPanel(
            title = if (english) "3. ANDROID LOCAL FILTERED (COMPARISON)" else "3. TÍN HIỆU LỌC TRÊN ANDROID (ĐỐI CHIẾU)",
            subtitle = if (english) "Computed in Kotlin on Android for filter validation & cross-check"
            else "Xử lý bằng thuật toán RealtimeEcgFilter cục bộ trên máy Android để kiểm chứng",
            samples = androidFiltered,
            color = Color(0xFF4DD0E1)
        )

        // BLE Connection Card
        Card {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Bluetooth LE (ESP32-S3)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) (if (english) "Collapse" else "Thu gọn")
                            else (if (english) "Expand" else "Mở rộng")
                        )
                    }
                }

                Text(
                    selected?.let { "${it.name} • ${it.address}" }
                        ?: if (english) "No device connected" else "Chưa kết nối thiết bị",
                    fontWeight = FontWeight.Medium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = scan) {
                        Text(if (english) "Scan again" else "Quét lại")
                    }
                    OutlinedButton(onClick = disconnect) {
                        Text(if (english) "Disconnect" else "Ngắt kết nối")
                    }
                }

                if (expanded) {
                    if (devices.isEmpty()) {
                        Text(
                            if (english) "Scanning for ESP32_ECG_AI_Tester... Ensure Bluetooth is turned on."
                            else "Đang tìm kiếm ESP32_ECG_AI_Tester... Hãy chắc chắn đã bật Bluetooth và cấp nguồn cho ESP32.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    devices.forEach { d ->
                        OutlinedButton(
                            onClick = { connect(d) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(d.name, fontWeight = FontWeight.Bold)
                                    Text(d.address, style = MaterialTheme.typography.labelSmall)
                                }
                                if (d.isRecommended) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            "ESP32 AI",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

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
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
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

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    english: Boolean,
    onEnglish: (Boolean) -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (english) "Settings" else "Cài đặt",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                    if (english) "Biomedical Hardware Guide" else "Hướng dẫn phần cứng sinh hiệu",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (english) "Target Device: ESP32-S3 (Service UUID: 4fafc201-1fb5-459e-8fcc-c5c9c331914b)\n" +
                            "Sensors: AD8232 (ECG 125Hz) & MAX30102 (SpO2 & PPG 100Hz)\n" +
                            "Edge AI: 3-branch CNN LeakyReLU with AAMI 5-class output."
                    else "Thiết bị kết nối: ESP32-S3 (Service UUID: 4fafc201-1fb5-459e-8fcc-c5c9c331914b)\n" +
                            "Cảm biến: AD8232 (Điện tim 125Hz) & MAX30102 (Oxy & Mạch PPG 100Hz)\n" +
                            "Mô hình AI: 3-nhánh CNN LeakyReLU phân loại 5 lớp theo chuẩn AAMI MIT-BIH.",
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

        // Draw background grid lines
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
            // Keep waveform within bounds with a 10% safety margin top and bottom
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
