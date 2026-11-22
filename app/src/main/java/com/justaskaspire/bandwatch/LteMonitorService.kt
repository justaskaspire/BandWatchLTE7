package com.justaskaspire.bandwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import com.justaskaspire.bandwatch.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LteMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var telephony: TelephonyManager
    private val prefs by lazy { getSharedPreferences("bandwatch", MODE_PRIVATE) }
    private var lastBand: String? = null
    private var currentBand: String? = null

    private var latestBandMessage = "Starting LTE monitor…"
    private var latestSpeedText = "0 KB/s"
    private var latestSpeedBytesPerSecond: Long? = 0L
    private var speedWasEnabled = true
    private var previousRxBytes: Long? = null
    private var previousTxBytes: Long? = null
    private var previousSpeedSampleAt = 0L

    private val poller = object : Runnable {
        override fun run() {
            requestNetworkReading()
            handler.postDelayed(this, 5000)
        }
    }

    private val speedPoller = object : Runnable {
        override fun run() {
            sampleMobileSpeed()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        lastBand = prefs.getString("history_last_band", null)
        currentBand = prefs.getString("band", null)?.takeIf { it == "Band 3" || it == "Band 7" }
        speedWasEnabled = prefs.getBoolean("speed_indicator_enabled", true)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(latestBandMessage))
        handler.removeCallbacks(poller)
        handler.removeCallbacks(speedPoller)
        handler.post(poller)
        handler.post(speedPoller)
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun requestNetworkReading() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                telephony.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        processCellInfo(cellInfo)
                    }
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        showUnavailable("Android cell-info request failed ($errorCode)")
                    }
                })
            } else {
                processCellInfo(telephony.allCellInfo)
            }
        } catch (_: SecurityException) {
            showUnavailable("Permission missing — check Phone and Precise Location access")
        } catch (_: Exception) {
            showUnavailable("Android could not provide cell information")
        }
    }

    private fun processCellInfo(cells: List<CellInfo>?) {
        val serving = cells?.filterIsInstance<CellInfoLte>()?.firstOrNull { it.isRegistered }
        if (serving == null) {
            val locationOn = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    getSystemService(LocationManager::class.java).isLocationEnabled
                } else true
            } catch (_: Exception) { true }
            val reason = if (!locationOn) {
                "Location Services are OFF — enable Location"
            } else {
                "No registered LTE cell returned by Android — retrying"
            }
            showUnavailable(reason)
            return
        }

        val earfcn = serving.cellIdentity.earfcn
        val signal = serving.cellSignalStrength
        val band = LteBand.fromEarfcn(earfcn)
        currentBand = band
        save(band, earfcn, signal.rsrp, signal.rsrq, signal.rssnr)
        if (band == "Band 3" || band == "Band 7") recordBandChange(band, earfcn, signal.rsrp, signal.rsrq, signal.rssnr)
        updateNotification("${band} • RSRP ${metric(signal.rsrp)} dBm")
        if (band == "Band 7" && lastBand != "Band 7") sendBand7Alert()
        if (band == "Band 3" || band == "Band 7") lastBand = band
    }

    private fun sampleMobileSpeed() {
        val enabled = prefs.getBoolean("speed_indicator_enabled", true)
        if (!enabled) {
            previousRxBytes = null
            previousTxBytes = null
            previousSpeedSampleAt = 0L
            if (speedWasEnabled) {
                speedWasEnabled = false
                refreshOngoingNotification()
            }
            return
        }

        if (!speedWasEnabled) {
            speedWasEnabled = true
            previousRxBytes = null
            previousTxBytes = null
            previousSpeedSampleAt = 0L
        }

        // Mobile counters include combined cellular RX and TX traffic for the device.
        val rxBytes = TrafficStats.getMobileRxBytes()
        val txBytes = TrafficStats.getMobileTxBytes()
        if (rxBytes < 0L || txBytes < 0L) {
            previousRxBytes = null
            previousTxBytes = null
            previousSpeedSampleAt = 0L
            setSpeedText("— KB/s", null)
            return
        }

        val now = SystemClock.elapsedRealtime()
        val oldRx = previousRxBytes
        val oldTx = previousTxBytes
        val elapsed = now - previousSpeedSampleAt
        val bytesPerSecond = if (oldRx == null || oldTx == null || previousSpeedSampleAt == 0L) {
            0L
        } else {
            NetworkSpeed.combinedBytesPerSecond(
                (rxBytes - oldRx).coerceAtLeast(0L),
                (txBytes - oldTx).coerceAtLeast(0L),
                elapsed
            )
        }

        previousRxBytes = rxBytes
        previousTxBytes = txBytes
        previousSpeedSampleAt = now
        setSpeedText(NetworkSpeed.format(bytesPerSecond), bytesPerSecond)
    }

    private fun setSpeedText(value: String, bytesPerSecond: Long?) {
        val oldLevel = speedLevel(latestSpeedBytesPerSecond)
        val newLevel = speedLevel(bytesPerSecond)
        if (latestSpeedText == value && oldLevel == newLevel) return
        latestSpeedText = value
        latestSpeedBytesPerSecond = bytesPerSecond
        refreshOngoingNotification()
    }

    // Icon thresholds: idle = 0, slow < 128 KB/s, medium < 1 MB/s, fast >= 1 MB/s.
    private fun speedLevel(bytesPerSecond: Long?): Int = when {
        bytesPerSecond == null -> -1
        bytesPerSecond <= 0L -> 0
        bytesPerSecond < 128L * 1024L -> 1
        bytesPerSecond < 1024L * 1024L -> 2
        else -> 3
    }

    private fun recordBandChange(band: String, earfcn: Int, rsrp: Int, rsrq: Int, sinr: Int) {
        if (lastBand == band) return
        val history = try {
            JSONArray(prefs.getString("history_json", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val entry = JSONObject().apply {
            put("band", band)
            put("timestamp", System.currentTimeMillis())
            put("earfcn", earfcn)
            put("rsrp", rsrp)
            put("rsrq", rsrq)
            put("sinr", sinr)
        }
        history.put(entry)
        // Keep the latest 300 transitions to prevent unbounded storage growth.
        val trimmed = JSONArray()
        val first = (history.length() - 300).coerceAtLeast(0)
        for (i in first until history.length()) trimmed.put(history.getJSONObject(i))
        prefs.edit()
            .putString("history_json", trimmed.toString())
            .putString("history_last_band", band)
            .apply()
    }

    private fun showUnavailable(reason: String) {
        currentBand = null
        save(reason, -1, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        updateNotification(reason)
    }

    private fun save(band: String, earfcn: Int, rsrp: Int, rsrq: Int, sinr: Int) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        prefs.edit().putString("band", band).putInt("earfcn", earfcn)
            .putInt("rsrp", rsrp).putInt("rsrq", rsrq).putInt("sinr", sinr)
            .putString("updated", time).apply()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("monitor", "LTE monitoring", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel("band7", "Band 7 alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
            })
        }
    }

    private fun notification(message: String): Notification {
        val speedEnabled = prefs.getBoolean("speed_indicator_enabled", true)
        val contentText = if (speedEnabled) "$latestSpeedText | ${LteBand.shortName(currentBand)}" else message
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "monitor")
                .setContentTitle("BandWatch")
                .setContentText(contentText)
                .apply { if (speedEnabled) setSubText(message) }
                .setSmallIcon(statusBarIcon()).setContentIntent(pending).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("BandWatch").setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pending).setOngoing(true).build()
        }
    }

    /**
     * Android status-bar small icons are monochrome and very small. Render a
     * short numeric rate into a transparent bitmap so the icon itself shows
     * a live value (for example 850K or 1.2M), rather than speed-level bars.
     */
    private fun statusBarIcon(): Icon {
        if (prefs.getBoolean("speed_indicator_enabled", true)) {
            val label = NetworkSpeed.statusBarLabel(latestSpeedBytesPerSecond)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                // Use the largest practical glyph size inside Android's fixed icon slot.
                textSize = when {
                    label.length >= 4 -> 25f
                    label.length == 3 -> 32f
                    else -> 38f
                }
            }
            val metrics = paint.fontMetrics
            val baseline = (64f - metrics.ascent - metrics.descent) / 2f
            canvas.drawText(label, 32f, baseline, paint)
            return Icon.createWithBitmap(bitmap)
        }

        val iconRes = when (currentBand) {
            "Band 3" -> R.drawable.ic_band3
            "Band 7" -> R.drawable.ic_band7
            else -> android.R.drawable.stat_notify_sync
        }
        return Icon.createWithResource(this, iconRes)
    }

    private fun updateNotification(message: String) {
        latestBandMessage = message
        refreshOngoingNotification()
    }

    private fun refreshOngoingNotification() {
        getSystemService(NotificationManager::class.java).notify(1, notification(latestBandMessage))
    }

    private fun sendBand7Alert() {
        val pending = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val alert = Notification.Builder(this, "band7").setSmallIcon(R.drawable.ic_band7)
            .setContentTitle("Band 7 detected!").setContentText("Your phone connected to LTE Band 7.")
            .setContentIntent(pending).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(7, alert)
    }

    private fun metric(value: Int) = if (value == Int.MAX_VALUE) "—" else value.toString()

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        handler.removeCallbacks(speedPoller)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
