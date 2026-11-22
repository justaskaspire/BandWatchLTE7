package com.justaskaspire.bandwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("bandwatch", MODE_PRIVATE) }
    private lateinit var bandValue: TextView
    private lateinit var bandCaption: TextView
    private lateinit var earfcnValue: TextView
    private lateinit var rsrpValue: TextView
    private lateinit var rsrqValue: TextView
    private lateinit var sinrValue: TextView
    private lateinit var updatedValue: TextView
    private lateinit var stateValue: TextView
    private lateinit var startButton: Button
    private val bg = Color.rgb(7, 12, 25)
    private val panel = Color.rgb(16, 25, 45)
    private val muted = Color.rgb(151, 169, 196)
    private val blue = Color.rgb(45, 139, 255)
    private val cyan = Color.rgb(62, 220, 255)
    private val green = Color.rgb(61, 226, 158)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildDashboard()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::bandValue.isInitialized) refresh()
        bandValue.postDelayed(object : Runnable {
            override fun run() {
                if (!isFinishing) {
                    refresh()
                    bandValue.postDelayed(this, 2500)
                }
            }
        }, 2500)
    }

    private fun buildDashboard() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = column()
        brand.addView(label("BANDWATCH", 13f, cyan, true))
        brand.addView(label("LTE NETWORK MONITOR", 11f, muted, false).apply { setPadding(0, dp(4), 0, 0) })
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(pill("LIVE MONITOR", green))
        root.addView(top)
        root.addView(space(26))

        root.addView(label("Your network", 14f, muted, true))
        root.addView(label("Connection overview", 25f, Color.WHITE, true).apply { setPadding(0, dp(5), 0, dp(16)) })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = rounded(panel, 24, Color.rgb(34, 64, 105), 1)
        }
        val heroTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroTop.addView(label("CONNECTED BAND", 11f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        stateValue = label("●  WAITING", 11f, cyan, true)
        heroTop.addView(stateValue)
        hero.addView(heroTop)
        bandValue = label("—", 54f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, 0) }
        hero.addView(bandValue)
        bandCaption = label("Start monitoring to detect your LTE band", 13f, muted, false).apply { setPadding(0, dp(2), 0, dp(18)) }
        hero.addView(bandCaption)
        val divider = View(this).apply { setBackgroundColor(Color.rgb(40, 57, 82)) }
        hero.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
        val earfcnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        earfcnRow.addView(label("EARFCN", 12f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        earfcnValue = label("—", 15f, Color.WHITE, true)
        earfcnRow.addView(earfcnValue)
        hero.addView(earfcnRow)
        root.addView(hero)

        root.addView(space(24))
        root.addView(label("SIGNAL METRICS", 12f, muted, true))
        root.addView(space(12))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(metricCard("RSRP", "—", "dBm", blue), weighted())
        row1.addView(spaceHorizontal(12))
        row1.addView(metricCard("RSRQ", "—", "dB", cyan), weighted())
        root.addView(row1)
        root.addView(space(12))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(metricCard("SINR", "—", "dB", green), weighted())
        row2.addView(spaceHorizontal(12))
        row2.addView(metricCard("STATUS", "—", "network", Color.rgb(184, 145, 255)), weighted())
        root.addView(row2)

        root.addView(space(24))
        val updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(panel, 18)
        }
        updateCard.addView(label("LAST UPDATE", 10f, muted, true))
        updatedValue = label("Not started", 14f, Color.WHITE, true).apply { setPadding(0, dp(6), 0, 0) }
        updateCard.addView(updatedValue)
        root.addView(updateCard)

        root.addView(space(22))
        startButton = Button(this).apply {
            text = "START LTE MONITORING"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(blue, 16)
            setOnClickListener { requestAndStart() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(space(10))
        val stop = Button(this).apply {
            text = "Stop monitoring"
            isAllCaps = false
            textSize = 13f
            setTextColor(muted)
            background = rounded(Color.rgb(24, 34, 55), 14)
            setOnClickListener {
                stopService(Intent(this@MainActivity, LteMonitorService::class.java))
                Toast.makeText(this@MainActivity, "Monitoring stopped", Toast.LENGTH_SHORT).show()
                stateValue.text = "●  STOPPED"
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(space(20))
        root.addView(label("Band 7 alerts are handled by the background monitoring service.", 11f, muted, false))
        root.addView(label("Keep Location Services enabled for LTE cell readings.", 11f, muted, false).apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun metricCard(title: String, initial: String, unit: String, accent: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(16))
            background = rounded(panel, 18)
        }
        card.addView(label(title, 10f, muted, true))
        val value = label(initial, 28f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, 0) }
        card.addView(value)
        card.addView(label(unit, 11f, accent, true).apply { setPadding(0, dp(1), 0, 0) })
        when (title) {
            "RSRP" -> rsrpValue = value
            "RSRQ" -> rsrqValue = value
            "SINR" -> sinrValue = value
            "STATUS" -> stateMetricValue = value
        }
        return card
    }

    private lateinit var stateMetricValue: TextView

    private fun refresh() {
        val band = prefs.getString("band", "Waiting for LTE data") ?: "Waiting for LTE data"
        val earfcn = prefs.getInt("earfcn", -1)
        val rsrp = prefs.getInt("rsrp", Int.MAX_VALUE)
        val rsrq = prefs.getInt("rsrq", Int.MAX_VALUE)
        val sinr = prefs.getInt("sinr", Int.MAX_VALUE)
        val active = band == "Band 3" || band == "Band 7"
        val accent = if (band == "Band 7") cyan else blue
        bandValue.text = when (band) {
            "Band 3" -> "B3"
            "Band 7" -> "B7"
            else -> "—"
        }
        bandValue.setTextColor(accent)
        bandCaption.text = when (band) {
            "Band 3" -> "LTE 1800 MHz • detected from EARFCN"
            "Band 7" -> "LTE 2600 MHz • detected from EARFCN"
            else -> band
        }
        stateValue.text = if (active) "●  CONNECTED" else "●  WAITING"
        stateValue.setTextColor(if (active) green else cyan)
        earfcnValue.text = if (earfcn >= 0) earfcn.toString() else "—"
        rsrpValue.text = metric(rsrp)
        rsrqValue.text = metric(rsrq)
        sinrValue.text = metric(sinr)
        stateMetricValue.text = if (active) "LIVE" else "WAIT"
        updatedValue.text = prefs.getString("updated", "Not started") ?: "Not started"
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
        stateValue.text = "●  STARTING"
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun pill(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = rounded(Color.rgb(20, 49, 55), 30)
    }
    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(strokeWidth), strokeColor)
        }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spaceHorizontal(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(112), 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun metric(value: Int) = if (value == Int.MAX_VALUE) "—" else value.toString()
}
