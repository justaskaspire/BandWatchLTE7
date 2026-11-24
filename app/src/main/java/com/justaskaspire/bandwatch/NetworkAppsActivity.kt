package com.justaskaspire.bandwatch

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.TimeUnit

/**
 * Small notification-launched view. Android does not expose live per-UID
 * traffic to ordinary apps, so this pairs a foreground-app signal with
 * device-wide mobile traffic movement. It is explicitly presented as an
 * approximation rather than claiming packet-level app attribution.
 */
class NetworkAppsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var previousRx = -1L
    private var previousTx = -1L
    private var previousAt = 0L

    private val refreshTask = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                refreshCurrentApp()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
            attributes = attributes.apply {
                width = (resources.displayMetrics.widthPixels * 0.90f).toInt()
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.CENTER
            }
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        refreshTask.run()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildUi() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(Color.rgb(10, 18, 34), 22, Color.rgb(42, 86, 125))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "NETWORK APPS"
            textSize = 14f
            setTextColor(Color.rgb(87, 220, 255))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(29, 43, 65), 30)
            setOnClickListener { finish() }
            contentDescription = "Close"
        }
        header.addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))
        content.addView(header)

        val hint = TextView(this).apply {
            text = "Apps active while mobile data is moving"
            textSize = 11f
            setTextColor(Color.rgb(151, 169, 196))
            setPadding(0, dp(5), 0, dp(16))
        }
        content.addView(hint)

        if (!hasUsageAccess()) {
            showUsageAccessPrompt()
        } else {
            refreshCurrentApp()
        }
        setContentView(content)
    }

    private fun showUsageAccessPrompt() {
        val message = TextView(this).apply {
            text = "Allow Usage Access so BandWatch can identify which app is currently on screen. Android still limits exact per-app live traffic detection."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(14))
        }
        content.addView(message)
        content.addView(actionButton("ENABLE USAGE ACCESS") {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        })
    }

    private fun refreshCurrentApp() {
        if (!::content.isInitialized || !hasUsageAccess()) return
        val appPackage = currentForegroundPackage()
        val moving = mobileTrafficMoving()
        content.removeViews(2, (content.childCount - 2).coerceAtLeast(0))

        if (moving && appPackage != null && appPackage != packageName) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(Color.rgb(17, 31, 51), 15, Color.rgb(34, 75, 103))
            }
            val icon = ImageView(this)
            try {
                icon.setImageDrawable(packageManager.getApplicationIcon(appPackage))
            } catch (_: Exception) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(appPackage, 0)).toString()
            } catch (_: Exception) {
                appPackage
            }
            val name = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), 0, 0, 0)
            }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(row)
            content.addView(TextView(this).apply {
                text = "Foreground app • device-wide traffic detected"
                textSize = 10f
                setTextColor(Color.rgb(151, 169, 196))
                setPadding(0, dp(10), 0, 0)
            })
        } else {
            val empty = TextView(this).apply {
                text = if (moving) "No foreground app identified" else "No current mobile data activity"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(190, 204, 224))
                setPadding(dp(8), dp(24), dp(8), dp(24))
                background = rounded(Color.rgb(17, 31, 51), 15)
            }
            content.addView(empty)
        }

        content.addView(TextView(this).apply {
            text = "Note: Android does not let BandWatch verify which app owns each live data packet. This is a best-effort foreground-app match."
            textSize = 9f
            setTextColor(Color.rgb(126, 145, 173))
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun currentForegroundPackage(): String? {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - TimeUnit.SECONDS.toMillis(30), now)
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val resumed = if (Build.VERSION.SDK_INT >= 29) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val paused = if (Build.VERSION.SDK_INT >= 29) {
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            }
            if (resumed) foreground = event.packageName
            if (paused && foreground == event.packageName) foreground = null
        }
        return foreground
    }

    private fun mobileTrafficMoving(): Boolean {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx < 0L || tx < 0L) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val active = previousRx >= 0L && previousTx >= 0L &&
            now - previousAt in 1L..3000L &&
            ((rx - previousRx).coerceAtLeast(0L) + (tx - previousTx).coerceAtLeast(0L) >= 1024L)
        previousRx = rx
        previousTx = tx
        previousAt = now
        return active
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.WHITE)
        isAllCaps = true
        background = rounded(Color.rgb(28, 112, 175), 12)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
