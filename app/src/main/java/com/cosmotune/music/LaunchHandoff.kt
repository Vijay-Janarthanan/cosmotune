package com.cosmotune.music

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi
import android.window.SplashScreenView

/**
 * How the launch screen gives way to the app.
 *
 * On Android 12 and up the system draws the splash (the black tile with the mark, `values-v31/themes.xml`) until the activity's first
 * frame is ready, then takes it away with its own exit: a hard cut from a black screen with an icon to the app, which reads as
 * something that opened and was gone before the home screen came. Here the splash is dissolved over the first frame instead, so the
 * launch is one continuous movement: splash, then the app coming up through it.
 *
 * Everything the first frame shows is known before it is drawn, which is what makes a dissolve enough and no hold on the splash
 * necessary: the theme and every look setting are read from the process's stores before `setContent` ([CosmotuneApplication.onCreate]
 * opens them), so the first composition already is the stored mode in the stored theme, with no frame of a default that is then
 * corrected.
 */
internal object LaunchHandoff {
    /** How long the splash takes to dissolve over the first frame. Short enough to be felt as a handover, never as a wait. */
    const val FADE_MS = 160L

    /**
     * Takes over the splash's exit. Called first in `onCreate`, before `super.onCreate`, the only point where the exit listener is
     * sure to be set before the system hands the splash over. A no-op below Android 12, where the window background is the
     * launch screen and there is nothing to dissolve.
     */
    fun install(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        activity.splashScreen.setOnExitAnimationListener { splash -> dissolve(splash) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dissolve(splash: SplashScreenView) {
        // The end action removes the view; the delayed removal is for a view the system detached before the animation could end,
        // which would otherwise leave nothing to run the end action and the splash on screen.
        splash.postDelayed({ runCatching { splash.remove() } }, FADE_MS * 2)
        splash.animate().alpha(0f).setDuration(FADE_MS).withEndAction { runCatching { splash.remove() } }.start()
    }
}
