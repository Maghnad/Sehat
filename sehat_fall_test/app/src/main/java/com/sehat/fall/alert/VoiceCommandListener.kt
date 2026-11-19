package com.sehat.fall.alert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt

class VoiceCommandListener(
    private val context: Context,
    private val onHelpSpotted: (String) -> Unit,
    private val onSpeechTextUpdate: (String) -> Unit,
    private val onMicLevelChanged: (Float) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognitionIntent: Intent? = null
    private var retryCount = 0
    private val maxRetries = 5

    // AudioRecord fallback for hardware voice/amplitude detection
    private var audioRecord: AudioRecord? = null
    private var audioRecordThread: Thread? = null
    private var isAudioRecordRunning = false

    companion object {
        // Extended keyword dictionary covering direct words, phonetics, and distress terms
        val HELP_KEYWORDS = listOf(
            "help",
            "help me",
            "please help",
            "help please",
            // Common phonetic mishearings by speech recognizer
            "yelp",
            "held",
            "health",
            "halp",
            // Distress terms
            "bachao",
            "save me",
            "emergency",
            "sos",
            "assist"
        )

        fun isPhraseMatching(phrase: String): Boolean {
            val lower = phrase.lowercase().trim()
            for (keyword in HELP_KEYWORDS) {
                if (keyword.contains(" ")) {
                    if (lower.contains(keyword)) return true
                } else {
                    val regex = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(lower)) return true
                }
            }
            return false
        }
    }

    fun start() {
        if (isListening) return
        isListening = true
        retryCount = 0

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission) {
            onStatusUpdate("Microphone permission NOT granted!")
            return
        }

        // Initialize SpeechRecognizer on the main thread
        mainHandler.post {
            initAndStartSpeechRecognizer()
        }

        // Also start AudioRecord fallback listener in background for loudness/energy detection
        startAudioRecordFallback()
    }

    private fun initAndStartSpeechRecognizer() {
        if (!isListening) return

        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (!isAvailable) {
            onStatusUpdate("Speech recognition service not found on device. Using microphone loudness sensor.")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onStatusUpdate("🎤 Microphone active. Say 'HELP'...")
                    }

                    override fun onBeginningOfSpeech() {
                        onStatusUpdate("🗣️ Voice detected, analyzing...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Forward RMS dB level to UI
                        onMicLevelChanged(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        onStatusUpdate("Analyzing speech chunk...")
                    }

                    override fun onError(error: Int) {
                        val (errorMsg, shouldRetry) = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> Pair("No words matched", true)
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Pair("Silence timeout", true)
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                Pair("Offline - attempting local recognition", true)
                            SpeechRecognizer.ERROR_AUDIO -> Pair("Audio recording busy", true)
                            SpeechRecognizer.ERROR_CLIENT -> Pair("Speech client reset", true)
                            else -> Pair("Recognition code $error", true)
                        }

                        if (isListening) {
                            onStatusUpdate("Listening... ($errorMsg)")
                            if (shouldRetry && retryCount < maxRetries) {
                                retryCount++
                                mainHandler.postDelayed({
                                    restartListeningSafely()
                                }, 250L)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        handleRecognizedPhrases(matches, isFinal = true)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        handleRecognizedPhrases(matches, isFinal = false)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }

            speechRecognizer?.startListening(recognitionIntent)
            onStatusUpdate("🎤 Listening for voice command 'HELP'...")
        } catch (e: Exception) {
            e.printStackTrace()
            onStatusUpdate("SpeechRecognizer init error: ${e.message}")
        }
    }

    private fun restartListeningSafely() {
        if (!isListening) return
        try {
            speechRecognizer?.cancel()
            recognitionIntent?.let {
                speechRecognizer?.startListening(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleRecognizedPhrases(matches: ArrayList<String>?, isFinal: Boolean) {
        if (matches.isNullOrEmpty() || !isListening) return

        val primary = matches[0]
        onSpeechTextUpdate(primary)

        for (phrase in matches) {
            if (isPhraseMatching(phrase)) {
                isListening = false
                stop()
                onHelpSpotted(phrase)
                return
            }
        }

        // If it was a final result without match, automatically re-listen for the remaining window
        if (isFinal && isListening) {
            mainHandler.postDelayed({
                restartListeningSafely()
            }, 200L)
        }
    }

    /**
     * Secondary hardware AudioRecord listener.
     * Continuously samples PCM audio to compute real-time RMS sound levels.
     * If the user shouts loudly (e.g. scream or loud "HELP!"), this can detect distress
     * even on devices where speech dictation is unavailable or failing.
     */
    private fun startAudioRecordFallback() {
        if (isAudioRecordRunning) return
        isAudioRecordRunning = true

        audioRecordThread = Thread {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = minBufSize.coerceAtLeast(sampleRate / 10) // ~100 ms

            var record: AudioRecord? = null
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                    audioRecord = record

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        record.startRecording()
                        val audioBuffer = ShortArray(bufferSize / 2)
                        var loudStreak = 0

                        while (isListening && isAudioRecordRunning) {
                            val read = record.read(audioBuffer, 0, audioBuffer.size)
                            if (read > 0) {
                                var sumSquares = 0.0
                                for (i in 0 until read) {
                                    val sample = audioBuffer[i]
                                    sumSquares += (sample * sample)
                                }
                                val rms = sqrt(sumSquares / read)
                                val db = (20.0 * log10(rms.coerceAtLeast(1.0))).toFloat()

                                mainHandler.post {
                                    onMicLevelChanged(db)
                                }

                                // Shouting threshold: > 78 dB sustained
                                if (db > 78.0f) {
                                    loudStreak++
                                    if (loudStreak >= 3) { // ~300 ms of loud shouting
                                        mainHandler.post {
                                            if (isListening) {
                                                isListening = false
                                                stop()
                                                onHelpSpotted("Loud vocal distress shout detected (${db.toInt()} dB)")
                                            }
                                        }
                                        break
                                    }
                                } else {
                                    loudStreak = 0
                                }
                            }
                            Thread.sleep(50)
                        }
                    }
                }
            } catch (e: Exception) {
                // Secondary fallback suppressed if primary microphone is held exclusively
            } finally {
                try {
                    record?.stop()
                    record?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.apply {
            name = "SehatAudioFallbackThread"
            start()
        }
    }

    fun stop() {
        isListening = false
        isAudioRecordRunning = false

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                speechRecognizer = null
            }
        }

        try {
            audioRecordThread?.interrupt()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord = null
            audioRecordThread = null
        }
    }
}
