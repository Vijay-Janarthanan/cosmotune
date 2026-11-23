package com.cosmotune.music.playback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Floating lyrics (Settings > Lyrics > Floating lyrics, Mochy's idea): the line being sung in a small pill above other apps, dragged
 * anywhere. Needs Android's "Display over other apps" for Cosmotune; without it, or with nothing being sung, nothing is drawn.
 */
internal class FloatingLyricsOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(96) }

    fun show(line: String?) {
        if (line == null || !Settings.canDrawOverlays(context)) return clear()
        val shown = view ?: create().also { runCatching { windows.addView(it, params); view = it }.onFailure { return } }
        if (shown.text != line) shown.text = line
    }

    fun clear() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(): TextView = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        maxWidth = context.resources.displayMetrics.widthPixels - dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xD9000000.toInt()) }
        contentDescription = "Floating lyrics. Drag to move."
        var startY = 0; var touchY = 0f
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; touchY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.y = (startY + (event.rawY - touchY)).toInt().coerceAtLeast(0)
                    runCatching { windows.updateViewLayout(v, params) }; true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
