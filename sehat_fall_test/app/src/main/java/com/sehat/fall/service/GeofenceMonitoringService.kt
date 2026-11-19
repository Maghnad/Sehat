package com.sehat.fall.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sehat.fall.R
import com.sehat.fall.SehatApp
import com.sehat.fall.location.LocationFenceManager
import com.sehat.fall.ui.MainActivity

class GeofenceMonitoringService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.sehat.fall.action.GEOFENCE_START"
        const val ACTION_STOP = "com.sehat.fall.action.GEOFENCE_STOP"

        const val CHANNEL_ID = "sehat_geofence_channel"
        const val NOTIFICATION_ID = 2002

        private const val MIN_TIME_MS = 15_000L // 15 seconds
        private const val MIN_DISTANCE_M = 20.0f // 20 meters

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastLocation: Location? = null
            private set

        @Volatile
        var lastState: LocationFenceManager.FenceState = LocationFenceManager.FenceState.HOME_NOT_SET
            private set

        @Volatile
        var lastDistanceMeters: Float = -1f
            private set

        var onStateChangedListener: ((LocationFenceManager.EvaluationResult) -> Unit)? = null
    }

    private var locationManager: LocationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startGeofenceTracking()
            ACTION_STOP -> stopGeofenceTracking()
            else -> if (!isRunning) stopSelf()
        }
        return START_STICKY
    }

    private fun startGeofenceTracking() {
        if (isRunning) return
        isRunning = true

        ensureNotificationChannel()
        val notif = buildNotification(null, LocationFenceManager.FenceState.HOME_NOT_SET, -1f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        registerLocationUpdates()
    }

    private fun registerLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        try {
            val lm = locationManager ?: return

            // 1. Grab last known location immediately across all providers
            val bestLast = LocationFenceManager.getBestLastKnownLocation(this)
            bestLast?.let { onLocationChanged(it) }

            // 2. Register all available providers for periodic updates
            for (p in lm.allProviders) {
                try {
                    if (p == LocationManager.PASSIVE_PROVIDER) {
                        lm.requestLocationUpdates(p, MIN_TIME_MS, 0f, this)
                    } else if (lm.isProviderEnabled(p)) {
                        lm.requestLocationUpdates(p, MIN_TIME_MS, MIN_DISTANCE_M, this)
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val result = LocationFenceManager.evaluate(location)
        lastState = result.state
        lastDistanceMeters = result.distanceToHomeMeters

        handleFenceTransition(result)
        updateNotification(result)

        onStateChangedListener?.invoke(result)
    }

    private fun handleFenceTransition(result: LocationFenceManager.EvaluationResult) {
        when (result.state) {
            LocationFenceManager.FenceState.OUTSIDE_HOME -> {
                // User is outside the house -> AUTOMATICALLY START FALL DETECTION
                if (!FallDetectionService.isRunning) {
                    val startIntent = Intent(this, FallDetectionService::class.java).apply {
                        action = FallDetectionService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(startIntent)
                    } else {
                        startService(startIntent)
                    }
                    SehatApp.instance.eventLogRepo.logEvent(
                        probability = 0.0f,
                        outcome = "AUTO_ACTIVATED_OUTSIDE (${result.distanceToHomeMeters.toInt()}m from home)",
                        appVersion = "1.0"
                    )
                }
            }
            LocationFenceManager.FenceState.INSIDE_HOME -> {
                // User returned home -> AUTOMATICALLY STOP FALL DETECTION TO SAVE BATTERY
                if (FallDetectionService.isRunning) {
                    val stopIntent = Intent(this, FallDetectionService::class.java).apply {
                        action = FallDetectionService.ACTION_STOP
                    }
                    startService(stopIntent)
                    SehatApp.instance.eventLogRepo.logEvent(
                        probability = 0.0f,
                        outcome = "AUTO_PAUSED_AT_HOME (${result.distanceToHomeMeters.toInt()}m inside radius)",
                        appVersion = "1.0"
                    )
                }
            }
            else -> {}
        }
    }

    private fun updateNotification(result: LocationFenceManager.EvaluationResult) {
        val notif = buildNotification(lastLocation, result.state, result.distanceToHomeMeters)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notif)
    }

    private fun buildNotification(loc: Location?, state: LocationFenceManager.FenceState, dist: Float): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, content) = when (state) {
            LocationFenceManager.FenceState.INSIDE_HOME -> Pair(
                "🏠 SEHAT: At Home Safe Zone",
                "Sensors IDLE to save battery. Distance: ${dist.toInt()}m. Monitoring will auto-start when leaving."
            )
            LocationFenceManager.FenceState.OUTSIDE_HOME -> Pair(
                "🚶 SEHAT: Outside Safe Zone",
                "Fall Monitoring ACTIVE! Distance from home: ${dist.toInt()}m."
            )
            LocationFenceManager.FenceState.HOME_NOT_SET -> Pair(
                "📍 SEHAT Location Mode Active",
                "Home location not set yet. Tap to configure your safe home zone."
            )
            LocationFenceManager.FenceState.NO_LOCATION -> Pair(
                "📍 SEHAT: Acquiring Location...",
                "Waiting for GPS/Network fix to determine safe zone."
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Location Safe Zone Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Monitors user location to pause sensors at home and activate outdoors"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun stopGeofenceTracking() {
        if (!isRunning) return
        isRunning = false

        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        locationManager = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        stopGeofenceTracking()
        super.onDestroy()
    }
}
