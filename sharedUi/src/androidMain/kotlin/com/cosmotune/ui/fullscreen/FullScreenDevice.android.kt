package com.cosmotune.ui.fullscreen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt

/**
 * Android: the brightness of the window the player is drawn in (the full window's dialog when it has one, else the activity's),
 * put back to the system's when the player leaves, and the music stream's volume.
 */
@Composable
actual fun rememberFullScreenDevice(): FullScreenDevice? {
    val view = LocalView.current
    val controls = remember(view) { AndroidFullScreenDevice(view) }
    DisposableEffect(controls) { onDispose { controls.restoreBrightness() } }
    return remember(controls) {
        FullScreenDevice(
            brightness = if (controls.window != null) FullScreenLevel(controls::brightness, controls::setBrightness) else null,
            volume = if (controls.audio != null) FullScreenLevel(controls::volume, controls::setVolume) else null,
        )
    }
}

private class AndroidFullScreenDevice(private val view: View) {
    val window: Window? = windowOf(view)
    val audio = view.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    private var changed = false

    fun brightness(): Float {
        val own = window?.attributes?.screenBrightness ?: return DEFAULT_BRIGHTNESS
        if (own >= 0f) return own
        // The window follows the system: start from the system's level.
        return try {
            Settings.System.getInt(view.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / SYSTEM_MAX
        } catch (_: Settings.SettingNotFoundException) {
            DEFAULT_BRIGHTNESS
        }.coerceIn(0f, 1f)
    }

    fun setBrightness(value: Float) {
        val target = window ?: return
        val attributes = target.attributes
        attributes.screenBrightness = value.coerceIn(MIN_BRIGHTNESS, 1f)
        target.attributes = attributes
        changed = true
    }

    fun volume(): Float {
        val manager = audio ?: return 1f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max <= 0) 0f else manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(value: Float) {
        val manager = audio ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max), 0)
    }

    fun restoreBrightness() {
        if (!changed) return
        val target = window ?: return
        val attributes = target.attributes
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        target.attributes = attributes
        changed = false
    }

    private companion object {
        const val DEFAULT_BRIGHTNESS = 0.5f
        const val SYSTEM_MAX = 255f

        /** Never fully dark: a window at 0 can look switched off. */
        const val MIN_BRIGHTNESS = 0.01f

        /** The dialog's window when the view is in one (the full window), else the activity's. */
        fun windowOf(view: View): Window? {
            var parent = view.parent
            while (parent != null) {
                if (parent is DialogWindowProvider) return parent.window
                parent = parent.parent
            }
            return view.context.findActivity()?.window
        }

        tailrec fun Context.findActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}
