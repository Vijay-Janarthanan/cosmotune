package com.cosmotune.video.player

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.ui.pip.ActivityPip
import com.cosmotune.ui.pip.ActivityPipHandle
import com.cosmotune.ui.pip.PipOwner
import com.cosmotune.ui.pip.PipWish

/**
 * Picture-in-picture on Android (spec 2.5, 6.2; plan task Y-11), on the activity's one picture-in-picture that Watch and the song's
 * music video share ([ActivityPip], sharedUi): Video is [PipOwner.VIDEO] there, and the one decision of who holds the window
 * (`PipDecision`) keeps the three from fighting over it.
 *
 * - [PictureInPictureState.supported]: a `ComponentActivity` is found from `LocalContext` and the device has
 *   `FEATURE_PICTURE_IN_PICTURE` (the app's `MainActivity` declares `android:supportsPictureInPicture`).
 * - [PictureInPictureState.active]: the activity is in picture-in-picture with Video in it.
 * - [PictureInPictureState.update]: Video's wish (ready while `enabled`, its shape and its source rectangle); the shared one
 *   clamps the shape to between 1:2.39 and 2.39:1, turns auto-enter on (Android 12 and later) or enters from the user-leave hint
 *   (Android 8 to 11) while Video holds the window and is ready.
 *
 * The wish goes when the caller that last set it leaves composition (`onDispose { handle.shared.release() }`), so nothing of Video's
 * enters picture-in-picture once its pages have gone. Every caller in one activity shares one state, and the last `update` wins.
 */
@Composable
actual fun rememberPictureInPicture(): PictureInPictureState {
    val context = LocalContext.current
    val activity = remember(context) { context.componentActivity() }
    if (activity == null) return remember { UnsupportedPictureInPicture }
    val handle = remember(activity) { PictureInPictureHandle(ActivityPip.handle(activity, PipOwner.VIDEO)) }
    DisposableEffect(handle) {
        handle.shared.acquire()
        onDispose { handle.shared.release() }
    }
    return handle
}

private tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}

/** One caller's view of its activity's picture-in-picture, as Video's. */
@Stable
private class PictureInPictureHandle(val shared: ActivityPipHandle) : PictureInPictureState {
    override val supported: Boolean get() = shared.supported
    override val active: Boolean get() = shared.active
    override fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) =
        shared.update(PipWish(ready = enabled, aspectWidth = aspectWidth, aspectHeight = aspectHeight, sourceRect = sourceRect))
}

/** No activity to follow (a preview, a test host): never supported, never active, `update` does nothing. */
private object UnsupportedPictureInPicture : PictureInPictureState {
    override val supported: Boolean get() = false
    override val active: Boolean get() = false
    override fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) {}
}
