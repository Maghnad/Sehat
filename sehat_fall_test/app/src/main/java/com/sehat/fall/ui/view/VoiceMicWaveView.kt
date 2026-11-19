package com.sehat.fall.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Animated sound wave equalizer bars that bounce with live microphone RMS amplitude
 * during the voice command ("HELP") emergency triage listening window.
 */
class VoiceMicWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val numBars = 5
    private val barHeights = FloatArray(numBars) { 12f }
    private var isRecognized = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38BDF8") // Cyan default
    }

    private val barRect = RectF()

    fun updateRmsLevel(rmsDb: Float) {
        if (isRecognized) return
        // RMS dB typically ranges from -2 to 10 dB in Android SpeechRecognizer
        val normalized = ((rmsDb + 2f) / 12f).coerceIn(0.1f, 1.0f)
        
        // Stagger heights across 5 bars for realistic equalizer animation
        barHeights[0] = 12f + (normalized * 28f)
        barHeights[1] = 16f + (normalized * 48f)
        barHeights[2] = 20f + (normalized * 65f) // Center tallest
        barHeights[3] = 16f + (normalized * 50f)
        barHeights[4] = 12f + (normalized * 30f)

        postInvalidateOnAnimation()
    }

    fun markRecognized() {
        isRecognized = true
        barPaint.color = Color.parseColor("#10B981") // Success Green
        for (i in 0 until numBars) {
            barHeights[i] = 55f
        }
        invalidate()
    }

    fun reset() {
        isRecognized = false
        barPaint.color = Color.parseColor("#38BDF8")
        for (i in 0 until numBars) {
            barHeights[i] = 14f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val barWidth = 14f
        val spacing = 12f
        val totalWidth = (numBars * barWidth) + ((numBars - 1) * spacing)
        var startX = (w - totalWidth) / 2f
        val centerY = h / 2f

        for (i in 0 until numBars) {
            val halfH = barHeights[i] / 2f
            barRect.set(startX, centerY - halfH, startX + barWidth, centerY + halfH)
            canvas.drawRoundRect(barRect, 8f, 8f, barPaint)
            startX += barWidth + spacing
        }
    }
}
