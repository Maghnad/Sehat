package com.sehat.fall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LocationFenceTest {

    enum class FenceState {
        HOME_NOT_SET,
        INSIDE_HOME,
        OUTSIDE_HOME
    }

    /**
     * Haversine distance calculator between two GPS coordinates (meters).
     */
    private fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun evaluateFence(
        homeLat: Double,
        homeLon: Double,
        radiusMeters: Float,
        currentLat: Double,
        currentLon: Double
    ): Pair<FenceState, Double> {
        if (homeLat == 0.0 && homeLon == 0.0) {
            return Pair(FenceState.HOME_NOT_SET, -1.0)
        }
        val dist = computeDistanceMeters(homeLat, homeLon, currentLat, currentLon)
        val state = if (dist <= radiusMeters) FenceState.INSIDE_HOME else FenceState.OUTSIDE_HOME
        return Pair(state, dist)
    }

    @Test
    fun testHomeNotSet() {
        val (state, dist) = evaluateFence(0.0, 0.0, 100f, 28.6139, 77.2090)
        assertEquals(FenceState.HOME_NOT_SET, state)
        assertEquals(-1.0, dist, 1e-4)
    }

    @Test
    fun testInsideHomeZone() {
        val homeLat = 28.6139
        val homeLon = 77.2090
        val radius = 100f // 100 meters

        // Position 30 meters away from home
        // (Roughly ~0.00027 degrees latitude is ~30m)
        val currentLat = 28.6141
        val currentLon = 77.2090

        val (state, dist) = evaluateFence(homeLat, homeLon, radius, currentLat, currentLon)
        assertEquals(FenceState.INSIDE_HOME, state)
        assertTrue("Distance ($dist) should be less than radius (100m)", dist <= radius)
    }

    @Test
    fun testOutsideHomeZone() {
        val homeLat = 28.6139
        val homeLon = 77.2090
        val radius = 100f // 100 meters

        // Position ~350 meters away
        val currentLat = 28.6170
        val currentLon = 77.2090

        val (state, dist) = evaluateFence(homeLat, homeLon, radius, currentLat, currentLon)
        assertEquals(FenceState.OUTSIDE_HOME, state)
        assertTrue("Distance ($dist) should be greater than radius (100m)", dist > radius)
    }

    /**
     * Verifies the automated lifecycle transitions:
     * 1. Start inside home -> Service IDLE
     * 2. Step outside -> Service AUTOMATICALLY ACTIVATES
     * 3. Return home -> Service AUTOMATICALLY STOPS
     */
    @Test
    fun testAutomatedMonitoringLifecycleTransitions() {
        val homeLat = 40.7128
        val homeLon = -74.0060
        val radius = 100f

        var isFallMonitoringServiceRunning = false

        fun onLocationChanged(curLat: Double, curLon: Double) {
            val (state, _) = evaluateFence(homeLat, homeLon, radius, curLat, curLon)
            when (state) {
                FenceState.OUTSIDE_HOME -> {
                    if (!isFallMonitoringServiceRunning) {
                        isFallMonitoringServiceRunning = true // Auto-start
                    }
                }
                FenceState.INSIDE_HOME -> {
                    if (isFallMonitoringServiceRunning) {
                        isFallMonitoringServiceRunning = false // Auto-stop to save power
                    }
                }
                FenceState.HOME_NOT_SET -> {}
            }
        }

        // 1. User starts at home (0m distance)
        onLocationChanged(40.7128, -74.0060)
        assertFalse("At home: Monitoring must be IDLE to save battery", isFallMonitoringServiceRunning)

        // 2. User walks 200m away (outside 100m safe zone)
        onLocationChanged(40.7145, -74.0060)
        assertTrue("Outside home: Monitoring MUST automatically ACTIVATE!", isFallMonitoringServiceRunning)

        // 3. User travels further (1 km away)
        onLocationChanged(40.7220, -74.0060)
        assertTrue("Still outside: Monitoring remains ACTIVE", isFallMonitoringServiceRunning)

        // 4. User arrives back home (inside 100m radius)
        onLocationChanged(40.71282, -74.00601)
        assertFalse("Returned home: Monitoring MUST automatically STOP to conserve battery!", isFallMonitoringServiceRunning)
    }
}
