package com.sehat.fall.detection

import android.content.Context
import com.sehat.fall.model.FallConfig
import com.sehat.fall.phase2.AudioClassifier
import com.sehat.fall.phase2.AudioClassifierImpl
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

data class MotionTestDetails(
    val passed: Boolean,
    val maxResampleDiff: Double,
    val maxProbDiff: Double,
    val windowsTested: Int,
    val error: String? = null
)

data class AudioTestDetails(
    val passed: Boolean,
    val maxProbDiff: Double,
    val chunksTested: Int,
    val error: String? = null
)

data class SelfTestResult(
    val passed: Boolean,
    val summary: String,
    val maxResampleDiff: Double,
    val maxProbDiff: Double,
    val windowsTested: Int,
    val maxAudioProbDiff: Double = 0.0,
    val audioChunksTested: Int = 0,
    val audioPassed: Boolean = true
)

/**
 * Executes automated on-device self-test comparing the phone's Signal processing
 * and TensorFlow Lite runtime against golden_test.json and audio_golden.json reference outputs.
 * Acceptance criteria: tolerance 1e-3 -> must show PASSED for both Motion and Audio.
 */
class SelfTestRunner(private val context: Context) {

    fun run(): SelfTestResult {
        val motionResult = runMotionTest()
        if (!motionResult.passed) {
            return SelfTestResult(
                passed = false,
                summary = "FAILED (Motion): ${motionResult.error}",
                maxResampleDiff = motionResult.maxResampleDiff,
                maxProbDiff = motionResult.maxProbDiff,
                windowsTested = motionResult.windowsTested,
                audioPassed = false
            )
        }

        val audioResult = runAudioTest()
        if (!audioResult.passed) {
            return SelfTestResult(
                passed = false,
                summary = "FAILED (Audio): ${audioResult.error}",
                maxResampleDiff = motionResult.maxResampleDiff,
                maxProbDiff = motionResult.maxProbDiff,
                windowsTested = motionResult.windowsTested,
                maxAudioProbDiff = audioResult.maxProbDiff,
                audioChunksTested = audioResult.chunksTested,
                audioPassed = false
            )
        }

        return SelfTestResult(
            passed = true,
            summary = "PASSED: Both Motion & Audio verified against golden vectors!\n" +
                    "• Motion (${motionResult.windowsTested} windows): max resample diff = ${"%.2e".format(motionResult.maxResampleDiff)}, prob diff = ${"%.2e".format(motionResult.maxProbDiff)}\n" +
                    "• Audio (${audioResult.chunksTested} chunks): max prob diff = ${"%.2e".format(audioResult.maxProbDiff)}",
            maxResampleDiff = motionResult.maxResampleDiff,
            maxProbDiff = motionResult.maxProbDiff,
            windowsTested = motionResult.windowsTested,
            maxAudioProbDiff = audioResult.maxProbDiff,
            audioChunksTested = audioResult.chunksTested,
            audioPassed = true
        )
    }

    private fun runMotionTest(): MotionTestDetails {
        return try {
            val assetManager = context.assets

            // 1. Load config
            val config = assetManager.open("fall_config.json").use {
                FallConfig.fromInputStream(it)
            }

            // 2. Load golden test data
            val goldenStr = assetManager.open("golden_test.json").bufferedReader().use { it.readText() }
            val goldenJson = JSONObject(goldenStr)

            // 3. Load model into TFLite Interpreter
            val modelFd = assetManager.openFd("fall_model.tflite")
            val modelBuffer = FileInputStream(modelFd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                modelFd.startOffset,
                modelFd.declaredLength
            )
            val interpreter = Interpreter(modelBuffer)

            // 4. Test Resampling Stream
            val streamObj = goldenJson.getJSONObject("stream")
            val accArray = streamObj.getJSONArray("acc")
            val gyroArray = streamObj.getJSONArray("gyro")
            val tStart = streamObj.getDouble("t_start")
            val expWinArray = streamObj.getJSONArray("expected_window")

            val accSamples = ArrayList<SensorSample>(accArray.length())
            for (i in 0 until accArray.length()) {
                val row = accArray.getJSONArray(i)
                accSamples.add(
                    SensorSample(
                        timestamp = row.getDouble(0),
                        x = row.getDouble(1).toFloat(),
                        y = row.getDouble(2).toFloat(),
                        z = row.getDouble(3).toFloat()
                    )
                )
            }

            val gyroSamples = ArrayList<SensorSample>(gyroArray.length())
            for (i in 0 until gyroArray.length()) {
                val row = gyroArray.getJSONArray(i)
                gyroSamples.add(
                    SensorSample(
                        timestamp = row.getDouble(0),
                        x = row.getDouble(1).toFloat(),
                        y = row.getDouble(2).toFloat(),
                        z = row.getDouble(3).toFloat()
                    )
                )
            }

            val actualWin = Signal.buildWindow(
                accSamples = accSamples,
                gyroSamples = gyroSamples,
                tStart = tStart,
                winSize = config.window,
                fs = config.fs
            )

            var maxResampleDiff = 0.0
            for (i in 0 until config.window) {
                val expRow = expWinArray.getJSONArray(i)
                for (c in 0 until 6) {
                    val expVal = expRow.getDouble(c).toFloat()
                    val actVal = actualWin[i][c]
                    val diff = abs(actVal - expVal).toDouble()
                    if (diff > maxResampleDiff) maxResampleDiff = diff
                }
            }

            if (maxResampleDiff >= 1e-3) {
                interpreter.close()
                return MotionTestDetails(
                    passed = false,
                    maxResampleDiff = maxResampleDiff,
                    maxProbDiff = 0.0,
                    windowsTested = 0,
                    error = "Resampling diff $maxResampleDiff exceeds tolerance 1e-3"
                )
            }

            // 5. Test Inference on Stream Resampled Window
            val streamNormed = Signal.extractFeatures(actualWin, config.mean, config.std, config.orient)
            val streamInput = Signal.toModelInput(streamNormed)
            val streamOutput = Array(1) { FloatArray(1) }
            interpreter.run(streamInput, streamOutput)
            val expStreamProb = streamObj.getDouble("expected_prob").toFloat()
            var maxProbDiff = abs(streamOutput[0][0] - expStreamProb).toDouble()

            // 6. Test Golden Windows
            val windowsArray = goldenJson.getJSONArray("windows")
            val numWindows = windowsArray.length()
            val output = Array(1) { FloatArray(1) }

            for (w in 0 until numWindows) {
                val item = windowsArray.getJSONObject(w)
                val rawArray = item.getJSONArray("raw")
                val expProb = item.getDouble("expected_prob").toFloat()

                val raw = Array(config.window) { i ->
                    val row = rawArray.getJSONArray(i)
                    FloatArray(6) { c -> row.getDouble(c).toFloat() }
                }

                val normed = Signal.extractFeatures(raw, config.mean, config.std, config.orient)
                val modelInput = Signal.toModelInput(normed)
                interpreter.run(modelInput, output)

                val prob = output[0][0]
                val diff = abs(prob - expProb).toDouble()
                if (diff > maxProbDiff) maxProbDiff = diff

                if (diff >= 1e-3) {
                    interpreter.close()
                    return MotionTestDetails(
                        passed = false,
                        maxResampleDiff = maxResampleDiff,
                        maxProbDiff = maxProbDiff,
                        windowsTested = w + 1,
                        error = "Window $w prob diff $diff exceeds tolerance 1e-3"
                    )
                }
            }

            interpreter.close()
            MotionTestDetails(
                passed = true,
                maxResampleDiff = maxResampleDiff,
                maxProbDiff = maxProbDiff,
                windowsTested = numWindows
            )
        } catch (e: Exception) {
            MotionTestDetails(
                passed = false,
                maxResampleDiff = 0.0,
                maxProbDiff = 0.0,
                windowsTested = 0,
                error = e.message
            )
        }
    }

    private fun runAudioTest(): AudioTestDetails {
        val classifier = AudioClassifierImpl(context)
        return try {
            if (!classifier.initialize()) {
                return AudioTestDetails(
                    passed = false,
                    maxProbDiff = 0.0,
                    chunksTested = 0,
                    error = "Failed to initialize AudioClassifier with audio_model.tflite"
                )
            }

            val goldenStr = context.assets.open("audio_golden.json").bufferedReader().use { it.readText() }
            val goldenJson = JSONObject(goldenStr)
            val examplesArray = goldenJson.getJSONArray("examples")
            val numExamples = examplesArray.length()

            var maxProbDiff = 0.0
            val pcmArray = ShortArray(32000)

            for (i in 0 until numExamples) {
                val item = examplesArray.getJSONObject(i)
                val sourceClass = item.getString("source_class")
                val base64Str = item.getString("pcm16_base64")
                val expProbsArray = item.getJSONArray("expected_probabilities")

                val bytes = java.util.Base64.getDecoder().decode(base64Str)
                require(bytes.size == 64000) { "Expected 64000 bytes for 32000 16-bit samples, got ${bytes.size}" }

                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmArray)

                val result = classifier.classifyChunk(pcmArray)
                val probs = result.probabilities

                for (c in 0 until 4) {
                    val expVal = expProbsArray.getDouble(c).toFloat()
                    val actVal = probs[c]
                    val diff = abs(actVal - expVal).toDouble()
                    if (diff > maxProbDiff) maxProbDiff = diff

                    if (diff >= 1e-3) {
                        classifier.close()
                        return AudioTestDetails(
                            passed = false,
                            maxProbDiff = maxProbDiff,
                            chunksTested = i + 1,
                            error = "Example $i ($sourceClass, class idx $c) prob diff $diff (act=$actVal, exp=$expVal) exceeds tolerance 1e-3"
                        )
                    }
                }
            }

            classifier.close()
            AudioTestDetails(
                passed = true,
                maxProbDiff = maxProbDiff,
                chunksTested = numExamples
            )
        } catch (e: Exception) {
            classifier.close()
            AudioTestDetails(
                passed = false,
                maxProbDiff = 0.0,
                chunksTested = 0,
                error = e.message
            )
        }
    }
}
