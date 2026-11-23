package com.cosmotune.music.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.settings.Extras

/**
 * Settings, Player, Keep the screen on, "Whenever music plays": the app holds the screen on while a song plays, wherever the listener
 * is in it. Put at the app's root; the player keeps its own hold for the lyrics, videos and "While the player is open". Nothing on
 * the desktop, whose display is held by its own host.
 */
@Composable
fun PlaybackKeepsScreenOn(isPlaying: Boolean) {
    val policy by Extras.settings.screenOn.collectAsStateWithLifecycle()
    KeepScreenOn(enabled = policy.keepsOn(playerOpen = false, lyricsOrVideo = false, isPlaying = isPlaying))
}
