package com.example.ecgmonitor.domain

data class EcgAssessment(
    val bpm: Int?,
    val title: String,
    val advice: String,
    val aiClassName: String? = null,
    val spo2: Float? = null,
    val ppgBpm: Float? = null
)

/** Screening assessment helper supporting both onboard peak detection and ESP32 AI inference output. */
object EcgAnalysis {
    fun assess(
        samples: List<Float>,
        sampleRate: Int = 125,
        espBpm: Float? = null,
        aiClass: Int? = null,
        aiClassName: String? = null,
        spo2: Float? = null,
        ppgBpm: Float? = null
    ): EcgAssessment {
        val effectiveBpm: Int? = espBpm?.takeIf { it > 0 }?.toInt() ?: run {
            if (samples.size < sampleRate * 5) return@run null
            val mean = samples.map { it.toDouble() }.average()
            val centered = samples.map { it - mean }
            val threshold = centered.maxOrNull() ?: 0.0
            val refractory = (sampleRate * 0.30).toInt()
            val peaks = mutableListOf<Int>()
            var last = -refractory
            for (i in 1 until centered.lastIndex) {
                if (centered[i] > threshold * .55 && centered[i] > centered[i - 1] && centered[i] >= centered[i + 1] && i - last >= refractory) {
                    peaks += i
                    last = i
                }
            }
            if (peaks.size >= 2) {
                (60.0 * sampleRate / ((peaks.last() - peaks.first()).toDouble() / (peaks.size - 1))).toInt()
            } else null
        }

        val aiTitle = when (aiClass) {
            0 -> "Bình thường (Normal N)"
            1 -> "Ngoại tâm thu trên thất (S)"
            2 -> "Ngoại tâm thu thất (PVC - V)"
            3 -> "Nhịp kết hợp (Fusion F)"
            4 -> "Nhịp máy tạo nhịp / Không xác định (Q)"
            else -> aiClassName ?: (if (effectiveBpm == null) "Chưa đủ dữ liệu" else "Nhịp bình thường")
        }

        val advice = when {
            effectiveBpm == null -> "Đang đợi tín hiệu điện tim. Vui lòng kiểm tra tiếp xúc điện cực AD8232."
            aiClass == 2 -> "Phát hiện ngoại tâm thu thất (PVC). Hãy ngồi nghỉ ngơi, hít thở đều. Nếu xuất hiện kèm đau thắt ngực hay chóng mặt, hãy tới cơ sở y tế."
            aiClass == 1 -> "Phát hiện nhịp sớm trên thất. Tránh căng thẳng, cà phê, chất kích thích và theo dõi thêm."
            aiClass == 3 || aiClass == 4 -> "Phát hiện hình thái phức bộ bất thường. Ghi lại phiên đo để tham khảo ý kiến bác sĩ chuyên khoa tim mạch."
            effectiveBpm < 50 -> "Nhịp tim chậm (< 50 BPM). Nếu có triệu chứng hoa mắt, chóng mặt hoặc choáng, cần đi khám sớm."
            effectiveBpm > 100 -> "Nhịp tim nhanh (> 100 BPM). Nghỉ ngơi thư giãn và đo lại khi bình tĩnh."
            spo2 != null && spo2 in 1.0f..94.0f -> "Nồng độ oxy trong máu SpO2 dưới 95%. Kiểm tra lại vị trí đặt ngón tay hoặc tìm tư vấn y tế nếu khó thở."
            else -> "Nhịp tim và chỉ số sinh hiệu trong khoảng tham khảo. Tiếp tục duy trì lối sống lành mạnh nhé!"
        }

        return EcgAssessment(
            bpm = effectiveBpm,
            title = aiTitle,
            advice = advice,
            aiClassName = aiClassName,
            spo2 = spo2,
            ppgBpm = ppgBpm
        )
    }
}
