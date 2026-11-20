package com.cosmotune.video.ui.library

import com.cosmotune.video.ui.blocks.VideoSectionHeading
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channels
import com.cosmotune.video.resources.video_count_videos
import com.cosmotune.video.resources.video_downloads
import com.cosmotune.video.resources.video_history
import com.cosmotune.video.resources.video_liked
import com.cosmotune.video.resources.video_local_title
import com.cosmotune.video.resources.video_new_playlist
import com.cosmotune.video.resources.video_saved_playlists
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.resources.video_your_playlists
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.actions.NewPlaylistDialog
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.local.rememberLocalRecentStore
import com.cosmotune.video.ui.account.VideoAccountSheet
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.music.sharedui.resources.library
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import com.cosmotune.music.sharedui.resources.show_all
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.ui.watch.rememberWatchedFractions

/**
 * The Library tab (board frame 5), top to bottom: the title with the plus that makes a playlist; the account; one row of chips with
 * their counts (Liked, Downloads, Local videos, Channels, Saved playlists), each opening its page; then History, Watch later and the
 * playlists as rows, the first two with Show all. Settings and Import and export are in the profile drawer, not here. [contentPadding]
 * and [wide] are the nav host's.
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
    val localCount = rememberLocalRecentStore().entries.collectAsState().value.size


    var newPlaylist by rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }

    // The pages the chip row opens, each with its count; History and Watch later have their own rows below.
    val links = listOf(
        LibraryLink(CosmoIcons.HeartFill, Res.string.video_liked, count = liked.size, route = VideoRoute.Liked),
        LibraryLink(CosmoIcons.Download, Res.string.video_downloads, count = downloadCount, route = VideoRoute.Downloads),
        LibraryLink(CosmoIcons.Video, Res.string.video_local_title, count = localCount, route = VideoRoute.LocalFiles),
        LibraryLink(VideoIcons.Subscriptions, Res.string.video_channels, count = subscriptions.size, route = VideoRoute.Channels),
        LibraryLink(VideoIcons.Bookmark, Res.string.video_saved_playlists, count = saved.size, route = VideoRoute.SavedPlaylists),
    )

    val account = graph.account
    val accountState by account.state.collectAsState()
    var accountSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The newest of History and Watch later as rows, each section opening its page.
    val recent = remember(history) { history.take(ROWS_MAX).map { it.item } }
    val recentFractions = remember(history) { LibraryPlan.fractions(history.take(ROWS_MAX)) }
    val later = remember(watchLater) { watchLater.take(ROWS_MAX) }
    val laterFractions by rememberWatchedFractions(later)
    val fonts = orbitFonts()
    val look = orbitLook()

    LibraryList(contentPadding, wide) { cell ->
        item(key = "heading") {
            LibraryHeader(stringResource(SharedRes.string.library), wide, cell) {
                OrbitAction(CosmoIcons.Plus, stringResource(Res.string.video_new_playlist), { newPlaylist = true })
            }
        }
        item(key = "you") {
            YouHeader(accountState, onSignIn = { scope.launch { account.signInRequest() } }, onAccount = { accountSheet = true }, modifier = cell)
        }
        if ((accountState as? com.cosmotune.video.account.AccountState.SignedIn)?.expired == true) {
            item(key = "account-expired") { com.cosmotune.video.ui.account.AccountExpiredNotice(cell) }
        }
        item(key = "links") {
            OrbitChipRow(CosmoSpacing.margin, cell.padding(top = 8.dp)) {
                links.forEach { link ->
                    val title = stringResource(link.title)
                    OrbitSelectChip(link.count?.takeIf { it > 0 }?.let { "$title · $it" } ?: title, false, fonts, look,
                        onClick = { navigator.push(link.route) }, icon = link.icon, haptic = false)
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "history:header") {
                VideoSectionHeading(stringResource(Res.string.video_history), cell, onSeeAll = { navigator.push(VideoRoute.History) }, seeAllLabel = stringResource(SharedRes.string.show_all))
            }
            items(recent.take(ROWS_SHOWN), key = { "history:" + it.id }) { item ->
                LibraryVideoRow(item, onClick = { actions.play(item, recent) }, onMore = { sheetFor = item }, wide = wide, modifier = cell,
                    watchedFraction = recentFractions[item.id], downloaded = item.id in downloadedIds)
            }
        }
        if (later.isNotEmpty()) {
            item(key = "later:header") {
                VideoSectionHeading(stringResource(Res.string.video_watch_later), cell, onSeeAll = { navigator.push(VideoRoute.WatchLater) },
                    seeAllLabel = stringResource(SharedRes.string.show_all), subtitle = watchLater.size.toString())
            }
            items(later.take(ROWS_SHOWN), key = { "later:" + it.id }) { item ->
                LibraryVideoRow(item, onClick = { actions.play(item, watchLater) }, onMore = { sheetFor = item }, wide = wide, modifier = cell,
                    watchedFraction = laterFractions[item.id], downloaded = item.id in downloadedIds)
            }
        }
        if (playlists.isNotEmpty()) {
            item(key = "playlists:header") {
                VideoSectionHeading(stringResource(Res.string.video_your_playlists), cell)
            }
            items(playlists, key = { "playlist:" + it.id }) { playlist ->
                val count = playlist.items.size
                OrbitInfoRow(
                    title = playlist.name,
                    onClick = { navigator.push(VideoRoute.LocalPlaylist(playlist.id)) },
                    modifier = cell,
                    subtitle = pluralStringResource(Res.plurals.video_count_videos, count, count),
                    cover = LibraryPlan.coverOf(playlist),
                    icon = CosmoIcons.List,
                )
            }
        }
    }

    if (accountSheet) {
        VideoAccountSheet(
            state = accountState,
            onSignIn = { scope.launch { account.signInRequest() } },
            onSwitch = { id -> scope.launch { account.switchAccount(id) } },
            onSignOut = { scope.launch { account.signOut() } },
            onDismiss = { accountSheet = false },
        )
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
            onAction = { action -> actions.handle(video, action, VideoSheetContext.DEFAULT, list = recent) },
            onDismiss = { sheetFor = null },
            downloaded = video.id in downloadedIds,
        )
    }
}

/** How many videos of History and Watch later the page reads, and how many rows of each it shows. */
private const val ROWS_MAX = 12
private const val ROWS_SHOWN = 3

/** One of the Library's chips: its icon, its name, its count and the page it opens. */
@Immutable
private data class LibraryLink(val icon: ImageVector, val title: StringResource, val count: Int?, val route: VideoRoute)


