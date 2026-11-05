package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import com.cosmotune.design.AppMode
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.components.FLOATING_BAR_MAX_WIDTH
import com.cosmotune.music.sharedui.resources.library
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.shell.CosmoBottomChrome
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.nav.VideoTab
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.resources.video_tab_home
import com.cosmotune.video.resources.video_tab_subscriptions
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.ui.VideoIcons
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import androidx.compose.runtime.getValue

/**
 * The four tabs of the dock (DESIGN_SYSTEM.md 5.2): Home, Shorts, Subscriptions and Library. Shorts is not a stack of its own: its tab
 * opens the Shorts pager over the page that is shown. Home, Subscriptions and Library are [VideoTab.HOME], [VideoTab.SUBSCRIPTIONS]
 * and [VideoTab.LIBRARY] (spec 5.1). The Library's name is the app's own word, translated in every language.
 */
@Composable
internal fun videoTabs(): List<CosmoTab> {
    val home = stringResource(Res.string.video_tab_home)
    val shorts = stringResource(Res.string.video_shorts)
    val subscriptions = stringResource(Res.string.video_tab_subscriptions)
    val library = stringResource(SharedRes.string.library)
    return remember(home, shorts, subscriptions, library) {
        listOf(
            CosmoTab(home, CosmoIcons.Home, CosmoIcons.HomeFill),
            CosmoTab(shorts, VideoIcons.Shorts, VideoIcons.Shorts),
            // The set has one Subscriptions glyph (outlined); the active tab's pill draws it on the ink fill.
            CosmoTab(subscriptions, VideoIcons.Subscriptions, VideoIcons.Subscriptions),
            CosmoTab(library, CosmoIcons.Library, CosmoIcons.LibraryFill),
        )
    }
}

private const val TAB_HOME = 0
private const val TAB_SHORTS = 1
private const val TAB_SUBSCRIPTIONS = 2
private const val TAB_LIBRARY = 3

/**
 * Video's dock (DESIGN_SYSTEM.md 4.16): the shared `CosmoBottomChrome`, with `mode = AppMode.VIDEO` so it draws only while the stored mode
 * is Video, the tabs (when [showTabs]) and, while a video is loaded and [capsuleShown] (the caller says no on the watch page and in
 * the Shorts pager), the shared mini player above them.
 *
 * A tab picks its stack ([VideoNavigator.selectTab]); the mini player's play button toggles playback, next plays the queue's next video,
 * close stops the video, and a tap on it opens the watch page of the video that is loaded. Next to the Shorts pager the dock is dark in
 * both themes, with the Shorts tab chosen. [onHeightChanged] gets the dock's height in pixels.
 */
@Composable
fun VideoBottomChrome(
    navigator: VideoNavigator,
    playback: VideoPlayback,
    hazeState: HazeState?,
    capsuleShown: Boolean,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
    showTabs: Boolean = true,
) {
    val capsule by rememberVideoCapsule(playback)
    val position = rememberCapsulePosition(playback, capsule)
    val expand: () -> Unit = {
        capsule.song?.let {
            if (LocalVideo.isLocal(it.videoId)) navigator.push(VideoRoute.LocalPlayer(it.videoId)) else navigator.push(VideoRoute.Watch(it.videoId))
        }
    }
    val onShorts = navigator.top is VideoRoute.Shorts
    val song = capsule.song.takeIf { capsuleShown }
    // Without the tabs and without a video the dock has nothing to hold.
    if (!showTabs && song == null) return
    CosmoBottomChrome(
        tabs = videoTabs(),
        selectedIndex = when {
            onShorts -> TAB_SHORTS
            else -> when (navigator.tab) {
                VideoTab.HOME -> TAB_HOME
                VideoTab.SUBSCRIPTIONS -> TAB_SUBSCRIPTIONS
                VideoTab.LIBRARY -> TAB_LIBRARY
            }
        },
        onTabSelected = { index ->
            when (index) {
                TAB_HOME -> navigator.selectTab(VideoTab.HOME)
                TAB_SHORTS -> if (!onShorts) navigator.push(VideoRoute.Shorts(ShortsOrigin.Home))
                TAB_SUBSCRIPTIONS -> navigator.selectTab(VideoTab.SUBSCRIPTIONS)
                else -> navigator.selectTab(VideoTab.LIBRARY)
            }
        },
        song = song,
        isPlaying = capsule.playing,
        isLoading = capsule.loading,
        position = position,
        durationMs = capsule.durationMs,
        onPlayPause = { playback.togglePlayPause() },
        onNext = { playback.playNext() },
        onExpand = expand,
        controlsLocked = false,
        onBlockedControl = {},
        hazeState = hazeState,
        onHeightChanged = onHeightChanged,
        modifier = modifier.widthIn(max = FLOATING_BAR_MAX_WIDTH).fillMaxWidth(),
        mode = AppMode.VIDEO,
        onClose = { playback.stop() },
        showTabs = showTabs,
        dark = onShorts,
        ownMedia = true,
    )
}

/**
 * What the mini capsule shows of the playing video (spec 5.1), as one value: the video as the song the capsule draws ([song]: its
 * title, the channel as the artist, its thumbnail as the cover), whether it plays or is still loading, and its length for the
 * progress line (0 for a live stream, which has none). [song] is null while nothing is loaded.
 */
@Immutable
internal data class VideoCapsuleModel(val song: Song?, val playing: Boolean, val loading: Boolean, val durationMs: Long) {
    companion object {
        /** Nothing is loaded. */
        val None = VideoCapsuleModel(song = null, playing = false, loading = false, durationMs = 0L)
    }
}

/** The capsule's view of this state: [VideoCapsuleModel.None] until a video is loaded, and again after [VideoPlayback.stop]. */
internal fun PlaybackUiState.toCapsule(): VideoCapsuleModel {
    val video = item
    if (video == null || status == PlayerStatus.IDLE) return VideoCapsuleModel.None
    return VideoCapsuleModel(
        song = video.asCapsuleSong(),
        playing = playing,
        loading = status == PlayerStatus.LOADING || status == PlayerStatus.BUFFERING,
        durationMs = if (live) 0L else durationMs,
    )
}

/** A video as the song the shared capsule draws: the capsule is Music's building block and takes a `Song`. */
internal fun VideoItem.asCapsuleSong(): Song =
    Song(videoId = id, title = title, artist = channelName.orEmpty(), thumbnailUrl = thumbnailUrl, isVideo = true)

/**
 * The capsule's model, kept up to date. Only a change of the model recomposes the reader: the engine's buffered figure and the
 * rest of the state move often and the capsule shows none of them.
 */
@Composable
internal fun rememberVideoCapsule(playback: VideoPlayback): State<VideoCapsuleModel> {
    val changes = remember(playback) { playback.state.map { it.toCapsule() }.distinctUntilChanged() }
    return changes.collectAsState(initial = playback.state.value.toCapsule())
}

/**
 * The playhead for the capsule's progress line: a reading twice a second while a video plays (and one when it starts, stops or
 * is replaced), written to a [PlaybackPosition], which the capsule reads only in its draw block. Nothing here recomposes.
 */
@Composable
internal fun rememberCapsulePosition(playback: VideoPlayback, model: VideoCapsuleModel): PlaybackPosition {
    val position = remember(playback) { PlaybackPosition() }
    val shown = model.song != null
    val playing = model.playing
    LaunchedEffect(playback, shown, playing, model.song?.videoId) {
        if (!shown) return@LaunchedEffect
        position.report(playback.positionMs())
        while (playing) {
            delay(TICK_MS)
            position.report(playback.positionMs())
        }
    }
    return position
}

private const val TICK_MS = 500L
