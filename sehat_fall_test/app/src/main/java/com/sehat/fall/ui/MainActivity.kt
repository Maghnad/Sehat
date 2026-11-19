package com.sehat.fall.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.TextView
import android.widget.Toast
import com.sehat.fall.R
import com.sehat.fall.SehatApp
import com.sehat.fall.alert.AlertManager
import com.sehat.fall.detection.SelfTestRunner
import com.sehat.fall.service.FallDetectionService

class MainActivity : Activity() {

    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var statusBadge: TextView
    private lateinit var contactCard: TextView
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
        // Request runtime permissions: Notifications, Audio (Microphone for HELP command), Call Phone, and SMS
        val neededPerms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
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
            setPadding(36, 48, 36, 36)
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
            text = "On-Device Edge-First Fall Protection"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 20)
        }
        root.addView(subtitleView)

        val scrollCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Service Status Banner
        statusBadge = TextView(this).apply {
            text = "MONITORING IDLE"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 16, 24, 16)
        }
        scrollCol.addView(statusBadge)

        // Emergency Contact Status Card
        contactCard = TextView(this).apply {
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
        scrollCol.addView(contactCard, contactParams)

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
        scrollCol.addView(warningBanner, warningParams)

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

        scrollCol.addView(metricsCard, cardParams)

        // Toggle Monitoring Button
        toggleServiceBtn = Button(this).apply {
            text = "START MONITORING"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(24, 20, 24, 20)
            setOnClickListener { toggleMonitoringService() }
        }
        scrollCol.addView(toggleServiceBtn)

        // Self-Test Section
        val testHeader = TextView(this).apply {
            text = "Validation & Self-Test"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 28, 0, 8)
        }
        scrollCol.addView(testHeader)

        val selfTestBtn = Button(this).apply {
            text = "Run Self-Test (Motion & Audio Golden)"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener { executeSelfTest() }
        }
        scrollCol.addView(selfTestBtn)

        selfTestResultText = TextView(this).apply {
            text = "Self-test: Not run yet (Tests Motion & Audio golden vectors)"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 8, 0, 16)
        }
        scrollCol.addView(selfTestResultText)

        // Operations Section
        val opsHeader = TextView(this).apply {
            text = "Operational Controls"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        }
        scrollCol.addView(opsHeader)

        val pocketBtn = Button(this).apply {
            text = "Pocket Mode (10s Countdown)"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PocketModeActivity::class.java))
            }
        }
        scrollCol.addView(pocketBtn)

        val simFallBtn = Button(this).apply {
            text = "Simulate Controlled Fall"
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
        scrollCol.addView(simFallBtn, simParams)

        // Navigation Row: Logs, Settings, About
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
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

        if (running) {
            statusBadge.text = "● MONITORING ACTIVE (SCREEN-OFF PROTECTED)"
            statusBadge.setBackgroundColor(Color.parseColor("#065F46"))
            toggleServiceBtn.text = "STOP MONITORING"
            toggleServiceBtn.setBackgroundColor(Color.parseColor("#EF4444"))
        } else {
            statusBadge.text = "○ MONITORING IDLE"
            statusBadge.setBackgroundColor(Color.parseColor("#1E293B"))
            toggleServiceBtn.text = "START MONITORING"
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

        if (settings.isRelativeConfigured) {
            contactCard.text = "📞 Emergency Contact: ${settings.relativeName} (${settings.relativePhoneNumber})\nTap to adjust contact or post-fall triage timing."
            contactCard.setTextColor(Color.parseColor("#A7F3D0"))
            contactCard.setBackgroundColor(Color.parseColor("#064E3B"))
        } else {
            contactCard.text = "⚠️ NO EMERGENCY CONTACT CONFIGURED!\nTap here to set your relative's phone number for auto-call protection."
            contactCard.setTextColor(Color.parseColor("#FEF08A"))
            contactCard.setBackgroundColor(Color.parseColor("#78350F"))
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
