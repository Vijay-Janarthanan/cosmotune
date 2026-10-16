package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable

@Composable
@Suppress("UNUSED_PARAMETER")
internal actual fun VideoBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // The desktop has no system Back to catch: VideoDesktopBack serves the window's Back button, the mouse and the keys.
}
