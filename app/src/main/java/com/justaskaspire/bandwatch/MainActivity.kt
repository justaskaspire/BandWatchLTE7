package com.justaskaspire.bandwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    private lateinit var towerValue: TextView
    private lateinit var signalGraph: SignalGraphView
    private lateinit var earfcnValue: TextView
    private lateinit var rsrpValue: TextView
    private lateinit var rsrqValue: TextView
    private lateinit var sinrValue: TextView
    private lateinit var speedValue: TextView
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
        metrics.addView(spaceHorizontal(7))
        metrics.addView(metricCard("RSRQ", "—", "dB", cyan), metricWeight())
        metrics.addView(spaceHorizontal(7))
        metrics.addView(metricCard("SINR", "—", "dB", green), metricWeight())
        root.addView(metrics)

        root.addView(space(7))
        val graphCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(8))
            background = rounded(panel, 16, Color.rgb(37, 67, 105), 1)
        }
        val graphHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        graphHeader.addView(label("SIGNAL HISTORY", 9f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        graphHeader.addView(label("10 MIN", 9f, cyan, true))
        graphCard.addView(graphHeader)
        signalGraph = SignalGraphView()
        graphCard.addView(signalGraph, LinearLayout.LayoutParams(-1, dp(105)))
        root.addView(graphCard)

        root.addView(space(7))
        val towerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = rounded(panel, 15)
        }
        towerCard.addView(label("CELL TOWER", 9f, muted, true))
        towerValue = label("Cell ID: —   PCI: —   TAC: —", 11f, Color.WHITE, true).apply {
            setPadding(0, dp(6), 0, 0)
        }
        towerCard.addView(towerValue)
        root.addView(towerCard)

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
        val pci = prefs.getInt("tower_pci", Int.MAX_VALUE)
        val tac = prefs.getInt("tower_tac", Int.MAX_VALUE)
        towerValue.text = "Cell ID: ${metric(ci)}   PCI: ${metric(pci)}   TAC: ${metric(tac)}"
        if (::signalGraph.isInitialized) signalGraph.invalidate()
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

    private inner class SignalGraphView : View(this@MainActivity) {
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(47, 65, 91)
            strokeWidth = dp(1).toFloat()
            style = Paint.Style.STROKE
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan
            strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(32, 62, 220, 255)
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = sp(9f)
            typeface = Typeface.DEFAULT
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(34).toFloat()
            val right = width - dp(7).toFloat()
            val top = dp(12).toFloat()
            val bottom = height - dp(20).toFloat()
            if (right <= left || bottom <= top) return
            for (i in 0..3) {
                val y = top + (bottom - top) * i / 3f
                canvas.drawLine(left, y, right, y, gridPaint)
            }
            val samples = try { JSONArray(prefs.getString("signal_samples", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            val values = mutableListOf<Int>()
            for (i in 0 until samples.length()) {
                val value = samples.optJSONObject(i)?.optInt("rsrp", Int.MAX_VALUE) ?: Int.MAX_VALUE
                if (value != Int.MAX_VALUE && value != Int.MIN_VALUE) values.add(value)
            }
            if (values.isEmpty()) {
                canvas.drawText("Waiting for signal readings…", left, (top + bottom) / 2f, textPaint)
                return
            }
            val minValue = -125f
            val maxValue = -65f
            val path = Path()
            val step = if (values.size > 1) (right - left) / (values.size - 1) else 0f
            values.forEachIndexed { index, value ->
                val clamped = value.toFloat().coerceIn(minValue, maxValue)
                val y = bottom - ((clamped - minValue) / (maxValue - minValue)) * (bottom - top)
                val x = left + step * index
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fill = Path(path).apply {
                lineTo(left + step * (values.size - 1), bottom)
                lineTo(left, bottom)
                close()
            }
            canvas.drawPath(fill, fillPaint)
            canvas.drawPath(path, linePaint)
            canvas.drawText("-65", 0f, top + dp(3), textPaint)
            canvas.drawText("-125", 0f, bottom, textPaint)
            canvas.drawText("OLD", left, height.toFloat() - dp(3), textPaint)
            canvas.drawText("NOW", right - dp(22), height.toFloat() - dp(3), textPaint)
        }
    }

    private fun metricCard(title: String, initial: String, unit: String, accent: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(11), dp(7), dp(10))
            background = rounded(panel, 15)
        }
        card.addView(label(title, 9f, muted, true))
        val value = label(initial, 20f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, 0) }
        card.addView(value)
        card.addView(label(unit, 9f, accent, true).apply { setPadding(0, dp(1), 0, 0) })
        when (title) {
            "RSRP" -> rsrpValue = value
            "RSRQ" -> rsrqValue = value
            "SINR" -> sinrValue = value
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
    private fun metricWeight() = LinearLayout.LayoutParams(0, dp(66), 1f)
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
