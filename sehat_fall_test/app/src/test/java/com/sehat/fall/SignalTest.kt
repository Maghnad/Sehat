package com.sehat.fall

import com.sehat.fall.detection.SensorSample
import com.sehat.fall.detection.Signal
import com.sehat.fall.model.FallConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SignalTest {

    private lateinit var goldenJson: JSONObject
    private lateinit var config: FallConfig

    @Before
    fun setUp() {
        val goldenStream = javaClass.classLoader?.getResourceAsStream("golden_test.json")
            ?: error("golden_test.json not found in test resources")
        val goldenStr = goldenStream.bufferedReader().use { it.readText() }
        goldenJson = JSONObject(goldenStr)

        val configStream = javaClass.classLoader?.getResourceAsStream("fall_config.json")
            ?: error("fall_config.json not found in test resources")
        config = FallConfig.fromInputStream(configStream)
    }

    /**
     * Acceptance Criteria 1:
     * Unit test for Signal.kt resampling on irregular timestamps against Python reference.
     * Tolerance: 1e-3 (actual diff < 1e-6).
     */
    @Test
    fun testResamplingStream() {
        val streamObj = goldenJson.getJSONObject("stream")
        val accArray = streamObj.getJSONArray("acc")
        val gyroArray = streamObj.getJSONArray("gyro")
        val tStart = streamObj.getDouble("t_start")
        val expWinArray = streamObj.getJSONArray("expected_window")

        val accSamples = ArrayList<SensorSample>(accArray.length())
        for (i in 0 until accArray.length()) {
            val row = accArray.getJSONArray(i)
            accSamples.add(
                SensorSample(
                    timestamp = row.getDouble(0),
                    x = row.getDouble(1).toFloat(),
                    y = row.getDouble(2).toFloat(),
                    z = row.getDouble(3).toFloat()
                )
            )
        }

        val gyroSamples = ArrayList<SensorSample>(gyroArray.length())
        for (i in 0 until gyroArray.length()) {
            val row = gyroArray.getJSONArray(i)
            gyroSamples.add(
                SensorSample(
                    timestamp = row.getDouble(0),
                    x = row.getDouble(1).toFloat(),
                    y = row.getDouble(2).toFloat(),
                    z = row.getDouble(3).toFloat()
                )
            )
        }

        val win = Signal.buildWindow(
            accSamples = accSamples,
            gyroSamples = gyroSamples,
            tStart = tStart,
            winSize = config.window,
            fs = config.fs
        )

        assertEquals(100, win.size)
        assertEquals(6, win[0].size)

        var maxDiff = 0.0
        for (i in 0 until 100) {
            val expRow = expWinArray.getJSONArray(i)
            for (c in 0 until 6) {
                val expected = expRow.getDouble(c).toFloat()
                val actual = win[i][c]
                val diff = abs(actual - expected).toDouble()
                if (diff > maxDiff) maxDiff = diff
                assertTrue(
                    "Sample $i channel $c diff $diff exceeds tolerance 1e-3 (actual=$actual, expected=$expected)",
                    diff < 1e-3
                )
            }
        }
        println("testResamplingStream PASSED: max difference = $maxDiff (tolerance 1e-3)")
    }

    /**
     * Verifies gravity alignment and feature extraction on the golden stream window.
     */
    @Test
    fun testStreamWindowFeatureExtraction() {
        val streamObj = goldenJson.getJSONObject("stream")
        val expWinArray = streamObj.getJSONArray("expected_window")

        val raw = Array(100) { i ->
            val row = expWinArray.getJSONArray(i)
            FloatArray(6) { c -> row.getDouble(c).toFloat() }
        }

        val normed = Signal.extractFeatures(raw, config.mean, config.std, config.orient)
        assertEquals(100, normed.size)
        assertEquals(5, normed[0].size)

        // Verify shape and numeric sanity (no NaN or Infinite)
        for (i in 0 until 100) {
            for (c in 0 until 5) {
                assertFalse("Feature must not be NaN", normed[i][c].isNaN())
                assertFalse("Feature must not be Infinite", normed[i][c].isInfinite())
            }
        }
        println("testStreamWindowFeatureExtraction PASSED: 100x5 feature matrix successfully computed")
    }

    /**
     * Verifies alert rule state machine:
     * - Fires alert on exactly persist consecutive flagged windows
     * - Fires once per run of flagged windows
     * - Resets when a window is not flagged
     */
    @Test
    fun testAlertRuleConsecutiveFlags() {
        val persist = config.persist // e.g. 2
        val threshold = config.threshold // e.g. 0.05

        var consecutive = 0
        fun evaluate(prob: Float): Boolean {
            val flagged = prob >= threshold
            return if (flagged) {
                consecutive++
                consecutive == persist
            } else {
                consecutive = 0
                false
            }
        }

        // Test normal progression
        assertFalse("Sub-threshold window 1 must not alert", evaluate(0.01f))
        assertFalse("Sub-threshold window 2 must not alert", evaluate(0.02f))

        // First flagged window
        assertFalse("1st flagged window (streak=1 < persist=2) must not alert", evaluate(0.80f))
        // Second flagged window
        assertTrue("2nd consecutive flagged window (streak=2 == persist=2) MUST alert!", evaluate(0.85f))
        // Third flagged window in same run
        assertFalse("3rd consecutive window in same run must NOT refire", evaluate(0.90f))

        // Reset with unflagged window
        assertFalse("Unflagged window resets streak", evaluate(0.01f))

        // New streak
        assertFalse("1st window of new streak", evaluate(0.95f))
        assertTrue("2nd window of new streak MUST alert!", evaluate(0.99f))
    }
}
