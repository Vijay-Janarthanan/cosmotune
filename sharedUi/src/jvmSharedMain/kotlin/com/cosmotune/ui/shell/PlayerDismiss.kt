package com.cosmotune.ui.shell

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** Closes the player: provided by [CosmoPlayerOverlay] around the Now Playing content, read by its collapse button. */
val LocalCosmoPlayerDismiss: ProvidableCompositionLocal<() -> Unit> = staticCompositionLocalOf { {} }
