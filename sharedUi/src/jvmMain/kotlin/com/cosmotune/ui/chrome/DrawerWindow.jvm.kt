package com.cosmotune.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** A focusable popup at the window's top left corner; its content fills the window, and the Back key (Escape is the content's) dismisses it. */
@Composable
internal actual fun DrawerWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = false),
        content = content,
    )
}

/** The popup sits at the window's top left corner. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset.Zero
}
