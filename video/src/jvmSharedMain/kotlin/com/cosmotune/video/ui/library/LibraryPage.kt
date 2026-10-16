package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channels
import com.cosmotune.video.resources.video_count_videos
import com.cosmotune.video.resources.video_downloads
import com.cosmotune.video.resources.video_history
import com.cosmotune.video.resources.video_import_export
import com.cosmotune.video.resources.video_library
import com.cosmotune.video.resources.video_liked
import com.cosmotune.video.resources.video_new_playlist
import com.cosmotune.video.resources.video_saved_playlists
import com.cosmotune.video.resources.video_shelf_continue
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.resources.video_your_playlists
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.actions.NewPlaylistDialog
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Library tab (spec 5.8, by analogy with `V2.library`), top to bottom: "Library" in `display(DISPLAY_M)` (`DISPLAY_L` when [wide])
 * with the 40 dp tonal plus that makes a playlist; "Continue watching" (shelf cards with the watched bar, spec 5.2's rule) when there
 * is something to continue; the rows with their 40 dp tonal icon tiles (History, Watch later, Liked videos, Downloads, Channels,
 * Saved playlists with their counts, then Import and export), each opening its page; then "Your playlists", a row each with the first
 * video's picture as its cover, opening the playlist. [contentPadding] and [wide] are the nav host's.
 */
@Composable
fun LibraryPage(contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val library = graph.library
    val navigator = LocalVideoNavigator.current
    val actions = rememberVideoActions()
    val history by library.history.collectAsState()
    val watchLater by library.watchLater.collectAsState()
    val liked by library.liked.collectAsState()
    val subscriptions by library.subscriptions.collectAsState()
    val saved by library.savedPlaylists.collectAsState()
    val playlists by library.playlists.collectAsState()
    val downloadCount by remember(graph.downloads) { graph.downloads.downloads.map { it.size }.distinctUntilChanged() }
        .collectAsState(graph.downloads.downloads.value.size)
    val downloadedIds by rememberDownloadedIds()

    val continuing = remember(history) { LibraryPlan.continueWatching(history) }
    val continuingItems = remember(continuing) { continuing.map { it.item } }
    val fractions = remember(continuing) { LibraryPlan.fractions(continuing) }

    var newPlaylist by rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }

    val links = listOf(
        LibraryLink(VideoIcons.History, Res.string.video_history, count = null, route = VideoRoute.History),
        LibraryLink(CosmoIcons.Clock, Res.string.video_watch_later, count = watchLater.size, route = VideoRoute.WatchLater),
        LibraryLink(CosmoIcons.HeartFill, Res.string.video_liked, count = liked.size, route = VideoRoute.Liked),
        LibraryLink(CosmoIcons.Download, Res.string.video_downloads, count = downloadCount, route = VideoRoute.Downloads),
        LibraryLink(VideoIcons.Subscriptions, Res.string.video_channels, count = subscriptions.size, route = VideoRoute.Channels),
        LibraryLink(VideoIcons.Bookmark, Res.string.video_saved_playlists, count = saved.size, route = VideoRoute.SavedPlaylists),
        LibraryLink(VideoIcons.Upload, Res.string.video_import_export, count = null, route = VideoRoute.Import),
    )

    LibraryList(contentPadding, wide) { cell ->
        item(key = "heading") {
            LibraryHeader(stringResource(Res.string.video_library), wide, cell.padding(bottom = HEADING_BOTTOM)) {
                CosmoIconButton(
                    CosmoIcons.Plus,
                    stringResource(Res.string.video_new_playlist),
                    { newPlaylist = true },
                    style = CosmoIconButtonStyle.TONAL,
                    size = 40.dp,
                )
            }
        }
        if (continuingItems.isNotEmpty()) {
            item(key = "continue") {
                LibraryShelf(
                    title = stringResource(Res.string.video_shelf_continue),
                    items = continuingItems,
                    onItemClick = { actions.play(it, continuingItems) },
                    onItemMore = { sheetFor = it },
                    watched = { fractions[it] },
                    downloaded = { it in downloadedIds },
                    cardWidth = (if (wide) WIDE_CARD_DP else CARD_DP).dp,
                    modifier = cell.padding(bottom = SHELF_BOTTOM),
                )
            }
        }
        items(links, key = { "link:" + it.route.encode() }) { link ->
            LinkRow(link.icon, stringResource(link.title), link.count, onClick = { navigator.push(link.route) }, modifier = cell)
        }
        if (playlists.isNotEmpty()) {
            item(key = "playlists:header") {
                CosmoSectionHeader(stringResource(Res.string.video_your_playlists), cell.padding(top = PLAYLISTS_TOP))
            }
            items(playlists, key = { "playlist:" + it.id }) { playlist ->
                val count = playlist.items.size
                CosmoListRow(
                    title = playlist.name,
                    onClick = { navigator.push(VideoRoute.LocalPlaylist(playlist.id)) },
                    modifier = cell,
                    subtitle = pluralStringResource(Res.plurals.video_count_videos, count, count),
                    leading = CosmoRowLeading.Cover(LibraryPlan.coverOf(playlist)),
                )
            }
        }
    }

    if (newPlaylist) {
        NewPlaylistDialog(
            onCreate = { name ->
                library.createPlaylist(name)
                newPlaylist = false
            },
            onDismiss = { newPlaylist = false },
        )
    }
    sheetFor?.let { video ->
        VideoActionsSheet(
            item = video,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(video, action, VideoSheetContext.DEFAULT, list = continuingItems) },
            onDismiss = { sheetFor = null },
            downloaded = video.id in downloadedIds,
        )
    }
}

/** One of the Library's rows: its icon, its name, its count (none for History and Import and export) and the page it opens. */
@Immutable
private data class LibraryLink(val icon: ImageVector, val title: StringResource, val count: Int?, val route: VideoRoute)

/** The shelf card on the phone (spec 5.0: 240 wide) and on a wide page (280, spec 5.2). */
private const val CARD_DP = VideoMetrics.SHELF_CARD_W_DP
private const val WIDE_CARD_DP = VideoMetrics.GRID_MIN_CARD_DP

/** Below the heading, below the shelf, and above "Your playlists". */
private val HEADING_BOTTOM = 10.dp
private val SHELF_BOTTOM = 10.dp
private val PLAYLISTS_TOP = 22.dp
