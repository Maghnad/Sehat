package com.sehat.fall.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.sehat.fall.SehatApp
import com.sehat.fall.alert.EmergencyCallHelper

class SettingsActivity : Activity() {

    // Emergency Contact fields
    private lateinit var relativeNameEdit: EditText
    private lateinit var relativePhoneEdit: EditText
    private lateinit var voiceSecValueText: TextView
    private lateinit var voiceSecSeekBar: SeekBar
    private lateinit var confirmSecValueText: TextView
    private lateinit var confirmSecSeekBar: SeekBar

    // Safe Home Zone & Power Saving
    private lateinit var homeRadiusValueText: TextView
    private lateinit var homeRadiusSeekBar: SeekBar

    // Fall detection parameters
    private lateinit var thresholdValueText: TextView
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var persistValueText: TextView
    private lateinit var persistSeekBar: SeekBar
    private lateinit var traceCheckBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        loadSettings()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(36, 48, 36, 36)
        }

        val header = TextView(this).apply {
            text = "Emergency & Detection Settings"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }
        root.addView(header)

        val scrollCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ==========================================
        // SECTION 1: EMERGENCY RELATIVE CONTACT SETUP
        // ==========================================
        val contactHeader = TextView(this).apply {
            text = "Emergency Relative Contact"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 8, 0, 4)
        }
        scrollCol.addView(contactHeader)

        val contactDesc = TextView(this).apply {
            text = "When a fall is detected, this relative will be called if you say 'HELP' or if you do not confirm safety (assumed unconscious)."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 14)
        }
        scrollCol.addView(contactDesc)

        // Contact Name
        val nameLabel = TextView(this).apply {
            text = "Relative's Name / Relationship:"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        scrollCol.addView(nameLabel)

        relativeNameEdit = EditText(this).apply {
            hint = "e.g. Mom / Son / Daughter / Relative"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 18, 24, 18)
        }
        val nameParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 6, 0, 16) }
        scrollCol.addView(relativeNameEdit, nameParams)

        // Contact Phone Number
        val phoneLabel = TextView(this).apply {
            text = "Relative's Phone Number (For Emergency Auto-Call):"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        scrollCol.addView(phoneLabel)

        relativePhoneEdit = EditText(this).apply {
            hint = "e.g. +1234567890 or 9876543210"
            inputType = InputType.TYPE_CLASS_PHONE
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 18, 24, 18)
        }
        val phoneParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 6, 0, 16) }
        scrollCol.addView(relativePhoneEdit, phoneParams)

        // Test Call Button
        val testCallBtn = Button(this).apply {
            text = "Test Call Relative"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener {
                val phone = relativePhoneEdit.text.toString().trim()
                if (phone.isBlank()) {
                    Toast.makeText(this@SettingsActivity, "Please enter a phone number first", Toast.LENGTH_SHORT).show()
                } else {
                    EmergencyCallHelper.makeEmergencyCall(this@SettingsActivity, "Test Call from Settings", 0.0f)
                }
            }
        }
        scrollCol.addView(testCallBtn)

        addDivider(scrollCol)

        // ==========================================
        // SECTION 2: VOICE RESPONSE & TRIAGE TIMING
        // ==========================================
        val triageHeader = TextView(this).apply {
            text = "Post-Fall Voice Triage Timing"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 8, 0, 4)
        }
        scrollCol.addView(triageHeader)

        // Voice Listen Timeout (5 - 15 s)
        voiceSecValueText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        scrollCol.addView(voiceSecValueText)

        val voiceDesc = TextView(this).apply {
            text = "Microphone listening window to hear 'HELP' voice command."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 2, 0, 8)
        }
        scrollCol.addView(voiceDesc)

        voiceSecSeekBar = SeekBar(this).apply {
            max = 10 // 0->5, 10->15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sec = progress + 5
                    voiceSecValueText.text = "Voice 'HELP' Listening Window: $sec seconds"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        scrollCol.addView(voiceSecSeekBar)

        // Safe Confirmation Window (10 - 30 s)
        confirmSecValueText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 0)
        }
        scrollCol.addView(confirmSecValueText)

        val confirmDesc = TextView(this).apply {
            text = "Countdown duration for 'PLEASE CONFIRM THAT YOU ARE SAFE' alert before user is assumed unconscious and called automatically."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 2, 0, 8)
        }
        scrollCol.addView(confirmDesc)

        confirmSecSeekBar = SeekBar(this).apply {
            max = 20 // 0->10, 20->30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sec = progress + 10
                    confirmSecValueText.text = "Safe Confirmation Window: $sec seconds"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        scrollCol.addView(confirmSecSeekBar)

        addDivider(scrollCol)

        // ==========================================
        // SECTION 3: SAFE HOME ZONE (POWER SAVING)
        // ==========================================
        val zoneHeader = TextView(this).apply {
            text = "Safe Home Zone & Battery Saving"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 8, 0, 4)
        }
        scrollCol.addView(zoneHeader)

        homeRadiusValueText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        scrollCol.addView(homeRadiusValueText)

        val zoneDesc = TextView(this).apply {
            text = "Inside this radius from your house, fall monitoring sensors pause to maximize battery life. Outside, monitoring activates automatically."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 2, 0, 8)
        }
        scrollCol.addView(zoneDesc)

        homeRadiusSeekBar = SeekBar(this).apply {
            max = 18 // 50m to 500m (step 25m)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val r = 50 + progress * 25
                    homeRadiusValueText.text = "Safe Home Radius: $r meters"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        scrollCol.addView(homeRadiusSeekBar)

        addDivider(scrollCol)

        // ==========================================
        // SECTION 4: FALL DETECTION PARAMETERS
        // ==========================================
        val modelHeader = TextView(this).apply {
            text = "Fall Model Sensitivity"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 8, 0, 4)
        }
        scrollCol.addView(modelHeader)

        // Threshold
        thresholdValueText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        scrollCol.addView(thresholdValueText)

        val thresholdDesc = TextView(this).apply {
            text = "Fall probability threshold above which a window is flagged."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 2, 0, 8)
        }
        scrollCol.addView(thresholdDesc)

        thresholdSeekBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = (progress / 100f).coerceIn(0.01f, 0.99f)
                    thresholdValueText.text = "Threshold: ${"%.2f".format(value)}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        scrollCol.addView(thresholdSeekBar)

        // Persist
        persistValueText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 0)
        }
        scrollCol.addView(persistValueText)

        val persistDesc = TextView(this).apply {
            text = "Consecutive flagged windows required to fire an alert (suppresses motion false alarms)."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 2, 0, 8)
        }
        scrollCol.addView(persistDesc)

        persistSeekBar = SeekBar(this).apply {
            max = 9
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress + 1
                    persistValueText.text = "Persist: $p consecutive windows"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        scrollCol.addView(persistSeekBar)

        addDivider(scrollCol)

        // Trace
        traceCheckBox = CheckBox(this).apply {
            text = "Record Trace to trace.csv (Offline Research Analysis)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(8, 12, 8, 16)
        }
        scrollCol.addView(traceCheckBox)

        // Save & Reset Buttons
        val saveBtn = Button(this).apply {
            text = "SAVE ALL SETTINGS"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 20)
            setOnClickListener { saveSettings() }
        }
        val saveParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 16, 0, 0) }
        scrollCol.addView(saveBtn, saveParams)

        val resetBtn = Button(this).apply {
            text = "Reset to Defaults"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { resetSettings() }
        }
        val resetParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 24) }
        scrollCol.addView(resetBtn, resetParams)

        val scroll = ScrollView(this).apply { addView(scrollCol) }
        root.addView(scroll)
        setContentView(root)
    }

    private fun addDivider(parent: LinearLayout) {
        val div = TextView(parent.context).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            height = 2
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            2
        ).apply { setMargins(0, 24, 0, 24) }
        parent.addView(div, params)
    }

    private fun loadSettings() {
        val settings = SehatApp.instance.settingsManager

        relativeNameEdit.setText(settings.relativeName)
        relativePhoneEdit.setText(settings.relativePhoneNumber)

        val voiceSec = settings.voiceListenSeconds.coerceIn(5, 15)
        voiceSecSeekBar.progress = voiceSec - 5
        voiceSecValueText.text = "Voice 'HELP' Listening Window: $voiceSec seconds"

        val confSec = settings.confirmSafeSeconds.coerceIn(10, 30)
        confirmSecSeekBar.progress = confSec - 10
        confirmSecValueText.text = "Safe Confirmation Window: $confSec seconds"

        val radius = settings.homeRadiusMeters
        val rProgress = ((radius - 50) / 25).toInt().coerceIn(0, 18)
        homeRadiusSeekBar.progress = rProgress
        homeRadiusValueText.text = "Safe Home Radius: ${radius.toInt()} meters"

        val thr = settings.threshold
        thresholdSeekBar.progress = (thr * 100).toInt()
        thresholdValueText.text = "Threshold: ${"%.2f".format(thr)}"

        val per = settings.persist.coerceIn(1, 10)
        persistSeekBar.progress = per - 1
        persistValueText.text = "Persist: $per consecutive windows"

        traceCheckBox.isChecked = settings.recordTrace
    }

    private fun saveSettings() {
        val settings = SehatApp.instance.settingsManager

        val name = relativeNameEdit.text.toString().trim()
        val phone = relativePhoneEdit.text.toString().trim()
        val voiceSec = voiceSecSeekBar.progress + 5
        val confSec = confirmSecSeekBar.progress + 10
        val homeRad = (50 + homeRadiusSeekBar.progress * 25).toFloat()
        val thr = (thresholdSeekBar.progress / 100f).coerceIn(0.01f, 0.99f)
        val per = persistSeekBar.progress + 1
        val trace = traceCheckBox.isChecked

        settings.relativeName = if (name.isBlank()) "Emergency Contact" else name
        settings.relativePhoneNumber = phone
        settings.voiceListenSeconds = voiceSec
        settings.confirmSafeSeconds = confSec
        settings.homeRadiusMeters = homeRad
        settings.threshold = thr
        settings.persist = per
        settings.recordTrace = trace
        SehatApp.instance.traceRepo.setEnabled(trace)

        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetSettings() {
        val settings = SehatApp.instance.settingsManager
        settings.resetToDefaults()
        loadSettings()
        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
    }
}
