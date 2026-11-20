package com.cosmotune.video.player.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps the screen on while a video plays, as a media player should: the window's keep-screen-on flag, held by every picture that
 * plays and cleared when the last one stops, pauses or leaves. Counted per window, so a picture that goes away (Video's page giving
 * way to Watch, a full-screen switch) does not let the screen sleep under another that still plays. Paused, ended or audio-only, the
 * screen times out as usual.
 */
internal object ScreenAwake {
    private val holds = java.util.WeakHashMap<Window, Int>()

    @MainThread
    fun hold(window: Window) {
        val count = (holds[window] ?: 0) + 1
        holds[window] = count
        if (count == 1) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @MainThread
    fun release(window: Window) {
        val count = (holds[window] ?: return) - 1
        if (count > 0) {
            holds[window] = count
        } else {
            holds.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Holds [ScreenAwake] for this composition's window while [awake]. */
@Composable
internal fun KeepScreenAwake(awake: Boolean) {
    val window = LocalContext.current.activity()?.window
    DisposableEffect(window, awake) {
        val held = window?.takeIf { awake }
        held?.let(ScreenAwake::hold)
        onDispose { held?.let(ScreenAwake::release) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
