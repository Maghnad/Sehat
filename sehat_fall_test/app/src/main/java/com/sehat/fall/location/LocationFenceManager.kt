package com.sehat.fall.location

import android.location.Location
import com.sehat.fall.SehatApp

object LocationFenceManager {

    enum class FenceState {
        HOME_NOT_SET,
        INSIDE_HOME,
        OUTSIDE_HOME,
        NO_LOCATION
    }

    data class EvaluationResult(
        val state: FenceState,
        val distanceToHomeMeters: Float,
        val radiusMeters: Float,
        val homeLatitude: Double,
        val homeLongitude: Double,
        val currentLatitude: Double?,
        val currentLongitude: Double?
    )

    fun evaluate(currentLocation: Location?): EvaluationResult {
        val settings = SehatApp.instance.settingsManager
        val homeLat = settings.homeLatitude
        val homeLon = settings.homeLongitude
        val radius = settings.homeRadiusMeters

        if (!settings.isHomeConfigured) {
            return EvaluationResult(
                state = FenceState.HOME_NOT_SET,
                distanceToHomeMeters = -1f,
                radiusMeters = radius,
                homeLatitude = 0.0,
                homeLongitude = 0.0,
                currentLatitude = currentLocation?.latitude,
                currentLongitude = currentLocation?.longitude
            )
        }

        if (currentLocation == null) {
            return EvaluationResult(
                state = FenceState.NO_LOCATION,
                distanceToHomeMeters = -1f,
                radiusMeters = radius,
                homeLatitude = homeLat,
                homeLongitude = homeLon,
                currentLatitude = null,
                currentLongitude = null
            )
        }

        val homeLoc = Location("home").apply {
            latitude = homeLat
            longitude = homeLon
        }

        val distance = currentLocation.distanceTo(homeLoc)
        val state = if (distance <= radius) {
            FenceState.INSIDE_HOME
        } else {
            FenceState.OUTSIDE_HOME
        }

        return EvaluationResult(
            state = state,
            distanceToHomeMeters = distance,
            radiusMeters = radius,
            homeLatitude = homeLat,
            homeLongitude = homeLon,
            currentLatitude = currentLocation.latitude,
            currentLongitude = currentLocation.longitude
        )
    }

    fun setHomeLocation(lat: Double, lon: Double, radius: Float? = null) {
        val settings = SehatApp.instance.settingsManager
        settings.homeLatitude = lat
        settings.homeLongitude = lon
        if (radius != null) {
            settings.homeRadiusMeters = radius
        }
    }
}
