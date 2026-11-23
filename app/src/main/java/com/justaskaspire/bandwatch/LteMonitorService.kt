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
import android.telephony.SubscriptionManager
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
    private lateinit var subscriptionManager: SubscriptionManager
    private var activeDataSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private val prefs by lazy { getSharedPreferences("bandwatch", MODE_PRIVATE) }
    private var lastBand: String? = null
    private var lastAlertedBand: String? = null
    private var currentBand: String? = null
    private var latestBandMessage = "Starting LTE monitor…"
    private var latestSpeedText = "0 KB/s"
    private var latestSpeedBytesPerSecond: Long? = 0L
    private var speedWasEnabled = true
    private var previousRxBytes: Long? = null
    private var previousTxBytes: Long? = null
    private var previousSpeedSampleAt = 0L

    private val poller = object : Runnable {
        override fun run() { requestNetworkReading(); handler.postDelayed(this, 5000) }
    }
    private val speedPoller = object : Runnable {
        override fun run() { sampleMobileSpeed(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate() {
        super.onCreate()
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        lastBand = prefs.getString("history_last_band", null)
        currentBand = prefs.getString("band", null)?.takeIf { it == "Band 3" || it == "Band 7" }
        speedWasEnabled = prefs.getBoolean("speed_indicator_enabled", true)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(latestBandMessage))
        handler.removeCallbacks(poller); handler.removeCallbacks(speedPoller)
        handler.post(poller); handler.post(speedPoller)
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun requestNetworkReading() {
        try {
            val selectedSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SubscriptionManager.getActiveDataSubscriptionId()
            } else {
                SubscriptionManager.getDefaultDataSubscriptionId()
            }
            if (selectedSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                showUnavailable("Android has not selected an active mobile-data SIM")
                return
            }
            activeDataSubId = selectedSubId
            val info = subscriptionManager.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == selectedSubId }
            val slot = info?.simSlotIndex?.let { "SIM ${it + 1}" } ?: "SIM"
            val carrier = info?.carrierName?.toString()?.takeIf { it.isNotBlank() }
            val simLabel = if (carrier != null) "$slot • $carrier" else "$slot • Mobile data"
            prefs.edit().putString("active_sim_label", simLabel)
                .putInt("active_data_sub_id", selectedSubId).apply()

            val dataTelephony = telephony.createForSubscriptionId(selectedSubId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dataTelephony.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (activeDataSubId == selectedSubId) processCellInfo(cellInfo)
                    }
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (activeDataSubId == selectedSubId) showUnavailable("SIM cell-info request failed ($errorCode)")
                    }
                })
            } else if (activeDataSubId == selectedSubId) {
                processCellInfo(dataTelephony.allCellInfo)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getSystemService(LocationManager::class.java).isLocationEnabled else true
            } catch (_: Exception) { true }
            showUnavailable(if (!locationOn) "Location Services are OFF — enable Location"
                else "No registered LTE cell returned by Android — retrying")
            return
        }
        val earfcn = serving.cellIdentity.earfcn
        val signal = serving.cellSignalStrength
        val band = LteBand.fromEarfcn(earfcn)
        currentBand = band
        save(band, earfcn, signal.rsrp, signal.rsrq, signal.rssnr)
        val identity = serving.cellIdentity
        prefs.edit()
            .putInt("tower_ci", identity.ci)
            .putInt("tower_pci", identity.pci)
            .putInt("tower_tac", identity.tac)
            .apply()
        recordSignalSample(signal.rsrp, signal.rsrq, signal.rssnr)
        if (band == "Band 3" || band == "Band 7") recordBandChange(band, earfcn, signal.rsrp, signal.rsrq, signal.rssnr)
        val simLabel = prefs.getString("active_sim_label", "Active data SIM") ?: "Active data SIM"
        updateNotification("$simLabel • $band • RSRP ${metric(signal.rsrp)} dBm")
        if (band == "Band 7" && lastAlertedBand != "Band 7") sendBand7Alert()
        lastAlertedBand = band
        if (band == "Band 3" || band == "Band 7") lastBand = band
    }

    private fun sampleMobileSpeed() {
        val enabled = prefs.getBoolean("speed_indicator_enabled", true)
        if (!enabled) {
            previousRxBytes = null; previousTxBytes = null; previousSpeedSampleAt = 0L
            if (speedWasEnabled) { speedWasEnabled = false; refreshOngoingNotification() }
            return
        }
        if (!speedWasEnabled) {
            speedWasEnabled = true
            previousRxBytes = null; previousTxBytes = null; previousSpeedSampleAt = 0L
        }
        val rxBytes = TrafficStats.getMobileRxBytes()
        val txBytes = TrafficStats.getMobileTxBytes()
        if (rxBytes < 0L || txBytes < 0L) {
            previousRxBytes = null; previousTxBytes = null; previousSpeedSampleAt = 0L
            setSpeedText("— KB/s", null)
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

    private fun speedLevel(bytesPerSecond: Long?): Int = when {
        bytesPerSecond == null -> -1
        bytesPerSecond <= 0L -> 0
        bytesPerSecond < 128L * 1024L -> 1
        bytesPerSecond < 1024L * 1024L -> 2
        else -> 3
    }

    private fun recordBandChange(band: String, earfcn: Int, rsrp: Int, rsrq: Int, sinr: Int) {
        if (lastBand == band) return
        val history = try { JSONArray(prefs.getString("history_json", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val entry = JSONObject().apply {
            put("band", band); put("timestamp", System.currentTimeMillis()); put("earfcn", earfcn)
            put("rsrp", rsrp); put("rsrq", rsrq); put("sinr", sinr)
        }
        history.put(entry)
        val trimmed = JSONArray()
        val first = (history.length() - 300).coerceAtLeast(0)
        for (i in first until history.length()) trimmed.put(history.getJSONObject(i))
        prefs.edit().putString("history_json", trimmed.toString()).putString("history_last_band", band).apply()
    }

    private fun recordSignalSample(rsrp: Int, rsrq: Int, sinr: Int) {
        if (rsrp == Int.MAX_VALUE || rsrp == Int.MIN_VALUE) return
        val samples = try { JSONArray(prefs.getString("signal_samples", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        samples.put(JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("rsrp", rsrp)
            put("rsrq", rsrq)
            put("sinr", sinr)
        })
        val trimmed = JSONArray()
        val first = (samples.length() - 120).coerceAtLeast(0)
        for (i in first until samples.length()) trimmed.put(samples.getJSONObject(i))
        prefs.edit().putString("signal_samples", trimmed.toString()).apply()
    }

    private fun showUnavailable(reason: String) {
        currentBand = null
        lastAlertedBand = null
        save(reason, -1, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        updateNotification(reason)
    }

    private fun save(band: String, earfcn: Int, rsrp: Int, rsrq: Int, sinr: Int) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        prefs.edit().putString("band", band).putInt("earfcn", earfcn)
            .putInt("rsrp", rsrp).putInt("rsrq", rsrq).putInt("sinr", sinr).putString("updated", time).apply()
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
        val contentText = if (speedEnabled) latestSpeedText else message
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "monitor").setContentTitle("BandWatch").setContentText(contentText)
                .setColor(if (currentBand == "Band 7") Color.rgb(45, 214, 130) else Color.WHITE)
                .apply { if (speedEnabled) setSubText(message) }
                .setSmallIcon(statusBarIcon()).setContentIntent(pending).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("BandWatch").setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pending).setOngoing(true).build()
        }
    }

    /** Compact monochrome status-bar icon: large live speed number, with a dot only on LTE Band 7. */
    private fun statusBarIcon(): Icon {
        if (prefs.getBoolean("speed_indicator_enabled", true)) {
            // The number changes units automatically; unit text is omitted to maximize digit size.
            val number = NetworkSpeed.statusBarLabel(latestSpeedBytesPerSecond)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                textAlign = Paint.Align.LEFT
                // Larger digits; compress horizontally only when the reading needs it.
                textSize = 54f
            }
            val measuredWidth = numberPaint.measureText(number)
            val maxWidth = if (currentBand == "Band 7") 52f else 61f
            val horizontalScale = (maxWidth / measuredWidth).coerceAtMost(1f)
            val renderedWidth = measuredWidth * horizontalScale
            val hasBand7Dot = currentBand == "Band 7"
            val groupWidth = renderedWidth + if (hasBand7Dot) 9f else 0f
            val textLeft = (64f - groupWidth) / 2f
            canvas.save()
            canvas.scale(horizontalScale, 1f, textLeft, 49f)
            canvas.drawText(number, textLeft, 49f, numberPaint)
            canvas.restore()
            // Keep the Band 7 dot immediately beside the live speed digits.
            if (hasBand7Dot) {
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(textLeft + renderedWidth + 5.5f, 42f, 3.5f, dotPaint)
            }
            return Icon.createWithBitmap(bitmap)
        }
        // No band-specific icon when the live speed indicator is disabled.
        return Icon.createWithResource(this, android.R.drawable.stat_notify_sync)
    }

    private fun updateNotification(message: String) { latestBandMessage = message; refreshOngoingNotification() }
    private fun refreshOngoingNotification() { getSystemService(NotificationManager::class.java).notify(1, notification(latestBandMessage)) }

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
        handler.removeCallbacks(poller); handler.removeCallbacks(speedPoller)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
