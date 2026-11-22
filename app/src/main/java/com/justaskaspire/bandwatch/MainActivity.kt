package com.justaskaspire.bandwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var heading: TextView
    private lateinit var details: TextView
    private val prefs by lazy { getSharedPreferences("bandwatch", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFF101820.toInt())
        }
        heading = TextView(this).apply { textSize = 30f; setTextColor(0xFFFFFFFF.toInt()) }
        details = TextView(this).apply { textSize = 17f; setTextColor(0xFFD4E1EA.toInt()); setPadding(0, 24, 0, 24) }
        root.addView(heading)
        root.addView(details)
        root.addView(Button(this).apply { text = "Start LTE monitoring"; setOnClickListener { requestAndStart() } })
        root.addView(Button(this).apply {
            text = "Stop monitoring"
            setOnClickListener { stopService(Intent(this@MainActivity, LteMonitorService::class.java)) }
        })
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        heading.postDelayed(object : Runnable {
            override fun run() {
                if (!isFinishing) { refresh(); heading.postDelayed(this, 2500) }
            }
        }, 2500)
    }

    private fun requestAndStart() {
        val required = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startMonitor() else requestPermissions(missing.toTypedArray(), 41)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            val ok = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (ok) startMonitor() else Toast.makeText(this, "Allow Location and Phone permissions to read LTE data.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMonitor() {
        val intent = Intent(this, LteMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "LTE monitoring started", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val band = prefs.getString("band", "Waiting for LTE data") ?: "Waiting for LTE data"
        val earfcn = prefs.getInt("earfcn", -1)
        val rsrp = prefs.getInt("rsrp", Int.MAX_VALUE)
        val rsrq = prefs.getInt("rsrq", Int.MAX_VALUE)
        val sinr = prefs.getInt("sinr", Int.MAX_VALUE)
        heading.text = band
        details.text = "EARFCN: ${if (earfcn >= 0) earfcn else "—"}\n" +
            "RSRP: ${metric(rsrp)} dBm\nRSRQ: ${metric(rsrq)} dB\nSINR: ${metric(sinr)} dB\n\n" +
            "Updated: ${prefs.getString("updated", "Not started")}\n\n" +
            "Keep Location Services enabled. Some phones or carriers may restrict cell readings."
    }

    private fun metric(value: Int) = if (value == Int.MAX_VALUE) "—" else value.toString()
}