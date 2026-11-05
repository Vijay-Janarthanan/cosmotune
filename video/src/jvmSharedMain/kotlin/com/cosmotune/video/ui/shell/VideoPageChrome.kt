package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.cosmotune.ui.chrome.CosmoDrawerEntry
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channels
import com.cosmotune.video.resources.video_downloads
import com.cosmotune.video.resources.video_drawer_downloads
import com.cosmotune.video.resources.video_drawer_history
import com.cosmotune.video.resources.video_drawer_import
import com.cosmotune.video.resources.video_drawer_settings
import com.cosmotune.video.resources.video_history
import com.cosmotune.video.resources.video_import
import com.cosmotune.video.resources.video_liked
import com.cosmotune.video.resources.video_local_title
import com.cosmotune.video.resources.video_saved_playlists
import com.cosmotune.video.resources.video_search
import com.cosmotune.video.resources.video_tab_home
import com.cosmotune.video.resources.video_tab_subscriptions
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.music.sharedui.resources.library
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * What Video gives the shared top bar: the name of the page on show, the rows of the profile drawer, and how far the page has scrolled.
 */

/** The name of a page for the top bar; empty where the page knows it better (a channel's or a playlist's own name). */
@Composable
internal fun routeTitle(route: VideoRoute): String = when (route) {
    VideoRoute.Home -> stringResource(Res.string.video_tab_home)
    VideoRoute.Subscriptions -> stringResource(Res.string.video_tab_subscriptions)
    VideoRoute.Library -> stringResource(SharedRes.string.library)
    is VideoRoute.Search -> stringResource(Res.string.video_search)
    VideoRoute.History -> stringResource(Res.string.video_history)
    VideoRoute.WatchLater -> stringResource(Res.string.video_watch_later)
    VideoRoute.Liked -> stringResource(Res.string.video_liked)
    VideoRoute.SavedPlaylists -> stringResource(Res.string.video_saved_playlists)
    VideoRoute.Channels -> stringResource(Res.string.video_channels)
    VideoRoute.Downloads -> stringResource(Res.string.video_downloads)
    VideoRoute.Settings -> stringResource(Res.string.video_drawer_settings)
    VideoRoute.Import -> stringResource(Res.string.video_import)
    VideoRoute.LocalFiles -> stringResource(Res.string.video_local_title)
    is VideoRoute.LocalPlayer, is VideoRoute.Watch, is VideoRoute.Shorts, is VideoRoute.Channel, is VideoRoute.Playlist, is VideoRoute.LocalPlaylist -> ""
}

/**
 * The rows of the profile drawer below the modes (spec 5.1): History, Downloads, Video settings and Import, each a page pushed on
 * [navigator]. The phone's top bar and the desktop's top bar both list them.
 */
@Composable
internal fun videoDrawerEntries(navigator: VideoNavigator): List<CosmoDrawerEntry> {
    val history = stringResource(Res.string.video_drawer_history)
    val downloads = stringResource(Res.string.video_drawer_downloads)
    val settings = stringResource(Res.string.video_drawer_settings)
    val importLabel = stringResource(Res.string.video_drawer_import)
    return remember(navigator, history, downloads, settings, importLabel) {
        listOf(
            CosmoDrawerEntry(VideoIcons.History, history, onClick = { navigator.push(VideoRoute.History) }),
            CosmoDrawerEntry(CosmoIcons.Download, downloads, onClick = { navigator.push(VideoRoute.Downloads) }),
            CosmoDrawerEntry(CosmoIcons.Settings, settings, onClick = { navigator.push(VideoRoute.Settings) }),
            CosmoDrawerEntry(VideoIcons.Upload, importLabel, onClick = { navigator.push(VideoRoute.Import) }),
        )
    }
}

/**
 * How far the page on show has been scrolled, for the top bar's title: whatever scrolls under [connection] reports what it
 * consumed, so no page has to know about the bar. The distance is kept per page ([show]), so a page that is returned to is as
 * scrolled as it was left (its own list state is restored by the nav host). [offset] is snapshot state: read it only inside a
 * `derivedStateOf`, which answers once when the title should appear, never per scroll step.
 */
@Stable
internal class TopBarScroll {
    private val left = HashMap<String, Float>()
    private var key = ""

    /** The scrolled distance of the page on show, in pixels, never negative. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** Hand it to `Modifier.nestedScroll` above the pages. */
    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y != 0f) offset = (offset - consumed.y).coerceAtLeast(0f)
            return Offset.Zero
        }
    }

    /** The page with this key is on show now: the one before keeps its distance, this one gets back its own. */
    fun show(pageKey: String) {
        if (pageKey == key) return
        if (left.size >= KEPT) left.clear()
        left[key] = offset
        key = pageKey
        offset = left[pageKey] ?: 0f
    }

    private companion object {
        const val KEPT = 64
    }
}
