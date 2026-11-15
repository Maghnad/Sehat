package com.sehat.fall.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sehat.fall.R
import com.sehat.fall.alert.AlertManager

class AlertActivity : Activity() {

    companion object {
        const val EXTRA_PROBABILITY = "extra_probability"
        const val EXTRA_COUNTDOWN_SECONDS = "extra_countdown_seconds"
    }

    private var countDownTimer: CountDownTimer? = null
    private var probability: Float = 1.0f
    private var countdownSeconds: Int = 10
    private var isResolved = false

    private lateinit var timerTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var okButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure activity wakes up device and displays over keyguard
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
        countdownSeconds = intent.getIntExtra(EXTRA_COUNTDOWN_SECONDS, 10).coerceIn(5, 10)

        buildLayout()
        startCountdown()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#450A0A")) // Deep crimson alarm background
            setPadding(48, 64, 48, 64)
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.fall_detected_title)
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(titleView)

        val probView = TextView(this).apply {
            text = "Confidence: ${"%.1f".format(probability * 100)}%"
            textSize = 18f
            setTextColor(Color.parseColor("#FECACA"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }
        root.addView(probView)

        timerTextView = TextView(this).apply {
            text = "$countdownSeconds"
            textSize = 96f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        root.addView(timerTextView)

        statusTextView = TextView(this).apply {
            text = "Escalating alert in seconds...\nTap button if you are unharmed."
            textSize = 18f
            setTextColor(Color.parseColor("#FCA5A5"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        root.addView(statusTextView)

        okButton = Button(this).apply {
            text = getString(R.string.im_ok)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#10B981")) // High-contrast emerald green
            setPadding(48, 36, 48, 36)
            setOnClickListener {
                handleUserOk()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(24, 16, 24, 16)
        }
        root.addView(okButton, btnParams)

        setContentView(root)
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer((countdownSeconds * 1000).toLong(), 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = (millisUntilFinished / 1000L).toInt() + 1
                timerTextView.text = "$secLeft"
            }

            override fun onFinish() {
                timerTextView.text = "0"
                handleCountdownExpired()
            }
        }.start()
    }

    private fun handleUserOk() {
        if (isResolved) return
        isResolved = true
        countDownTimer?.cancel()
        AlertManager.onUserCancelled(this, probability)
        finish()
    }

    private fun handleCountdownExpired() {
        if (isResolved) return
        isResolved = true
        AlertManager.onCountdownExpired(this, probability)

        statusTextView.text = "ALERT ESCALATED!\nEmergency notification posted locally."
        statusTextView.setTextColor(Color.YELLOW)
        okButton.text = "DISMISS"
        okButton.setBackgroundColor(Color.DKGRAY)
        okButton.setTextColor(Color.WHITE)
        okButton.setOnClickListener {
            finish()
        }
    }

    override fun onBackPressed() {
        // Do not allow accidental back-press dismissal; user must tap "I'M OK"
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        if (!isResolved) {
            AlertManager.stopAlarmAndVibration(this)
        }
        super.onDestroy()
    }
}
