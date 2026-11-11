package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.player.PlayerPlatform

/**
 * Tells the shell which cover is playing and which comes next. Call it from the root, where the song is known.
 *
 * It writes three plain values and nothing else: the palette follower (its own small scope, in [CosmoLookTheme]) reads them,
 * so a new song recomposes the follower and not the root's screens.
 *
 * The cover's palette reaches Now Playing and the detail headers only (DESIGN_SYSTEM.md 2.3), and Video has no such zone on its pages: a
 * playing video hands nothing over and changes no colour, so this writes Music's song in every mode.
 */
@Composable
fun CosmoShellState.FollowCover(song: Song?, next: Song?) {
    val url = PlayerPlatform.host.rememberRemoteArtworkUrl(song)   // the art the player itself shows (WebDAV, SMB too)
    val nextUrl = next?.thumbnailUrl
    val album = song?.let { it.albumId ?: it.albumName }
    SideEffect {
        // The song a music video kept in picture-in-picture follows once Now Playing has gone.
        com.cosmotune.ui.nowplaying.NowPlayingFeed.song = song
        coverUrl = url
        nextCoverUrl = nextUrl
        coverAlbum = album
    }
}
