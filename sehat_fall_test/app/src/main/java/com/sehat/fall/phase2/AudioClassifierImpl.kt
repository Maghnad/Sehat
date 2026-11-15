package com.sehat.fall.phase2

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Concrete implementation of AudioClassifier using on-device TensorFlow Lite.
 * Loads audio_model.tflite (YAMNet embeddings + trained distress head)
 * and audio_config.json.
 *
 * Input: 32000 samples of 16-bit PCM @ 16 kHz (2.0 seconds), normalized to [-1, 1]
 * Output: 4 classes ["background", "scream", "cry", "glass_break"]
 * P(distress) = 1.0 - P(background)
 */
class AudioClassifierImpl(private val context: Context) : AudioClassifier {

    private var interpreter: Interpreter? = null

    var sampleRate: Int = 16000
        private set
    var chunkSamples: Int = 32000
        private set
    var classNames: List<String> = listOf("background", "scream", "cry", "glass_break")
        private set
    var thresholdFa1Pct: Float = 0.896f
        private set
    var thresholdFa2Pct: Float = 0.779f
        private set
    var thresholdFa5Pct: Float = 0.602f
        private set

    override fun initialize(): Boolean {
        return try {
            val assetManager = context.assets

            // 1. Load config
            val configStr = assetManager.open("audio_config.json").bufferedReader().use { it.readText() }
            val configJson = JSONObject(configStr)
            sampleRate = configJson.optInt("sample_rate", 16000)
            chunkSamples = configJson.optInt("chunk_samples", 32000)

            val classesArray = configJson.optJSONArray("classes")
            if (classesArray != null) {
                val list = mutableListOf<String>()
                for (i in 0 until classesArray.length()) {
                    list.add(classesArray.getString(i))
                }
                classNames = list
            }

            val thrObj = configJson.optJSONObject("suggested_thresholds")
            if (thrObj != null) {
                thresholdFa1Pct = thrObj.optJSONObject("fa_1pct")?.optDouble("threshold", 0.896)?.toFloat() ?: 0.896f
                thresholdFa2Pct = thrObj.optJSONObject("fa_2pct")?.optDouble("threshold", 0.779)?.toFloat() ?: 0.779f
                thresholdFa5Pct = thrObj.optJSONObject("fa_5pct")?.optDouble("threshold", 0.602)?.toFloat() ?: 0.602f
            }

            // 2. Load TFLite Model
            val modelFd = assetManager.openFd("audio_model.tflite")
            val modelBuffer = FileInputStream(modelFd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                modelFd.startOffset,
                modelFd.declaredLength
            )
            interpreter = Interpreter(modelBuffer)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun classifyChunk(pcm16kMono16Bit: ShortArray): AudioClassifier.AudioResult {
        val interp = interpreter ?: throw IllegalStateException("AudioClassifier is not initialized")
        require(pcm16kMono16Bit.size == chunkSamples) {
            "Expected chunk size $chunkSamples, received ${pcm16kMono16Bit.size}"
        }

        val inputBuffer = ByteBuffer.allocateDirect(chunkSamples * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until chunkSamples) {
            inputBuffer.putFloat(pcm16kMono16Bit[i].toFloat() / 32768.0f)
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(4) }
        interp.run(inputBuffer, output)

        val probs = output[0]
        val pDistress = (1.0f - probs[0]).coerceIn(0.0f, 1.0f)

        var maxIdx = 0
        var maxProb = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > maxProb) {
                maxProb = probs[i]
                maxIdx = i
            }
        }

        val topClass = when (maxIdx) {
            0 -> AudioClassifier.AudioClass.BACKGROUND
            1 -> AudioClassifier.AudioClass.SCREAM
            2 -> AudioClassifier.AudioClass.CRY
            3 -> AudioClassifier.AudioClass.GLASS_BREAK
            else -> AudioClassifier.AudioClass.BACKGROUND
        }

        return AudioClassifier.AudioResult(
            probabilities = probs,
            distressProbability = pDistress,
            topClass = topClass
        )
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}
