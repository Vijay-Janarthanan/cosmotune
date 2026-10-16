package com.cosmotune.video.host

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.desktop.LocalCosmoPlayPause
import com.cosmotune.desktop.LocalCosmoPlaying
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.video.graph.stopVideoForMusic
import com.cosmotune.video.ui.PendingVideo
import com.cosmotune.video.ui.VideoHostLinks
import com.cosmotune.video.ui.desktop.VideoDesktopPage
import com.music.bitchord.data.model.Song

/**
 * The desktop's side of Video mode (hook table rows 8 and 9). The window keeps Music's frame; while the stored mode is Video
 * ([desktopVideoShown]) the page, the sidebar, the top bar's back button and drawer entries and the keys follow it, and the music
 * player bar and side panel stand aside. [active] is the same fact for code that is not composable (the window's key handler), kept up
 * to date by [desktopVideoShown].
 */
object DesktopModeHost {
    /** True while Video is the mode that is drawn. Written by [desktopVideoShown] from a side effect; read by the key handler. */
    @Volatile
    var active: Boolean = false

    /**
     * A video waiting to be opened, set by the host when Music asks for one ("Watch the music video", Y-26). It is snapshot state, so a
     * request that arrives while Video is open is noticed; [DesktopVideoPage]'s links read it and clear it when the page is opened.
     */
    var askedVideo: PendingVideo? by mutableStateOf(null)
}

/** "Play this song" in Music, provided by the desktop root (Y-26); null where nothing provides it. */
val LocalDesktopPlaySong = staticCompositionLocalOf<((Song) -> Unit)?> { null }

/**
 * Whether Video is the mode that is drawn: the new look is on (there is a shell), the stored mode is Video and Video is available
 * ([AppModes.available]); the phone's host uses the same rule. It reads the stored mode, so a composable that calls it recomposes when
 * the mode changes, and it keeps [DesktopModeHost.active] equal to the answer.
 */
@Composable
fun desktopVideoShown(): Boolean {
    val shell = LocalCosmoShell.current
    val mode = shell?.settings?.mode?.collectAsState()?.value ?: AppMode.MUSIC
    val shown = shell != null && mode == AppMode.VIDEO && AppModes.available(AppMode.VIDEO)
    // A stored mode that does not work (Video, after Video was switched off again) becomes Music: Music's own chrome draws only for the
    // stored mode, so without this the Music window would stand without its bottom bar.
    if (shell != null && !AppModes.available(mode)) LaunchedEffect(mode) { shell.settings.setMode(AppMode.MUSIC) }
    SideEffect { DesktopModeHost.active = shown }
    return shown
}

/**
 * Video's page in the window's content area, with the desktop's links: leave for Music, "play as a song" (the mode becomes Music, then
 * Music plays the song if the root has provided [LocalDesktopPlaySong]), pause Music when a video starts and the video that Music asked
 * for. The playing flag and the actions are read in composition and kept current, so a link used later sees the player as it is then.
 */
@Composable
fun DesktopVideoPage(contentPadding: PaddingValues) {
    val shell = LocalCosmoShell.current ?: return
    val playing = rememberUpdatedState(LocalCosmoPlaying.current)
    val playPause = rememberUpdatedState(LocalCosmoPlayPause.current)
    val playSong = rememberUpdatedState(LocalDesktopPlaySong.current)
    val links = remember(shell) {
        VideoHostLinks(
            switchToMusic = { shell.settings.setMode(AppMode.MUSIC) },
            playAsSong = { id, title, channel, thumbnailUrl ->
                shell.settings.setMode(AppMode.MUSIC)
                playSong.value?.invoke(Song(videoId = id, title = title, artist = channel, thumbnailUrl = thumbnailUrl, isVideo = true))
            },
            pauseMusic = { if (playing.value) playPause.value?.invoke() },
            pendingVideo = { DesktopModeHost.askedVideo },
            onPendingHandled = { DesktopModeHost.askedVideo = null },
        )
    }
    // Audio is exclusive across the modes: Music starting while a video plays (the system's media controls can start it) stops the
    // video; a video starting pauses Music through `pauseMusic` above.
    val musicPlaying = LocalCosmoPlaying.current
    LaunchedEffect(musicPlaying) { if (musicPlaying) stopVideoForMusic() }
    VideoDesktopPage(contentPadding, links)
}
