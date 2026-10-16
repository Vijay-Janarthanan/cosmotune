package com.cosmotune.video.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Picture-in-picture on Android (spec 2.5, 6.2; plan task Y-11).
 *
 * - [PictureInPictureState.supported]: a `ComponentActivity` is found from `LocalContext` and the device has
 *   `FEATURE_PICTURE_IN_PICTURE` (the app's `MainActivity` declares `android:supportsPictureInPicture`).
 * - [PictureInPictureState.active]: from `addOnPictureInPictureModeChangedListener`, removed when the last caller leaves.
 * - [PictureInPictureState.update]: the aspect ratio clamped to between 1:2.39 and 2.39:1; Android 12 and later
 *   `setPictureInPictureParams(...setAspectRatio().setSourceRectHint().setAutoEnterEnabled(enabled))`; Android 8 to 11 keep the
 *   params, and the activity's user-leave hint (`addOnUserLeaveHintListener`) enters with them when `enabled`.
 *
 * `enabled = false` whenever the caller says so (the video paused, the watch page leaving), and also when the caller that last
 * set it leaves composition: so Music never goes into picture-in-picture. Every caller in one activity shares one state (one set
 * of listeners, one `active`), and the last `update` wins.
 */
@Composable
actual fun rememberPictureInPicture(): PictureInPictureState {
    val context = LocalContext.current
    val activity = remember(context) { context.componentActivity() }
    if (activity == null) return remember { UnsupportedPictureInPicture }
    val handle = remember(activity) { PictureInPictureHandle(ActivityPictureInPicture.of(activity)) }
    DisposableEffect(handle) {
        handle.shared.acquire()
        onDispose { handle.shared.leave(handle) }
    }
    return handle
}

private tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}

/** One caller's view of its activity's picture-in-picture. */
@Stable
private class PictureInPictureHandle(val shared: ActivityPictureInPicture) : PictureInPictureState {
    override val supported: Boolean get() = shared.supported
    override val active: Boolean get() = shared.active
    override fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) =
        shared.update(this, enabled, aspectWidth, aspectHeight, sourceRect)
}

/** The picture-in-picture of one activity, shared by every caller in it. Main thread only, like composition. */
private class ActivityPictureInPicture(private val activity: ComponentActivity) {

    val supported: Boolean = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    var active by mutableStateOf(activity.isInPictureInPictureMode)
        private set

    private var users = 0
    private var enabled = false
    private var owner: PictureInPictureHandle? = null
    private var params: PictureInPictureParams? = null
    private var lastAspect = Rational(16, 9)

    private val modeListener = Consumer<PictureInPictureModeChangedInfo> { active = it.isInPictureInPictureMode }

    /** Android 8 to 11: leaving the app enters picture-in-picture when it is enabled (12 and later enter by themselves). */
    private val leaveHintListener = Runnable {
        val wanted = params
        if (enabled && wanted != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { activity.enterPictureInPictureMode(wanted) }
        }
    }

    fun acquire() {
        if (users++ > 0) return
        shared[activity] = this
        activity.addOnPictureInPictureModeChangedListener(modeListener)
        activity.addOnUserLeaveHintListener(leaveHintListener)
        active = activity.isInPictureInPictureMode
    }

    /** A caller leaves composition: if it was the last to say "enabled", picture-in-picture is turned off. */
    fun leave(handle: PictureInPictureHandle) {
        if (owner === handle && enabled) apply(handle, enabled = false, aspect = lastAspect, sourceRect = null)
        if (owner === handle) owner = null
        if (--users > 0) return
        activity.removeOnPictureInPictureModeChangedListener(modeListener)
        activity.removeOnUserLeaveHintListener(leaveHintListener)
        shared.remove(activity)
    }

    fun update(handle: PictureInPictureHandle, enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) {
        if (!supported) return
        apply(handle, enabled, clampedAspect(aspectWidth, aspectHeight), sourceRect)
    }

    private fun apply(handle: PictureInPictureHandle, enabled: Boolean, aspect: Rational, sourceRect: Rect?) {
        this.enabled = enabled
        owner = handle
        lastAspect = aspect
        val builder = PictureInPictureParams.Builder().setAspectRatio(aspect)
        sourceRect?.let {
            builder.setSourceRectHint(
                android.graphics.Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt()),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(enabled)
        val built = builder.build()
        params = built
        // Android 12 and later: auto-enter follows `enabled`. Earlier: the shape is kept for the leave hint and, while in the
        // window, applied at once. Refused shapes (a device's own limits) leave the window as it was rather than crash.
        runCatching { activity.setPictureInPictureParams(built) }
    }

    companion object {
        /** The picture's widest and tallest shapes Android takes: 2.39:1 and 1:2.39. */
        private val WIDEST = Rational(239, 100)
        private val TALLEST = Rational(100, 239)

        private val shared = WeakHashMap<ComponentActivity, ActivityPictureInPicture>()

        fun of(activity: ComponentActivity): ActivityPictureInPicture = shared.getOrPut(activity) { ActivityPictureInPicture(activity) }

        /** [width] to [height] clamped to between 1:2.39 and 2.39:1; 16:9 when the size is not known yet. */
        fun clampedAspect(width: Int, height: Int): Rational {
            if (width <= 0 || height <= 0) return Rational(16, 9)
            val ratio = width.toFloat() / height
            return when {
                ratio > WIDEST.toFloat() -> WIDEST
                ratio < TALLEST.toFloat() -> TALLEST
                else -> Rational(width, height)
            }
        }
    }
}

/** No activity to follow (a preview, a test host): never supported, never active, `update` does nothing. */
private object UnsupportedPictureInPicture : PictureInPictureState {
    override val supported: Boolean get() = false
    override val active: Boolean get() = false
    override fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) {}
}
