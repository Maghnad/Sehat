package com.sehat.fall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.sehat.fall.R
import com.sehat.fall.SehatApp
import com.sehat.fall.alert.AlertManager
import com.sehat.fall.detection.FallDetector
import com.sehat.fall.ui.MainActivity

class FallDetectionService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.sehat.fall.action.START"
        const val ACTION_STOP = "com.sehat.fall.action.STOP"

        const val NOTIFICATION_CHANNEL_ID = "sehat_monitoring_channel"
        const val NOTIFICATION_ID = 1001
        private const val SENSOR_PERIOD_US = 10_000 // ~100 Hz as specified in contract

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentAccRateHz = 0f
            private set

        @Volatile
        var currentGyroRateHz = 0f
            private set

        @Volatile
        var windowsPerMinute = 0f
            private set

        @Volatile
        var hasLowRateWarning = false
            private set

        @Volatile
        var latestProbability = 0f
            private set

        @Volatile
        var consecutiveFlagged = 0
            private set
    }

    private var sensorManager: SensorManager? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fallDetector: FallDetector? = null

    // Rate monitoring counters
    private var accEventCount = 0L
    private var gyroEventCount = 0L
    private var windowsInLastMinute = 0
    private var lastRateCheckTime = 0L
    private var lastMinuteCheckTime = 0L

    // Ticker runnable for evaluating windows every stride_ms (500 ms)
    private val strideRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            // Dynamically synchronize detection settings with user preferences
            val settings = SehatApp.instance.settingsManager
            fallDetector?.let { detector ->
                detector.currentThreshold = settings.threshold
                detector.currentPersist = settings.persist
            }

            val prob = fallDetector?.evaluateLatestWindow()
            if (prob != null) {
                latestProbability = prob
                consecutiveFlagged = fallDetector?.consecutiveFlagged ?: 0
                windowsInLastMinute++
            }

            // Schedule next window evaluation
            val strideMs = fallDetector?.config?.strideMs ?: 500L
            backgroundHandler?.postDelayed(this, strideMs)
        }
    }

    // Ticker runnable for computing live sensor Hz and updating notification
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateStatsAndNotification()
            backgroundHandler?.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
            else -> if (!isRunning) stopSelf()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (isRunning) return
        isRunning = true

        val app = SehatApp.instance
        val config = app.fallConfig
        val settings = app.settingsManager

        // Instantiate detector
        fallDetector = FallDetector(
            context = this,
            config = config,
            traceRepo = app.traceRepo,
            onAlertTriggered = { prob ->
                AlertManager.triggerFallAlert(this, prob)
            }
        ).apply {
            currentThreshold = settings.threshold
            currentPersist = settings.persist
        }

        // Acquire partial wake lock for continuous screen-off operation
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "sehat:ContinuousFallMonitoringWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours safe timeout
        }

        // Start background thread for sensor processing and window evaluation
        handlerThread = HandlerThread("SehatSensorThread").apply {
            start()
            val handler = Handler(looper)
            backgroundHandler = handler

            // Register sensors on background thread
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val acc = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            sensorManager?.registerListener(
                this@FallDetectionService,
                acc,
                SENSOR_PERIOD_US,
                handler
            )
            sensorManager?.registerListener(
                this@FallDetectionService,
                gyro,
                SENSOR_PERIOD_US,
                handler
            )

            lastRateCheckTime = System.currentTimeMillis()
            lastMinuteCheckTime = System.currentTimeMillis()

            handler.post(strideRunnable)
            handler.post(statsRunnable)
        }

        // Promote to foreground service
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopMonitoring() {
        if (!isRunning) return
        isRunning = false

        backgroundHandler?.removeCallbacks(strideRunnable)
        backgroundHandler?.removeCallbacks(statsRunnable)

        sensorManager?.unregisterListener(this)
        sensorManager = null

        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null

        fallDetector?.close()
        fallDetector = null

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRunning) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accEventCount++
                fallDetector?.onAccelerometerEvent(
                    timestampNs = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2]
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroEventCount++
                fallDetector?.onGyroscopeEvent(
                    timestampNs = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2]
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateStatsAndNotification() {
        val now = System.currentTimeMillis()
        val dtSec = (now - lastRateCheckTime) / 1000.0
        if (dtSec >= 1.0) {
            currentAccRateHz = (accEventCount / dtSec).toFloat()
            currentGyroRateHz = (gyroEventCount / dtSec).toFloat()
            accEventCount = 0L
            gyroEventCount = 0L
            lastRateCheckTime = now

            // Warning if a sensor delivers < 50 Hz as specified in requirements
            hasLowRateWarning = (currentAccRateHz < 50f || currentGyroRateHz < 50f)
        }

        val dtMin = (now - lastMinuteCheckTime) / 60000.0
        if (dtMin >= 1.0) {
            windowsPerMinute = (windowsInLastMinute / dtMin).toFloat()
            windowsInLastMinute = 0
            lastMinuteCheckTime = now
        }

        val notification = buildForegroundNotification()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val warningText = if (hasLowRateWarning) " [WARNING: Sensor rate < 50 Hz]" else ""
        val contentText = "Acc: ${"%.0f".format(currentAccRateHz)} Hz | Gyro: ${"%.0f".format(currentGyroRateHz)} Hz | Prob: ${"%.2f".format(latestProbability)}$warningText"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SEHAT-ERS Monitoring Active")
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Real-time fall detection active with screen-off protection.\n" +
                    "Accelerometer: ${"%.1f".format(currentAccRateHz)} Hz\n" +
                    "Gyroscope: ${"%.1f".format(currentGyroRateHz)} Hz\n" +
                    "Latest window probability: ${"%.3f".format(latestProbability)}\n" +
                    (if (hasLowRateWarning) "WARNING: Low sampling rate (<50 Hz) detected! Model accuracy may be impacted." else "Sampling rates nominal.")
                )
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.channel_monitoring_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.channel_monitoring_desc)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }
}
