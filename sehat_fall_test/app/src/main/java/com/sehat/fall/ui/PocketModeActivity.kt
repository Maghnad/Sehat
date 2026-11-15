package com.sehat.fall.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class PocketModeActivity : Activity() {

    private var countdownTimer: CountDownTimer? = null
    private var isBlackScreenActive = false

    private lateinit var rootLayout: FrameLayout
    private lateinit var countdownLayout: LinearLayout
    private lateinit var countdownText: TextView
    private lateinit var hintText: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        countdownLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 64, 48, 64)
        }

        val title = TextView(this).apply {
            text = "Entering Pocket Mode"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        countdownLayout.addView(title)

        countdownText = TextView(this).apply {
            text = "10"
            textSize = 80f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        countdownLayout.addView(countdownText)

        hintText = TextView(this).apply {
            text = "Put the phone into your trouser pocket now.\nScreen turns black in 10 seconds."
            textSize = 16f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
        }
        countdownLayout.addView(hintText)

        rootLayout.addView(
            countdownLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Long press listener on root layout to exit pocket mode
        rootLayout.setOnLongClickListener {
            Toast.makeText(this, "Exited Pocket Mode", Toast.LENGTH_SHORT).show()
            finish()
            true
        }

        setContentView(rootLayout)
        startPocketCountdown()
    }

    private fun startPocketCountdown() {
        countdownTimer = object : CountDownTimer(10000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L).toInt() + 1
                countdownText.text = "$sec"
            }

            override fun onFinish() {
                enterBlackScreen()
            }
        }.start()
    }

    private fun enterBlackScreen() {
        isBlackScreenActive = true
        countdownLayout.visibility = View.GONE

        // Dim screen brightness to minimum to save power and prevent accidental inputs
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp

        Toast.makeText(this, "Pocket mode active. Long-press screen to stop.", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        super.onDestroy()
    }
}
