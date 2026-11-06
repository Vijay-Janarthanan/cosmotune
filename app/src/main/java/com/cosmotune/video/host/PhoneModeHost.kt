package com.cosmotune.video.host

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.music.auth.SignInRequest
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
    // Video's account is Music's sign-in, published by MainViewModel; a launch straight into Video has composed no Music yet, so the
    // view model is asked for here, and the account is known as soon as Video opens.
    if (videoShell != null) androidx.lifecycle.viewmodel.compose.viewModel<com.cosmotune.music.ui.MainViewModel>()
    // A stored mode that does not work (Video, after Video was switched off again) becomes Music: Music's own chrome draws only for the
    // stored mode, so without this a Music root would stand without its tab bar and its mini player.
    if (shell != null && !AppModes.available(mode)) LaunchedEffect(mode) { shell.settings.setMode(AppMode.MUSIC) }
    val holder = rememberSaveableStateHolder()
    // Video asks for the Google sign-in in place: PhoneVideo draws the one login screen over Video (see there); Music is not made the mode.
    // A video that Music asked for ("Watch the music video", Y-26 sets it); Video opens it first.
    val asked = remember { mutableStateOf<PendingVideo?>(null) }
    OpenVideoFiles(shell, asked)
    // A video the room plays ([RoomVideo]) is opened the same way: the video first, then the mode.
    val roomVideo = RoomVideo.opening.collectAsStateWithLifecycle()
    LaunchedEffect(roomVideo.value, shell) {
        val wanted = roomVideo.value ?: return@LaunchedEffect
        if (shell != null && AppModes.available(AppMode.VIDEO)) {
            asked.value = wanted
            shell.settings.setMode(AppMode.VIDEO)
        }
        RoomVideo.opening.value = null
    }
    // Music's way into Video: no shell (the look off) or Video not available, no links. One identity per shell, so the static local
    // changes only when the look does.
    val links = remember(shell, asked) { shell?.let { phoneModeLinks(it.settings, asked) } }
    val profileAction: (@Composable () -> Unit)? = if (shell != null && (mode == AppMode.MANGA || mode == AppMode.READ || mode == AppMode.PODIO || mode == AppMode.WATCH)) {
        { com.cosmotune.ui.chrome.PhoneModeProfileButton(shell.settings, mode) }
    } else null
    // The room, around every mode: "Play in room" on any page, the room icon, the full-screen room and its video ([RoomVideo]).
    com.cosmotune.music.ui.room.PhoneRoomRoot(RoomVideo) {
    CompositionLocalProvider(com.cosmotune.ui.blocks.LocalModeProfileAction provides profileAction) {
    CompositionLocalProvider(LocalModeLinks provides links?.takeIf { AppModes.available(AppMode.VIDEO) }) {
        // The same call tree whether the new look is on or off: the look switch must not rebuild Music's root (part one, D2).
        if (shell != null && mode == AppMode.MANGA) holder.SaveableStateProvider("manga") {
            androidx.activity.compose.BackHandler { shell.settings.setMode(AppMode.MUSIC) }
            val request = MusicLink.pending.collectAsStateWithLifecycle()
            LaunchedEffect(request.value) { if (request.value != null) shell.settings.setMode(AppMode.MUSIC) }
            com.cosmotune.manga.ui.MangaApp(onMusic = { shell.settings.setMode(AppMode.MUSIC) }, showLeave = true)
        }
        else if (shell != null && mode == AppMode.READ) holder.SaveableStateProvider("read") {
            androidx.activity.compose.BackHandler { shell.settings.setMode(AppMode.MUSIC) }
            val request = MusicLink.pending.collectAsStateWithLifecycle()
            LaunchedEffect(request.value) { if (request.value != null) shell.settings.setMode(AppMode.MUSIC) }
            com.cosmotune.reading.ui.ReadApp(onMusic = { shell.settings.setMode(AppMode.MUSIC) }, showLeave = true)
        }
        else if (shell != null && mode == AppMode.PODIO) holder.SaveableStateProvider("podio") {
            val request = MusicLink.pending.collectAsStateWithLifecycle()
            LaunchedEffect(request.value) { if (request.value != null) shell.settings.setMode(AppMode.MUSIC) }
            com.cosmotune.podio.PhonePodioPage(onMusic = { shell.settings.setMode(AppMode.MUSIC) })
        }
        else if (shell != null && mode == AppMode.WATCH) holder.SaveableStateProvider("watch") {
            // Back closes Watch's top page, then goes Home, then leaves for Music.
            androidx.activity.compose.BackHandler { if (!com.cosmotune.ui.watch.WatchNav.back()) shell.settings.setMode(AppMode.MUSIC) }
            val request = MusicLink.pending.collectAsStateWithLifecycle()
            LaunchedEffect(request.value) { if (request.value != null) shell.settings.setMode(AppMode.MUSIC) }
            com.cosmotune.ui.watch.ProvideWatch(qrCode = { payload, size -> com.cosmotune.music.ui.components.QrCode(payload, size = size) }) {
                com.cosmotune.ui.watch.WatchApp(
                    contentPadding = androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues(),
                    showNavigation = true,
                )
            }
        }
        else if (videoShell == null) holder.SaveableStateProvider("music") { music() }
        // While the room screen draws the room's video over the app ([RoomVideo.covered]), Video's pages step aside so the one player
        // has one picture; the holder keeps what they saved, as on a mode flip.
        else if (RoomVideo.covered) Unit
        else holder.SaveableStateProvider("video") { PhoneVideo(videoShell, windowWidth, windowHeight, asked) }
    }
    }
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
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
    val links = rememberPhoneLinks(shell, asked, request)
    // The room's video follower reaches Video's player through the graph these links build ([RoomVideo.Attach]).
    RoomVideo.Attach(links)
    CosmoVideoApp(windowWidth, windowHeight, links)
    // The one Google sign-in of the app, opened from Video's account sheet and drawn over Video: the same screen and the same
    // validation Music uses (MainViewModel.onWebSession), so one sign-in serves both modes and Video stays where it was.
    val signIn = SignInRequest.pending.collectAsStateWithLifecycle()
    if (signIn.value) {
        val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<com.cosmotune.music.ui.MainViewModel>()
        com.cosmotune.music.auth.YouTubeSignInOverlay(
            mode = com.cosmotune.music.auth.WebSessionMode.SIGN_IN,
            viewModel = viewModel,
            initialCookie = null,
            onClose = { SignInRequest.handled() },
            onSignedIn = { SignInRequest.handled() },
        )
    }
    }
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

/**
 * A video file that another app opens with Cosmotune ("Open with" in a file manager, a gallery, a download) is Video's, and plays in its
 * local player: the activity's launch intent and every later one ([ComponentActivity.addOnNewIntentListener]) are read here, so the
 * activity itself knows nothing of Video. The file is handed to Video as the video [asked] waits for, and the mode becomes Video; where
 * Video is not available (or the new look is off) the file is dropped.
 */
@Composable
private fun OpenVideoFiles(shell: CosmoShellState?, asked: MutableState<PendingVideo?>) {
    val context = LocalContext.current
    DisposableEffect(context, shell, asked) {
        val activity = context.findComponentActivity()
        if (activity == null) {
            onDispose { }
        } else {
            val open = { intent: Intent? ->
                val file = LocalVideoLink.read(activity, intent)
                if (file != null && shell != null && AppModes.available(AppMode.VIDEO)) {
                    asked.value = file
                    shell.settings.setMode(AppMode.VIDEO)
                }
            }
            open(activity.intent)
            val listener = Consumer<Intent> { open(it) }
            activity.addOnNewIntentListener(listener)
            onDispose { activity.removeOnNewIntentListener(listener) }
        }
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** The video that a link asks for, or null: only a link to one video ([LinkRequest.Track]) is Video's. */
internal fun videoOf(request: LinkRequest?): PendingVideo? = (request as? LinkRequest.Track)?.let { PendingVideo(it.videoId, null) }

/** Whether a link that arrived while Video is on screen is Music's (a page, a search, "play music"): the host then leaves Video. */
internal fun belongsToMusic(request: LinkRequest?): Boolean = request != null && request !is LinkRequest.Track
