package com.sehat.fall

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTest {

    private lateinit var configJson: JSONObject
    private lateinit var goldenJson: JSONObject

    @Before
    fun setUp() {
        val configStream = javaClass.classLoader?.getResourceAsStream("audio_config.json")
            ?: error("audio_config.json not found in test resources")
        configJson = JSONObject(configStream.bufferedReader().use { it.readText() })

        val goldenStream = javaClass.classLoader?.getResourceAsStream("audio_golden.json")
            ?: error("audio_golden.json not found in test resources")
        goldenJson = JSONObject(goldenStream.bufferedReader().use { it.readText() })
    }

    @Test
    fun testAudioConfigContract() {
        assertEquals(16000, configJson.getInt("sample_rate"))
        assertEquals(32000, configJson.getInt("chunk_samples"))
        assertEquals("background", configJson.getString("background_class"))

        val classes = configJson.getJSONArray("classes")
        assertEquals(4, classes.length())
        assertEquals("background", classes.getString(0))
        assertEquals("scream", classes.getString(1))
        assertEquals("cry", classes.getString(2))
        assertEquals("glass_break", classes.getString(3))

        val thr = configJson.getJSONObject("suggested_thresholds")
        assertTrue(thr.has("fa_1pct"))
        assertTrue(thr.has("fa_2pct"))
        assertTrue(thr.has("fa_5pct"))
    }

    @Test
    fun testAudioGoldenVectorsIntegrity() {
        val examples = goldenJson.getJSONArray("examples")
        assertEquals(8, examples.length())

        for (i in 0 until examples.length()) {
            val ex = examples.getJSONObject(i)
            val sourceClass = ex.getString("source_class")
            val base64 = ex.getString("pcm16_base64")
            val probs = ex.getJSONArray("expected_probabilities")

            assertNotNull(sourceClass)
            assertEquals(4, probs.length())

            var sumProbs = 0.0
            for (c in 0 until 4) {
                sumProbs += probs.getDouble(c)
            }
            assertTrue("Probabilities should sum to ~1.0, got $sumProbs", kotlin.math.abs(sumProbs - 1.0) < 1e-4)

            val bytes = java.util.Base64.getDecoder().decode(base64)
            assertEquals("32000 16-bit PCM samples must decode to 64000 bytes", 64000, bytes.size)

            val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertEquals(32000, shortBuffer.remaining())
        }
    }
}
