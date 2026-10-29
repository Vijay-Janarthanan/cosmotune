package com.cosmotune.video.host

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.ui.modes.LocalModeLinks
import com.cosmotune.ui.modes.ModeLinks
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.video.ui.CosmoVideoApp
import com.cosmotune.video.ui.PendingVideo
import com.cosmotune.video.ui.VideoHostLinks
import com.cosmotune.music.playback.LinkRequest
import com.cosmotune.music.playback.MusicLink

/**
 * The phone's root while Video mode exists (hook table row 6): `MainActivity` draws Music's root, `CosmotuneApp`, inside [music], and
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
 *
 * Music's own way into Video, "Watch the music video" in its Now Playing (spec section 9), reads [LocalModeLinks], which this host
 * provides around both slots only while Video is available and the new look is on ([phoneModeLinks]); otherwise Music sees null
 * and draws no such tool.
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
    // Music's way into Video: no shell (the look off) or Video not available, no links. One identity per shell, so the static local
    // changes only when the look does.
    val links = remember(shell, asked) { shell?.let { phoneModeLinks(it.settings, asked) } }
    CompositionLocalProvider(LocalModeLinks provides links?.takeIf { AppModes.available(AppMode.VIDEO) }) {
        // The same call tree whether the new look is on or off: the look switch must not rebuild Music's root (part one, D2).
        if (shell != null && mode == AppMode.MANGA) holder.SaveableStateProvider("manga") {
            val request = MusicLink.pending.collectAsStateWithLifecycle()
            LaunchedEffect(request.value) { if (request.value != null) shell.settings.setMode(AppMode.MUSIC) }
            com.cosmotune.manga.ui.MangaApp(onMusic = { shell.settings.setMode(AppMode.MUSIC) }, showLeave = true)
        }
        else if (shell != null && mode == AppMode.PODIO) holder.SaveableStateProvider("podio") {
            val request = MusicLink.pending.collectAsStateWithLifecycle()
            LaunchedEffect(request.value) { if (request.value != null) shell.settings.setMode(AppMode.MUSIC) }
            com.cosmotune.podio.PhonePodioPage(onMusic = { shell.settings.setMode(AppMode.MUSIC) })
        }
        else if (videoShell == null) holder.SaveableStateProvider("music") { music() }
        else holder.SaveableStateProvider("video") { PhoneVideo(videoShell, windowWidth, windowHeight, asked) }
    }
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

/**
 * Music's way into Video on the phone (spec section 9): "Watch the music video" keeps the video and its moment in [asked], which
 * [rememberPhoneLinks] hands to Video first, then makes Video the mode, so Video opens the watch page as it appears. Music is left
 * playing: the video takes audio focus when it starts, and the platform pauses Music then (W2).
 */
internal fun phoneModeLinks(settings: CosmoLookSettings, asked: MutableState<PendingVideo?>): ModeLinks = object : ModeLinks {
    override fun watchMusicVideo(videoId: String, title: String, artist: String, atVideoMs: Long?) {
        asked.value = PendingVideo(videoId, atVideoMs)
        settings.setMode(AppMode.VIDEO)
    }
}

/** The video that a link asks for, or null: only a link to one video ([LinkRequest.Track]) is Video's. */
internal fun videoOf(request: LinkRequest?): PendingVideo? = (request as? LinkRequest.Track)?.let { PendingVideo(it.videoId, null) }

/** Whether a link that arrived while Video is on screen is Music's (a page, a search, "play music"): the host then leaves Video. */
internal fun belongsToMusic(request: LinkRequest?): Boolean = request != null && request !is LinkRequest.Track
