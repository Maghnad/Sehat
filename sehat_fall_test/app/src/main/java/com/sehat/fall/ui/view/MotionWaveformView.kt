package com.sehat.fall.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time oscilloscope-style motion visualizer that renders dynamic waveform
 * traces of physical acceleration magnitude and gyroscope angular velocity.
 */
class MotionWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxHistory = 100
    private val accHistory = LinkedList<Float>()
    private val gyroHistory = LinkedList<Float>()

    private val accLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#38BDF8") // Bright Neon Cyan
        strokeCap = Paint.Cap.ROUND
    }

    private val accFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gyroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#F59E0B") // Amber Gold
        strokeCap = Paint.Cap.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#1E293B")
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 24f
    }

    private val accPath = Path()
    private val accFillPath = Path()
    private val gyroPath = Path()

    init {
        // Initialize with baseline values (~1g for gravity = 9.8 m/s^2)
        for (i in 0 until maxHistory) {
            accHistory.add(9.8f)
            gyroHistory.add(0.0f)
        }
    }

    fun pushSample(accMag: Float, gyroMag: Float) {
        if (accHistory.size >= maxHistory) {
            accHistory.removeFirst()
        }
        accHistory.add(accMag)

        if (gyroHistory.size >= maxHistory) {
            gyroHistory.removeFirst()
        }
        gyroHistory.add(gyroMag)

        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw subtle background grid
        val midY = h / 2f
        canvas.drawLine(0f, midY, w, midY, gridPaint)
        canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, gridPaint)
        canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, gridPaint)

        // 2. Legend / labels
        canvas.drawText("ACCEL |g|", 20f, 32f, textPaint.apply { color = Color.parseColor("#38BDF8") })
        canvas.drawText("GYRO |ω|", w - 140f, 32f, textPaint.apply { color = Color.parseColor("#F59E0B") })

        val stepX = w / (maxHistory - 1)

        // Scale acceleration: 9.8 is baseline around midY, +/- 20 m/s^2 range
        val maxAccRange = 30f // from 0 to 30 m/s^2
        fun accToY(acc: Float): Float {
            val clamped = acc.coerceIn(0f, maxAccRange)
            val normalized = clamped / maxAccRange
            return h - (normalized * (h - 40f) + 20f)
        }

        // Scale gyroscope: 0 to 10 rad/s
        val maxGyroRange = 10f
        fun gyroToY(gyro: Float): Float {
            val clamped = gyro.coerceIn(0f, maxGyroRange)
            val normalized = clamped / maxGyroRange
            return h - (normalized * (h * 0.6f) + 10f)
        }

        // 3. Build Acc Waveform
        accPath.reset()
        accFillPath.reset()
        accFillPath.moveTo(0f, h)

        var x = 0f
        for ((idx, a) in accHistory.withIndex()) {
            val y = accToY(a)
            if (idx == 0) {
                accPath.moveTo(x, y)
                accFillPath.lineTo(x, y)
            } else {
                accPath.lineTo(x, y)
                accFillPath.lineTo(x, y)
            }
            x += stepX
        }
        accFillPath.lineTo(w, h)
        accFillPath.close()

        // Gradient under acceleration line
        accFillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#3338BDF8"), // Semi-transparent Cyan
            Color.parseColor("#000B0F19"), // Fully transparent
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(accFillPath, accFillPaint)
        canvas.drawPath(accPath, accLinePaint)

        // 4. Build Gyro Waveform
        gyroPath.reset()
        x = 0f
        for ((idx, g) in gyroHistory.withIndex()) {
            val y = gyroToY(g)
            if (idx == 0) {
                gyroPath.moveTo(x, y)
            } else {
                gyroPath.lineTo(x, y)
            }
            x += stepX
        }
        canvas.drawPath(gyroPath, gyroLinePaint)
    }
}
