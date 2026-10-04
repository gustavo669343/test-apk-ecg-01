package com.example.ecgmonitor.domain

/**
 * Metadata definition for On-Device TFLite ECG classification models.
 */
data class TfliteModelMeta(
    val id: String,
    val displayName: String,
    val assetFilename: String,
    val windowSize: Int,
    val preSamples: Int,
    val postSamples: Int,
    val accuracyPercent: Float,
    val macroF1: Float,
    val architecture: String,
    val description: String
) {
    companion object {
        val ALL_MODELS = listOf(
            TfliteModelMeta(
                id = "kachuee_residual_int8",
                displayName = "Kachuee Deep ResNet (Top 187 Mẫu)",
                assetFilename = "models/kachuee_residual_int8.tflite",
                windowSize = 187,
                preSamples = 37,
                postSamples = 149,
                accuracyPercent = 99.21f,
                macroF1 = 0.9418f,
                architecture = "5-Block Residual Conv1D",
                description = "Mạng ResNet chuẩn Kaggle/MIT-BIH (187 mẫu @ 125Hz, Acc 99.21%, S-F1 92.94%)"
            ),
            TfliteModelMeta(
                id = "model_3_branch_f32_leaky_int8",
                displayName = "3-Branch Inception f32 Leaky (Top 1 Toàn diện)",
                assetFilename = "models/model_3_branch_f32_leaky_int8.tflite",
                windowSize = 375,
                preSamples = 250,
                postSamples = 124,
                accuracyPercent = 98.85f,
                macroF1 = 0.9401f,
                architecture = "3-Branch Inception Conv1D (MaxPool)",
                description = "Quán quân toàn diện đa nhánh (375 mẫu @ 125Hz, F1 0.9401, dung lượng 42.6 KB INT8)"
            ),
            TfliteModelMeta(
                id = "model_4_branch_f16_relu_int8",
                displayName = "4-Branch Inception f16 ReLU (Chuẩn TinyML)",
                assetFilename = "models/model_4_branch_f16_relu_int8.tflite",
                windowSize = 375,
                preSamples = 250,
                postSamples = 124,
                accuracyPercent = 98.78f,
                macroF1 = 0.9299f,
                architecture = "4-Branch Inception Conv1D (MaxPool)",
                description = "Cân bằng tối ưu giữa độ chính xác và tài nguyên bộ nhớ (34.8 KB INT8)"
            ),
            TfliteModelMeta(
                id = "model_4_branch_f8_relu_int8",
                displayName = "4-Branch Inception f8 ReLU (Siêu nhẹ Edge)",
                assetFilename = "models/model_4_branch_f8_relu_int8.tflite",
                windowSize = 375,
                preSamples = 250,
                postSamples = 124,
                accuracyPercent = 98.66f,
                macroF1 = 0.9242f,
                architecture = "4-Branch Inception Conv1D (MaxPool)",
                description = "Mô hình siêu nhẹ chỉ 27.9 KB INT8, tối ưu hóa tốc độ suy luận nhanh nhất"
            ),
            TfliteModelMeta(
                id = "model_3_branch_f32_relu_avgpool_int8",
                displayName = "3-Branch Inception f32 ReLU (Top 1 AvgPool)",
                assetFilename = "models/model_3_branch_f32_relu_avgpool_int8.tflite",
                windowSize = 375,
                preSamples = 250,
                postSamples = 124,
                accuracyPercent = 98.76f,
                macroF1 = 0.9338f,
                architecture = "3-Branch Inception Conv1D (AvgPool)",
                description = "Quán quân kiến trúc AveragePooling làm mượt đặc trưng (S-beat F1 90.85%)"
            )
        )

        val DEFAULT_MODEL = ALL_MODELS[1] // model_3_branch_f32_leaky_int8
    }
}
