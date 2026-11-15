package com.sehat.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/**
 * Logs raw sensor events to  <externalFiles>/sessions/<session>/
 *   sensors.csv : t_ns,s,v0,v1,v2   (s = a | g | p ; p uses only v0 in hPa)
 *   events.csv  : t_ns,type,label   (type = start | end | mark)
 *   meta.json   : volunteer, phone model, position, sensor availability
 * Sensor timestamps and event timestamps share the elapsedRealtime clock on
 * nearly all devices; the converter assumes that.
 */
class RecorderService : Service(), SensorEventListener {

    companion object {
        const val START = "com.sehat.recorder.START"
        const val STOP = "com.sehat.recorder.STOP"
        const val LABEL_START = "com.sehat.recorder.LABEL_START"
        const val LABEL_END = "com.sehat.recorder.LABEL_END"
        const val MARK = "com.sehat.recorder.MARK"
        private const val CHANNEL = "rec"
        private const val PERIOD_US = 10_000  // request ~100 Hz, resample to 50 Hz later

        @Volatile var running = false
        @Volatile var currentLabel = ""
        @Volatile var accN = 0L
        @Volatile var gyroN = 0L
        @Volatile var baroN = 0L
        @Volatile var hasBaro = false
    }

    private var sm: SensorManager? = null
    private var thread: HandlerThread? = null
    private var sensorOut: BufferedWriter? = null
    private var eventOut: BufferedWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> start(
                intent.getStringExtra("volunteer") ?: "anon",
                intent.getStringExtra("position") ?: "unknown"
            )
            STOP -> stopRecording()
            LABEL_START -> {
                val label = intent.getStringExtra("label") ?: ""
                if (running && label.isNotEmpty()) {
                    if (currentLabel.isNotEmpty()) logEvent("end", currentLabel)
                    currentLabel = label
                    logEvent("start", label)
                }
            }
            LABEL_END -> if (running && currentLabel.isNotEmpty()) {
                logEvent("end", currentLabel)
                currentLabel = ""
            }
            MARK -> if (running) logEvent("mark", currentLabel.ifEmpty { "none" })
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(volunteer: String, position: String) {
        if (running) return
        showForeground()

        val safeVol = volunteer.replace(Regex("[^A-Za-z0-9]"), "")
        val model = "${Build.MANUFACTURER}_${Build.MODEL}".replace(Regex("[^A-Za-z0-9_]"), "")
        val dir = File(getExternalFilesDir(null), "sessions/${safeVol}_${model}_${System.currentTimeMillis()}")
        dir.mkdirs()

        sensorOut = File(dir, "sensors.csv").bufferedWriter().also { it.write("t_ns,s,v0,v1,v2\n") }
        eventOut = File(dir, "events.csv").bufferedWriter().also { it.write("t_ns,type,label\n"); it.flush() }

        val manager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = manager
        val acc = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val baro = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBaro = baro != null

        File(dir, "meta.json").writeText(
            JSONObject().apply {
                put("volunteer", safeVol)
                put("position", position)
                put("phone", model)
                put("android_sdk", Build.VERSION.SDK_INT)
                put("has_accelerometer", acc != null)
                put("has_gyroscope", gyro != null)
                put("has_barometer", baro != null)
                put("requested_period_us", PERIOD_US)
                put("started_wall_ms", System.currentTimeMillis())
            }.toString(2)
        )

        accN = 0; gyroN = 0; baroN = 0; currentLabel = ""
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sehat:rec").apply { acquire(4 * 60 * 60 * 1000L) }

        thread = HandlerThread("sensors").also { it.start() }
        val handler = Handler(thread!!.looper)
        listOfNotNull(acc, gyro, baro).forEach { manager.registerListener(this, it, PERIOD_US, handler) }
        running = true
    }

    override fun onSensorChanged(e: SensorEvent) {
        val w = sensorOut ?: return
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { w.write("${e.timestamp},a,${e.values[0]},${e.values[1]},${e.values[2]}\n"); accN++ }
            Sensor.TYPE_GYROSCOPE -> { w.write("${e.timestamp},g,${e.values[0]},${e.values[1]},${e.values[2]}\n"); gyroN++ }
            Sensor.TYPE_PRESSURE -> { w.write("${e.timestamp},p,${e.values[0]},,\n"); baroN++ }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun logEvent(type: String, label: String) {
        eventOut?.run {
            write("${SystemClock.elapsedRealtimeNanos()},$type,$label\n")
            flush()
        }
    }

    private fun stopRecording() {
        if (running) {
            if (currentLabel.isNotEmpty()) logEvent("end", currentLabel)
            sm?.unregisterListener(this)
            thread?.quitSafely()
            thread?.join(1000)  // let the sensor thread finish writing before closing
            sensorOut?.close(); eventOut?.close()
            sensorOut = null; eventOut = null
            wakeLock?.takeIf { it.isHeld }?.release()
            running = false
            currentLabel = ""
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopRecording()
        super.onDestroy()
    }

    private fun showForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("SEHAT recorder")
            .setContentText("Recording sensor data")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
