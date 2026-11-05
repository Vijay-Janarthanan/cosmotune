package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable

/** The desktop has no system animation scale to follow; only the app's own switch counts. */
@Composable
internal actual fun systemAnimationsOff(): Boolean = false
