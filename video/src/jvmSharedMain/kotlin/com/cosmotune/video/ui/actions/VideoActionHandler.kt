package com.cosmotune.video.ui.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cosmotune.ui.sheets.CosmoToast
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_added
import com.cosmotune.video.resources.video_dl_already
import com.cosmotune.video.resources.video_dl_in_downloads
import com.cosmotune.video.resources.video_dl_toast_change
import com.cosmotune.video.resources.video_dl_toast_started
import com.cosmotune.video.resources.video_dl_view
import com.cosmotune.video.resources.video_link_copied
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.ui.blocks.VideoAction
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.downloads.DownloadToast
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.ui.shorts.openShort
import com.music.bitchord.ui.components.FLOATING_BAR_MAX_WIDTH
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * What every card, row and Short does with a row of its video sheet (spec 5.12), and the entry points the pages share: [play] (a
 * card's tap), [download] and [downloadBatch] (spec 5.16). Get it with [rememberVideoActions]; the sheets it opens (save to a
 * playlist, a new playlist, the download sheet) and its toast are drawn by the shell's action host.
 */
@Stable
class VideoActionHandler internal constructor(
    private val graph: VideoGraph,
    private val navigator: VideoNavigator,
    private val state: VideoActionsState,
    private val sharer: ShareVideo,
) {
    /**
     * Does [action] for [item], whose sheet was opened in [context]. [details] is the watch page's, when there is one (its item is
     * the fuller one). [playlistId] is the playlist a row of your playlist belongs to (Remove from this playlist); [list] is the
     * list the item was shown in (a Short opens the pager with the other Shorts of that list).
     *
     * - Play: a Short opens the Shorts pager at it ([openShort], origin [ShortsOrigin.Related]); a video opens its watch page and
     *   plays (spec 5.15: any Short card anywhere opens the pager, a video card the watch page).
     * - Add to queue; Play as a song (the video stops, then Music plays it as a song, spec 9); Save to a playlist (the sheet);
     *   Watch later (added, or removed when it is there); Download (the download sheet); Share (the short address); Open the
     *   channel; Remove from history; Remove from this playlist.
     *
     * A live stream or a premiere that has not started offers no Play as a song and no Download (spec 5.13): the sheet hides
     * them, and a call for one does nothing.
     */
    fun handle(
        item: VideoItem,
        action: VideoAction,
        context: VideoSheetContext,
        details: VideoDetails? = null,
        playlistId: String? = null,
        list: List<VideoItem> = emptyList(),
    ) {
        val video = details?.item?.takeIf { it.id == item.id } ?: item
        when (action) {
            VideoAction.Play -> play(video, list)
            VideoAction.AddToQueue -> graph.playback.addToQueue(video)
            VideoAction.PlayAsSong -> if (streamable(video)) {
                graph.playback.stop()
                graph.music.playAsSong(video)
            }
            VideoAction.SaveToPlaylist -> state.saveTarget = video
            VideoAction.WatchLater -> toggleWatchLater(graph.library, video)
            VideoAction.Download -> download(video)
            VideoAction.Share -> sharer.share(video.title, shareUrl(video.id))
            VideoAction.OpenChannel -> video.channelId?.let { navigator.push(VideoRoute.Channel(it)) }
            VideoAction.RemoveFromHistory -> graph.library.removeFromHistory(video.id)
            VideoAction.RemoveFromPlaylist -> if (context == VideoSheetContext.YOUR_PLAYLIST && playlistId != null) {
                graph.library.removeFromPlaylist(playlistId, video.id)
            }
        }
    }

    /** A card's tap: a Short opens the pager at it with the other Shorts of [list], a video its watch page (and it plays). */
    fun play(item: VideoItem, list: List<VideoItem> = emptyList()) {
        if (item.isShort) {
            navigator.openShort(item, ShortsOrigin.Related(item.id), list)
        } else {
            navigator.push(VideoRoute.Watch(item.id))
            graph.playback.play(item.id)
        }
    }

    /** Asks the download sheet about [item] (spec 5.16). A live stream or a premiere has nothing to download. */
    fun download(item: VideoItem) {
        if (streamable(item)) state.downloadAsk = DownloadAsk.One(item)
    }

    /**
     * Asks the download sheet about a batch (spec 5.16): [info] names it, [items] are the videos loaded (live streams and premieres
     * left out), [latestPicker] asks how many of the newest to take.
     */
    fun downloadBatch(info: DownloadBatchInfo, items: List<VideoItem>, latestPicker: Boolean) {
        val wanted = items.filter(::streamable)
        if (wanted.isNotEmpty()) state.downloadAsk = DownloadAsk.Batch(info, wanted, latestPicker)
    }

    internal companion object {
        /** The short address a shared video carries (spec 5.12). */
        const val SHARE_BASE = "https://youtu.be/"

        fun shareUrl(videoId: String): String = SHARE_BASE + videoId

        /** Whether [item] has streams to take apart (not live, not a premiere that has not started; spec 5.13). */
        fun streamable(item: VideoItem): Boolean = !item.isLive && !item.isUpcoming

        /** Adds [item] to watch later, or takes it out when it is there. */
        fun toggleWatchLater(library: VideoLibrary, item: VideoItem) {
            if (library.watchLater.value.any { it.id == item.id }) library.removeFromWatchLater(item.id) else library.addToWatchLater(item)
        }

        /**
         * Creates a playlist named [name] (trimmed) and saves [item] to it; the id, or null when the name is blank or the library
         * is full (500 playlists: it then answers an id that belongs to no playlist).
         */
        fun createPlaylistWith(library: VideoLibrary, name: String, item: VideoItem): String? {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return null
            val id = library.createPlaylist(trimmed)
            if (library.playlists.value.none { it.id == id }) return null
            library.addToPlaylist(id, item)
            return id
        }
    }
}

/**
 * The action handler of the screen being drawn: the graph and the navigator of the shell around it. The sheets it opens and its
 * toast need the shell's action host (`VideoNavHost` composes it once), so every page and block can call this.
 */
@Composable
fun rememberVideoActions(): VideoActionHandler {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val state = VideoActionsState.shared
    val sharer = rememberShareVideo(onCopied = { state.showToast(ActionToastKind.LINK_COPIED) })
    return remember(graph, navigator, state, sharer) { VideoActionHandler(graph, navigator, state, sharer) }
}

/**
 * What a toast of the action host says: "Link copied"; the download host's (plan task Y-32, spec 5.16) "Downloading in 720p · Change",
 * "Added to Downloads · View", "Already downloaded" and "Already in Downloads · View".
 */
internal enum class ActionToastKind { LINK_COPIED, DOWNLOAD_STARTED, DOWNLOAD_ADDED, ALREADY_DOWNLOADED, ALREADY_IN_DOWNLOADS }

/**
 * One toast; [serial] tells two toasts of the same kind apart, so a second copy shows again. [detail] fills its text (the quality of
 * "Downloading in 720p"); [onAction] is its button (Change, View), when it has one.
 */
internal data class ActionToast(val kind: ActionToastKind, val serial: Int, val detail: String? = null, val onAction: (() -> Unit)? = null)

/**
 * What the action host shows, set by the handlers and cleared by the host: the download that waits for its sheet, the video that
 * waits to be saved to a playlist, the video a new playlist is made for, the toast. One for the process ([shared]): one activity
 * or one window draws Video at a time.
 */
@Stable
internal class VideoActionsState {
    var downloadAsk: DownloadAsk? by mutableStateOf(null)
    var saveTarget: VideoItem? by mutableStateOf(null)
    var newPlaylistFor: VideoItem? by mutableStateOf(null)
    var toast: ActionToast? by mutableStateOf(null)
        private set
    private var serial = 0

    fun showToast(kind: ActionToastKind, detail: String? = null, onAction: (() -> Unit)? = null) {
        serial += 1
        toast = ActionToast(kind, serial, detail, onAction)
    }

    /** Takes [shown] away, unless another toast has replaced it since. */
    fun clearToast(shown: ActionToast) {
        if (toast == shown) toast = null
    }

    companion object {
        val shared = VideoActionsState()
    }
}

/**
 * The shell's action host: the save-to-playlist sheet, the new-playlist dialog, the download sheet ([DownloadOptionsHost]) and the
 * toast, for whatever [rememberVideoActions] asked. Composed once, by `VideoNavHost`, beside the page; [contentPadding] is the page's,
 * so the toast stands above the floating chrome. The sheets and the dialog are windows of their own; the toast lies over the page.
 */
@Composable
internal fun VideoActionsHost(contentPadding: PaddingValues) {
    val state = VideoActionsState.shared
    val library = LocalVideoGraph.current.library
    state.saveTarget?.let { item ->
        SaveToPlaylistSheet(
            item = item,
            onNewPlaylist = {
                state.saveTarget = null
                state.newPlaylistFor = item
            },
            onDismiss = { state.saveTarget = null },
        )
    }
    state.newPlaylistFor?.let { item ->
        NewPlaylistDialog(
            onCreate = { name ->
                VideoActionHandler.createPlaylistWith(library, name, item)
                state.newPlaylistFor = null
            },
            onDismiss = { state.newPlaylistFor = null },
        )
    }
    DownloadOptionsHost(ask = state.downloadAsk, onDismiss = { state.downloadAsk = null })
    ActionToastHost(state, contentPadding)
}

/** The toast of the action host: 4 s over the page, above the chrome (its life is the host's, as `CosmoToast` says). */
@Composable
private fun ActionToastHost(state: VideoActionsState, contentPadding: PaddingValues) {
    val toast = state.toast ?: return
    val message = when (toast.kind) {
        ActionToastKind.LINK_COPIED -> stringResource(Res.string.video_link_copied)
        ActionToastKind.DOWNLOAD_STARTED -> stringResource(Res.string.video_dl_toast_started, toast.detail.orEmpty())
        ActionToastKind.DOWNLOAD_ADDED -> stringResource(Res.string.video_dl_added)
        ActionToastKind.ALREADY_DOWNLOADED -> stringResource(Res.string.video_dl_already)
        ActionToastKind.ALREADY_IN_DOWNLOADS -> stringResource(Res.string.video_dl_in_downloads)
    }
    val actionLabel = when (toast.kind) {
        ActionToastKind.DOWNLOAD_STARTED -> stringResource(Res.string.video_dl_toast_change)
        ActionToastKind.DOWNLOAD_ADDED, ActionToastKind.ALREADY_IN_DOWNLOADS -> stringResource(Res.string.video_dl_view)
        ActionToastKind.LINK_COPIED, ActionToastKind.ALREADY_DOWNLOADED -> null
    }
    LaunchedEffect(toast) {
        delay(TOAST_MS)
        state.clearToast(toast)
    }
    Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()), contentAlignment = Alignment.BottomCenter) {
        key(toast.serial) {
            val action = toast.onAction
            if (action != null && actionLabel != null) {
                DownloadToast(
                    message = message,
                    action = actionLabel,
                    onAction = {
                        state.clearToast(toast)
                        action()
                    },
                    modifier = Modifier.widthIn(max = FLOATING_BAR_MAX_WIDTH),
                )
            } else {
                CosmoToast(message, Modifier.widthIn(max = FLOATING_BAR_MAX_WIDTH))
            }
        }
    }
}

/** How long a toast stays (`CosmoToast`: 4 s). */
private const val TOAST_MS = 4_000L
