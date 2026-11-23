package com.cosmotune.ui.nowplaying

import androidx.compose.runtime.Composable

/**
 * The song's YouTube comments in the music player ("Comments" in the player's more menu): the same comments sheet Video mode
 * draws, read and written there, which lives in the video module this one cannot see. Each host installs [sheet] at start-up;
 * while it is null the menu has no Comments line.
 */
object MusicComments {
    @Volatile
    var sheet: (@Composable (videoId: String, onDismiss: () -> Unit) -> Unit)? = null

    /** Whether [videoId] is a YouTube video that can have comments (not a local file, an add-on's track or a station). */
    fun applies(videoId: String): Boolean = videoId.length == 11 && videoId.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
