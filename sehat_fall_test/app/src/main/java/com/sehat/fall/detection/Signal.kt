package com.sehat.fall.detection

import kotlin.math.sqrt

/**
 * Sensor reading sample with timestamp in seconds.
 */
data class SensorSample(
    val timestamp: Double,
    val x: Float,
    val y: Float,
    val z: Float
)

/**
 * Core signal processing and numeric feature extraction contract for SEHAT-ERS.
 * Conforms precisely to Python reference pipeline (max diff < 6e-6).
 */
object Signal {

    /**
     * 1D Linear interpolation with endpoint clamping, matching numpy.interp.
     */
    fun interp(t: Double, timestamps: DoubleArray, values: FloatArray): Float {
        val n = timestamps.size
        if (n == 0) return 0f
        if (n == 1 || t <= timestamps[0]) return values[0]
        if (t >= timestamps[n - 1]) return values[n - 1]

        // Binary search for j such that timestamps[j] <= t < timestamps[j + 1]
        var low = 0
        var high = n - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (timestamps[mid] <= t) {
                if (mid == n - 1 || timestamps[mid + 1] > t) {
                    val t0 = timestamps[mid]
                    val t1 = timestamps[mid + 1]
                    val frac = (t - t0) / (t1 - t0)
                    return (values[mid] + frac * (values[mid + 1] - values[mid])).toFloat()
                }
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return values[0]
    }

    /**
     * 4x oversampling with linear interpolation, then average groups of 4.
     * Output shape: Array(winSize) { FloatArray(6) } where columns are:
     * ax, ay, az, gx, gy, gz.
     */
    fun buildWindow(
        accSamples: List<SensorSample>,
        gyroSamples: List<SensorSample>,
        tStart: Double,
        winSize: Int = 100,
        fs: Int = 50
    ): Array<FloatArray> {
        val nAcc = accSamples.size
        val nGyro = gyroSamples.size
        require(nAcc > 0 && nGyro > 0) { "Sensor buffers must not be empty" }

        val ta = DoubleArray(nAcc)
        val ax = FloatArray(nAcc)
        val ay = FloatArray(nAcc)
        val az = FloatArray(nAcc)
        for (i in 0 until nAcc) {
            val s = accSamples[i]
            ta[i] = s.timestamp
            ax[i] = s.x
            ay[i] = s.y
            az[i] = s.z
        }

        val tg = DoubleArray(nGyro)
        val gx = FloatArray(nGyro)
        val gy = FloatArray(nGyro)
        val gz = FloatArray(nGyro)
        for (i in 0 until nGyro) {
            val s = gyroSamples[i]
            tg[i] = s.timestamp
            gx[i] = s.x
            gy[i] = s.y
            gz[i] = s.z
        }

        val window = Array(winSize) { FloatArray(6) }
        val dt = 1.0 / (4.0 * fs)

        for (i in 0 until winSize) {
            var sumAx = 0f
            var sumAy = 0f
            var sumAz = 0f
            var sumGx = 0f
            var sumGy = 0f
            var sumGz = 0f

            for (k in 0..3) {
                val t = tStart + (4.0 * i + k + 0.5) * dt
                sumAx += interp(t, ta, ax)
                sumAy += interp(t, ta, ay)
                sumAz += interp(t, ta, az)
                sumGx += interp(t, tg, gx)
                sumGy += interp(t, tg, gy)
                sumGz += interp(t, tg, gz)
            }

            window[i][0] = sumAx / 4f
            window[i][1] = sumAy / 4f
            window[i][2] = sumAz / 4f
            window[i][3] = sumGx / 4f
            window[i][4] = sumGy / 4f
            window[i][5] = sumGz / 4f
        }

        return window
    }

    /**
     * Rotation-independent gravity alignment.
     * Computes mean acceleration over the window g, unit gravity vector h.
     * Output features per sample:
     *   av = a . h
     *   ah = |a - av * h|
     *   am = |a|
     *   wv = w . h
     *   wh = |w - wv * h|
     * Output shape: Array(winSize) { FloatArray(5) }
     */
    fun gravityAlign(rawWindow: Array<FloatArray>): Array<FloatArray> {
        val winSize = rawWindow.size
        var gSumX = 0.0
        var gSumY = 0.0
        var gSumZ = 0.0
        for (i in 0 until winSize) {
            gSumX += rawWindow[i][0]
            gSumY += rawWindow[i][1]
            gSumZ += rawWindow[i][2]
        }
        val gx = (gSumX / winSize).toFloat()
        val gy = (gSumY / winSize).toFloat()
        val gz = (gSumZ / winSize).toFloat()
        val gNorm = sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()

        val hx: Float
        val hy: Float
        val hz: Float
        if (gNorm < 1e-3f) {
            hx = 0f
            hy = 0f
            hz = 1f
        } else {
            hx = gx / gNorm
            hy = gy / gNorm
            hz = gz / gNorm
        }

        val features = Array(winSize) { FloatArray(5) }
        for (i in 0 until winSize) {
            val ax = rawWindow[i][0]
            val ay = rawWindow[i][1]
            val az = rawWindow[i][2]
            val wx = rawWindow[i][3]
            val wy = rawWindow[i][4]
            val wz = rawWindow[i][5]

            // Acceleration along gravity and orthogonal
            val av = ax * hx + ay * hy + az * hz
            val ahX = ax - av * hx
            val ahY = ay - av * hy
            val ahZ = az - av * hz
            val ah = sqrt((ahX * ahX + ahY * ahY + ahZ * ahZ).toDouble()).toFloat()
            val am = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

            // Angular velocity along gravity and orthogonal
            val wv = wx * hx + wy * hy + wz * hz
            val whX = wx - wv * hx
            val whY = wy - wv * hy
            val whZ = wz - wv * hz
            val wh = sqrt((whX * whX + whY * whY + whZ * whZ).toDouble()).toFloat()

            features[i][0] = av
            features[i][1] = ah
            features[i][2] = am
            features[i][3] = wv
            features[i][4] = wh
        }

        return features
    }

    /**
     * Standardizes features: (x - mean[c]) / std[c]
     */
    fun normalize(
        features: Array<FloatArray>,
        mean: FloatArray,
        std: FloatArray
    ): Array<FloatArray> {
        val winSize = features.size
        val channels = mean.size
        val normed = Array(winSize) { FloatArray(channels) }
        for (i in 0 until winSize) {
            for (c in 0 until channels) {
                normed[i][c] = (features[i][c] - mean[c]) / std[c]
            }
        }
        return normed
    }

    /**
     * Complete feature extraction pipeline for raw window [100, 6] -> normalized [100, 5].
     */
    fun extractFeatures(
        rawWindow: Array<FloatArray>,
        mean: FloatArray,
        std: FloatArray,
        orient: String = "gravity"
    ): Array<FloatArray> {
        val features = if (orient == "gravity") {
            gravityAlign(rawWindow)
        } else {
            rawWindow
        }
        return normalize(features, mean, std)
    }

    /**
     * Formats normalized features [100, 5] into a 3D float array [1, 100, 5] for TFLite input.
     */
    fun toModelInput(normed: Array<FloatArray>): Array<Array<FloatArray>> {
        val winSize = normed.size
        val channels = normed[0].size
        val input = Array(1) { Array(winSize) { FloatArray(channels) } }
        for (i in 0 until winSize) {
            for (c in 0 until channels) {
                input[0][i][c] = normed[i][c]
            }
        }
        return input
    }
}
