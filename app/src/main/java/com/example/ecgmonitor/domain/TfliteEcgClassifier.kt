package com.example.ecgmonitor.domain

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

data class EcgInferenceResult(
    val classIndex: Int,
    val className: String,
    val classFullName: String,
    val confidence: Float,
    val probabilities: FloatArray,
    val modelId: String,
    val inferenceTimeMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EcgInferenceResult
        return classIndex == other.classIndex &&
                className == other.className &&
                probabilities.contentEquals(other.probabilities) &&
                modelId == other.modelId
    }

    override fun hashCode(): Int {
        var result = classIndex
        result = 31 * result + className.hashCode()
        result = 31 * result + probabilities.contentHashCode()
        result = 31 * result + modelId.hashCode()
        return result
    }
}

class TfliteEcgClassifier(private val context: Context) {
    companion object {
        private const val TAG = "TfliteEcgClassifier"
        val CLASS_SHORT_NAMES = arrayOf("N", "S", "V", "F", "Q")
        val CLASS_FULL_NAMES = arrayOf(
            "Bình thường (Normal N)",
            "Ngoại tâm thu trên thất (S)",
            "Ngoại tâm thu thất (PVC - V)",
            "Nhịp kết hợp (Fusion F)",
            "Nhịp máy tạo nhịp / Không xác định (Q)"
        )
    }

    private var currentModelMeta: TfliteModelMeta = TfliteModelMeta.DEFAULT_MODEL
    private var interpreter: Interpreter? = null

    init {
        loadModel(currentModelMeta)
    }

    @Synchronized
    fun loadModel(meta: TfliteModelMeta): Boolean {
        return try {
            interpreter?.close()
            interpreter = null

            val assetFd = context.assets.openFd(meta.assetFilename)
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFd.startOffset
            val declaredLength = assetFd.declaredLength
            val modelByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseNNAPI(false)
            }

            val newInterpreter = Interpreter(modelByteBuffer, options)
            newInterpreter.allocateTensors()
            interpreter = newInterpreter
            currentModelMeta = meta
            Log.i(TAG, "Successfully loaded TFLite model: ${meta.displayName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${meta.assetFilename}", e)
            false
        }
    }

    fun getCurrentModel(): TfliteModelMeta = currentModelMeta

    @Synchronized
    fun classify(beatSamples: FloatArray): EcgInferenceResult? {
        val activeInterpreter = interpreter ?: return null
        val meta = currentModelMeta

        if (beatSamples.size != meta.windowSize) {
            Log.w(TAG, "Sample size mismatch: expected ${meta.windowSize}, got ${beatSamples.size}")
            return null
        }

        val startTime = System.currentTimeMillis()

        return try {
            val inputTensor = activeInterpreter.getInputTensor(0)
            val outputTensor = activeInterpreter.getOutputTensor(0)

            val inDataType = inputTensor.dataType()
            val inQuant = inputTensor.quantizationParams()
            val inScale = if (inQuant.scale > 0f) inQuant.scale else 0.004136f
            val inZeroPoint = inQuant.zeroPoint

            val outDataType = outputTensor.dataType()
            val outQuant = outputTensor.quantizationParams()
            val outScale = if (outQuant.scale > 0f) outQuant.scale else 0.00390625f
            val outZeroPoint = outQuant.zeroPoint

            // Prepare input ByteBuffer
            val bytePerSample = if (inDataType == DataType.INT8) 1 else 4
            val inputBuffer = ByteBuffer.allocateDirect(meta.windowSize * bytePerSample).apply {
                order(ByteOrder.nativeOrder())
                rewind()
            }

            for (sample in beatSamples) {
                // Ensure sample is within [0.0, 1.0]
                val clamped = sample.coerceIn(0f, 1f)
                if (inDataType == DataType.INT8) {
                    val qVal = Math.round(clamped / inScale + inZeroPoint).coerceIn(-128, 127).toByte()
                    inputBuffer.put(qVal)
                } else {
                    inputBuffer.putFloat(clamped)
                }
            }
            inputBuffer.rewind()

            // Prepare output ByteBuffer for 5 classes
            val numClasses = 5
            val outByteCount = if (outDataType == DataType.INT8) numClasses else numClasses * 4
            val outputBuffer = ByteBuffer.allocateDirect(outByteCount).apply {
                order(ByteOrder.nativeOrder())
                rewind()
            }

            // Run inference
            activeInterpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            // Extract raw floats
            val rawOutputs = FloatArray(numClasses)
            for (c in 0 until numClasses) {
                rawOutputs[c] = if (outDataType == DataType.INT8) {
                    val bVal = outputBuffer.get()
                    (bVal.toFloat() - outZeroPoint) * outScale
                } else {
                    outputBuffer.getFloat()
                }
            }

            // Softmax normalization
            val maxLogit = rawOutputs.maxOrNull() ?: 0f
            var sumExp = 0.0
            val expVals = DoubleArray(numClasses)
            for (c in 0 until numClasses) {
                expVals[c] = exp((rawOutputs[c] - maxLogit).toDouble())
                sumExp += expVals[c]
            }

            val probabilities = FloatArray(numClasses)
            var maxProb = -1f
            var bestClass = 0
            for (c in 0 until numClasses) {
                val p = if (sumExp > 0.0) (expVals[c] / sumExp).toFloat() else 0.2f
                probabilities[c] = p
                if (p > maxProb) {
                    maxProb = p
                    bestClass = c
                }
            }

            val elapsed = System.currentTimeMillis() - startTime

            EcgInferenceResult(
                classIndex = bestClass,
                className = CLASS_SHORT_NAMES.getOrElse(bestClass) { "N" },
                classFullName = CLASS_FULL_NAMES.getOrElse(bestClass) { "Bình thường (Normal N)" },
                confidence = maxProb * 100f,
                probabilities = probabilities,
                modelId = meta.id,
                inferenceTimeMs = elapsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error running inference", e)
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
