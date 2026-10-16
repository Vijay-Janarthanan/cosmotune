package com.cosmotune.video.ui.watch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Desktop: nothing to turn and no status bar (see the expect declaration). */
@Composable
@Suppress("UNUSED_PARAMETER")
internal actual fun FullscreenController(orientation: WatchOrientation, lightStatusBar: Boolean) {
    // The desktop window has no orientation and no system bars; its full screen is the full window.
}

/** Desktop: a focusable popup at the window's origin that fills the window; Escape (the window's Back) calls [onExit]. */
@Composable
internal actual fun VideoFullWindow(onExit: () -> Unit, content: @Composable () -> Unit) {
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onExit,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** The popup stands at the window's top left corner and fills the window from there. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}

/** Desktop: no picture-in-picture. */
@Composable
internal actual fun rememberEnterPictureInPicture(): (() -> Unit)? = null

/** Desktop: a short wide window is still the desktop's page. */
internal actual val rotationEntersFullScreen: Boolean = false
