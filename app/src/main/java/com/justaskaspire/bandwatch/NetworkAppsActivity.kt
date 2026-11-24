package com.justaskaspire.bandwatch

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Best-available per-app traffic view using Android's NetworkStatsManager.
 * Android records usage in accounting buckets, so refresh speed and precision
 * depend on the device's system stats updates; this is not packet capture.
 */
class NetworkAppsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var totals: TextView
    private lateinit var status: TextView
    private var previous = emptyMap<Int, Pair<Long, Long>>()
    private var lastQueryAt = 0L
    private var queryInFlight = false
    private var firstSnapshot = true

    private val refreshTask = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                collectTraffic()
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
                width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
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

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(Color.rgb(13, 19, 31), 22, Color.rgb(48, 77, 106))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "BandWatch"
            textSize = 12f
            setTextColor(Color.rgb(92, 218, 255))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(35, 47, 65), 30)
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(28), dp(28)))
        content.addView(header)

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)
        setContentView(content)

        if (!hasUsageAccess()) showUsageAccessPrompt()
    }

    private fun showUsageAccessPrompt() {
        rows.removeAllViews()
        rows.addView(TextView(this).apply {
            text = "Enable Usage Access to let BandWatch read Android's per-app network accounting. This permission is required by Android."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        })
        rows.addView(Button(this).apply {
            text = "ENABLE USAGE ACCESS"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        })
    }

    private fun collectTraffic() {
        if (!hasUsageAccess()) {
            showUsageAccessPrompt()
            return
        }
        if (queryInFlight) return
        queryInFlight = true
        worker.execute {
            val now = System.currentTimeMillis()
            val snapshot = try {
                readUidTotals(now - TimeUnit.HOURS.toMillis(24), now)
            } catch (e: Exception) {
                null
            }
            val sampleTime = android.os.SystemClock.elapsedRealtime()
            val old = previous
            val elapsed = if (lastQueryAt == 0L) 0L else sampleTime - lastQueryAt
            val rates = if (snapshot != null && elapsed in 500L..30000L) {
                snapshot.mapNotNull { (uid, bytes) ->
                    val before = old[uid] ?: return@mapNotNull null
                    val rx = (bytes.first - before.first).coerceAtLeast(0L)
                    val tx = (bytes.second - before.second).coerceAtLeast(0L)
                    val rxRate = rx * 1000L / elapsed
                    val txRate = tx * 1000L / elapsed
                    if (rxRate + txRate > 0L) uid to (rxRate to txRate) else null
                }.toMap()
            } else emptyMap()
            if (snapshot != null) {
                previous = snapshot
                lastQueryAt = sampleTime
            }
            runOnUiThread {
                queryInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (snapshot == null) {
                    return@runOnUiThread
                }
                if (firstSnapshot) {
                    firstSnapshot = false
                    return@runOnUiThread
                }
                renderRates(rates, elapsed)
            }
        }
    }

    private fun readUidTotals(start: Long, end: Long): Map<Int, Pair<Long, Long>> {
        val manager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val result = HashMap<Int, Pair<Long, Long>>()
        val types = intArrayOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)
        for (type in types) {
            var stats: NetworkStats? = null
            try {
                stats = manager.querySummary(type, null, start, end)
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    if (stats.getNextBucket(bucket)) {
                        val uid = bucket.uid
                        val old = result[uid] ?: (0L to 0L)
                        result[uid] = (old.first + bucket.rxBytes) to (old.second + bucket.txBytes)
                    }
                }
            } finally {
                stats?.close()
            }
        }
        return result
    }

    private fun renderRates(rates: Map<Int, Pair<Long, Long>>, elapsed: Long) {
        rows.removeAllViews()
        val active = rates.entries.sortedByDescending { it.value.first + it.value.second }

        if (active.isEmpty()) {
            rows.addView(TextView(this).apply {
                text = "—"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(190, 204, 224))
                setPadding(dp(6), dp(10), dp(6), dp(10))
                background = rounded(Color.rgb(24, 34, 49), 14)
            })
        } else {
            active.take(8).forEach { entry -> addAppRow(entry.key, entry.value.first, entry.value.second, active.sumOf { it.value.first + it.value.second }) }
        }
    }

    private fun addAppRow(uid: Int, down: Long, up: Long, total: Long) {
        val packages = try { packageManager.getPackagesForUid(uid)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        val label = when (uid) {
            0 -> "System"
            1000 -> "Android OS"
            else -> packages.firstNotNullOfOrNull { pkg ->
                try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                catch (_: Exception) { null }
            } ?: "UID $uid"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(Color.rgb(22, 31, 45), 13)
        }
        val icon = ImageView(this)
        val appInfo = packages.firstNotNullOfOrNull { pkg ->
            try { packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
        }
        try {
            if (appInfo != null) icon.setImageDrawable(packageManager.getApplicationIcon(appInfo))
            else icon.setImageResource(android.R.drawable.sym_def_app_icon)
        } catch (_: Exception) { icon.setImageResource(android.R.drawable.sym_def_app_icon) }
        row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), 0, dp(4), 0)
        }
        middle.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        val bar = android.view.View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(255, 155, 36), Color.rgb(255, 155, 36), Color.rgb(50, 196, 235))
            ).apply { cornerRadius = dp(4).toFloat() }
        }
        middle.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
        row.addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply {
            text = "↓ ${formatRate(down)}  ↑ ${formatRate(up)}"
            textSize = 9f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(225, 234, 246))
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(value)
        val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) }
        rows.addView(row, params)
        val weight = if (total > 0L) ((down + up).toFloat() / total).coerceIn(0.04f, 1f) else 0.04f
        bar.scaleX = weight
        bar.pivotX = 0f
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun formatRate(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB/s", bytes / 1048576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB/s", bytes / 1024.0)
        else -> "${max(0L, bytes)} B/s"
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
