package com.sehat.fall.model

import org.json.JSONObject
import java.io.InputStream

/**
 * Model and detection configuration loaded from fall_config.json.
 * Numbers are NEVER hardcoded; all thresholds, dimensions, and normalisation
 * constants are parsed dynamically from the config file.
 */
data class FallConfig(
    val window: Int,
    val fs: Int,
    val strideMs: Long,
    val orient: String,
    val mean: FloatArray,
    val std: FloatArray,
    val threshold: Float,
    val persist: Int,
    val featureOrder: List<String>
) {
    val durationSeconds: Double
        get() = window.toDouble() / fs.toDouble()

    companion object {
        fun fromJson(jsonStr: String): FallConfig {
            val json = JSONObject(jsonStr)
            val window = json.getInt("window")
            val fs = json.getInt("fs")
            val strideMs = json.getLong("stride_ms")
            val orient = json.optString("orient", "gravity")

            val meanArray = json.getJSONArray("mean")
            val mean = FloatArray(meanArray.length()) { i -> meanArray.getDouble(i).toFloat() }

            val stdArray = json.getJSONArray("std")
            val std = FloatArray(stdArray.length()) { i -> stdArray.getDouble(i).toFloat() }

            val threshold = json.getDouble("threshold").toFloat()
            val persist = json.optInt("persist", 2)

            val featureOrderList = mutableListOf<String>()
            val featArray = json.optJSONArray("feature_order")
            if (featArray != null) {
                for (i in 0 until featArray.length()) {
                    featureOrderList.add(featArray.getString(i))
                }
            }

            return FallConfig(
                window = window,
                fs = fs,
                strideMs = strideMs,
                orient = orient,
                mean = mean,
                std = std,
                threshold = threshold,
                persist = persist,
                featureOrder = featureOrderList
            )
        }

        fun fromInputStream(stream: InputStream): FallConfig {
            val jsonStr = stream.bufferedReader().use { it.readText() }
            return fromJson(jsonStr)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FallConfig
        if (window != other.window) return false
        if (fs != other.fs) return false
        if (strideMs != other.strideMs) return false
        if (orient != other.orient) return false
        if (!mean.contentEquals(other.mean)) return false
        if (!std.contentEquals(other.std)) return false
        if (threshold != other.threshold) return false
        if (persist != other.persist) return false
        if (featureOrder != other.featureOrder) return false

        return true
    }

    override fun hashCode(): Int {
        var result = window
        result = 31 * result + fs
        result = 31 * result + strideMs.hashCode()
        result = 31 * result + orient.hashCode()
        result = 31 * result + mean.contentHashCode()
        result = 31 * result + std.contentHashCode()
        result = 31 * result + threshold.hashCode()
        result = 31 * result + persist
        result = 31 * result + featureOrder.hashCode()
        return result
    }
}
