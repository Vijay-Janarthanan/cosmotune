package com.cosmotune.video.ui.watch

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

/**
 * Android: the activity's `requestedOrientation` (landscape held with the sensor, portrait held with the user's rotation setting) and
 * the light glyphs of its status bar, both given back as they were when the last watch page leaves (see the expect declaration).
 * Every watch page of one activity shares one hold, because two stand composed at once while one page fades into the next (the
 * playback moved on to another video): the newer page's wish wins, and nothing is given back while a page still holds it.
 */
@Composable
internal actual fun FullscreenController(orientation: WatchOrientation, lightStatusBar: Boolean) {
    val view = LocalView.current
    val hold = remember(view) { view.context.findActivity()?.let { ActivityWindowHold.of(it) } }
    val handle = remember { Any() }
    SideEffect { hold?.set(handle, orientation, lightStatusBar) }
    DisposableEffect(hold, handle) { onDispose { hold?.remove(handle) } }
}

/**
 * What the watch pages of one activity changed on it, and what it was before: the orientation the activity asked for, and whether
 * the status bar's glyphs were dark. Main thread only, like composition.
 */
private class ActivityWindowHold(private val activity: Activity) {
    /** Each page's wish, oldest first (a page that asks again keeps its place). */
    private val wishes = LinkedHashMap<Any, Wish>()
    private var savedOrientation: Int? = null
    private var savedLightBars: Boolean? = null

    private class Wish(val orientation: WatchOrientation, val lightStatusBar: Boolean)

    fun set(handle: Any, orientation: WatchOrientation, lightStatusBar: Boolean) {
        wishes[handle] = Wish(orientation, lightStatusBar)
        apply()
    }

    fun remove(handle: Any) {
        wishes.remove(handle)
        apply()
    }

    private fun apply() {
        val orientation = wishes.values.lastOrNull { it.orientation != WatchOrientation.FREE }?.orientation ?: WatchOrientation.FREE
        val wanted = when (orientation) {
            WatchOrientation.FREE -> null
            WatchOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WatchOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
        if (wanted == null) {
            savedOrientation?.let { saved ->
                savedOrientation = null
                activity.requestedOrientation = saved
            }
        } else {
            if (savedOrientation == null) savedOrientation = activity.requestedOrientation
            if (activity.requestedOrientation != wanted) activity.requestedOrientation = wanted
        }
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (wishes.values.any { it.lightStatusBar }) {
            if (savedLightBars == null) savedLightBars = controller.isAppearanceLightStatusBars
            // `isAppearanceLightStatusBars` true means dark glyphs for a light bar: over the black band they are light.
            controller.isAppearanceLightStatusBars = false
        } else {
            savedLightBars?.let { saved ->
                savedLightBars = null
                controller.isAppearanceLightStatusBars = saved
            }
        }
    }

    companion object {
        /** One hold per activity, for as long as the activity lives (the keys are weak). */
        private val holds = WeakHashMap<Activity, ActivityWindowHold>()

        fun of(activity: Activity): ActivityWindowHold = holds.getOrPut(activity) { ActivityWindowHold(activity) }
    }
}

/**
 * Android: a dialog window as large as the screen (`usePlatformDefaultWidth = false`, `decorFitsSystemWindows = false`), drawn into
 * the display cutout, with no dim, its window black behind the content and its system bars hidden (a swipe from an edge shows them
 * for a moment). The system bars follow the window that has the focus, so they are hidden here, on the dialog's own window.
 */
@Composable
internal actual fun VideoFullWindow(onExit: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            dialogWindowOf(view)?.let { window -> immerse(window, view) }
            onDispose { }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** The full window's setup: no dim, black behind (the black of a full window), the cutout used, the bars hidden. */
private fun immerse(window: Window, view: View) {
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.setDimAmount(0f)
    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val attributes = window.attributes
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = attributes
    }
    val controller = WindowCompat.getInsetsController(window, view)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}

/** The window of the dialog that [view] is drawn in: the parent that provides it (the content's own view is its child). */
private fun dialogWindowOf(view: View): Window? {
    var parent = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}

/**
 * Android: `enterPictureInPictureMode` with empty parameters, which the system combines with the ones the page set last (the
 * video's shape and the player's bounds); null when the activity cannot (no activity, or a device without the feature).
 */
@Composable
internal actual fun rememberEnterPictureInPicture(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        val activity = context.findActivity()
        if (activity == null || !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            null
        } else {
            { runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }; Unit }
        }
    }
}

/** Android: a phone turned to landscape on the watch page enters full screen. */
internal actual val rotationEntersFullScreen: Boolean = true

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
