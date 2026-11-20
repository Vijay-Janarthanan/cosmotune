package com.cosmotune.ui.fullscreen

import androidx.compose.runtime.Composable

/** Desktop: no touch screen to drag on and no screen brightness, so the full-screen player has no level drags. */
@Composable
actual fun rememberFullScreenDevice(): FullScreenDevice? = null
