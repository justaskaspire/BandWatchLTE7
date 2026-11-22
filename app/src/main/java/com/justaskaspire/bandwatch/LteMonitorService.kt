package com.justaskaspire.bandwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LteMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var telephony: TelephonyManager
    private val prefs by lazy { getSharedPreferences("bandwatch", MODE_PRIVATE) }
    private var lastBand: String? = null
    private val poller = object : Runnable {
        override fun run() { readNetwork(); handler.postDelayed(this, 5000) }
    }

    override fun onCreate() {
        super.onCreate()
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Monitoring LTE network…"))
        handler.removeCallbacks(poller)
        handler.post(poller)
        return START_STICKY
    }

    private fun readNetwork() {
        try {
            val serving = telephony.allCellInfo?.filterIsInstance<CellInfoLte>()?.firstOrNull { it.isRegistered }
            if (serving == null) {
                save("LTE data unavailable", -1, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
                updateNotification("LTE data unavailable — check signal and Location Services")
                return
            }
            val earfcn = serving.cellIdentity.earfcn
            val signal = serving.cellSignalStrength
            val band = when (earfcn) {
                in 1200..1949 -> "Band 3"
                in 2750..3449 -> "Band 7"
                else -> "LTE band unknown"
            }
            save(band, earfcn, signal.rsrp, signal.rsrq, signal.rssnr)
            updateNotification("${band} • RSRP ${metric(signal.rsrp)} dBm")
            if (band == "Band 7" && lastBand != "Band 7") sendBand7Alert()
            lastBand = band
        } catch (_: SecurityException) {
            save("Permission required", -1, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
            updateNotification("Permission required — open BandWatch")
        } catch (_: Exception) {
            save("Reading unavailable", -1, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
            updateNotification("LTE reading unavailable")
        }
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
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "monitor")
            .setContentTitle("BandWatch").setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pending).setOngoing(true).build()
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("BandWatch").setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pending).setOngoing(true).build()
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(message))
    }

    private fun sendBand7Alert() {
        val pending = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val alert = Notification.Builder(this, "band7").setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Band 7 detected!").setContentText("Your phone connected to LTE Band 7.")
            .setContentIntent(pending).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(7, alert)
    }

    private fun metric(value: Int) = if (value == Int.MAX_VALUE) "—" else value.toString()
    override fun onDestroy() { handler.removeCallbacks(poller); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}