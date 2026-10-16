package com.cosmotune.video.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.cosmotune.design.BlockMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_empty_playlist
import com.cosmotune.video.resources.video_move_down
import com.cosmotune.video.resources.video_move_up
import com.cosmotune.video.resources.video_remove
import com.cosmotune.video.ui.actions.ActionToastKind
import com.cosmotune.video.ui.actions.VideoActionsState
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberShareVideo
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.channel.BrandedVideoRow
import com.cosmotune.video.ui.channel.DefaultEmpty
import com.cosmotune.video.ui.channel.ListPageItems
import com.cosmotune.video.ui.channel.PageLine
import com.cosmotune.video.ui.channel.PagedState
import com.cosmotune.video.ui.channel.ShareLinks
import com.cosmotune.video.ui.channel.pagedStatus
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A wide page's rows stand at most this wide (the Search page's list is 840 dp at most; the hero row beside it is wider). */
private val ROW_MAX_WIDTH = 880.dp

/** Within this distance of the list's top or bottom edge a held row scrolls the list, at most [AUTOSCROLL_STEP] per frame. */
private val AUTOSCROLL_EDGE = 56.dp
private val AUTOSCROLL_STEP = 18.dp

/** The dialogs of your own playlist's More menu. */
private enum class PlaylistDialog { NONE, RENAME, DELETE }

/**
 * A playlist's page (spec 5.6): a remote one ([remoteId], read from the source a page at a time) or one of your own ([localId], in the
 * library). Phone: the thumbnail centred (16:9, the window less 32 dp, radius 22, the album hero's deeper shadow), what the page is
 * (Playlist, Your playlist, Saved), the title in `title(TITLE_L)` up to three lines, the owner, the count and the time, the buttons (Play all,
 * Shuffle, Save for a remote one, Download all, More), then the videos as rows. Your own playlist: a handle at the end of each row drags it
 * to a new place, a swipe removes it, a hold opens the video sheet with "Remove from this playlist"; More has Rename and Delete. A remote one
 * reads on as the end comes into view and keeps Share in More. [wide] (840 dp and up, the desktop): the hero is a row of the thumbnail
 * (320 x 180) beside the title (`display(DISPLAY_S)`) and the buttons.
 */
@Composable
fun PlaylistPage(remoteId: String?, localId: String?, contentPadding: PaddingValues, wide: Boolean) {
    when {
        localId != null -> LocalPlaylistPage(localId, contentPadding, wide)
        remoteId != null -> RemotePlaylistPage(remoteId, contentPadding, wide)
        else -> MissingPlaylist(contentPadding)
    }
}

/** A playlist that is not there (deleted, or never imported): the empty state. */
@Composable
private fun MissingPlaylist(contentPadding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()), contentAlignment = Alignment.TopCenter) { DefaultEmpty() }
}

/** The text of an empty playlist. */
@Composable
private fun PlaylistEmpty() {
    CosmoEmptyState(message = stringResource(Res.string.video_empty_playlist))
}

/**
 * Plays [queue] from [start] (spec 5.6: "Play all" and a tap on a row): a Short opens the pager through [openShort]; a video opens its
 * watch page and plays with [queue] as the list, [start] first of what plays.
 */
internal fun startPlaylist(
    start: VideoItem,
    queue: List<VideoItem>,
    navigator: VideoNavigator,
    playback: VideoPlayback,
    openShort: (VideoItem, List<VideoItem>) -> Unit,
) {
    if (start.isShort) {
        openShort(start, queue)
    } else {
        navigator.push(VideoRoute.Watch(start.id))
        playback.play(start.id, queue = queue)
    }
}

// ---- a playlist of the source ---------------------------------------------------------------------------------------------------

@Composable
private fun RemotePlaylistPage(playlistId: String, contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val actions = rememberVideoActions()
    val scope = rememberCoroutineScope()
    val model = remember(graph.source, playlistId) { PlaylistModels.obtain(graph.source, playlistId) }
    LaunchedEffect(model) { model.start() }
    val saved by graph.library.savedPlaylists.collectAsState()
    val downloaded = rememberDownloadedIds()
    val sharer = rememberShareVideo(onCopied = { VideoActionsState.shared.showToast(ActionToastKind.LINK_COPIED) })
    val paged = model.videos
    val items = paged.items
    val playlist = model.info?.playlist
    val complete = paged.state == PagedState.READY && !paged.hasMore
    var preparing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<VideoItem?>(null) }
    val listState = rememberLazyListState()
    val line = if (wide) PageLine.WideColumn else PageLine.Phone

    val isSaved = playlist != null && saved.any { it.id == playlist.id }
    val shown = playlist?.let { p ->
        PlaylistShown(
            kind = PlaylistKind.REMOTE,
            id = p.id,
            title = p.title,
            thumbnailUrl = p.thumbnailUrl ?: items.firstOrNull()?.thumbnailUrl,
            ownerName = p.ownerName,
            ownerId = p.ownerId,
            videoCount = p.videoCount?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: if (complete) items.size else null,
            totalSec = if (complete) PlaylistText.totalSec(items) else null,
            saved = isSaved,
        )
    }
    val play = { start: VideoItem, queue: List<VideoItem> -> startPlaylist(start, queue, navigator, graph.playback) { s, l -> actions.play(s, l) } }
    val headerActions = PlaylistActions(
        hasVideos = items.isNotEmpty(),
        preparing = preparing,
        onPlayAll = { items.firstOrNull()?.let { play(it, items) } },
        onShuffle = { items.shuffled().let { order -> order.firstOrNull()?.let { play(it, order) } } },
        onDownloadAll = {
            if (!preparing && playlist != null) {
                preparing = true
                scope.launch {
                    try {
                        val everything = model.everything()
                        actions.downloadBatch(DownloadBatchInfo(playlist.id, BatchKind.PLAYLIST, playlist.title), everything, latestPicker = false)
                    } finally {
                        preparing = false
                    }
                }
            }
        },
        onToggleSaved = playlist?.let { p ->
            {
                if (isSaved) {
                    graph.library.unsavePlaylist(p.id)
                } else {
                    graph.library.savePlaylist(p.copy(thumbnailUrl = p.thumbnailUrl ?: items.firstOrNull()?.thumbnailUrl, videoCount = p.videoCount ?: items.size.toLong()))
                }
            }
        },
        onShare = playlist?.let { p -> { sharer.share(p.title, ShareLinks.playlist(p.id)) } },
        onRename = null,
        onDelete = null,
        onOpenOwner = playlist?.ownerId?.let { owner -> { navigator.push(VideoRoute.Channel(owner)) } },
    )

    val keys = uniqueKeys(items)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
        headerItems(shown, failed = paged.state == PagedState.FAILED_FIRST, actions = headerActions, wide = wide, line = line)
        items(count = items.size, key = { "row:${keys[it]}" }, contentType = { "row" }) { index ->
            val item = items[index]
            BrandedVideoRow(
                item = item,
                downloaded = downloaded.value.contains(item.id),
                onClick = { play(item, items) },
                onMore = { menu = item },
                modifier = rowFit(line, wide),
                desktop = wide,
            )
        }
        ListPageItems(this).pagedStatus("playlist", paged, rowStyle = true, empty = { PlaylistEmpty() })
    }

    menu?.let { target ->
        VideoActionsSheet(
            item = target,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(target, action, VideoSheetContext.DEFAULT, list = items) },
            onDismiss = { menu = null },
            downloaded = downloaded.value.contains(target.id),
        )
    }
}

/** The side margin of a row of videos on the page's content line, and the wide page's cap on its width. */
private fun rowFit(line: PageLine, wide: Boolean): Modifier = line.fit(if (wide) Modifier.widthIn(max = ROW_MAX_WIDTH) else Modifier)

/** The head of the page: the skeleton while the first page loads (nothing when it failed: the status says so), else the hero, title and buttons. */
private fun LazyListScope.headerItems(shown: PlaylistShown?, failed: Boolean, actions: PlaylistActions, wide: Boolean, line: PageLine) {
    if (shown == null) {
        if (!failed) item(key = "header-skeleton", contentType = "header") { PlaylistHeaderSkeleton(wide, line.own) }
        return
    }
    if (wide) {
        item(key = "hero-row", contentType = "header") { PlaylistHeroRow(shown, actions, line.own) }
    } else {
        item(key = "hero", contentType = "header") { PlaylistHeroPhone(shown.thumbnailUrl, line.own) }
        item(key = "meta", contentType = "header") { PlaylistTitleBlock(shown, wide = false, actions = actions, margin = line.own) }
        item(key = "actions", contentType = "header") { PlaylistActionsRow(shown, actions, wide = false, margin = line.own) }
    }
}

/** How many items stand before the first row when the head is shown: the hero row on a wide page, else the hero, the title block and the buttons. */
private fun headerItemCount(wide: Boolean): Int = if (wide) 1 else 3

// ---- one of your own playlists -------------------------------------------------------------------------------------------------

@Composable
private fun LocalPlaylistPage(id: String, contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val library = graph.library
    val navigator = LocalVideoNavigator.current
    val actions = rememberVideoActions()
    val playlists by library.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        MissingPlaylist(contentPadding)
        return
    }
    val items = playlist.items
    val downloaded = rememberDownloadedIds()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val count by rememberUpdatedState(items.size)
    val reorder = remember(listState, id, wide) {
        ReorderState(listState, firstRow = headerItemCount(wide), rowCount = { count }, onMove = { from, to -> library.moveInPlaylist(id, from, to) })
    }
    ReorderAutoScroll(reorder, listState, thresholdPx = with(density) { AUTOSCROLL_EDGE.toPx() }, maxStepPx = with(density) { AUTOSCROLL_STEP.toPx() })
    var menu by remember { mutableStateOf<VideoItem?>(null) }
    var dialog by remember { mutableStateOf(PlaylistDialog.NONE) }
    val reduced = LocalReducedMotion.current
    val line = if (wide) PageLine.WideColumn else PageLine.Phone

    val shown = PlaylistShown(
        kind = PlaylistKind.LOCAL,
        id = id,
        title = playlist.name,
        thumbnailUrl = items.firstOrNull()?.thumbnailUrl,
        ownerName = null,
        ownerId = null,
        videoCount = items.size,
        totalSec = PlaylistText.totalSec(items),
        saved = false,
    )
    val play = { start: VideoItem -> startPlaylist(start, items, navigator, graph.playback) { s, l -> actions.play(s, l) } }
    val headerActions = PlaylistActions(
        hasVideos = items.isNotEmpty(),
        preparing = false,
        onPlayAll = { items.firstOrNull()?.let(play) },
        onShuffle = {
            val order = items.shuffled()
            order.firstOrNull()?.let { startPlaylist(it, order, navigator, graph.playback) { s, l -> actions.play(s, l) } }
        },
        onDownloadAll = { actions.downloadBatch(DownloadBatchInfo(id, BatchKind.PLAYLIST, playlist.name), items, latestPicker = false) },
        onToggleSaved = null,
        onShare = null,
        onRename = { dialog = PlaylistDialog.RENAME },
        onDelete = { dialog = PlaylistDialog.DELETE },
        onOpenOwner = null,
    )

    val keys = uniqueKeys(items)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
        headerItems(shown, failed = false, actions = headerActions, wide = wide, line = line)
        items(count = items.size, key = { "row:${keys[it]}" }, contentType = { "row" }) { index ->
            val item = items[index]
            val key = "row:${keys[index]}"
            val held = reorder.heldKey == key
            LocalRow(
                item = item,
                index = index,
                count = items.size,
                key = key,
                reorder = reorder,
                wide = wide,
                downloaded = downloaded.value.contains(item.id),
                onClick = { play(item) },
                onMore = { menu = item },
                onRemove = { library.removeFromPlaylist(id, item.id) },
                onMoveBy = { by -> library.moveInPlaylist(id, index, index + by) },
                modifier = rowFit(line, wide).then(
                    when {
                        held -> Modifier
                        reduced -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = null)
                        else -> Modifier.animateItem()
                    },
                ),
            )
        }
        if (items.isEmpty()) item(key = "empty", contentType = "status") { PlaylistEmpty() }
    }

    menu?.let { target ->
        VideoActionsSheet(
            item = target,
            context = VideoSheetContext.YOUR_PLAYLIST,
            onAction = { action -> actions.handle(target, action, VideoSheetContext.YOUR_PLAYLIST, playlistId = id, list = items) },
            onDismiss = { menu = null },
            downloaded = downloaded.value.contains(target.id),
        )
    }
    when (dialog) {
        PlaylistDialog.NONE -> Unit
        PlaylistDialog.RENAME -> RenamePlaylistDialog(
            current = playlist.name,
            onRename = { name ->
                library.renamePlaylist(id, name)
                dialog = PlaylistDialog.NONE
            },
            onDismiss = { dialog = PlaylistDialog.NONE },
        )
        PlaylistDialog.DELETE -> DeletePlaylistDialog(
            name = playlist.name,
            onDelete = {
                dialog = PlaylistDialog.NONE
                library.deletePlaylist(id)
                navigator.pop()
            },
            onDismiss = { dialog = PlaylistDialog.NONE },
        )
    }
}

/** A held row is lifted: a fill of the cover's `surfaceHigh` and a soft shadow, in the corner of a video row's focus ring. */
private val HELD_SHAPE = RoundedCornerShape(16.dp)
private val HELD_LIFT = 10.dp
private const val HELD_SCALE = 1.02f

/**
 * A row of your own playlist: the video row with the drag handle where its more button would be (a hold or a right click still opens the
 * sheet), inside a swipe that removes it. Assistive technology gets Move up, Move down and Remove as actions of the row. While it is held
 * the row follows the finger ([ReorderState.translationOf]) above the others.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalRow(
    item: VideoItem,
    index: Int,
    count: Int,
    key: String,
    reorder: ReorderState,
    wide: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onRemove: () -> Unit,
    onMoveBy: (Int) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalCosmoColors.current
    val held = reorder.heldKey == key
    val removeNow by rememberUpdatedState(onRemove)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                false
            } else {
                removeNow()
                true
            }
        },
        positionalThreshold = { distance -> distance * BlockMetrics.SWIPE_POSITIONAL_THRESHOLD },
    )
    val moveUp = stringResource(Res.string.video_move_up)
    val moveDown = stringResource(Res.string.video_move_down)
    val remove = stringResource(Res.string.video_remove)
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier
            .zIndex(if (held) 1f else 0f)
            .graphicsLayer {
                translationY = reorder.translationOf(key)
                if (held) {
                    scaleX = HELD_SCALE
                    scaleY = HELD_SCALE
                    shadowElevation = HELD_LIFT.toPx()
                    shape = HELD_SHAPE
                    clip = false
                    spotShadowColor = colors.live(CoverField.SHADOW).copy(alpha = 1f)
                    ambientShadowColor = Color.Transparent
                }
            },
        gesturesEnabled = reorder.heldKey == null,
        backgroundContent = { RemoveReveal(swipe) },
    ) {
        BrandedVideoRow(
            item = item,
            downloaded = downloaded,
            onClick = onClick,
            onMore = onMore,
            desktop = wide,
            modifier = Modifier
                .then(if (held) Modifier.clip(HELD_SHAPE).drawBehind { drawRect(colors.live(CoverField.SURFACE_HIGH)) } else Modifier)
                .semantics(mergeDescendants = true) {
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(moveUp) { onMoveBy(-1); true })
                        if (index < count - 1) add(CustomAccessibilityAction(moveDown) { onMoveBy(1); true })
                        add(CustomAccessibilityAction(remove) { onRemove(); true })
                    }
                },
            trailing = {
                DragHandle(onStart = { reorder.start(key) }, onDrag = reorder::drag, onEnd = { reorder.end(key) })
            },
        )
    }
}

/**
 * What the swipe uncovers (as part two's song row does): a container of radius 20 in ink at 5 % under the row, and in it a 72 dp block of
 * radius 16 in the tonal fill with the close icon and "Remove", 6 dp in from the edge the row slid away from. Only the strip the row has
 * left is drawn, so the page shows through the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoveReveal(state: SwipeToDismissBoxState) {
    val colors = LocalCosmoColors.current
    val label = stringResource(Res.string.video_remove)
    Box(
        Modifier
            .fillMaxSize()
            // the reveal is a gesture's by-product; the row's own actions are the way in for assistive technology
            .clearAndSetSemantics { }
            .drawWithContent {
                val offset = try { state.requireOffset() } catch (e: IllegalStateException) { 0f }
                if (offset == 0f) return@drawWithContent
                val left = if (offset > 0f) 0f else size.width + offset
                val right = if (offset > 0f) offset else size.width
                clipRect(left = left, top = 0f, right = right, bottom = size.height) {
                    drawRoundRect(
                        colors.live(CoverField.TEXT_PRIMARY).copy(alpha = BlockMetrics.SWIPE_CONTAINER_INK),
                        cornerRadius = CornerRadius(BlockMetrics.SWIPE_CONTAINER_RADIUS_DP.dp.toPx()),
                    )
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        RemoveAction(label, Modifier.align(Alignment.CenterStart))
        RemoveAction(label, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun RemoveAction(label: String, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    val ink = Color(colors.palette.onAccentContainer)
    Column(
        modifier
            .padding(BlockMetrics.SWIPE_ACTION_INSET_DP.dp)
            .width(BlockMetrics.SWIPE_ACTION_DP.dp)
            .fillMaxHeight()
            .clip(SuperellipseShape(BlockMetrics.SWIPE_ACTION_RADIUS_DP.dp))
            .drawBehind { drawRect(colors.live(CoverField.ACCENT_CONTAINER)) }
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(CosmoIcons.Close, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
