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
                is VideoRoute.Home -> VideoPlaceholder(r)   // Y-17 replaces this line

                is VideoRoute.Subscriptions -> VideoPlaceholder(r)   // Y-21 replaces this line

                is VideoRoute.Library -> VideoPlaceholder(r)   // Y-22 replaces this line

                is VideoRoute.Search -> VideoPlaceholder(r)   // Y-18 replaces this line

                is VideoRoute.Watch -> VideoPlaceholder(r)   // Y-19 replaces this line

                is VideoRoute.Shorts -> VideoPlaceholder(r)   // Y-31 replaces this line

                is VideoRoute.Channel -> com.cosmotune.video.ui.channel.ChannelPage(r, contentPadding, wide)

                is VideoRoute.Playlist -> com.cosmotune.video.ui.playlist.PlaylistPage(r.playlistId, null, contentPadding, wide)

                is VideoRoute.LocalPlaylist -> com.cosmotune.video.ui.playlist.PlaylistPage(null, r.id, contentPadding, wide)

                is VideoRoute.History -> VideoPlaceholder(r)   // Y-22 replaces this line

                is VideoRoute.WatchLater -> VideoPlaceholder(r)   // Y-22 replaces this line

                is VideoRoute.Liked -> VideoPlaceholder(r)   // Y-22 replaces this line

                is VideoRoute.SavedPlaylists -> VideoPlaceholder(r)   // Y-22 replaces this line

                is VideoRoute.Channels -> VideoPlaceholder(r)   // Y-22 replaces this line

                is VideoRoute.Downloads -> VideoPlaceholder(r)   // Y-33 replaces this line

                is VideoRoute.Settings -> VideoPlaceholder(r)   // Y-25 replaces this line

                is VideoRoute.Import -> VideoPlaceholder(r)   // Y-22 replaces this line
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
