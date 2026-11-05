package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable

/**
 * The system Back key of the phone while [enabled]: [onBack] runs instead of leaving the screen. On the desktop there is none to
 * catch (the window has no system Back; its Back button, the mouse's and Alt and Left go through `VideoDesktopBack`).
 */
@Composable
internal expect fun VideoBackHandler(enabled: Boolean, onBack: () -> Unit)
