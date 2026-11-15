package com.sehat.fall.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.sehat.fall.SehatApp

class SettingsActivity : Activity() {

    private lateinit var thresholdValueText: TextView
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var persistValueText: TextView
    private lateinit var persistSeekBar: SeekBar
    private lateinit var cancelSecValueText: TextView
    private lateinit var cancelSecSeekBar: SeekBar
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
            text = "Detection Settings"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
        root.addView(header)

        val scrollCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Threshold
        thresholdValueText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        scrollCol.addView(thresholdValueText)

        val thresholdDesc = TextView(this).apply {
            text = "Fall probability threshold above which a window is flagged. Default loaded from fall_config.json."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 8)
        }
        scrollCol.addView(thresholdDesc)

        thresholdSeekBar = SeekBar(this).apply {
            max = 100 // 0.00 to 1.00
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

        addDivider(scrollCol)

        // 2. Persist
        persistValueText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        scrollCol.addView(persistValueText)

        val persistDesc = TextView(this).apply {
            text = "Consecutive flagged windows (1 to 10) required to fire an alert. Prevents false triggers."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 8)
        }
        scrollCol.addView(persistDesc)

        persistSeekBar = SeekBar(this).apply {
            max = 9 // 0->1, 1->2, ..., 9->10
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

        // 3. Cancel Window Duration
        cancelSecValueText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        scrollCol.addView(cancelSecValueText)

        val cancelDesc = TextView(this).apply {
            text = "Countdown duration (5 to 10 seconds) on alert screen before escalating locally."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 8)
        }
        scrollCol.addView(cancelDesc)

        cancelSecSeekBar = SeekBar(this).apply {
            max = 5 // 0->5, 1->6, ..., 5->10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sec = progress + 5
                    cancelSecValueText.text = "Cancel Window: $sec seconds"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        scrollCol.addView(cancelSecSeekBar)

        addDivider(scrollCol)

        // 4. Record Trace Switch
        traceCheckBox = CheckBox(this).apply {
            text = "Record Trace (Save window evaluations to trace.csv)"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(8, 16, 8, 16)
        }
        scrollCol.addView(traceCheckBox)

        val traceDesc = TextView(this).apply {
            text = "Logs every evaluated 2-second window (timestamp, probability, flagged) for offline analysis."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 32)
        }
        scrollCol.addView(traceDesc)

        // Save & Reset Buttons
        val saveBtn = Button(this).apply {
            text = "Save Settings"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener { saveSettings() }
        }
        scrollCol.addView(saveBtn)

        val resetBtn = Button(this).apply {
            text = "Reset to Defaults from fall_config.json"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { resetSettings() }
        }
        scrollCol.addView(resetBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 16, 0, 0) })

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

        val thr = settings.threshold
        thresholdSeekBar.progress = (thr * 100).toInt()
        thresholdValueText.text = "Threshold: ${"%.2f".format(thr)}"

        val per = settings.persist.coerceIn(1, 10)
        persistSeekBar.progress = per - 1
        persistValueText.text = "Persist: $per consecutive windows"

        val sec = settings.cancelWindowSeconds
        cancelSecSeekBar.progress = sec - 5
        cancelSecValueText.text = "Cancel Window: $sec seconds"

        traceCheckBox.isChecked = settings.recordTrace
    }

    private fun saveSettings() {
        val settings = SehatApp.instance.settingsManager
        val thr = (thresholdSeekBar.progress / 100f).coerceIn(0.01f, 0.99f)
        val per = persistSeekBar.progress + 1
        val sec = cancelSecSeekBar.progress + 5
        val trace = traceCheckBox.isChecked

        settings.threshold = thr
        settings.persist = per
        settings.cancelWindowSeconds = sec
        settings.recordTrace = trace
        SehatApp.instance.traceRepo.setEnabled(trace)

        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetSettings() {
        val settings = SehatApp.instance.settingsManager
        settings.resetToDefaults()
        loadSettings()
        Toast.makeText(this, "Reset to fall_config.json defaults", Toast.LENGTH_SHORT).show()
    }
}
