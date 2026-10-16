package com.cosmotune.video.host

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.video.ui.CosmoVideoApp
import com.cosmotune.video.ui.PendingVideo
import com.cosmotune.video.ui.VideoHostLinks
import com.music.bitchord.playback.LinkRequest
import com.music.bitchord.playback.MusicLink

/**
 * The phone's root while Video mode exists (hook table row 6): `MainActivity` draws Music's root, `BitChordApp`, inside [music], and
 * this host draws it, or Video's screen ([CosmoVideoApp]) in its place, by the stored mode ([com.cosmotune.settings.CosmoLookSettings.mode]).
 *
 * Video is drawn only when the new look is on (there is a shell), the stored mode is Video and Video is available
 * ([AppModes.available]); in every other case Music is drawn, so with the look off, or the mode on Music, the app is as it was. A stored
 * mode that is not available (Video, after Video was switched off again) is reset to Music.
 * The two are never composed together: each stands in its own [androidx.compose.runtime.saveable.SaveableStateHolder] slot, so a
 * flip of the mode keeps what a screen saved with `rememberSaveable` (the tab, the scroll position, Video's pages) and brings it back
 * when the mode comes back.
 *
 * What leaves composition when the mode flips: everything that Music's root holds in `remember` (its controller connection, its
 * transient state, its effects) is released and rebuilt on the way back; what it holds in `rememberSaveable` is kept by the
 * holder; the activity-scoped view models stay, and so does the music service, which keeps playing until a video takes audio
 * focus (the platform then pauses Music, which is why [VideoHostLinks.pauseMusic] is empty here). The host itself stays composed
 * across the flip, so the video that Music asked for (`asked` below) lives through it.
 *
 * Links that other apps hand to the activity ([MusicLink]) are served inside Music's composition. While Video is on screen a link to
 * one video is Video's (it opens the watch page); any other (an album, an artist, a search, "play music") switches the mode to Music so that
 * Music serves it.
 */
@Composable
fun PhoneModeHost(windowWidth: Dp, windowHeight: Dp, music: @Composable () -> Unit) {
    val shell = LocalCosmoShell.current
    val mode = shell?.settings?.mode?.collectAsStateWithLifecycle()?.value ?: AppMode.MUSIC
    val videoShell = shell?.takeIf { mode == AppMode.VIDEO && AppModes.available(AppMode.VIDEO) }
    // A stored mode that does not work (Video, after Video was switched off again) becomes Music: Music's own chrome draws only for the
    // stored mode, so without this a Music root would stand without its tab bar and its mini player.
    if (shell != null && !AppModes.available(mode)) LaunchedEffect(mode) { shell.settings.setMode(AppMode.MUSIC) }
    val holder = rememberSaveableStateHolder()
    // A video that Music asked for ("Watch the music video", Y-26 sets it); Video opens it first.
    val asked = remember { mutableStateOf<PendingVideo?>(null) }
    // The same call tree whether the new look is on or off: the look switch must not rebuild Music's root (part one, D2).
    if (videoShell == null) holder.SaveableStateProvider("music") { music() }
    else holder.SaveableStateProvider("video") { PhoneVideo(videoShell, windowWidth, windowHeight, asked) }
}

/** Video's screen with the phone's links, and the routing of the links that arrive while it is on screen. */
@Composable
private fun PhoneVideo(shell: CosmoShellState, windowWidth: Dp, windowHeight: Dp, asked: MutableState<PendingVideo?>) {
    val request = MusicLink.pending.collectAsStateWithLifecycle()
    // A request that Music must serve (a page, a search, "play music"): leave Video; Music's composition picks it up.
    LaunchedEffect(request.value) {
        if (belongsToMusic(request.value)) shell.settings.setMode(AppMode.MUSIC)
    }
    CosmoVideoApp(windowWidth, windowHeight, rememberPhoneLinks(shell, asked, request))
}

/**
 * The links Video gets from the phone: leave for Music, "play as a song" (Music serves a track link as it does for one shared from
 * another app), a video waiting to be opened and that it was. [asked] is read first, then a pending [LinkRequest.Track]; both are
 * snapshot state, so a link that arrives while Video is open is noticed.
 */
@Composable
private fun rememberPhoneLinks(shell: CosmoShellState, asked: MutableState<PendingVideo?>, request: State<LinkRequest?>): VideoHostLinks =
    remember(shell, asked, request) {
        val settings = shell.settings
        VideoHostLinks(
            switchToMusic = { settings.setMode(AppMode.MUSIC) },
            playAsSong = { id, _, _, _ ->
                // The mode first: the request must be pending when Music is already the mode, or Video would take it for its own.
                settings.setMode(AppMode.MUSIC)
                MusicLink.consume(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=" + Uri.encode(id))))
            },
            // Audio focus pauses Music when the video starts (the platform's rule, W2).
            pauseMusic = {},
            pendingVideo = { asked.value ?: if (settings.mode.value == AppMode.VIDEO) videoOf(request.value) else null },
            onPendingHandled = { if (asked.value != null) asked.value = null else MusicLink.handled() },
        )
    }

/** The video that a link asks for, or null: only a link to one video ([LinkRequest.Track]) is Video's. */
internal fun videoOf(request: LinkRequest?): PendingVideo? = (request as? LinkRequest.Track)?.let { PendingVideo(it.videoId, null) }

/** Whether a link that arrived while Video is on screen is Music's (a page, a search, "play music"): the host then leaves Video. */
internal fun belongsToMusic(request: LinkRequest?): Boolean = request != null && request !is LinkRequest.Track
