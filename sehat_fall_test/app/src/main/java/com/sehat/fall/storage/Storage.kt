package com.sehat.fall.storage

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EventRecord(
    val timestampMs: Long,
    val formattedTime: String,
    val probability: Float,
    val outcome: String,
    val appVersion: String
)

class EventLogRepository(private val context: Context) {

    private val logFile: File by lazy {
        File(context.filesDir, "event_log.csv")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun logEvent(probability: Float, outcome: String, appVersion: String = "1.0") {
        val now = System.currentTimeMillis()
        val formatted = dateFormat.format(Date(now))
        val needsHeader = !logFile.exists() || logFile.length() == 0L

        FileWriter(logFile, true).use { writer ->
            if (needsHeader) {
                writer.append("timestamp_ms,formatted_time,probability,outcome,app_version\n")
            }
            writer.append("$now,\"$formatted\",$probability,\"$outcome\",\"$appVersion\"\n")
            writer.flush()
        }
    }

    @Synchronized
    fun getEvents(): List<EventRecord> {
        if (!logFile.exists()) return emptyList()
        val list = mutableListOf<EventRecord>()
        logFile.bufferedReader().useLines { lines ->
            var first = true
            for (line in lines) {
                if (first) {
                    first = false
                    continue
                }
                val parts = line.split(",")
                if (parts.size >= 5) {
                    val ts = parts[0].toLongOrNull() ?: 0L
                    val timeStr = parts[1].replace("\"", "")
                    val prob = parts[2].toFloatOrNull() ?: 0f
                    val outcome = parts[3].replace("\"", "")
                    val ver = parts[4].replace("\"", "")
                    list.add(EventRecord(ts, timeStr, prob, outcome, ver))
                }
            }
        }
        return list.reversed() // Most recent first
    }

    @Synchronized
    fun clear() {
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    fun getFile(): File = logFile
}

class TraceRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("sehat_trace_prefs", Context.MODE_PRIVATE)
    }

    private val traceFile: File by lazy {
        File(context.filesDir, "trace.csv")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun isEnabled(): Boolean = prefs.getBoolean("record_trace", false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("record_trace", enabled).apply()
    }

    @Synchronized
    fun logTrace(probability: Float, flagged: Boolean) {
        if (!isEnabled()) return
        val now = System.currentTimeMillis()
        val formatted = dateFormat.format(Date(now))
        val needsHeader = !traceFile.exists() || traceFile.length() == 0L

        FileWriter(traceFile, true).use { writer ->
            if (needsHeader) {
                writer.append("timestamp_ms,formatted_time,probability,flagged\n")
            }
            writer.append("$now,\"$formatted\",$probability,$flagged\n")
            writer.flush()
        }
    }

    @Synchronized
    fun clear() {
        if (traceFile.exists()) {
            traceFile.delete()
        }
    }

    fun getFile(): File = traceFile
}
