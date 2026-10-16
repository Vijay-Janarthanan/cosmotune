package com.cosmotune.video.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.cosmotune.design.Springs
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.nav.VideoTab

/**
 * Draws the page on top of [navigator]'s stack (spec 4.4): one branch of the `when` per route, each on its own line and each a
 * placeholder until the task that owns the page replaces that one line (the comment at its end names the task). Pages change only
 * their own line, so the file merges.
 *
 * [contentPadding] is what the shell keeps clear for its own bars: the top bar and the floating chrome on the phone (nothing
 * under the Shorts pager), the desktop frame's bar and the page's top margin on the desktop. It carries no side margin: the
 * blocks of a page keep the phone's 16 dp themselves, and a [wide] page (840 dp and up, always on the desktop) lays its own
 * 28 dp margins out of the width it is given. A page puts the padding on its scrolling content, so that content passes under
 * the bars and a last row can be scrolled clear of them.
 *
 * Each page keeps its saved state (a list's place, a field's text) while it is in a stack: it is `rememberSaveable` state, kept
 * per entry of the stacks, and dropped when the entry is popped or replaced. Changing page cross-fades (150 ms under reduced
 * motion), and changing between two tab roots does not.
 *
 * Beside the page stands the action host (`VideoActionsHost`): the save-to-playlist sheet, the new-playlist dialog, the download
 * sheet and the toast that the pages' `rememberVideoActions()` asks for, once for every page.
 */
@Composable
fun VideoNavHost(navigator: VideoNavigator, contentPadding: PaddingValues, wide: Boolean) {
    val holder = rememberSaveableStateHolder()
    val reduced = LocalReducedMotion.current
    val page = VideoPage(navigator.topPageKey(), navigator.top)

    // A page that has left every stack takes its saved state with it.
    val keys = navigator.pageKeys()
    val kept = remember { HashSet<String>() }
    LaunchedEffect(keys) {
        for (gone in kept - keys) holder.removeState(gone)
        kept.clear()
        kept.addAll(keys)
    }

    val fadeMs = if (reduced) Springs.REDUCED_MS else FADE_MS
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (initialState.route.isTabRoot() && targetState.route.isTabRoot()) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(fadeMs)) togetherWith fadeOut(tween(fadeMs))
            }
        },
        label = "video-route",
    ) { shown ->
        holder.SaveableStateProvider(shown.key) {
            val r = shown.route
            when (r) {
                is VideoRoute.Home -> com.cosmotune.video.ui.home.VideoHome(contentPadding, wide)

                is VideoRoute.Subscriptions -> com.cosmotune.video.ui.subscriptions.SubscriptionsPage(contentPadding, wide)

                is VideoRoute.Library -> com.cosmotune.video.ui.library.LibraryPage(contentPadding, wide)   // Y-22

                is VideoRoute.Search -> com.cosmotune.video.ui.search.VideoSearch(r, contentPadding, wide)

                is VideoRoute.Watch -> VideoPlaceholder(r)   // Y-19 replaces this line

                is VideoRoute.Shorts -> VideoPlaceholder(r)   // Y-31 replaces this line

                is VideoRoute.Channel -> VideoPlaceholder(r)   // Y-20 replaces this line

                is VideoRoute.Playlist -> VideoPlaceholder(r)   // Y-20 replaces this line

                is VideoRoute.LocalPlaylist -> VideoPlaceholder(r)   // Y-20 replaces this line

                is VideoRoute.History -> com.cosmotune.video.ui.library.HistoryPage(contentPadding, wide)   // Y-22

                is VideoRoute.WatchLater -> com.cosmotune.video.ui.library.WatchLaterPage(contentPadding, wide)   // Y-22

                is VideoRoute.Liked -> com.cosmotune.video.ui.library.LikedPage(contentPadding, wide)   // Y-22

                is VideoRoute.SavedPlaylists -> com.cosmotune.video.ui.library.SavedPlaylistsPage(contentPadding, wide)   // Y-22

                is VideoRoute.Channels -> com.cosmotune.video.ui.library.ChannelsPage(contentPadding, wide)   // Y-22

                is VideoRoute.Downloads -> com.cosmotune.video.ui.downloads.DownloadsPage(contentPadding, wide)   // Y-33: the Downloads page

                is VideoRoute.Settings -> com.cosmotune.video.ui.settings.VideoSettingsPage(contentPadding, wide)

                is VideoRoute.Import -> com.cosmotune.video.ui.library.ImportPage(contentPadding, wide)   // Y-22
            }
        }
    }

    // The sheets, the dialog and the toast the pages' action handler asks for (Y-16): once, beside the page.
    com.cosmotune.video.ui.actions.VideoActionsHost(contentPadding)
}

/** The page on top of the stack: its route, and the key that tells its entry from every other one (see [VideoNavigator.pageKeys]). */
@Immutable
private data class VideoPage(val key: String, val route: VideoRoute)

private fun VideoRoute.isTabRoot() = VideoTab.rootedAt(this) != null

private const val FADE_MS = 180
