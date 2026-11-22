package com.justaskaspire.bandwatch

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Pure Kotlin helpers for mobile traffic-rate calculations and display.
 * All rates are combined RX + TX bytes per second.
 */
object NetworkSpeed {
    fun combinedBytesPerSecond(downloadDeltaBytes: Long, uploadDeltaBytes: Long, elapsedMillis: Long): Long {
        if (elapsedMillis <= 0L) return 0L
        val download = downloadDeltaBytes.coerceAtLeast(0L).toDouble()
        val upload = uploadDeltaBytes.coerceAtLeast(0L).toDouble()
        return ((download + upload) * 1000.0 / elapsedMillis.toDouble())
            .roundToLong()
            .coerceAtLeast(0L)
    }

    /**
     * Ultra-compact label for Android's narrow status-bar icon slot.
     * Kilo-byte values below 1 MB/s omit the K suffix (e.g. 850 = 850 KB/s)
     * to give the digits more room. The notification body keeps the full unit.
     */
    fun statusBarLabel(bytesPerSecond: Long?): String {
        if (bytesPerSecond == null) return "—"
        if (bytesPerSecond <= 0L) return "0"

        val rate = bytesPerSecond.toDouble()
        return when {
            rate >= 1024.0 * 1024.0 * 1024.0 ->
                String.format(Locale.US, "%.0fG", rate / (1024.0 * 1024.0 * 1024.0))
            rate >= 10.0 * 1024.0 * 1024.0 ->
                String.format(Locale.US, "%.0fM", rate / (1024.0 * 1024.0))
            rate >= 1024.0 * 1024.0 ->
                String.format(Locale.US, "%.1fM", rate / (1024.0 * 1024.0))
            rate >= 10.0 * 1024.0 ->
                String.format(Locale.US, "%.0f", rate / 1024.0)
            rate >= 1024.0 ->
                String.format(Locale.US, "%.0fK", rate / 1024.0)
            else -> "<1K"
        }
    }

    fun format(bytesPerSecond: Long): String {
        if (bytesPerSecond < 0L) return "— KB/s"
        if (bytesPerSecond == 0L) return "0 KB/s"

        val mebibytesPerSecond = bytesPerSecond / (1024.0 * 1024.0)
        return if (bytesPerSecond >= 1024L * 1024L) {
            String.format(Locale.US, "%.2f MB/s", mebibytesPerSecond)
        } else {
            val kibibytesPerSecond = bytesPerSecond / 1024.0
            val pattern = if (kibibytesPerSecond < 10.0) "%.1f KB/s" else "%.0f KB/s"
            String.format(Locale.US, pattern, kibibytesPerSecond)
        }
    }
}

object LteBand {
    fun fromEarfcn(earfcn: Int): String = when (earfcn) {
        in 1200..1949 -> "Band 3"
        in 2750..3449 -> "Band 7"
        else -> "LTE band unknown"
    }

    fun shortName(band: String?): String = when (band) {
        "Band 3" -> "B3"
        "Band 7" -> "B7"
        else -> "—"
    }
}
