# SEHAT-ERS: On-Device Emergency Response System (Phase 1)

**SEHAT-ERS** is an edge-first, privacy-preserving personal safety and fall detection application built for Android (Kotlin, minSdk 26, targetSdk 34).

All motion detection, model inference, and event logging execute **100% locally on the device**. In Phase 1, the app operates without cloud dependencies or network connectivity: **the `INTERNET` permission is strictly omitted**.

---

## 1. System Architecture & Features

### Core Modules (Phase 1)
- **Continuous Background Monitoring (`FallDetectionService`)**:
  - Operates as an Android Foreground Service (`specialUse` foreground service type).
  - Holds a `PARTIAL_WAKE_LOCK` to maintain continuous sensor sampling and inference when the screen is turned off.
  - Automatically handles `POST_NOTIFICATIONS` (API 33+) and guides users through exempting the app from battery optimization (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
  - Monitors sensor delivery frequency in real time and raises a visual warning if accelerometer or gyroscope drops below 50 Hz.
- **Signal Processing & Feature Extraction (`Signal.kt`)**:
  - Implements the strict numeric contract matching the training pipeline.
  - Converts irregular 100 Hz sensor samples into standardized 50 Hz windows via 4x oversampled linear interpolation.
  - Computes rotation-independent gravity-aligned features (`av, ah, am, wv, wh`) so detection does not depend on phone orientation in the pocket.
- **On-Device Inference (`FallDetector.kt`)**:
  - Evaluates sliding 2.0-second windows every `stride_ms` (500 ms).
  - Runs a float32 TensorFlow Lite / LiteRT model (`fall_model.tflite`).
  - Trims sliding buffers to window start while preserving boundary samples for interpolation.
  - Implements alert persist logic: an alert fires when `persist` consecutive windows are flagged (`prob >= threshold`), firing **once per run of flagged windows** and resetting on unflagged windows.
- **Alert & Escalation Flow (`AlertManager.kt` & `AlertActivity.kt`)**:
  - Plays a loud siren over `STREAM_ALARM` (bypassing silent mode) and pulses vibration.
  - Displays a full-screen, high-contrast emergency screen (`showWhenLocked`, `turnScreenOn`).
  - Prominent "I'M OK" button immediately silences alarm, stops vibration, and logs `outcome="cancelled"`.
  - Visible countdown timer (configurable 5–10 s, default 10 s). If countdown finishes without user cancellation, logs `outcome="ESCALATED"` and posts a persistent local notification with an extensible TODO hook for future transport networks.
- **Local Event & Trace Storage (`Storage.kt`)**:
  - Logs every emergency event (`timestamp_ms`, `formatted_time`, `probability`, `outcome`, `app_version`) to `event_log.csv`.
  - Optional "Record Trace" toggle records all evaluated 2.0 s windows (`timestamp_ms`, `formatted_time`, `probability`, `flagged`) to `trace.csv`.
  - In-app screens to inspect logs, clear logs, and export/share CSVs securely via `FileProvider`.
- **Pocket Mode (`PocketModeActivity.kt`)**:
  - 10-second preparatory countdown allowing the user to place the handset into a trouser pocket.
  - Switches to a pitch-black screen with dimmed backlight to eliminate accidental touch inputs and minimize battery draw. Long-press anywhere to exit.
- **On-Device Self-Test (`SelfTestRunner.kt`)**:
  - Validates the phone's signal processing and inference against Python-generated test vectors (`golden_test.json`).
  - Confirms tolerance < 1e-3 (must show `PASSED`).
- **Privacy & Limitations Disclosure (`AboutActivity.kt`)**:
  - First-run consent screen explaining on-device processing and explicitly disclosing model constraints.

---

## 2. Numeric Contract

The signal pipeline strictly preserves the numeric contract:

1. **Sensors**:
   - `Sensor.TYPE_ACCELEROMETER` ($m/s^2$, gravity included).
   - `Sensor.TYPE_GYROSCOPE` ($rad/s$).
   - Registered at ~100 Hz (`10,000 µs`) on a dedicated background `HandlerThread`.
   - Time axis: `SensorEvent.timestamp / 1e9` (seconds since boot).
2. **Windowing**:
   - Evaluated every `stride_ms` (500 ms).
   - Window end: $t_{end} = \min(\text{last acc time}, \text{last gyro time})$.
   - Window start: $t_{start} = t_{end} - 2.0\text{ s}$.
   - Skips evaluation until 2.0 s of overlapping data exist and skips if no new data arrived.
   - Buffers trimmed to $t_{start}$, keeping one older sample before $t_{start}$ for linear interpolation.
3. **Resampling (`buildWindow`)**:
   - Output: 100 samples at 50 Hz, 6 channels (`ax, ay, az, gx, gy, gz`).
   - For output index $i \in [0, 99]$ and $k \in [0, 3]$:
     $$t = t_{start} + \frac{4i + k + 0.5}{200.0}$$
   - Linearly interpolated separately on each sensor's own timestamps with endpoint clamping.
   - Averages the 4 points for each channel.
4. **Features (`gravityAlign` & `normalize`)**:
   - Gravity vector: $\mathbf{g} = \frac{1}{100} \sum_{i=0}^{99} \mathbf{a}_i$.
   - Direction: $\mathbf{h} = \mathbf{g} / \|\mathbf{g}\|$ (if $\|\mathbf{g}\| < 10^{-3}$, defaults to $[0, 0, 1]$).
   - Per sample:
     - $a_v = \mathbf{a} \cdot \mathbf{h}$
     - $a_h = \|\mathbf{a} - a_v \mathbf{h}\|$
     - $a_m = \|\mathbf{a}\|$
     - $\omega_v = \boldsymbol{\omega} \cdot \mathbf{h}$
     - $\omega_h = \|\boldsymbol{\omega} - \omega_v \mathbf{h}\|$
   - Normalized per channel: $\frac{x_c - \mu_c}{\sigma_c}$ using `mean` and `std` from `fall_config.json`.
   - Model input tensor shape: `[1, 100, 5]` float32.
5. **Alert Rule**:
   - $p \ge \text{threshold}$ flags a window.
   - Fires an alert when `persist` consecutive windows are flagged, **once per run of flagged windows**.
   - Resets streak when a window is not flagged.

---

## 3. Asset Generation

Assets in `app/src/main/assets/` were exported directly from the trained motion model using `sehat_pipeline/export_for_android.py`:

```bash
cd sehat_pipeline
python export_for_android.py \
    --run out_mobiact_tcn_grav \
    --data motion_mobiact.npz \
    --assets "../sehat_fall_test/app/src/main/assets"
```

Generated files:
- `fall_model.tflite`: Unquantized float32 TFLite model (input: `[1, 100, 5]`, output: `[1, 1]` sigmoid probability).
- `fall_config.json`: Configuration constants (`window=100`, `fs=50`, `stride_ms=500`, `threshold=0.05`, `persist=2`, normalisation means/stds).
- `golden_test.json`: 12 golden test windows and a jittered stream with expected outputs generated from the Python Keras model.

---

## 4. Building and Verification

### Project Build & Packaging
To build the debug APK:
```bash
./gradlew assembleDebug
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`

### JVM Unit Tests
Run unit tests verifying `Signal.kt` against `golden_test.json`:
```bash
./gradlew testDebugUnitTest
```
Tests executed in `SignalTest.kt`:
1. `testResamplingStream`: Validates 4x oversampled linear interpolation on jittered timestamps against Python's `expected_window`.
   - **Result**: Max difference = `4.98e-7` (well within tolerance `1e-3`).
2. `testStreamWindowFeatureExtraction`: Validates rotation-independent gravity features and normalization on 100x6 stream data.
   - **Result**: `PASSED` (clean 100x5 normalized matrix, no NaNs/Infs).
3. `testAlertRuleConsecutiveFlags`: Validates the alert rule state machine (threshold checks, persist streak requirements, once-per-run triggering, and streak reset).
   - **Result**: `PASSED`.

### On-Device Self-Test
Tapping the **"Run Self-Test"** button in `MainActivity` runs `SelfTestRunner.kt`:
- Executes on the phone's native TensorFlow Lite runtime.
- Re-runs resampling and inference across all 12 golden test windows.
- Displays `Self-test: PASSED (tolerance 1e-3)` with measured maximum difference metrics.

---

## 5. Phase 2+ Stubs & Extensibility

Architecture interfaces for future phases are defined under `com.sehat.fall.phase2`:
- `AudioClassifier`: Interface for 2.0 s mono 16 kHz chunks, log-mel spectrogram (101x64x1), int8 TFLite model predicting 4 classes (`background, scream, cry, glass_break`), with distress probability $1 - P(\text{background})$.
- `RiskFusion`: Interface for weighted multimodal risk combination:
  $$R = w_m M + w_a A + w_c C \quad (\sum w_i = 1.0)$$
  mapped to risk bands: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.
- `EvidenceRecorder`: Interface for 15 s audio pre-roll circular ring buffer + 60 s post-trigger recording sealed with AES-256-GCM (Android Keystore hardware-backed key) and signed SHA-256 hash-chain manifest.
- `AlertTransport`: Interface for communication fallback cascade:
  `TLS 1.3 -> Encoded SMS -> BLE mesh relay -> LoRa broadcast`.

---

## 6. What Was NOT Verified & Known Limitations

1. **Hardware & Population Scope**:
   - The model was trained exclusively on simulated laboratory fall protocols from 66 healthy young adult volunteers with the smartphone positioned inside a trouser pocket.
   - It has **not been validated** on unconstrained real-world falls or frail geriatric populations.
2. **False Alert Profile**:
   - In laboratory trials, false alerts occurred predominantly during vehicle entry and egress (approximately 8% of car entries and 6% of car exits triggered false flags during rapid transitional motion).
3. **Medical Disclaimer**:
   - **NOT A MEDICAL DEVICE**. This software is a research prototype for edge-first personal safety. It does not replace professional medical advice, diagnosis, or commercial emergency response systems.
4. **Physical Fall Test on Hardware**:
   - Software compilation, unit testing, asset validation, and APK packaging were fully verified. Physical mattress drops and a 30-minute uninterrupted physical screen-off test require a physical device running in a test room with a spotter.
