package com.sehat.fall.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.sehat.fall.R
import com.sehat.fall.SehatApp
import com.sehat.fall.phase2.AlertTransport
import com.sehat.fall.ui.AlertActivity

object AlertManager {

    private const val EMERGENCY_CHANNEL_ID = "sehat_emergency_channel"
    private const val EMERGENCY_NOTIF_ID = 9999

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isAlertActive = false

    @Synchronized
    fun triggerFallAlert(context: Context, probability: Float) {
        if (isAlertActive) return
        isAlertActive = true

        val settings = SehatApp.instance.settingsManager
        val voiceSec = settings.voiceListenSeconds
        val confirmSec = settings.confirmSafeSeconds

        // 1. Play brief attention chime (do not drown out microphone during voice command phase)
        playAttentionChime(context)

        // 2. Start initial prompt vibration
        startPromptVibration(context)

        // 3. Post full-screen emergency notification and launch AlertActivity
        val alertIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlertActivity.EXTRA_PROBABILITY, probability)
            putExtra(AlertActivity.EXTRA_VOICE_SECONDS, voiceSec)
            putExtra(AlertActivity.EXTRA_CONFIRM_SECONDS, confirmSec)
        }

        ensureNotificationChannel(context)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, EMERGENCY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SEHAT-ERS: Fall Detected!")
            .setContentText("Listening for 'HELP' voice command or tap screen.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(EMERGENCY_NOTIF_ID, notification)

        // Direct launch of full-screen activity
        context.startActivity(alertIntent)
    }

    @Synchronized
    fun onUserCancelled(context: Context, probability: Float) {
        stopAlarmAndVibration(context)
        isAlertActive = false

        // Clear emergency notification
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(EMERGENCY_NOTIF_ID)

        // Log outcome = cancelled
        SehatApp.instance.eventLogRepo.logEvent(
            probability = probability,
            outcome = "cancelled",
            appVersion = "1.0"
        )
    }

    @Synchronized
    fun onCountdownExpired(context: Context, probability: Float) {
        stopAlarmAndVibration(context)
        isAlertActive = false

        // 1. Log outcome = ESCALATED
        SehatApp.instance.eventLogRepo.logEvent(
            probability = probability,
            outcome = "ESCALATED",
            appVersion = "1.0"
        )

        // 2. Post persistent local notification for escalated incident
        ensureNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val escalatedNotif = NotificationCompat.Builder(context, EMERGENCY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("EMERGENCY: Fall Alert Escalated!")
            .setContentText("No response received within countdown. Alert recorded locally.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Fall probability: ${"%.1f".format(probability * 100)}%\n" +
                    "Countdown expired without user cancellation.\n" +
                    "Incident logged locally to device memory."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(false)
            .build()

        nm.notify(EMERGENCY_NOTIF_ID, escalatedNotif)

        // 3. Phase 4 Alert Transport Hook
        dispatchPhase4AlertHook(probability)
    }

    private fun dispatchPhase4AlertHook(probability: Float) {
        // TODO: In Phase 4, dispatch over TLS 1.3 -> SMS -> BLE relay -> LoRa fallback
        // Current Phase 1 strictly on-device, no network transmission
        /*
        val payload = AlertTransport.TransportPayload(
            incidentId = "INC_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            riskScore = probability,
            location = null,
            manifestHash = null
        )
        // AlertTransport.dispatchEmergencyAlert(payload)
        */
    }

    fun playAttentionChime(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(context, uri)
            r?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startPromptVibration(context: Context) {
        try {
            val pattern = longArrayOf(0, 300, 150, 300)
            val vibrator = getVibrator(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startAlarm(context: Context) {
        if (mediaPlayer != null) return
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startVibration(context: Context) {
        try {
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 500)
            val vib = getVibrator(context)
            vibrator = vib
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun stopAlarmAndVibration(context: Context) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(EMERGENCY_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    EMERGENCY_CHANNEL_ID,
                    context.getString(R.string.channel_emergency_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.channel_emergency_desc)
                    enableVibration(true)
                    setSound(null, null) // Audio handled by MediaPlayer STREAM_ALARM
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
