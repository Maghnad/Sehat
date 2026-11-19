package com.sehat.fall.alert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sehat.fall.SehatApp

object EmergencyCallHelper {

    /**
     * Initiates an emergency phone call to the saved relative's phone number.
     * Uses ACTION_CALL if CALL_PHONE permission is granted, otherwise falls back to ACTION_DIAL.
     */
    fun makeEmergencyCall(context: Context, reason: String, probability: Float = 1.0f): Boolean {
        val settings = SehatApp.instance.settingsManager
        val phoneNumber = settings.relativePhoneNumber
        val relativeName = settings.relativeName

        if (phoneNumber.isBlank()) {
            Toast.makeText(
                context,
                "No emergency contact number configured! Please set up in Settings.",
                Toast.LENGTH_LONG
            ).show()
            SehatApp.instance.eventLogRepo.logEvent(
                probability = probability,
                outcome = "FAILED_NO_CONTACT: $reason",
                appVersion = "1.0"
            )
            return false
        }

        // 1. Try sending automated SMS alert if SEND_SMS permission is granted
        sendEmergencySms(context, phoneNumber, reason)

        // 2. Place phone call
        return try {
            val hasCallPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:${phoneNumber.trim()}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${phoneNumber.trim()}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }

            context.startActivity(intent)

            Toast.makeText(
                context,
                "Calling $relativeName ($phoneNumber)...",
                Toast.LENGTH_LONG
            ).show()

            SehatApp.instance.eventLogRepo.logEvent(
                probability = probability,
                outcome = "CALLED_${relativeName.replace(" ", "_")}: $reason",
                appVersion = "1.0"
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "Error placing emergency call: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun sendEmergencySms(context: Context, phoneNumber: String, reason: String) {
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPermission) return

        try {
            val message = "EMERGENCY ALERT: SEHAT-ERS detected a fall! Status: $reason. Please contact user immediately."
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNumber.trim(), null, message, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
