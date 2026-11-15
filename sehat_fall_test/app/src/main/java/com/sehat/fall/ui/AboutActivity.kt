package com.sehat.fall.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sehat.fall.SehatApp

class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(36, 48, 36, 36)
        }

        val header = TextView(this).apply {
            text = "About & Privacy Consent"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        root.addView(header)

        val scrollCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Edge-First Privacy Section
        addSectionHeader(scrollCol, "Edge-First Privacy Architecture")
        addSectionBody(
            scrollCol,
            "• All motion sensor analysis (accelerometer and gyroscope) executes 100% locally on your device.\n" +
            "• No raw sensor stream or telemetry ever leaves your phone.\n" +
            "• Phase 1 of SEHAT-ERS contains NO internet permission and NO cloud connectivity.\n" +
            "• All event logs and traces are stored strictly on device storage."
        )

        // 2. Clinical and Operational Limitations
        addSectionHeader(scrollCol, "Important Scientific Limitations")
        addSectionBody(
            scrollCol,
            "• Laboratory Trained: The fall detection model was trained on simulated fall data from 66 healthy young adult volunteers with the handset placed in a trouser pocket.\n" +
            "• False Alert Profile: False alerts occur predominantly during vehicular transitions—specifically when stepping into or stepping out of a car (accounting for approximately 8% and 6% of such movements respectively during testing).\n" +
            "• Population Validation: This system has NOT been validated for real-world unconstrained falls or frail elderly populations.\n" +
            "• NOT A MEDICAL DEVICE: SEHAT-ERS is an edge-first emergency prototype for personal safety research. It is not an FDA/CE-cleared medical device and should not replace primary emergency care systems."
        )

        // 3. Sensor Requirements
        addSectionHeader(scrollCol, "Sensor Contract")
        addSectionBody(
            scrollCol,
            "• Continuously samples hardware accelerometer (including gravity) and gyroscope at ~100 Hz.\n" +
            "• Re-samples to standard 50 Hz windows via 4x linear interpolation averaging.\n" +
            "• Uses gravity-aligned rotational invariance so pocket orientation does not bias detection."
        )

        val ackBtn = Button(this).apply {
            text = "I Understand & Accept"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                SehatApp.instance.settingsManager.hasConsented = true
                finish()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 32, 0, 16)
        }
        scrollCol.addView(ackBtn, btnParams)

        val scroll = ScrollView(this).apply { addView(scrollCol) }
        root.addView(scroll)
        setContentView(root)
    }

    private fun addSectionHeader(parent: LinearLayout, title: String) {
        val tv = TextView(parent.context).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 24, 0, 8)
        }
        parent.addView(tv)
    }

    private fun addSectionBody(parent: LinearLayout, text: String) {
        val tv = TextView(parent.context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(4f, 1.2f)
            setPadding(0, 0, 0, 12)
        }
        parent.addView(tv)
    }
}
