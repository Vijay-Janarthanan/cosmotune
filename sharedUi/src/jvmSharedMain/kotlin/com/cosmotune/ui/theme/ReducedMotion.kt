package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.ui.player.PlayerPlatform

/**
 * Reduced motion means the app's "Reduce animation" or the system's animator scale at 0 (handoff section 5).
 * Then springs become short fades, presses do not scale, the wave stands still and the video does not start by itself.
 * Before the player is installed there is no app setting to read (a dialog that [EnsureCosmoLook] gives a theme of its own, a
 * test that draws one piece), and only the system's scale counts.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val appSetting = PlayerPlatform.hostOrNull?.settings?.reduceAnimation?.collectAsStateWithLifecycle()?.value ?: false
    return appSetting || systemAnimationsOff()
}

/** True when the system animator duration scale is 0 (Android developer options, accessibility "remove animations"). */
@Composable
internal expect fun systemAnimationsOff(): Boolean
