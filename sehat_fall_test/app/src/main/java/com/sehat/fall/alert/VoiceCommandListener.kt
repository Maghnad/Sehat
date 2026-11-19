package com.sehat.fall.alert

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceCommandListener(
    private val context: Context,
    private val onHelpSpotted: (String) -> Unit,
    private val onErrorOrSilence: (String) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        val HELP_KEYWORDS = listOf(
            "help",
            "help me",
            "emergency",
            "save me",
            "bachao",
            "please help",
            "assist"
        )
    }

    fun start() {
        if (isListening) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onErrorOrSilence("Speech recognition not available on device")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech matched"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout (silence)"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network unavailable"
                            else -> "Recognition code $error"
                        }
                        if (isListening) {
                            // If still in listening window, attempt one restart or pass silence notice
                            onErrorOrSilence(errorMsg)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        checkMatches(matches)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        checkMatches(matches)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            isListening = true
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            isListening = false
            onErrorOrSilence("Failed to start speech recognizer: ${e.message}")
        }
    }

    private fun checkMatches(matches: ArrayList<String>?) {
        if (matches.isNullOrEmpty() || !isListening) return

        for (phrase in matches) {
            val lower = phrase.lowercase().trim()
            for (keyword in HELP_KEYWORDS) {
                if (lower.contains(keyword)) {
                    isListening = false
                    stop()
                    onHelpSpotted(phrase)
                    return
                }
            }
        }
    }

    fun stop() {
        isListening = false
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
}
