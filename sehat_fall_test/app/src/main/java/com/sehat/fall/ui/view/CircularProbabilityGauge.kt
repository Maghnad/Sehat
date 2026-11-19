package com.sehat.fall.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Animated circular gauge displaying real-time fall probability and flag persistence status.
 */
class CircularProbabilityGauge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetProbability: Float = 0.0f
    private var animatedProbability: Float = 0.0f
    private var streakText: String = "Streak: 0/2"

    private var animator: ValueAnimator? = null

    private val bgTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        color = Color.parseColor("#1E293B")
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val percentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = 58f
        isFakeBoldText = true
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#94A3B8")
        textSize = 26f
    }

    private val streakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#38BDF8")
        textSize = 24f
        isFakeBoldText = true
    }

    private val arcBounds = RectF()

    fun setProbability(prob: Float, consecutive: Int = 0, targetStreak: Int = 2) {
        val clamped = prob.coerceIn(0.0f, 1.0f)
        targetProbability = clamped
        streakText = "Streak: $consecutive/$targetStreak"

        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedProbability, targetProbability).apply {
            duration = 350L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                animatedProbability = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = 24f
        val diameter = (w.coerceAtMost(h) - pad * 2)
        val left = (w - diameter) / 2f
        val top = (h - diameter) / 2f
        arcBounds.set(left, top, left + diameter, top + diameter)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arcBounds.isEmpty) return

        val startAngle = 135f
        val sweepMax = 270f

        // 1. Background Arc (270 degrees)
        canvas.drawArc(arcBounds, startAngle, sweepMax, false, bgTrackPaint)

        // 2. Determine progress arc color based on risk tier
        val currentColor = when {
            animatedProbability >= 0.60f -> Color.parseColor("#EF4444") // Danger Crimson
            animatedProbability >= 0.20f -> Color.parseColor("#F59E0B") // Amber Caution
            else -> Color.parseColor("#10B981") // Safe Emerald Green
        }
        progressPaint.color = currentColor

        // 3. Active Progress Arc
        val currentSweep = (animatedProbability * sweepMax).coerceAtLeast(1f)
        canvas.drawArc(arcBounds, startAngle, currentSweep, false, progressPaint)

        // 4. Center Typography
        val cx = arcBounds.centerX()
        val cy = arcBounds.centerY()

        // Percent Text
        val percentStr = "${"%.1f".format(animatedProbability * 100)}%"
        percentTextPaint.color = currentColor
        canvas.drawText(percentStr, cx, cy - 6f, percentTextPaint)

        // Title Label
        canvas.drawText("FALL RISK", cx, cy - 70f, labelTextPaint)

        // Persistence streak label
        streakTextPaint.color = if (animatedProbability >= 0.60f) Color.parseColor("#FCA5A5") else Color.parseColor("#38BDF8")
        canvas.drawText(streakText, cx, cy + 50f, streakTextPaint)
    }
}
