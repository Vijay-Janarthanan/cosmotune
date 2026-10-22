package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.player.PlayerPlatform

/**
 * Tells the shell which cover is playing and which comes next. Call it from the root, where the song is known.
 *
 * It writes three plain values and nothing else: the palette follower (its own small scope, in [CosmoLookTheme]) reads them,
 * so a new song recomposes the follower and not the root's screens.
 *
 * While Video is the mode that is drawn it writes nothing: the playing video sets the accent then ([CosmoShellState.followCover]), and the
 * desktop's root, which calls this in every mode, must not take it back whenever the Music song or its queue changes. When the mode
 * returns to Music this writes the song's cover again.
 */
@Composable
fun CosmoShellState.FollowCover(song: Song?, next: Song?) {
    val url = PlayerPlatform.host.rememberRemoteArtworkUrl(song)   // the art the player itself shows (WebDAV, SMB too)
    val nextUrl = next?.thumbnailUrl
    val album = song?.let { it.albumId ?: it.albumName }
    val videoShown = settings.mode.collectAsState().value == AppMode.VIDEO && AppModes.available(AppMode.VIDEO)
    SideEffect {
        if (videoShown) return@SideEffect
        coverUrl = url
        nextCoverUrl = nextUrl
        coverAlbum = album
    }
}
