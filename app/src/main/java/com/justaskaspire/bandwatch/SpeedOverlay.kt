package com.justaskaspire.bandwatch

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max

/**
 * Draggable, coloured live-speed pill. Application overlays stay below
 * critical system windows, so this is positioned immediately under the status bar.
 */
class SpeedOverlay(private val context: Context) {
    private val prefs = context.getSharedPreferences("bandwatch", Context.MODE_PRIVATE)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var currentText: String? = null
    private var currentColor: Int? = null

    fun update(speedText: String, band: String?, enabled: Boolean) {
        if (!enabled || !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        val accent = when (band) {
            "Band 7" -> Color.rgb(45, 214, 130)
            "Band 3" -> Color.rgb(65, 145, 255)
            else -> Color.rgb(255, 86, 86)
        }
        val pill = view ?: createView().also { view = it }
        if (!attached) {
            try {
                windowManager.addView(pill, params)
                attached = true
            } catch (_: Exception) {
                attached = false
                return
            }
        }
        if (currentText != speedText || currentColor != accent) {
            pill.text = speedText
            pill.setTextColor(accent)
            pill.background = GradientDrawable().apply {
                setColor(Color.argb(232, 8, 16, 30))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), accent)
            }
            currentText = speedText
            currentColor = accent
        }
    }

    private fun createView(): TextView {
        val statusBarId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) context.resources.getDimensionPixelSize(statusBarId) else dp(24)
        val x = prefs.getInt("floating_x", dp(8)).coerceAtLeast(0)
        val y = prefs.getInt("floating_y", statusBarHeight + dp(7)).coerceAtLeast(statusBarHeight)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            this.x = x
            this.y = y
        }
        return TextView(context).apply {
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(13), dp(8), dp(13), dp(8))
            elevation = dp(8).toFloat()
            contentDescription = "Live mobile network speed"
            setOnTouchListener(object : View.OnTouchListener {
                private var downRawX = 0f
                private var downRawY = 0f
                private var startX = 0
                private var startY = 0
                private var moved = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val lp = params ?: return false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            startX = lp.x
                            startY = lp.y
                            moved = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - downRawX
                            val dy = event.rawY - downRawY
                            if (kotlin.math.abs(dx) > dp(3) || kotlin.math.abs(dy) > dp(3)) moved = true
                            if (moved) {
                                lp.x = max(0, startX - dx.toInt())
                                lp.y = max(statusBarHeight, startY + dy.toInt())
                                try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) { }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (moved) prefs.edit().putInt("floating_x", lp.x).putInt("floating_y", lp.y).apply()
                            return true
                        }
                    }
                    return true
                }
            })
        }
    }

    fun hide() {
        val old = view
        if (old != null && attached) {
            try { windowManager.removeView(old) } catch (_: Exception) { }
        }
        attached = false
    }

    private fun dp(value: Int) = (value * density).toInt()
}
