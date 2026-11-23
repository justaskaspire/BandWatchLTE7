package com.justaskaspire.bandwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("bandwatch", MODE_PRIVATE) }
    private lateinit var pageHost: FrameLayout
    private lateinit var navItems: List<LinearLayout>
    private lateinit var bandValue: TextView
    private lateinit var simValue: TextView
    private lateinit var cellIdValue: TextView
    private lateinit var earfcnValue: TextView
    private lateinit var rsrpValue: TextView
    private lateinit var rsrqValue: TextView
    private lateinit var sinrValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var downloadResultValue: TextView
    private lateinit var uploadResultValue: TextView
    private lateinit var speedTestStatus: TextView
    private lateinit var speedTestButton: Button
    @Volatile private var speedTestRunning = false
    private var selectedTab = 0
    private var previousRxBytes: Long? = null
    private var previousTxBytes: Long? = null
    private var previousSpeedSampleAt = 0L

    private val bg = Color.rgb(7, 12, 25)
    private val panel = Color.rgb(16, 25, 45)
    private val muted = Color.rgb(151, 169, 196)
    private val blue = Color.rgb(45, 139, 255)
    private val cyan = Color.rgb(62, 220, 255)
    private val green = Color.rgb(61, 226, 158)
    private val band7Green = Color.rgb(45, 214, 130)
    private val band3Blue = Color.rgb(65, 145, 255)

    private val dashboardRefresh = object : Runnable {
        override fun run() {
            if (::bandValue.isInitialized && selectedTab == 0 && !isFinishing) {
                refresh()
                bandValue.postDelayed(this, 2500)
            }
        }
    }
    private val speedRefresh = object : Runnable {
        override fun run() {
            if (::speedValue.isInitialized && selectedTab == 0 && !isFinishing) {
                refreshSpeed()
                speedValue.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShell()
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        if (selectedTab == 0 && ::bandValue.isInitialized) {
            bandValue.removeCallbacks(dashboardRefresh)
            dashboardRefresh.run()
        }
        if (selectedTab == 0 && ::speedValue.isInitialized) {
            speedValue.removeCallbacks(speedRefresh)
            speedRefresh.run()
        }
    }

    override fun onPause() {
        if (::bandValue.isInitialized) bandValue.removeCallbacks(dashboardRefresh)
        if (::speedValue.isInitialized) speedValue.removeCallbacks(speedRefresh)
        super.onPause()
    }

    private fun buildShell() {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        // Android 15 enforces edge-to-edge for target SDK 35. Apply system-bar
        // insets to the shell so the header and bottom navigation stay visible.
        shell.setOnApplyWindowInsetsListener { view, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                topInset = bars.top
                bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    topInset = insets.systemWindowInsetTop
                    bottomInset = insets.systemWindowInsetBottom
                }
            }
            view.setPadding(0, topInset, 0, bottomInset)
            insets
        }
        pageHost = FrameLayout(this)
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setBackgroundColor(Color.rgb(11, 19, 36))
            elevation = dp(14).toFloat()
        }
        val items = mutableListOf<LinearLayout>()
        listOf("Dashboard", "History", "Settings").forEachIndexed { index, title ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // Give the icon and label enough vertical room so the selected
                // tab title cannot be clipped on smaller Android screens.
                setPadding(0, dp(4), 0, dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { showTab(index) }
            }
            val symbols = listOf("▦", "◷", "⚙")
            item.addView(label(symbols[index], 22f, muted, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            item.addView(label(title, 11f, muted, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, dp(2), 0, 0)
            })
            nav.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
            items.add(item)
        }
        navItems = items
        shell.addView(nav, LinearLayout.LayoutParams(-1, dp(78)))
        setContentView(shell)
    }

    private fun showTab(tab: Int) {
        if (::bandValue.isInitialized) bandValue.removeCallbacks(dashboardRefresh)
        if (::speedValue.isInitialized) speedValue.removeCallbacks(speedRefresh)
        selectedTab = tab
        pageHost.removeAllViews()
        pageHost.addView(when (tab) {
            0 -> buildDashboardPage()
            1 -> buildHistoryPage()
            else -> buildSettingsPage()
        })
        updateNavigation()
        if (tab == 0) {
            refresh()
            dashboardRefresh.run()
            speedRefresh.run()
        }
    }

    private fun updateNavigation() {
        navItems.forEachIndexed { index, item ->
            val active = index == selectedTab
            item.background = rounded(
                if (active) Color.rgb(19, 47, 68) else Color.TRANSPARENT,
                17,
                if (active) Color.rgb(35, 91, 126) else null,
                1
            )
            (item.getChildAt(0) as TextView).apply {
                setTextColor(if (active) cyan else Color.rgb(139, 157, 184))
                textSize = if (active) 22f else 21f
            }
            (item.getChildAt(1) as TextView).apply {
                setTextColor(if (active) Color.WHITE else Color.rgb(139, 157, 184))
                textSize = if (active) 11f else 10.5f
            }
        }
    }

    private fun buildDashboardPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(6))
            setBackgroundColor(bg)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label("BANDWATCH", 13f, cyan, true), LinearLayout.LayoutParams(0, -2, 1f))
        simValue = label("SIM: detecting…", 10f, muted, true)
        top.addView(simValue)
        root.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(9), dp(15), dp(9))
            background = rounded(panel, 17, Color.rgb(34, 64, 105), 1)
        }
        val bandColumn = column()
        bandColumn.addView(label("LTE BAND", 9f, muted, true))
        bandValue = label("—", 37f, Color.WHITE, true).apply {
            includeFontPadding = false
        }
        bandColumn.addView(bandValue)
        hero.addView(bandColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val earfcnColumn = column().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        earfcnColumn.addView(label("EARFCN", 9f, muted, true).apply { gravity = Gravity.END })
        earfcnValue = label("—", 15f, Color.WHITE, true).apply { gravity = Gravity.END; setPadding(0, dp(3), 0, 0) }
        earfcnColumn.addView(earfcnValue)
        hero.addView(earfcnColumn)
        root.addView(hero)

        root.addView(space(7))
        val speedCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(panel, 14)
        }
        val speedLabels = column()
        speedLabels.addView(label("NETWORK SPEED", 9f, muted, true))
        speedValue = label("0 KB/s", 20f, cyan, true).apply {
            setPadding(0, dp(1), 0, 0)
            includeFontPadding = false
        }
        speedLabels.addView(speedValue)
        speedCard.addView(speedLabels)
        root.addView(speedCard)

        root.addView(label("SIGNAL", 9f, muted, true).apply { setPadding(0, dp(8), 0, dp(4)) })
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(metricCard("RSRP", "—", "dBm", blue), metricWeight())
        metrics.addView(spaceHorizontal(5))
        metrics.addView(metricCard("RSRQ", "—", "dB", cyan), metricWeight())
        metrics.addView(spaceHorizontal(5))
        metrics.addView(metricCard("SINR", "—", "dB", green), metricWeight())
        metrics.addView(spaceHorizontal(5))
        metrics.addView(metricCard("CELL ID", "—", "", band3Blue), metricWeight())
        root.addView(metrics)

        root.addView(space(9))
        val testCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panel, 15)
        }
        testCard.addView(label("INTERNET SPEED TEST", 10f, muted, true))
        val resultsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(8))
        }
        val downloadColumn = column()
        downloadColumn.addView(label("↓ DOWNLOAD", 9f, muted, true))
        downloadResultValue = label("— Mbps", 20f, cyan, true).apply { setPadding(0, dp(3), 0, 0) }
        downloadColumn.addView(downloadResultValue)
        resultsRow.addView(downloadColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val uploadColumn = column()
        uploadColumn.addView(label("↑ UPLOAD", 9f, muted, true))
        uploadResultValue = label("— Mbps", 20f, green, true).apply { setPadding(0, dp(3), 0, 0) }
        uploadColumn.addView(uploadResultValue)
        resultsRow.addView(uploadColumn, LinearLayout.LayoutParams(0, -2, 1f))
        testCard.addView(resultsRow)
        val testActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speedTestButton = actionButton("START TEST", blue) { startSpeedTest() }
        testActions.addView(speedTestButton, LinearLayout.LayoutParams(0, dp(39), 1f))
        testCard.addView(testActions)
        speedTestStatus = label("Uses about 7 MB per test • Current internet connection", 9f, muted, false)
        speedTestStatus.setPadding(0, dp(7), 0, 0)
        testCard.addView(speedTestStatus)
        root.addView(testCard)

        root.addView(space(8))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("START", blue) { requestAndStart() }, LinearLayout.LayoutParams(0, dp(40), 1.4f))
        actions.addView(spaceHorizontal(8))
        actions.addView(actionButton("STOP", Color.rgb(35, 48, 70)) {
            stopService(Intent(this@MainActivity, LteMonitorService::class.java))
            Toast.makeText(this@MainActivity, "Monitoring stopped", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(40), 0.8f))
        root.addView(actions)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        scroll.addView(root)
        return scroll
    }

    private fun buildHistoryPage(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        scroll.addView(root)
        root.addView(label("BANDWATCH", 12f, cyan, true))
        root.addView(label("Connection history", 24f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, dp(4)) })
        root.addView(label("Detected switches between LTE Band 3 and Band 7.", 11f, muted, false).apply { setPadding(0, 0, 0, dp(14)) })
        val entries = try { JSONArray(prefs.getString("history_json", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if (entries.length() == 0) {
            root.addView(infoCard("No band switches recorded yet",
                "Start LTE monitoring. BandWatch records each detected change between Band 3 and Band 7."))
        } else {
            val dateFormat = SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.getDefault())
            val now = System.currentTimeMillis()
            for (i in entries.length() - 1 downTo 0) {
                val item = entries.optJSONObject(i) ?: continue
                val band = item.optString("band", "LTE")
                val timestamp = item.optLong("timestamp", now).coerceIn(0L, now)
                val nextItem = if (i < entries.length() - 1) entries.optJSONObject(i + 1) else null
                val nextTimestamp = nextItem?.optLong("timestamp", timestamp) ?: now
                val duration = formatDuration((nextTimestamp - timestamp).coerceAtLeast(0L))
                val isBand7 = band == "Band 7"
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = rounded(panel, 17)
                }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label(if (isBand7) "BAND 7" else if (band == "Band 3") "BAND 3" else band.uppercase(Locale.getDefault()),
                    17f, if (isBand7) cyan else blue, true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label(duration, 11f, green, true))
                card.addView(row)
                card.addView(label(dateFormat.format(Date(timestamp)), 11f, muted, false).apply { setPadding(0, dp(6), 0, dp(8)) })
                card.addView(label("EARFCN ${item.optInt("earfcn", -1)}  •  RSRP ${historyMetric(item, "rsrp")} dBm", 11f, Color.WHITE, false))
                card.addView(label("RSRQ ${historyMetric(item, "rsrq")} dB  •  SINR ${historyMetric(item, "sinr")} dB", 11f, muted, false).apply { setPadding(0, dp(4), 0, 0) })
                root.addView(card)
                root.addView(space(8))
            }
        }
        return scroll
    }

    private fun buildSettingsPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(bg)
        }
        root.addView(label("BANDWATCH", 12f, cyan, true))
        root.addView(label("Settings", 25f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, dp(15)) })
        val speedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            background = rounded(panel, 18)
        }
        val settingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val settingText = column()
        settingText.addView(label("Status-bar speed indicator", 14f, Color.WHITE, true))
        settingText.addView(label("Show live mobile download + upload speed.", 10f, muted, false).apply { setPadding(0, dp(4), 0, 0) })
        settingRow.addView(settingText, LinearLayout.LayoutParams(0, -2, 1f))
        settingRow.addView(Switch(this).apply {
            isChecked = prefs.getBoolean("speed_indicator_enabled", true)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("speed_indicator_enabled", enabled).apply()
                previousRxBytes = null
                previousTxBytes = null
                previousSpeedSampleAt = 0L
                // The running service reads this preference on its next poll.
                // Do not start monitoring just because the display option changed.
            }
        })
        speedCard.addView(settingRow)
        root.addView(speedCard)
        root.addView(space(10))
        root.addView(infoCard("About the speed reading",
            "BandWatch combines mobile download (RX) and upload (TX) traffic. It refreshes about once per second and does not include Wi-Fi traffic."))
        root.addView(space(10))
        root.addView(infoCard("LTE monitoring",
            "Band 3 / Band 7 detection and Band 7 alerts run in the background. Keep Phone permission and Location Services enabled."))
        return root
    }

    private fun startSpeedTest() {
        if (speedTestRunning) return
        speedTestRunning = true
        speedTestButton.isEnabled = false
        speedTestButton.text = "TESTING…"
        speedTestStatus.text = "Measuring download speed…"
        Thread {
            try {
                val result = SpeedTestRunner.run { stage ->
                    runOnUiThread {
                        if (!isFinishing && ::speedTestStatus.isInitialized) {
                            speedTestStatus.text = when (stage) {
                                SpeedTestRunner.Stage.DOWNLOAD -> "Measuring download speed…"
                                SpeedTestRunner.Stage.UPLOAD -> "Measuring upload speed…"
                            }
                        }
                    }
                }
                runOnUiThread {
                    if (!isFinishing && ::downloadResultValue.isInitialized) {
                        downloadResultValue.text = String.format(Locale.US, "%.1f Mbps", result.downloadMbps)
                        uploadResultValue.text = String.format(Locale.US, "%.1f Mbps", result.uploadMbps)
                        speedTestStatus.text = "Test complete • Approx. 7 MB used"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && ::speedTestStatus.isInitialized) {
                        speedTestStatus.text = "Test failed: ${error.message ?: "Check your connection"}"
                    }
                }
            } finally {
                runOnUiThread {
                    speedTestRunning = false
                    if (!isFinishing && ::speedTestButton.isInitialized) {
                        speedTestButton.isEnabled = true
                        speedTestButton.text = "TEST AGAIN"
                    }
                }
            }
        }.start()
    }

    private fun refreshSpeed() {
        if (!prefs.getBoolean("speed_indicator_enabled", true)) {
            speedValue.text = "Speed indicator disabled"
            previousRxBytes = null; previousTxBytes = null; previousSpeedSampleAt = 0L
            return
        }
        val rxBytes = TrafficStats.getMobileRxBytes()
        val txBytes = TrafficStats.getMobileTxBytes()
        if (rxBytes < 0L || txBytes < 0L) {
            previousRxBytes = null; previousTxBytes = null; previousSpeedSampleAt = 0L
            speedValue.text = "— KB/s"
            return
        }
        val now = SystemClock.elapsedRealtime()
        val oldRx = previousRxBytes
        val oldTx = previousTxBytes
        val elapsed = now - previousSpeedSampleAt
        val bytesPerSecond = if (oldRx == null || oldTx == null || previousSpeedSampleAt == 0L) 0L
        else NetworkSpeed.combinedBytesPerSecond((rxBytes - oldRx).coerceAtLeast(0L),
            (txBytes - oldTx).coerceAtLeast(0L), elapsed)
        previousRxBytes = rxBytes; previousTxBytes = txBytes; previousSpeedSampleAt = now
        speedValue.text = NetworkSpeed.format(bytesPerSecond)
    }

    private fun refresh() {
        val band = prefs.getString("band", "Waiting for LTE data") ?: "Waiting for LTE data"
        val earfcn = prefs.getInt("earfcn", -1)
        val rsrp = prefs.getInt("rsrp", Int.MAX_VALUE)
        val rsrq = prefs.getInt("rsrq", Int.MAX_VALUE)
        val sinr = prefs.getInt("sinr", Int.MAX_VALUE)
        val active = band == "Band 3" || band == "Band 7"
        val accent = if (band == "Band 7") band7Green else band3Blue
        bandValue.text = when (band) { "Band 3" -> "B3"; "Band 7" -> "B7"; else -> "—" }
        bandValue.setTextColor(accent)
        simValue.text = prefs.getString("active_sim_label", "SIM not identified") ?: "SIM not identified"
        earfcnValue.text = if (earfcn >= 0) earfcn.toString() else "—"
        val ci = prefs.getInt("tower_ci", Int.MAX_VALUE)
        cellIdValue.text = metric(ci)
        rsrpValue.text = metric(rsrp); rsrqValue.text = metric(rsrq); sinrValue.text = metric(sinr)
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

    private fun metricCard(title: String, initial: String, unit: String, accent: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(11), dp(7), dp(10))
            background = rounded(panel, 15)
        }
        card.addView(label(title, 9f, muted, true))
        val valueSize = if (title == "CELL ID") 12f else 16f
        val value = label(initial, valueSize, Color.WHITE, true).apply { setPadding(0, dp(5), 0, 0); includeFontPadding = false }
        card.addView(value)
        if (unit.isNotBlank()) card.addView(label(unit, 8f, accent, true).apply { setPadding(0, dp(2), 0, 0) })
        when (title) {
            "RSRP" -> rsrpValue = value
            "RSRQ" -> rsrqValue = value
            "SINR" -> sinrValue = value
            "CELL ID" -> cellIdValue = value
        }
        return card
    }

    private fun infoCard(title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(panel, 17)
        addView(label(title, 14f, Color.WHITE, true))
        addView(label(body, 11f, muted, false).apply { setPadding(0, dp(7), 0, 0) })
    }

    private fun actionButton(text: String, color: Int, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 11f
        setTextColor(if (color == blue) Color.WHITE else muted)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color, 14)
        setOnClickListener { action() }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun pill(text: String, color: Int) = TextView(this).apply {
        this.text = text; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(color); setPadding(dp(9), dp(6), dp(9), dp(6))
        background = rounded(Color.rgb(20, 49, 55), 30)
    }
    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(strokeWidth), strokeColor)
        }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spaceHorizontal(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun metricWeight() = LinearLayout.LayoutParams(0, dp(62), 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
    private fun metric(value: Int) = if (value == Int.MAX_VALUE) "—" else value.toString()
    private fun historyMetric(item: JSONObject, key: String): String {
        val value = item.optInt(key, Int.MAX_VALUE)
        return if (value == Int.MAX_VALUE) "—" else value.toString()
    }
    private fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }
}
