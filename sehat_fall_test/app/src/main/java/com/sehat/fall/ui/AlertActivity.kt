package com.sehat.fall.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sehat.fall.R
import com.sehat.fall.SehatApp
import com.sehat.fall.alert.AlertManager
import com.sehat.fall.alert.EmergencyCallHelper
import com.sehat.fall.alert.VoiceAlertPrompt
import com.sehat.fall.alert.VoiceCommandListener

class AlertActivity : Activity() {

    companion object {
        const val EXTRA_PROBABILITY = "extra_probability"
        const val EXTRA_VOICE_SECONDS = "extra_voice_seconds"
        const val EXTRA_CONFIRM_SECONDS = "extra_confirm_seconds"
    }

    private enum class AlertPhase {
        LISTENING_FOR_HELP,
        AWAITING_SAFE_CONFIRMATION,
        ESCALATED_CALLING,
        RESOLVED
    }

    private var currentPhase = AlertPhase.LISTENING_FOR_HELP
    private var probability: Float = 1.0f
    private var voiceSeconds: Int = 7
    private var confirmSeconds: Int = 15

    private var activeTimer: CountDownTimer? = null
    private var voiceListener: VoiceCommandListener? = null
    private var voiceAlertPrompt: VoiceAlertPrompt? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // UI elements
    private lateinit var rootLayout: LinearLayout
    private lateinit var phaseBadge: TextView
    private lateinit var titleTextView: TextView
    private lateinit var promptTextView: TextView
    private lateinit var liveHeardTextView: TextView
    private lateinit var micLevelTextView: TextView
    private lateinit var timerTextView: TextView
    private lateinit var substatusTextView: TextView
    private lateinit var actionButtonPrimary: Button
    private lateinit var actionButtonSecondary: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen and show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        probability = intent.getFloatExtra(EXTRA_PROBABILITY, 1.0f)
        voiceSeconds = intent.getIntExtra(EXTRA_VOICE_SECONDS, 7).coerceIn(5, 15)
        confirmSeconds = intent.getIntExtra(EXTRA_CONFIRM_SECONDS, 15).coerceIn(10, 30)

        voiceAlertPrompt = VoiceAlertPrompt(this)

        buildLayout()
        startPhase1ListeningForHelp()
    }

    private fun buildLayout() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#450A0A")) // Deep crimson alarm background
            setPadding(40, 48, 40, 48)
        }

        phaseBadge = TextView(this).apply {
            text = "PHASE 1: VOICE COMMAND DETECTION"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FEF08A"))
            setBackgroundColor(Color.parseColor("#78350F"))
            gravity = Gravity.CENTER
            setPadding(24, 10, 24, 10)
        }
        rootLayout.addView(phaseBadge)

        titleTextView = TextView(this).apply {
            text = getString(R.string.fall_detected_title)
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 4)
        }
        rootLayout.addView(titleTextView)

        val probView = TextView(this).apply {
            text = "Confidence: ${"%.1f".format(probability * 100)}%"
            textSize = 15f
            setTextColor(Color.parseColor("#FECACA"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        rootLayout.addView(probView)

        promptTextView = TextView(this).apply {
            text = "🎤 LISTENING FOR VOICE COMMAND 'HELP'..."
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        }
        rootLayout.addView(promptTextView)

        // Live transcription view showing what words the recognizer is currently hearing
        liveHeardTextView = TextView(this).apply {
            text = "Say 'HELP' loudly to call relative"
            textSize = 14f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(Color.parseColor("#93C5FD"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
        }
        rootLayout.addView(liveHeardTextView)

        // Live audio level visualizer
        micLevelTextView = TextView(this).apply {
            text = "Mic Level: ▰▱▱▱▱▱"
            textSize = 12f
            setTextColor(Color.parseColor("#6EE7B7"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(micLevelTextView)

        timerTextView = TextView(this).apply {
            text = "$voiceSeconds"
            textSize = 76f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }
        rootLayout.addView(timerTextView)

        substatusTextView = TextView(this).apply {
            val relativeName = SehatApp.instance.settingsManager.relativeName
            text = "Say 'HELP' loudly to immediately call $relativeName.\nOr tap button below if you are unharmed."
            textSize = 14f
            setTextColor(Color.parseColor("#FCA5A5"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(substatusTextView)

        // Primary Action Button
        actionButtonPrimary = Button(this).apply {
            text = "I'M OK - CANCEL ALERT"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#10B981")) // Emerald green
            setPadding(32, 22, 32, 22)
            setOnClickListener { handleUserSafeConfirmation() }
        }
        val btnParams1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(16, 6, 16, 10) }
        rootLayout.addView(actionButtonPrimary, btnParams1)

        // Secondary Action Button (Immediate Emergency Call)
        val relativeName = SehatApp.instance.settingsManager.relativeName
        actionButtonSecondary = Button(this).apply {
            text = "CALL $relativeName NOW"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#DC2626")) // Bright Red
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                triggerDirectCall("Manual emergency button pressed on alert screen")
            }
        }
        val btnParams2 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(16, 4, 16, 8) }
        rootLayout.addView(actionButtonSecondary, btnParams2)

        setContentView(rootLayout)
    }

    /**
     * Phase 1: Open microphone and listen for voice command "HELP".
     */
    private fun startPhase1ListeningForHelp() {
        currentPhase = AlertPhase.LISTENING_FOR_HELP
        val settings = SehatApp.instance.settingsManager
        val relativeName = settings.relativeName

        phaseBadge.text = "PHASE 1: LISTENING FOR 'HELP'"
        phaseBadge.setBackgroundColor(Color.parseColor("#1E3A8A")) // Dark blue
        phaseBadge.setTextColor(Color.parseColor("#93C5FD"))

        promptTextView.text = "🎤 LISTENING FOR VOICE COMMAND 'HELP'..."
        promptTextView.setTextColor(Color.parseColor("#38BDF8"))

        substatusTextView.text = "Say 'HELP' loudly to immediately call $relativeName.\nOr tap green button if you are safe."

        // Check audio recording permission
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMicPermission) {
            // Delay 300 ms so attention chime is finished and microphone hardware is completely free
            mainHandler.postDelayed({
                if (currentPhase == AlertPhase.LISTENING_FOR_HELP) {
                    voiceListener = VoiceCommandListener(
                        context = this,
                        onHelpSpotted = { recognizedText ->
                            runOnUiThread {
                                handleVoiceHelpSpotted(recognizedText)
                            }
                        },
                        onSpeechTextUpdate = { partialSpeech ->
                            runOnUiThread {
                                liveHeardTextView.text = "Hearing: \"$partialSpeech\""
                                liveHeardTextView.setTextColor(Color.parseColor("#FDE047")) // Bright yellow
                            }
                        },
                        onMicLevelChanged = { db ->
                            runOnUiThread {
                                updateMicLevelVisual(db)
                            }
                        },
                        onStatusUpdate = { status ->
                            runOnUiThread {
                                promptTextView.text = status
                            }
                        }
                    ).apply {
                        start()
                    }
                }
            }, 300L)
        } else {
            promptTextView.text = "Microphone permission required for 'HELP' command.\nAwaiting user input..."
            promptTextView.setTextColor(Color.parseColor("#FBBF24"))
        }

        // Run Phase 1 countdown timer
        activeTimer?.cancel()
        activeTimer = object : CountDownTimer((voiceSeconds * 1000).toLong(), 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = (millisUntilFinished / 1000L).toInt() + 1
                timerTextView.text = "$secLeft"
            }

            override fun onFinish() {
                timerTextView.text = "0"
                if (currentPhase == AlertPhase.LISTENING_FOR_HELP) {
                    // No HELP received -> transition to Phase 2 voice confirmation alert
                    transitionToPhase2VoiceAlert()
                }
            }
        }.start()
    }

    private fun updateMicLevelVisual(db: Float) {
        if (currentPhase != AlertPhase.LISTENING_FOR_HELP) return
        val normalized = ((db - 30f) / 50f).coerceIn(0f, 1f)
        val bars = (normalized * 8).toInt()
        val visual = buildString {
            append("Mic Level: ")
            for (i in 0 until 8) {
                if (i <= bars) append("▰") else append("▱")
            }
        }
        micLevelTextView.text = visual
        if (bars > 5) {
            micLevelTextView.setTextColor(Color.parseColor("#F87171")) // Red/High
        } else {
            micLevelTextView.setTextColor(Color.parseColor("#6EE7B7")) // Green
        }
    }

    /**
     * Triggered when voice command "HELP" (or loud vocal distress) is successfully recognized!
     */
    private fun handleVoiceHelpSpotted(recognizedPhrase: String) {
        if (currentPhase != AlertPhase.LISTENING_FOR_HELP) return
        currentPhase = AlertPhase.ESCALATED_CALLING

        activeTimer?.cancel()
        voiceListener?.stop()
        AlertManager.stopAlarmAndVibration(this)

        val settings = SehatApp.instance.settingsManager
        val relativeName = settings.relativeName

        phaseBadge.text = "DISTRESS VOICE COMMAND RECOGNIZED!"
        phaseBadge.setBackgroundColor(Color.parseColor("#7F1D1D"))
        phaseBadge.setTextColor(Color.YELLOW)

        promptTextView.text = "🚨 HEARD: \"$recognizedPhrase\""
        promptTextView.setTextColor(Color.parseColor("#F87171"))

        liveHeardTextView.text = "Emergency matched! Placing call now..."
        micLevelTextView.visibility = View.GONE

        timerTextView.text = "CALLING"
        timerTextView.textSize = 42f

        substatusTextView.text = "Calling $relativeName immediately..."
        substatusTextView.setTextColor(Color.WHITE)

        actionButtonPrimary.text = "DISMISS"
        actionButtonPrimary.setBackgroundColor(Color.DKGRAY)
        actionButtonPrimary.setTextColor(Color.WHITE)
        actionButtonPrimary.setOnClickListener { finish() }

        actionButtonSecondary.visibility = View.GONE

        // Speak confirmation & initiate call
        voiceAlertPrompt?.speak("Help command heard. Calling emergency contact now.")
        EmergencyCallHelper.makeEmergencyCall(
            context = this,
            reason = "Voice distress command 'HELP' spotted (\"$recognizedPhrase\")",
            probability = probability
        )
    }

    /**
     * Phase 2: "HELP" command was NOT received within listening window.
     * Give voice alert: "PLEASE CONFIRM THAT YOU ARE SAFE" and await confirmation button press.
     */
    private fun transitionToPhase2VoiceAlert() {
        if (currentPhase != AlertPhase.LISTENING_FOR_HELP) return
        currentPhase = AlertPhase.AWAITING_SAFE_CONFIRMATION

        voiceListener?.stop()
        micLevelTextView.visibility = View.GONE
        liveHeardTextView.visibility = View.GONE

        val settings = SehatApp.instance.settingsManager
        val relativeName = settings.relativeName

        // Change background to pulsing high-alert purple-red
        rootLayout.setBackgroundColor(Color.parseColor("#581C87"))
        phaseBadge.text = "PHASE 2: SAFE CONFIRMATION REQUIRED"
        phaseBadge.setBackgroundColor(Color.parseColor("#B45309")) // Amber
        phaseBadge.setTextColor(Color.WHITE)

        promptTextView.text = "📢 \"PLEASE CONFIRM THAT YOU ARE SAFE\""
        promptTextView.setTextColor(Color.parseColor("#FEF08A"))

        substatusTextView.text = "If you do not confirm, device will assume you are UNCONSCIOUS and call $relativeName automatically."
        substatusTextView.setTextColor(Color.parseColor("#FDE047"))

        actionButtonPrimary.text = "CONFIRM I AM SAFE"
        actionButtonPrimary.setBackgroundColor(Color.parseColor("#10B981"))
        actionButtonPrimary.setTextColor(Color.BLACK)

        // Audible Voice Alert via TextToSpeech
        voiceAlertPrompt?.speak("PLEASE CONFIRM THAT YOU ARE SAFE. Tap the button if you are unharmed.")

        // Start background siren / vibration so it is audible
        AlertManager.startAlarm(this)
        AlertManager.startVibration(this)

        // Schedule repeating TTS prompt every 5 seconds
        val ttsRunnable = object : Runnable {
            override fun run() {
                if (currentPhase == AlertPhase.AWAITING_SAFE_CONFIRMATION) {
                    voiceAlertPrompt?.speak("PLEASE CONFIRM THAT YOU ARE SAFE.")
                    mainHandler.postDelayed(this, 5000L)
                }
            }
        }
        mainHandler.postDelayed(ttsRunnable, 5000L)

        // Start Phase 2 countdown timer
        activeTimer?.cancel()
        activeTimer = object : CountDownTimer((confirmSeconds * 1000).toLong(), 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = (millisUntilFinished / 1000L).toInt() + 1
                timerTextView.text = "$secLeft"
            }

            override fun onFinish() {
                timerTextView.text = "0"
                if (currentPhase == AlertPhase.AWAITING_SAFE_CONFIRMATION) {
                    handleUnconsciousTimeout()
                }
            }
        }.start()
    }

    /**
     * User pressed "CONFIRM I AM SAFE" / "I'M OK".
     */
    private fun handleUserSafeConfirmation() {
        if (currentPhase == AlertPhase.RESOLVED) return
        currentPhase = AlertPhase.RESOLVED

        activeTimer?.cancel()
        voiceListener?.stop()
        mainHandler.removeCallbacksAndMessages(null)
        voiceAlertPrompt?.stop()
        AlertManager.onUserCancelled(this, probability)

        Toast.makeText(this, "Confirmed: You are safe. Fall alert cancelled.", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Timeout expired without user pressing confirm button.
     * The device assumes the user may be UNCONSCIOUS!
     * Automatically places the emergency call to the saved relative's number.
     */
    private fun handleUnconsciousTimeout() {
        if (currentPhase != AlertPhase.AWAITING_SAFE_CONFIRMATION) return
        currentPhase = AlertPhase.ESCALATED_CALLING

        activeTimer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        AlertManager.stopAlarmAndVibration(this)

        val settings = SehatApp.instance.settingsManager
        val relativeName = settings.relativeName
        val relativePhone = settings.relativePhoneNumber

        rootLayout.setBackgroundColor(Color.parseColor("#7F1D1D")) // Deep alert red

        phaseBadge.text = "⚠️ USER UNRESPONSIVE - ASSUMED UNCONSCIOUS"
        phaseBadge.setBackgroundColor(Color.parseColor("#991B1B"))
        phaseBadge.setTextColor(Color.WHITE)

        promptTextView.text = "🚨 UNCONSCIOUS STATE DETECTED"
        promptTextView.setTextColor(Color.YELLOW)

        timerTextView.text = "DIALING"
        timerTextView.textSize = 42f

        substatusTextView.text = "No response received. Calling $relativeName ($relativePhone) automatically..."
        substatusTextView.setTextColor(Color.WHITE)

        actionButtonPrimary.text = "DISMISS"
        actionButtonPrimary.setBackgroundColor(Color.DKGRAY)
        actionButtonPrimary.setTextColor(Color.WHITE)
        actionButtonPrimary.setOnClickListener { finish() }

        actionButtonSecondary.visibility = View.GONE

        // Announce unconscious triage and make emergency phone call!
        voiceAlertPrompt?.speak("No response received. User may be unconscious. Calling emergency contact now.")

        EmergencyCallHelper.makeEmergencyCall(
            context = this,
            reason = "User unresponsive (unconscious) after fall verification timeout",
            probability = probability
        )
    }

    private fun triggerDirectCall(reason: String) {
        if (currentPhase == AlertPhase.RESOLVED) return
        currentPhase = AlertPhase.ESCALATED_CALLING

        activeTimer?.cancel()
        voiceListener?.stop()
        mainHandler.removeCallbacksAndMessages(null)
        AlertManager.stopAlarmAndVibration(this)

        voiceAlertPrompt?.speak("Emergency call requested.")
        EmergencyCallHelper.makeEmergencyCall(
            context = this,
            reason = reason,
            probability = probability
        )
        finish()
    }

    override fun onBackPressed() {
        // Prevent accidental back dismiss; user must press "CONFIRM I AM SAFE"
    }

    override fun onDestroy() {
        activeTimer?.cancel()
        voiceListener?.stop()
        mainHandler.removeCallbacksAndMessages(null)
        voiceAlertPrompt?.stop()
        if (currentPhase != AlertPhase.RESOLVED) {
            AlertManager.stopAlarmAndVibration(this)
        }
        super.onDestroy()
    }
}
