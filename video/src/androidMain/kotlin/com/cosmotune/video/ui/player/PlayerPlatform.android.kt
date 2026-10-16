package com.cosmotune.video.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.video.player.VideoPlayback
import kotlin.math.roundToInt

/** Android: the window's brightness and the music stream's volume (spec 6.5 "Full-screen gestures"). */
@Composable
actual fun rememberPlayerPlatform(playback: VideoPlayback): PlayerPlatformControls {
    val context = LocalContext.current
    val controls = remember(context) { AndroidPlayerPlatform(context) }
    // The brightness a full-screen drag set belongs to that screen: it goes back to the system's when the box leaves.
    DisposableEffect(controls) { onDispose { controls.restoreBrightness() } }
    return controls
}

private class AndroidPlayerPlatform(private val context: Context) : PlayerPlatformControls {
    private val activity: Activity? = context.findActivity()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    private var changedBrightness = false

    override val brightnessSupported: Boolean get() = activity != null
    override val swipeControls: Boolean get() = true

    override fun brightness(): Float {
        val own = activity?.window?.attributes?.screenBrightness ?: return DEFAULT_BRIGHTNESS
        if (own >= 0f) return own
        // The window follows the system: start from the system's level.
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / SYSTEM_BRIGHTNESS_MAX
        } catch (_: Settings.SettingNotFoundException) {
            DEFAULT_BRIGHTNESS
        }.coerceIn(0f, 1f)
    }

    override fun setBrightness(value: Float) {
        val window = activity?.window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = value.coerceIn(MIN_BRIGHTNESS, 1f)
        window.attributes = attributes
        changedBrightness = true
    }

    override fun volume(): Float {
        val manager = audio ?: return 1f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max <= 0) 0f else manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun setVolume(value: Float) {
        val manager = audio ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max), 0)
    }

    fun restoreBrightness() {
        if (!changedBrightness) return
        val window = activity?.window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
        changedBrightness = false
    }

    private companion object {
        const val DEFAULT_BRIGHTNESS = 0.5f
        const val SYSTEM_BRIGHTNESS_MAX = 255f

        /** Never fully dark: a window at 0 can look switched off. */
        const val MIN_BRIGHTNESS = 0.01f
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
