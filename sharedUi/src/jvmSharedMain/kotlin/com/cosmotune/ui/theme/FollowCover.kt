package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.PlayerPlatform

/**
 * Tells the shell which cover is playing and which comes next. Call it from the root, where the song is known.
 *
 * It writes three plain values and nothing else: the palette follower (its own small scope, in [CosmoLookTheme]) reads them,
 * so a new song recomposes the follower and not the root's screens.
 */
@Composable
fun CosmoShellState.FollowCover(song: Song?, next: Song?) {
    val url = PlayerPlatform.host.rememberRemoteArtworkUrl(song)   // the art the player itself shows (WebDAV, SMB too)
    val nextUrl = next?.thumbnailUrl
    val album = song?.let { it.albumId ?: it.albumName }
    SideEffect {
        coverUrl = url
        nextCoverUrl = nextUrl
        coverAlbum = album
    }
}
