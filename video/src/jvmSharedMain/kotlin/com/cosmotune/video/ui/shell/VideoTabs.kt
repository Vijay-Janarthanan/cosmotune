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
import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.nav.VideoTab
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_tab_home
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.resources.video_tab_subscriptions
import com.cosmotune.video.resources.video_tab_you
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.music.ui.components.FLOATING_BAR_MAX_WIDTH
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/**
 * The four tabs of the capsule, as the video apps have them: Home, Shorts, Subscriptions and You. Shorts is not a stack of its own: its
 * tab opens the Shorts pager over the page that is shown. Home, Subscriptions and You are [VideoTab.HOME], [VideoTab.SUBSCRIPTIONS]
 * and [VideoTab.LIBRARY] (spec 5.1).
 */
@Composable
internal fun videoTabs(): List<CosmoTab> {
    val home = stringResource(Res.string.video_tab_home)
    val shorts = stringResource(Res.string.video_shorts)
    val subscriptions = stringResource(Res.string.video_tab_subscriptions)
    val you = stringResource(Res.string.video_tab_you)
    return remember(home, shorts, subscriptions, you) {
        listOf(
            CosmoTab(home, CosmoIcons.Home, CosmoIcons.HomeFill),
            CosmoTab(shorts, VideoIcons.Shorts, VideoIcons.Shorts),
            // The set has one Subscriptions glyph (outlined); the active tab's pill draws it on the ink fill.
            CosmoTab(subscriptions, VideoIcons.Subscriptions, VideoIcons.Subscriptions),
            CosmoTab(you, CosmoIcons.Library, CosmoIcons.LibraryFill),
        )
    }
}

private const val TAB_HOME = 0
private const val TAB_SHORTS = 1
private const val TAB_SUBSCRIPTIONS = 2
private const val TAB_YOU = 3

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
        selectedIndex = when (navigator.tab) {
            VideoTab.HOME -> TAB_HOME
            VideoTab.SUBSCRIPTIONS -> TAB_SUBSCRIPTIONS
            VideoTab.LIBRARY -> TAB_YOU
        },
        onTabSelected = { index ->
            when (index) {
                TAB_HOME -> navigator.selectTab(VideoTab.HOME)
                TAB_SHORTS -> navigator.push(VideoRoute.Shorts(ShortsOrigin.Home))
                TAB_SUBSCRIPTIONS -> navigator.selectTab(VideoTab.SUBSCRIPTIONS)
                else -> navigator.selectTab(VideoTab.LIBRARY)
            }
        },
        song = if (capsuleShown) capsule.song else null,
        isPlaying = capsule.playing,
        isLoading = capsule.loading,
        position = position,
        durationMs = capsule.durationMs,
        onPlayPause = { playback.togglePlayPause() },
        onNext = { playback.playNext() },
        onExpand = {
            capsule.song?.let {
                if (LocalVideo.isLocal(it.videoId)) navigator.push(VideoRoute.LocalPlayer(it.videoId)) else navigator.push(VideoRoute.Watch(it.videoId))
            }
        },
        controlsLocked = false,
        onBlockedControl = {},
        hazeState = hazeState,
        onHeightChanged = onHeightChanged,
        modifier = modifier.widthIn(max = FLOATING_BAR_MAX_WIDTH).fillMaxWidth(),
        mode = AppMode.VIDEO,
    )
}
