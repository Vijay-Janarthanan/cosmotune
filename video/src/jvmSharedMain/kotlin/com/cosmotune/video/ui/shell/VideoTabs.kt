package com.cosmotune.video.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.cosmotune.design.AppMode
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.shell.CosmoBottomChrome
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.nav.VideoTab
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_tab_home
import com.cosmotune.video.resources.video_tab_library
import com.cosmotune.video.resources.video_tab_subscriptions
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.music.ui.components.FLOATING_BAR_MAX_WIDTH
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/** The three tabs of the capsule, in the order of [VideoTab.entries]: Home, Subscriptions, Library (spec 5.1). */
@Composable
internal fun videoTabs(): List<CosmoTab> {
    val home = stringResource(Res.string.video_tab_home)
    val subscriptions = stringResource(Res.string.video_tab_subscriptions)
    val library = stringResource(Res.string.video_tab_library)
    return remember(home, subscriptions, library) {
        listOf(
            CosmoTab(home, CosmoIcons.Home, CosmoIcons.HomeFill),
            // The set has one Subscriptions glyph (outlined); the active tab's pill draws it on the ink fill.
            CosmoTab(subscriptions, VideoIcons.Subscriptions, VideoIcons.Subscriptions),
            CosmoTab(library, CosmoIcons.Library, CosmoIcons.LibraryFill),
        )
    }
}

/**
 * The floating chrome of Video (spec 5.1): the tab capsule, and above it the mini capsule while a video is loaded and
 * [capsuleShown] (the caller says no on the watch page and in the Shorts pager). It is `CosmoBottomChrome` with
 * `mode = AppMode.VIDEO`, so it draws only while the stored mode is Video, as Music's own draws only while it is Music.
 *
 * A tab picks its stack ([VideoNavigator.selectTab]); the capsule's play button toggles playback, its next button plays the
 * queue's next video, and a tap on it opens the watch page of the video that is loaded. [onHeightChanged] gets the column's
 * height in pixels, as the chrome reports it.
 */
@Composable
internal fun VideoBottomChrome(
    navigator: VideoNavigator,
    playback: VideoPlayback,
    hazeState: HazeState?,
    capsuleShown: Boolean,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
) {
    val capsule by rememberVideoCapsule(playback)
    val position = rememberCapsulePosition(playback, capsule)
    CosmoBottomChrome(
        tabs = videoTabs(),
        selectedIndex = navigator.tab.ordinal,
        onTabSelected = { index -> navigator.selectTab(VideoTab.entries[index]) },
        song = if (capsuleShown) capsule.song else null,
        isPlaying = capsule.playing,
        isLoading = capsule.loading,
        position = position,
        durationMs = capsule.durationMs,
        onPlayPause = { playback.togglePlayPause() },
        onNext = { playback.playNext() },
        onExpand = { capsule.song?.let { navigator.push(VideoRoute.Watch(it.videoId)) } },
        controlsLocked = false,
        onBlockedControl = {},
        hazeState = hazeState,
        onHeightChanged = onHeightChanged,
        modifier = modifier.widthIn(max = FLOATING_BAR_MAX_WIDTH).fillMaxWidth(),
        mode = AppMode.VIDEO,
    )
}
