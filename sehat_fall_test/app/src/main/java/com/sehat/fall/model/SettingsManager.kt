package com.sehat.fall.model

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context, private val defaultConfig: FallConfig) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sehat_settings_prefs", Context.MODE_PRIVATE)

    var threshold: Float
        get() = prefs.getFloat("threshold", defaultConfig.threshold)
        set(value) = prefs.edit().putFloat("threshold", value).apply()

    var persist: Int
        get() = prefs.getInt("persist", defaultConfig.persist)
        set(value) = prefs.edit().putInt("persist", value.coerceIn(1, 10)).apply()

    var cancelWindowSeconds: Int
        get() = prefs.getInt("cancel_window_seconds", 10)
        set(value) = prefs.edit().putInt("cancel_window_seconds", value.coerceIn(5, 10)).apply()

    var recordTrace: Boolean
        get() = prefs.getBoolean("record_trace", false)
        set(value) = prefs.edit().putBoolean("record_trace", value).apply()

    var hasConsented: Boolean
        get() = prefs.getBoolean("has_consented", false)
        set(value) = prefs.edit().putBoolean("has_consented", value).apply()

    var relativePhoneNumber: String
        get() = prefs.getString("relative_phone_number", "") ?: ""
        set(value) = prefs.edit().putString("relative_phone_number", value.trim()).apply()

    var relativeName: String
        get() = prefs.getString("relative_name", "Emergency Contact") ?: "Emergency Contact"
        set(value) = prefs.edit().putString("relative_name", value.trim()).apply()

    var voiceListenSeconds: Int
        get() = prefs.getInt("voice_listen_seconds", 7)
        set(value) = prefs.edit().putInt("voice_listen_seconds", value.coerceIn(5, 15)).apply()

    var confirmSafeSeconds: Int
        get() = prefs.getInt("confirm_safe_seconds", 15)
        set(value) = prefs.edit().putInt("confirm_safe_seconds", value.coerceIn(10, 30)).apply()

    val isRelativeConfigured: Boolean
        get() = relativePhoneNumber.isNotBlank()

    // ==========================================
    // Location & Power-Saving Geofence Settings
    // ==========================================
    var isLocationMonitoringEnabled: Boolean
        get() = prefs.getBoolean("is_location_monitoring_enabled", false)
        set(value) = prefs.edit().putBoolean("is_location_monitoring_enabled", value).apply()

    var homeLatitude: Double
        get() = prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("home_latitude", value.toString()).apply()

    var homeLongitude: Double
        get() = prefs.getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("home_longitude", value.toString()).apply()

    var homeRadiusMeters: Float
        get() = prefs.getFloat("home_radius_meters", 100.0f)
        set(value) = prefs.edit().putFloat("home_radius_meters", value.coerceIn(50.0f, 1000.0f)).apply()

    val isHomeConfigured: Boolean
        get() = homeLatitude != 0.0 || homeLongitude != 0.0

    var activeAppTab: String
        get() = prefs.getString("active_app_tab", "main") ?: "main"
        set(value) = prefs.edit().putString("active_app_tab", value).apply()

    fun resetToDefaults() {
        prefs.edit()
            .putFloat("threshold", defaultConfig.threshold)
            .putInt("persist", defaultConfig.persist)
            .putInt("cancel_window_seconds", 10)
            .putInt("voice_listen_seconds", 7)
            .putInt("confirm_safe_seconds", 15)
            .apply()
    }
}
