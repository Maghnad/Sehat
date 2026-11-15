package com.sehat.recorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    // Keep these names in sync with tools/sessions_to_npz.py
    private val labels = arrayOf(
        "walk", "run", "stairs_up", "stairs_down", "sit_hard", "phone_drop",
        "bus_ride", "two_wheeler_ride", "two_wheeler_brake", "pothole", "still",
        "fall_forward", "fall_backward", "fall_side", "struggle", "shove"
    )
    private val positions = arrayOf("pocket_front", "pocket_back", "hand", "bag", "waist", "armband")

    private val ui = Handler(Looper.getMainLooper())
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    private var pendingStart: Runnable? = null

    private lateinit var status: TextView
    private lateinit var volunteer: EditText
    private lateinit var labelSpin: Spinner
    private lateinit var posSpin: Spinner

    private val refresh = object : Runnable {
        override fun run() {
            status.text = if (RecorderService.running)
                "REC  |  label: ${RecorderService.currentLabel.ifEmpty { "-" }}\n" +
                "acc=${RecorderService.accN}  gyro=${RecorderService.gyroN}  baro=${RecorderService.baroN}\n" +
                "barometer on this phone: ${if (RecorderService.hasBaro) "yes" else "NO"}"
            else "Idle"
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        status = TextView(this).apply { textSize = 16f; text = "Idle" }
        volunteer = EditText(this).apply { hint = "Volunteer ID (e.g. V01)"; setSingleLine() }
        posSpin = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, positions) }
        labelSpin = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels) }

        col.addView(TextView(this).apply { text = "SEHAT sensor recorder"; textSize = 22f })
        col.addView(status)
        col.addView(volunteer)
        col.addView(TextView(this).apply { text = "Phone position (put the phone there BEFORE starting):" })
        col.addView(posSpin)
        col.addView(button("Start session") { startSession() })
        col.addView(TextView(this).apply { text = "Activity label:" })
        col.addView(labelSpin)
        col.addView(button("Begin activity (5 s countdown)") { beginLabel() })
        col.addView(button("MARK impact moment") { send(RecorderService.MARK) })
        col.addView(button("End activity") { endLabel() })
        col.addView(button("Stop session") { stopSession() })
        col.addView(button("Share last session (zip)") { shareLast() })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { ui.removeCallbacks(refresh); super.onPause() }

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply { this.text = text; setOnClickListener { onClick() } }

    private fun send(action: String, vararg extras: Pair<String, String>) {
        val i = Intent(this, RecorderService::class.java).setAction(action)
        extras.forEach { i.putExtra(it.first, it.second) }
        if (action == RecorderService.START) startForegroundService(i) else startService(i)
    }

    private fun startSession() {
        val v = volunteer.text.toString().trim()
        if (v.isEmpty()) return toast("Enter a volunteer ID first")
        if (RecorderService.running) return toast("Already recording")
        send(RecorderService.START, "volunteer" to v, "position" to positions[posSpin.selectedItemPosition])
    }

    private fun beginLabel() {
        if (!RecorderService.running) return toast("Start a session first")
        val label = labels[labelSpin.selectedItemPosition]
        pendingStart?.let { ui.removeCallbacks(it) }
        toast("Get ready: $label starts in 5 s")
        pendingStart = Runnable {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP)
            send(RecorderService.LABEL_START, "label" to label)
        }.also { ui.postDelayed(it, 5000) }
    }

    private fun endLabel() {
        pendingStart?.let { ui.removeCallbacks(it) }
        pendingStart = null
        if (RecorderService.running) { send(RecorderService.LABEL_END); tone.startTone(ToneGenerator.TONE_PROP_BEEP2) }
    }

    private fun stopSession() {
        pendingStart?.let { ui.removeCallbacks(it) }
        send(RecorderService.STOP)
    }

    private fun shareLast() {
        if (RecorderService.running) return toast("Stop the session first")
        val root = File(getExternalFilesDir(null), "sessions")
        val dir = root.listFiles { f -> f.isDirectory }?.maxByOrNull { it.lastModified() }
            ?: return toast("No session found")
        val zip = File(root, dir.name + ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            dir.listFiles()?.forEach { f ->
                z.putNextEntry(ZipEntry(dir.name + "/" + f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share session"))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
