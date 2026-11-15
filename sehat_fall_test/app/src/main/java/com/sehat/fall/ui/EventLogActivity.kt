package com.sehat.fall.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.sehat.fall.SehatApp

class EventLogActivity : Activity() {

    private lateinit var logContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        refreshLogs()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(32, 48, 32, 32)
        }

        val header = TextView(this).apply {
            text = "Emergency Event Log"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
        root.addView(header)

        // Action Buttons Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        val shareBtn = Button(this).apply {
            text = "Share CSV"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener { shareLogFile() }
        }
        btnRow.addView(shareBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 8, 0)
        })

        val shareTraceBtn = Button(this).apply {
            text = "Share Trace"
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setTextColor(Color.WHITE)
            setOnClickListener { shareTraceFile() }
        }
        btnRow.addView(shareTraceBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(8, 0, 8, 0)
        })

        val clearBtn = Button(this).apply {
            text = "Clear"
            setBackgroundColor(Color.parseColor("#EF4444"))
            setTextColor(Color.WHITE)
            setOnClickListener { clearLogs() }
        }
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(8, 0, 0, 0)
        })

        root.addView(btnRow)

        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            addView(logContainer)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        setContentView(root)
    }

    private fun refreshLogs() {
        logContainer.removeAllViews()
        val repo = SehatApp.instance.eventLogRepo
        val events = repo.getEvents()

        if (events.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No emergency events recorded yet."
                textSize = 16f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 48, 0, 0)
                gravity = Gravity.CENTER
            }
            logContainer.addView(emptyView)
            return
        }

        for (item in events) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1E293B"))
                setPadding(24, 20, 24, 20)
            }

            val isEscalated = item.outcome.equals("ESCALATED", ignoreCase = true)
            val outcomeColor = if (isEscalated) Color.parseColor("#EF4444") else Color.parseColor("#10B981")

            val titleView = TextView(this).apply {
                text = "${item.formattedTime}  •  ${item.outcome.uppercase()}"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(outcomeColor)
            }
            card.addView(titleView)

            val detailsView = TextView(this).apply {
                text = "Fall Probability: ${"%.1f".format(item.probability * 100)}%   |   App: v${item.appVersion}"
                textSize = 14f
                setTextColor(Color.parseColor("#CBD5E1"))
                setPadding(0, 8, 0, 0)
            }
            card.addView(detailsView)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            logContainer.addView(card, params)
        }
    }

    private fun shareLogFile() {
        val file = SehatApp.instance.eventLogRepo.getFile()
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Event log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        shareFile(file, "text/csv", "Share SEHAT Event Log")
    }

    private fun shareTraceFile() {
        val file = SehatApp.instance.traceRepo.getFile()
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Trace file is empty. Turn on 'Record Trace' in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        shareFile(file, "text/csv", "Share SEHAT Window Trace")
    }

    private fun shareFile(file: java.io.File, mimeType: String, title: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, title))
        } catch (e: Exception) {
            Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearLogs() {
        SehatApp.instance.eventLogRepo.clear()
        Toast.makeText(this, "Event log cleared", Toast.LENGTH_SHORT).show()
        refreshLogs()
    }
}
