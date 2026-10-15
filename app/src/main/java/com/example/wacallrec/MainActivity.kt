package com.example.wacallrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val info = TextView(this).apply {
            text = "1) Put the scrcpy server file at /sdcard/Download/scrcpy-server.jar\n" +
                "2) Enter its exact scrcpy version below\n" +
                "3) Start a WhatsApp call, then tap Start. Tap Stop before or after hanging up."
        }
        val ver = EditText(this).apply { setText("3.3.3"); hint = "scrcpy version" }
        val asRoot = Switch(this).apply { text = "Run server as root (default: shell uid 2000)" }
        val start = Button(this).apply { text = "Start recording" }
        val stop = Button(this).apply { text = "Stop" }
        statusView = TextView(this).apply { setPadding(0, pad, 0, 0) }

        start.setOnClickListener {
            startForegroundService(
                Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_VERSION, ver.text.toString().trim())
                    .putExtra(RecorderService.EXTRA_ASROOT, asRoot.isChecked)
            )
        }
        stop.setOnClickListener {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        }
        listOf(info, ver, asRoot, start, stop, statusView).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })

        h.post(object : Runnable {
            override fun run() {
                statusView.text = RecorderService.status
                h.postDelayed(this, 1000)
            }
        })
    }
}
