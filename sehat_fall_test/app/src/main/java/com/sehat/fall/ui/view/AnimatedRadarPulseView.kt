package com.sehat.fall.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Animated concentric radar pulse visualizer showing active Geofence boundary protection.
 */
class AnimatedRadarPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PulseMode {
        SAFE_HOME,       // Calm pulsing Emerald Green (sensors asleep, battery saving)
        OUTSIDE_ACTIVE,  // Vigilant scanning Crimson/Blue (fall sensors active)
        SEARCHING_GPS    // Ambient Amber pulse (acquiring position)
    }

    private var currentMode: PulseMode = PulseMode.SAFE_HOME
    private var pulseFraction: Float = 0.0f
    private var sweepAngle: Float = 0.0f

    private var pulseAnimator: ValueAnimator? = null
    private var sweepAnimator: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val iconTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 42f
    }

    init {
        startAnimators()
    }

    fun setMode(mode: PulseMode) {
        if (currentMode != mode) {
            currentMode = mode
            invalidate()
        }
    }

    private fun startAnimators() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                pulseFraction = va.animatedValue as Float
                sweepAngle = (pulseFraction * 360f) % 360f
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pulseAnimator?.isRunning != true) {
            startAnimators()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = (w.coerceAtMost(h) / 2f) - 10f

        val baseColor = when (currentMode) {
            PulseMode.SAFE_HOME -> Color.parseColor("#10B981")     // Emerald
            PulseMode.OUTSIDE_ACTIVE -> Color.parseColor("#EF4444") // Crimson Alert
            PulseMode.SEARCHING_GPS -> Color.parseColor("#F59E0B")  // Amber
        }

        // Draw 3 expanding concentric pulse rings
        for (i in 0 until 3) {
            val ringProgress = (pulseFraction + (i * 0.333f)) % 1.0f
            val r = ringProgress * maxRadius
            val alpha = ((1.0f - ringProgress) * 180).toInt().coerceIn(0, 255)

            ringPaint.color = baseColor
            ringPaint.alpha = alpha
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        // Central Core
        corePaint.color = baseColor
        corePaint.alpha = 220
        canvas.drawCircle(cx, cy, 26f, corePaint)

        // Center Icon
        val icon = when (currentMode) {
            PulseMode.SAFE_HOME -> "🏠"
            PulseMode.OUTSIDE_ACTIVE -> "🛡️"
            PulseMode.SEARCHING_GPS -> "📍"
        }
        val textY = cy - ((iconTextPaint.descent() + iconTextPaint.ascent()) / 2f)
        canvas.drawText(icon, cx, textY, iconTextPaint)
    }
}
