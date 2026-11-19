package com.sehat.fall.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sehat.fall.R
import com.sehat.fall.SehatApp
import com.sehat.fall.alert.AlertManager
import com.sehat.fall.detection.SelfTestRunner
import com.sehat.fall.location.LocationFenceManager
import com.sehat.fall.service.FallDetectionService
import com.sehat.fall.service.GeofenceMonitoringService

class MainActivity : Activity() {

    private val uiHandler = Handler(Looper.getMainLooper())

    // Tabs
    private lateinit var tabMainBtn: Button
    private lateinit var tabTestingBtn: Button
    private lateinit var mainTabContainer: LinearLayout
    private lateinit var testingTabContainer: LinearLayout

    // TAB 1: Main (Location & Power Saving) views
    private lateinit var geoStatusCard: TextView
    private lateinit var geoToggleBtn: Button
    private lateinit var homeCoordsText: TextView
    private lateinit var liveDistanceText: TextView
    private lateinit var homeRadiusText: TextView
    private lateinit var homeRadiusSeekBar: SeekBar
    private lateinit var setHomeBtn: Button
    private lateinit var mainContactCard: TextView

    // TAB 2: Testing views
    private lateinit var statusBadge: TextView
    private lateinit var testingContactCard: TextView
    private lateinit var metricsCard: LinearLayout
    private lateinit var accRateText: TextView
    private lateinit var gyroRateText: TextView
    private lateinit var windowRateText: TextView
    private lateinit var probText: TextView
    private lateinit var warningBanner: TextView
    private lateinit var toggleServiceBtn: Button
    private lateinit var selfTestResultText: TextView

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDashboard()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        checkFirstRunConsent()
        checkPermissionsAndBattery()

        // Set initial tab from saved state
        val initialTab = SehatApp.instance.settingsManager.activeAppTab
        switchTab(if (initialTab == "testing") "testing" else "main")
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun checkFirstRunConsent() {
        if (!SehatApp.instance.settingsManager.hasConsented) {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun checkPermissionsAndBattery() {
        val neededPerms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.CALL_PHONE)
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            neededPerms.add(Manifest.permission.SEND_SMS)
        }

        if (neededPerms.isNotEmpty()) {
            requestPermissions(neededPerms.toTypedArray(), 101)
        }

        // Battery Optimization Exemption Prompt
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(36, 44, 36, 36)
        }

        // Title and Subtitle
        val titleView = TextView(this).apply {
            text = "SEHAT-ERS"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Edge-First Safe Fall Protection"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 2, 0, 16)
        }
        root.addView(subtitleView)

        // ==========================================
        // TOP MODE SWITCHER TABS: MAIN vs TESTING
        // ==========================================
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(6, 6, 6, 6)
        }

        tabMainBtn = Button(this).apply {
            text = "🏠 MAIN (SMART LOCATION)"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2563EB"))
            setOnClickListener { switchTab("main") }
        }
        val tabParams1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 4, 0)
        }
        tabRow.addView(tabMainBtn, tabParams1)

        tabTestingBtn = Button(this).apply {
            text = "🧪 TESTING & LAB"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { switchTab("testing") }
        }
        val tabParams2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(4, 0, 0, 0)
        }
        tabRow.addView(tabTestingBtn, tabParams2)

        root.addView(tabRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) })

        val scrollCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ==========================================
        // CONTAINER 1: MAIN APP (SMART LOCATION MODE)
        // ==========================================
        mainTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        buildMainTabContent(mainTabContainer)
        scrollCol.addView(mainTabContainer)

        // ==========================================
        // CONTAINER 2: TESTING VERSION
        // ==========================================
        testingTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        buildTestingTabContent(testingTabContainer)
        scrollCol.addView(testingTabContainer)

        // Navigation Footer Row: Logs, Settings, About
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 16)
        }

        val logBtn = Button(this).apply {
            text = "Logs"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EventLogActivity::class.java))
            }
        }
        navRow.addView(logBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 6, 0)
        })

        val settingsBtn = Button(this).apply {
            text = "Settings"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        navRow.addView(settingsBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(6, 0, 6, 0)
        })

        val aboutBtn = Button(this).apply {
            text = "About"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AboutActivity::class.java))
            }
        }
        navRow.addView(aboutBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(6, 0, 0, 0)
        })

        scrollCol.addView(navRow)

        val scroll = ScrollView(this).apply { addView(scrollCol) }
        root.addView(scroll)
        setContentView(root)
    }

    /**
     * Builds the Production Main View: Location-Aware Auto Power-Saving Mode.
     */
    private fun buildMainTabContent(parent: LinearLayout) {
        val bannerDesc = TextView(this).apply {
            text = "AUTOMATED LOCATION PROTECTION"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 4, 0, 8)
        }
        parent.addView(bannerDesc)

        // Geofence Live State Indicator Card
        geoStatusCard = TextView(this).apply {
            text = "CHECKING LOCATION STATUS..."
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 20, 24, 20)
        }
        parent.addView(geoStatusCard)

        // Toggle Feature Button
        geoToggleBtn = Button(this).apply {
            text = "ENABLE LOCATION AUTO-PROTECTION"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(20, 18, 20, 18)
            setOnClickListener { toggleLocationProtection() }
        }
        val toggleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 14, 0, 16) }
        parent.addView(geoToggleBtn, toggleParams)

        // Home Zone Setup Card
        val homeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161E2E"))
            setPadding(24, 20, 24, 20)
        }

        val homeTitle = TextView(this).apply {
            text = "Safe Home Zone Configuration"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        homeCard.addView(homeTitle)

        homeCoordsText = TextView(this).apply {
            text = "Home Location: Not Set"
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 8, 0, 4)
        }
        homeCard.addView(homeCoordsText)

        liveDistanceText = TextView(this).apply {
            text = "Distance to Home: Calculating..."
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 12)
        }
        homeCard.addView(liveDistanceText)

        setHomeBtn = Button(this).apply {
            text = "📍 SET CURRENT LOCATION AS HOME"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2563EB"))
            setOnClickListener { setCurrentLocationAsHome() }
        }
        homeCard.addView(setHomeBtn)

        // Radius Slider
        homeRadiusText = TextView(this).apply {
            text = "Safe Zone Radius: 100 meters"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 4)
        }
        homeCard.addView(homeRadiusText)

        val radiusDesc = TextView(this).apply {
            text = "Within this radius from house, fall sensors remain paused to save battery."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 6)
        }
        homeCard.addView(radiusDesc)

        homeRadiusSeekBar = SeekBar(this).apply {
            max = 18 // 50m to 500m (step 25m: 50 + p*25)
            progress = 2 // default 100m
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val r = (50 + progress * 25).toFloat()
                    homeRadiusText.text = "Safe Zone Radius: ${r.toInt()} meters"
                    if (fromUser) {
                        SehatApp.instance.settingsManager.homeRadiusMeters = r
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        homeCard.addView(homeRadiusSeekBar)

        parent.addView(homeCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) })

        // Emergency Relative Contact Card
        mainContactCard = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(20, 14, 20, 14)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        parent.addView(mainContactCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) })

        // Power benefit explainer note
        val powerBenefit = TextView(this).apply {
            text = "⚡ Battery Saver Benefit:\nContinuous IMU sensor polling and AI inferences consume battery. With Smart Location enabled, sensors only run when you are away from home, extending battery life by up to 5x."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(18, 14, 18, 14)
        }
        parent.addView(powerBenefit)
    }

    /**
     * Builds the Testing & Lab View.
     */
    private fun buildTestingTabContent(parent: LinearLayout) {
        val bannerDesc = TextView(this).apply {
            text = "TESTING & LAB DIAGNOSTICS"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(0, 4, 0, 8)
        }
        parent.addView(bannerDesc)

        // Manual Service Status Banner
        statusBadge = TextView(this).apply {
            text = "MONITORING IDLE"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 16, 24, 16)
        }
        parent.addView(statusBadge)

        // Contact Card
        testingContactCard = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(20, 14, 20, 14)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        val contactParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 0) }
        parent.addView(testingContactCard, contactParams)

        // Low Rate Warning Banner
        warningBanner = TextView(this).apply {
            text = "⚠️ WARNING: Sensor delivery rate < 50 Hz! Detection precision may be compromised."
            textSize = 13f
            setTextColor(Color.parseColor("#FEF08A"))
            setBackgroundColor(Color.parseColor("#78350F"))
            gravity = Gravity.CENTER
            setPadding(20, 14, 20, 14)
            visibility = View.GONE
        }
        val warningParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 0) }
        parent.addView(warningBanner, warningParams)

        // Metrics Card
        metricsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161E2E"))
            setPadding(24, 20, 24, 20)
        }
        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 16, 0, 16) }

        accRateText = TextView(this).apply {
            text = "Accelerometer: 0.0 Hz (Target ~100 Hz)"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
        }
        metricsCard.addView(accRateText)

        gyroRateText = TextView(this).apply {
            text = "Gyroscope: 0.0 Hz (Target ~100 Hz)"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 8, 0, 0)
        }
        metricsCard.addView(gyroRateText)

        windowRateText = TextView(this).apply {
            text = "Inference: 0 windows/min (Stride 500ms)"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 8, 0, 0)
        }
        metricsCard.addView(windowRateText)

        probText = TextView(this).apply {
            text = "Latest Probability: 0.00% (Flag streak: 0/2)"
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 8, 0, 0)
        }
        metricsCard.addView(probText)

        parent.addView(metricsCard, cardParams)

        // Manual Toggle Monitoring Button
        toggleServiceBtn = Button(this).apply {
            text = "MANUAL START MONITORING"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(24, 18, 24, 18)
            setOnClickListener { toggleMonitoringService() }
        }
        parent.addView(toggleServiceBtn)

        // Controlled Fall Simulation Button
        val simFallBtn = Button(this).apply {
            text = "Simulate Controlled Fall (Voice 'HELP' Triage)"
            setBackgroundColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#FEF08A"))
            setOnClickListener {
                AlertManager.triggerFallAlert(this@MainActivity, 0.99f)
            }
        }
        val simParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 0) }
        parent.addView(simFallBtn, simParams)

        // Self-Test Section
        val testHeader = TextView(this).apply {
            text = "Validation & Self-Test"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 8)
        }
        parent.addView(testHeader)

        val selfTestBtn = Button(this).apply {
            text = "Run Self-Test (Motion & Audio Golden)"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener { executeSelfTest() }
        }
        parent.addView(selfTestBtn)

        selfTestResultText = TextView(this).apply {
            text = "Self-test: Not run yet"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 8, 0, 12)
        }
        parent.addView(selfTestResultText)

        val pocketBtn = Button(this).apply {
            text = "Pocket Mode (10s Countdown)"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PocketModeActivity::class.java))
            }
        }
        parent.addView(pocketBtn)
    }

    private fun switchTab(targetTab: String) {
        val settings = SehatApp.instance.settingsManager
        settings.activeAppTab = targetTab

        if (targetTab == "main") {
            tabMainBtn.setBackgroundColor(Color.parseColor("#2563EB"))
            tabMainBtn.setTextColor(Color.WHITE)
            tabTestingBtn.setBackgroundColor(Color.TRANSPARENT)
            tabTestingBtn.setTextColor(Color.parseColor("#94A3B8"))

            mainTabContainer.visibility = View.VISIBLE
            testingTabContainer.visibility = View.GONE
        } else {
            tabTestingBtn.setBackgroundColor(Color.parseColor("#2563EB"))
            tabTestingBtn.setTextColor(Color.WHITE)
            tabMainBtn.setBackgroundColor(Color.TRANSPARENT)
            tabMainBtn.setTextColor(Color.parseColor("#94A3B8"))

            testingTabContainer.visibility = View.VISIBLE
            mainTabContainer.visibility = View.GONE
        }
        updateDashboard()
    }

    private fun toggleLocationProtection() {
        val settings = SehatApp.instance.settingsManager
        val newState = !settings.isLocationMonitoringEnabled
        settings.isLocationMonitoringEnabled = newState

        val intent = Intent(this, GeofenceMonitoringService::class.java)
        if (newState) {
            intent.action = GeofenceMonitoringService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Location auto-protection ENABLED", Toast.LENGTH_SHORT).show()
        } else {
            intent.action = GeofenceMonitoringService.ACTION_STOP
            startService(intent)
            Toast.makeText(this, "Location auto-protection DISABLED", Toast.LENGTH_SHORT).show()
        }
        updateDashboard()
    }

    private fun setCurrentLocationAsHome() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Toast.makeText(this, "Location permission required to set home", Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 102)
            return
        }

        try {
            val lastGps = if (hasFine) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = lastGps ?: lastNet

            if (best != null) {
                LocationFenceManager.setHomeLocation(best.latitude, best.longitude)
                Toast.makeText(
                    this,
                    "Safe Home Zone saved!\nLat: ${"%.5f".format(best.latitude)}, Lon: ${"%.5f".format(best.longitude)}",
                    Toast.LENGTH_LONG
                ).show()
                updateDashboard()
            } else {
                Toast.makeText(this, "Acquiring GPS fix... please try again in 5 seconds outdoors or near window.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error getting location: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMonitoringService() {
        val intent = Intent(this, FallDetectionService::class.java)
        if (FallDetectionService.isRunning) {
            intent.action = FallDetectionService.ACTION_STOP
            startService(intent)
        } else {
            intent.action = FallDetectionService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        updateDashboard()
    }

    private fun updateDashboard() {
        val running = FallDetectionService.isRunning
        val settings = SehatApp.instance.settingsManager

        // Update Tab 1 (Main Location Mode)
        val geoRunning = GeofenceMonitoringService.isRunning
        if (geoRunning) {
            geoToggleBtn.text = "DISABLE LOCATION AUTO-PROTECTION"
            geoToggleBtn.setBackgroundColor(Color.parseColor("#EF4444"))
        } else {
            geoToggleBtn.text = "ENABLE LOCATION AUTO-PROTECTION"
            geoToggleBtn.setBackgroundColor(Color.parseColor("#10B981"))
        }

        if (settings.isHomeConfigured) {
            homeCoordsText.text = "Home Coordinates: Lat ${"%.5f".format(settings.homeLatitude)}, Lon ${"%.5f".format(settings.homeLongitude)}"
        } else {
            homeCoordsText.text = "Home Coordinates: Not Configured (Tap button below)"
        }

        val r = settings.homeRadiusMeters
        val progress = ((r - 50) / 25).toInt().coerceIn(0, 18)
        if (homeRadiusSeekBar.progress != progress) {
            homeRadiusSeekBar.progress = progress
        }
        homeRadiusText.text = "Safe Zone Radius: ${r.toInt()} meters"

        // Evaluate live geofence state
        val lastLoc = GeofenceMonitoringService.lastLocation
        val eval = LocationFenceManager.evaluate(lastLoc)

        when (eval.state) {
            LocationFenceManager.FenceState.INSIDE_HOME -> {
                geoStatusCard.text = "🏠 AT HOME — SAFE ZONE ACTIVE\nMotion sensors IDLE (Battery consumption minimized)\nAuto-starts whenever you step outside."
                geoStatusCard.setBackgroundColor(Color.parseColor("#065F46")) // Dark Green
                liveDistanceText.text = "Distance to Home: ${eval.distanceToHomeMeters.toInt()}m (Inside ${r.toInt()}m radius)"
            }
            LocationFenceManager.FenceState.OUTSIDE_HOME -> {
                geoStatusCard.text = "🚶 OUTSIDE HOUSE — MONITORING ACTIVE!\nFall detection sensors running continuously.\nProtected wherever you go."
                geoStatusCard.setBackgroundColor(Color.parseColor("#991B1B")) // Crimson Alert
                liveDistanceText.text = "Distance to Home: ${eval.distanceToHomeMeters.toInt()}m (Outside ${r.toInt()}m safe zone)"
            }
            LocationFenceManager.FenceState.HOME_NOT_SET -> {
                geoStatusCard.text = "📍 HOME LOCATION NOT SET\nTap 'Set Current Location as Home' to initialize your safe zone."
                geoStatusCard.setBackgroundColor(Color.parseColor("#1E293B"))
                liveDistanceText.text = "Distance to Home: N/A"
            }
            LocationFenceManager.FenceState.NO_LOCATION -> {
                geoStatusCard.text = "📍 WAITING FOR LOCATION FIX...\nEnsure GPS is turned ON."
                geoStatusCard.setBackgroundColor(Color.parseColor("#374151"))
                liveDistanceText.text = "Distance to Home: Locating..."
            }
        }

        // Contact cards
        val contactText = if (settings.isRelativeConfigured) {
            "📞 Emergency Contact: ${settings.relativeName} (${settings.relativePhoneNumber})\nTap to adjust contact or triage timing."
        } else {
            "⚠️ NO EMERGENCY CONTACT CONFIGURED!\nTap here to set relative's phone number for auto-call."
        }
        mainContactCard.text = contactText
        testingContactCard.text = contactText

        if (settings.isRelativeConfigured) {
            mainContactCard.setTextColor(Color.parseColor("#A7F3D0"))
            mainContactCard.setBackgroundColor(Color.parseColor("#064E3B"))
            testingContactCard.setTextColor(Color.parseColor("#A7F3D0"))
            testingContactCard.setBackgroundColor(Color.parseColor("#064E3B"))
        } else {
            mainContactCard.setTextColor(Color.parseColor("#FEF08A"))
            mainContactCard.setBackgroundColor(Color.parseColor("#78350F"))
            testingContactCard.setTextColor(Color.parseColor("#FEF08A"))
            testingContactCard.setBackgroundColor(Color.parseColor("#78350F"))
        }

        // Update Tab 2 (Testing & Lab)
        if (running) {
            statusBadge.text = "● MONITORING ACTIVE (SCREEN-OFF PROTECTED)"
            statusBadge.setBackgroundColor(Color.parseColor("#065F46"))
            toggleServiceBtn.text = "STOP MONITORING"
            toggleServiceBtn.setBackgroundColor(Color.parseColor("#EF4444"))
        } else {
            statusBadge.text = "○ MONITORING IDLE"
            statusBadge.setBackgroundColor(Color.parseColor("#1E293B"))
            toggleServiceBtn.text = "MANUAL START MONITORING"
            toggleServiceBtn.setBackgroundColor(Color.parseColor("#10B981"))
        }

        accRateText.text = "Accelerometer: ${"%.1f".format(FallDetectionService.currentAccRateHz)} Hz (Target ~100 Hz)"
        gyroRateText.text = "Gyroscope: ${"%.1f".format(FallDetectionService.currentGyroRateHz)} Hz (Target ~100 Hz)"
        windowRateText.text = "Inference: ${"%.1f".format(FallDetectionService.windowsPerMinute)} windows/min"
        probText.text = "Latest Probability: ${"%.2f".format(FallDetectionService.latestProbability * 100)}% (Streak: ${FallDetectionService.consecutiveFlagged}/${settings.persist})"

        if (running && FallDetectionService.hasLowRateWarning) {
            warningBanner.visibility = View.VISIBLE
        } else {
            warningBanner.visibility = View.GONE
        }
    }

    private fun executeSelfTest() {
        selfTestResultText.text = "Running on-device self-test against Motion & Audio golden vectors..."
        selfTestResultText.setTextColor(Color.YELLOW)

        Thread {
            val runner = SelfTestRunner(this)
            val result = runner.run()
            runOnUiThread {
                if (result.passed) {
                    selfTestResultText.text = result.summary
                    selfTestResultText.setTextColor(Color.parseColor("#10B981"))
                    Toast.makeText(this, "Self-test PASSED (Motion & Audio verified)", Toast.LENGTH_SHORT).show()
                } else {
                    selfTestResultText.text = result.summary
                    selfTestResultText.setTextColor(Color.parseColor("#EF4444"))
                    Toast.makeText(this, "Self-test FAILED", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
