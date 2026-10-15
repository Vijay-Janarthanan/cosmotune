package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.ui.player.PlayerPlatform

/**
 * Reduced motion means the app's "Reduce animation" or the system's animator scale at 0 (handoff section 5).
 * Then springs become short fades, presses do not scale, the wave stands still and the video does not start by itself.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val appSetting by PlayerPlatform.host.settings.reduceAnimation.collectAsStateWithLifecycle()
    return appSetting || systemAnimationsOff()
}

/** True when the system animator duration scale is 0 (Android developer options, accessibility "remove animations"). */
@Composable
internal expect fun systemAnimationsOff(): Boolean
