package com.justaskaspire.bandwatch

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {
    private val bg = Color.rgb(7, 12, 25)
    private val panel = Color.rgb(16, 25, 45)
    private val muted = Color.rgb(151, 169, 196)
    private val cyan = Color.rgb(62, 220, 255)
    private val blue = Color.rgb(45, 139, 255)
    private val green = Color.rgb(61, 226, 158)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            renderHistory()
        } catch (error: Exception) {
            showFallback()
        }
    }

    private fun renderHistory() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "BANDWATCH"
            textSize = 13f
            setTextColor(cyan)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Connection history"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, dp(5))
        })
        root.addView(TextView(this).apply {
            text = "Every detected switch between LTE Band 3 and Band 7."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(20))
        })

        val prefs = getSharedPreferences("bandwatch", MODE_PRIVATE)
        val entries = try {
            JSONArray(prefs.getString("history_json", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }

        if (entries.length() == 0) {
            root.addView(card().apply {
                addView(TextView(this@HistoryActivity).apply {
                    text = "No band switches recorded yet"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@HistoryActivity).apply {
                    text = "Start LTE monitoring. BandWatch will record each time it detects a change between Band 3 and Band 7."
                    textSize = 12f
                    setTextColor(muted)
                    setPadding(0, dp(8), 0, 0)
                })
            })
        } else {
            val dateFormat = SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.getDefault())
            val now = System.currentTimeMillis()
            for (i in entries.length() - 1 downTo 0) {
                // Ignore malformed entries rather than allowing one bad record to crash the screen.
                val item = entries.optJSONObject(i) ?: continue
                val band = item.optString("band", "LTE")
                val timestamp = item.optLong("timestamp", now).coerceIn(0L, now)
                val nextItem = if (i < entries.length() - 1) entries.optJSONObject(i + 1) else null
                val nextTimestamp = nextItem?.optLong("timestamp", timestamp) ?: now
                val duration = formatDuration((nextTimestamp - timestamp).coerceAtLeast(0L))
                val isBand7 = band == "Band 7"
                val accent = if (isBand7) cyan else blue

                root.addView(card().apply {
                    val header = LinearLayout(this@HistoryActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    header.addView(TextView(this@HistoryActivity).apply {
                        text = if (isBand7) "BAND 7" else if (band == "Band 3") "BAND 3" else band.uppercase(Locale.getDefault())
                        textSize = 19f
                        setTextColor(accent)
                        typeface = Typeface.DEFAULT_BOLD
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    header.addView(TextView(this@HistoryActivity).apply {
                        text = duration
                        textSize = 12f
                        setTextColor(green)
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(header)
                    addView(TextView(this@HistoryActivity).apply {
                        text = dateFormat.format(Date(timestamp))
                        textSize = 12f
                        setTextColor(muted)
                        setPadding(0, dp(8), 0, dp(10))
                    })
                    addView(TextView(this@HistoryActivity).apply {
                        text = "EARFCN ${item.optInt("earfcn", -1)}    •    RSRP ${metric(item, "rsrp")} dBm"
                        textSize = 12f
                        setTextColor(Color.WHITE)
                    })
                    addView(TextView(this@HistoryActivity).apply {
                        text = "RSRQ ${metric(item, "rsrq")} dB    •    SINR ${metric(item, "sinr")} dB"
                        textSize = 12f
                        setTextColor(muted)
                        setPadding(0, dp(5), 0, 0)
                    })
                })
                root.addView(LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(1, dp(10))
                })
            }
        }

        root.addView(TextView(this).apply {
            text = "←  Back to dashboard"
            textSize = 14f
            setTextColor(cyan)
            setPadding(0, dp(16), 0, dp(8))
            setOnClickListener { finish() }
        })
    }

    private fun showFallback() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(bg)
        }
        root.addView(TextView(this).apply {
            text = "History is temporarily unavailable"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Your LTE monitor is separate and can keep running. Return to the dashboard and try again later."
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        })
        root.addView(TextView(this).apply {
            text = "←  Back to dashboard"
            textSize = 14f
            setTextColor(cyan)
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    private fun metric(item: JSONObject, key: String): String {
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

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(16), dp(17), dp(16))
        background = GradientDrawable().apply {
            setColor(panel)
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}