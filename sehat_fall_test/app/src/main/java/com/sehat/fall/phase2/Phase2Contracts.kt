package com.sehat.fall.phase2

import java.io.File

/**
 * Phase 2 Audio Classifier Interface.
 * Specification:
 * - Input: 2 s mono 16 kHz audio chunks
 * - Transform: Log-Mel spectrogram (n_fft=1024, hop=320, 64 mel bands, fmin=50, fmax=8000,
 *              power spectrogram -> dB with top_db=80, shape 101x64x1, normalized)
 * - Model: int8 TFLite
 * - Output: 4 classes [background, scream, cry, glass_break]
 * - P(distress) = 1.0 - P(background)
 * Note: Requires golden test vectors from Python before integration acceptance.
 */
interface AudioClassifier {
    enum class AudioClass {
        BACKGROUND,
        SCREAM,
        CRY,
        GLASS_BREAK
    }

    data class AudioResult(
        val probabilities: FloatArray, // size 4
        val distressProbability: Float,
        val topClass: AudioClass
    )

    fun initialize(): Boolean
    fun classifyChunk(pcm16kMono16Bit: ShortArray): AudioResult
    fun close()
}

/**
 * Phase 2 Multimodal Risk Fusion Engine.
 * Specification:
 * - R = wm*M + wa*A + wc*C (where weights wm + wa + wc == 1.0)
 * - M = Motion fall score [0, 1]
 * - A = Audio distress score [0, 1]
 * - C = Context score [0, 1] (e.g. location, time of day, user state)
 * - Bands: LOW, MEDIUM, HIGH, CRITICAL
 */
interface RiskFusion {
    enum class RiskBand {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    data class FusionWeights(
        val wm: Float = 0.5f,
        val wa: Float = 0.3f,
        val wc: Float = 0.2f
    ) {
        init {
            require(kotlin.math.abs((wm + wa + wc) - 1.0f) < 1e-4) { "Weights must sum to 1.0" }
        }
    }

    fun computeRisk(
        motionScore: Float,
        audioDistressScore: Float,
        contextScore: Float,
        weights: FusionWeights = FusionWeights()
    ): Pair<Float, RiskBand>
}

/**
 * Phase 3 Evidence Recorder Interface.
 * Specification:
 * - 15 s audio pre-roll ring buffer + 60 s post-trigger continuous recording
 * - Encrypted with AES-256-GCM using hardware-backed Android Keystore key
 * - SHA-256 hash-chaining of audio frames and sensor records
 * - Cryptographically signed manifest
 */
interface EvidenceRecorder {
    data class SealedEvidence(
        val encryptedFile: File,
        val sha256ManifestHash: String,
        val signature: ByteArray,
        val keyAlias: String
    )

    fun startPreRollBuffer()
    fun triggerSealing(onComplete: (SealedEvidence) -> Unit)
    fun cancel()
}

/**
 * Phase 4 Alert Transport with Graceful Communication Degradation.
 * Specification:
 * Transport fallback cascade:
 * 1. Primary: HTTPS / TLS 1.3 to SEHAT backend
 * 2. Secondary: Encoded emergency SMS to trusted contacts / dispatch
 * 3. Tertiary: BLE (Bluetooth Low Energy) mesh relay to nearby devices
 * 4. Fallback: LoRa packet broadcast
 */
interface AlertTransport {
    enum class TransportType {
        TLS_1_3,
        SMS,
        BLE_RELAY,
        LORA
    }

    data class TransportPayload(
        val incidentId: String,
        val timestamp: Long,
        val riskScore: Float,
        val location: Pair<Double, Double>?, // lat, lon
        val manifestHash: String?
    )

    fun dispatchEmergencyAlert(
        payload: TransportPayload,
        onStatusUpdate: (TransportType, Boolean, String?) -> Unit
    )
}
