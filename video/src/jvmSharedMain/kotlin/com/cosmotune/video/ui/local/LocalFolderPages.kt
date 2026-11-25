package com.cosmotune.video.ui.local

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.local.LocalRecent
import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.LocalBundle
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.VideoPageHeading
import com.cosmotune.video.ui.library.OrbitAction
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * The folder pages of Video mode's local files: the added folders as tiles on the local files page, a folder's own page (its folders,
 * then its videos, each with its picture), and Reels, the folder's videos one at a time full screen.
 */

/**
 * The added folders as a row of tiles, each with its newest video as its picture; a tap opens the folder, the cross takes it off the
 * list (the files stay). [onAdd] asks for another folder.
 */
@Composable
internal fun LocalFolderShelf(folders: List<PickedVideo>, onOpen: (PickedVideo) -> Unit, onRemove: (PickedVideo) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val access = rememberLocalFolderAccess()
    LazyRow(modifier.fillMaxWidth().testTag("video:local:folders"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(folders, key = { it.source }) { folder ->
            val cover by produceState(LocalFolders.keptCover(folder.source), folder.source) { value = LocalFolders.cover(access, folder.source) }
            FolderTile(folder.name, cover, access, onClick = { onOpen(folder) }, onRemove = { onRemove(folder) }, modifier = Modifier.width(SHELF_TILE))
        }
        item(key = "add") { AddFolderTile(onAdd, Modifier.width(SHELF_TILE)) }
    }
}

/** A folder: its picture (the video that stands for it, else a folder mark) with the folder mark over it, and its name under it. */
@Composable
private fun FolderTile(name: String, cover: String?, access: LocalFolderAccess, onClick: () -> Unit, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.clip(RoundedCornerShape(TILE_RADIUS)).clickable(onClick = onClick).padding(bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(FOLDER_ASPECT).clip(RoundedCornerShape(TILE_RADIUS)).background(look.chip)) {
            VideoThumb(cover, access, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            Icon(VideoIcons.Folder, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).size(22.dp))
            if (onRemove != null) OrbitAction(CosmoIcons.Close, "Remove $name from the list", onRemove, Modifier.align(Alignment.TopEnd).padding(4.dp), size = 32.dp)
        }
        Text(name, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
    }
}

@Composable
private fun AddFolderTile(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.clip(RoundedCornerShape(TILE_RADIUS)).clickable(onClick = onAdd).testTag("video:local:add-folder")) {
        Box(Modifier.fillMaxWidth().aspectRatio(FOLDER_ASPECT).clip(RoundedCornerShape(TILE_RADIUS)).background(look.chip), contentAlignment = Alignment.Center) {
            Icon(CosmoIcons.Plus, null, tint = look.ink, modifier = Modifier.size(28.dp))
        }
        Text("Add a folder", style = fonts.title, color = look.ink, maxLines = 1, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
    }
}

/** A video's picture, made on first sight and kept; the video mark on the tile's ground until it is ready (or when none can be made). */
@Composable
private fun VideoThumb(video: String?, access: LocalFolderAccess, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val look = orbitLook()
    val image by produceState(video?.let { LocalThumbs.kept(it) }, video) { if (video != null && value == null) value = LocalThumbs.load(access, video) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val shown = image
        if (shown != null) Image(shown, null, Modifier.fillMaxSize(), contentScale = scale)
        else Icon(if (video == null) VideoIcons.Folder else CosmoIcons.Video, null, tint = look.dim, modifier = Modifier.size(26.dp))
    }
}

/**
 * A folder's page ([VideoRoute.LocalFolder]): its name, Reels (every video here and in the folders inside it, one at a time full
 * screen) and Play all, then its folders and its videos as tiles. A video plays in the local player with the ones after it queued.
 */
@Composable
fun LocalFolderPage(route: VideoRoute.LocalFolder, contentPadding: PaddingValues, wide: Boolean) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val navigator = LocalVideoNavigator.current
    val access = rememberLocalFolderAccess()
    val opener = rememberLocalVideoOpener()
    // What was read last time shows at once; the folder is read again behind it.
    val entries by produceState(LocalFolders.kept(route.source), route.source) { value = LocalFolders.list(access, route.source) }
    val list = entries
    val folders = list.orEmpty().filter { it.isFolder }
    val videos = list.orEmpty().filter { !it.isFolder }
    val direction = LocalLayoutDirection.current
    val side = if (wide) 28.dp else 16.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) 190.dp else 104.dp),
        modifier = Modifier.fillMaxSize().testTag("video:local:folder"),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(direction) + side, end = contentPadding.calculateEndPadding(direction) + side,
            top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                VideoPageHeading(route.name, "Folder")
                Text(
                    when {
                        list == null -> "Reading the folder"
                        folders.isEmpty() && videos.isEmpty() -> "No videos in this folder"
                        else -> listOfNotNull(
                            folders.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "folder" else "folders"}" },
                            videos.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "video" else "videos"}" },
                        ).joinToString(" · ")
                    },
                    style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 2.dp),
                )
                if (folders.isNotEmpty() || videos.isNotEmpty()) Row(Modifier.padding(top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrbitActionPill("Reels", { navigator.push(VideoRoute.LocalReels(route.source, route.name)) }, fonts, look, primary = true, icon = VideoIcons.Shorts,
                        modifier = Modifier.testTag("video:local:reels"))
                    if (videos.isNotEmpty()) OrbitActionPill("Play all", { opener.openFolder(videos, 0) }, fonts, look, icon = CosmoIcons.Play)
                }
            }
        }
        items(folders, key = { "f:" + it.source }) { folder ->
            val cover by produceState(LocalFolders.keptCover(folder.source), folder.source) { value = LocalFolders.cover(access, folder.source) }
            FolderTile(folder.name, cover, access, onClick = { navigator.push(VideoRoute.LocalFolder(folder.source, folder.name)) })
        }
        items(videos, key = { "v:" + it.source }) { video ->
            val at = videos.indexOf(video)
            Column(Modifier.clip(RoundedCornerShape(TILE_RADIUS)).clickable { opener.openFolder(videos, at) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(VIDEO_ASPECT).clip(RoundedCornerShape(TILE_RADIUS)).background(look.chip)) {
                    VideoThumb(video.source, access, Modifier.fillMaxSize())
                }
                Text(LocalVideo.titleOf(video.name), style = fonts.body, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
            }
        }
    }
}

/**
 * Reels ([VideoRoute.LocalReels]): the videos of a folder and of every folder in it, newest first, one at a time over the whole
 * screen. The one in view plays (and starts over when it ends); a swipe, the mouse wheel or the arrow keys bring the next or the one
 * before; a tap or Space pauses. Leaving stops it.
 */
@Composable
fun LocalReelsPage(route: VideoRoute.LocalReels) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val access = rememberLocalFolderAccess()
    val fonts = orbitFonts()
    val videos by produceState<List<FolderEntry>?>(null, route.source) { value = LocalFolders.allVideos(access, route.source) }
    val list = videos
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    DisposableEffect(graph.playback) { onDispose { graph.playback.stop() } }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("video:local:reels-page")) {
        when {
            list == null -> Text("Gathering the videos", style = fonts.body, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
            list.isEmpty() -> Text("No videos in this folder", style = fonts.body, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
            else -> ReelsPager(list, route, access)
        }
        Row(Modifier.padding(top = top + 8.dp, start = 12.dp, end = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OrbitAction(CosmoIcons.ArrowLeft, "Back", { navigator.pop() }, size = 40.dp)
            Text("Reels · ${route.name}", style = fonts.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ReelsPager(list: List<FolderEntry>, route: VideoRoute.LocalReels, access: LocalFolderAccess) {
    val graph = LocalVideoGraph.current
    val base = graph.playback
    val fonts = orbitFonts()
    val scope = rememberCoroutineScope()
    val start = remember(list) { list.indexOfFirst { it.source == route.startSource }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { list.size }
    val state by base.state.collectAsState()
    val focus = remember { FocusRequester() }
    val play: (FolderEntry) -> Unit = { video ->
        val id = LocalVideo.idFor(video.source)
        val entry = LocalRecent(id = id, source = video.source, title = LocalVideo.titleOf(video.name))
        base.playLocal(LocalVideo.itemFor(entry), LocalBundle(MediaInput.LocalFiles(muxedPath = video.source, videoPath = null, audioPath = null), emptyList()), 0L)
    }
    // The video in view plays; one that ends starts over, as a reel does.
    LaunchedEffect(pager, list) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page -> list.getOrNull(page)?.let(play) }
    }
    LaunchedEffect(pager, list) {
        base.state.map { it.status == PlayerStatus.ENDED && it.item?.id == list.getOrNull(pager.settledPage)?.let { v -> LocalVideo.idFor(v.source) } }
            .distinctUntilChanged().filter { it }.collect { list.getOrNull(pager.settledPage)?.let(play) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    VerticalPager(
        state = pager,
        beyondViewportPageCount = 1,
        key = { list[it].source },
        modifier = Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown, Key.PageDown -> { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(list.size - 1)) }; true }
                Key.DirectionUp, Key.PageUp -> { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }; true }
                Key.Spacebar -> { base.togglePlayPause(); true }
                else -> false
            }
        },
    ) { page ->
        val video = list[page]
        val id = LocalVideo.idFor(video.source)
        val loaded = page == pager.settledPage && state.item?.id == id &&
            (state.status == PlayerStatus.READY || state.status == PlayerStatus.BUFFERING || state.status == PlayerStatus.ENDED)
        Box(Modifier.fillMaxSize().pointerInput(id) { detectTapGestures(onTap = { base.togglePlayPause() }) }) {
            VideoThumb(video.source, access, Modifier.fillMaxSize(), ContentScale.Fit)
            if (loaded) graph.surface?.Surface(Modifier.fillMaxSize(), VideoScale.FIT)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
                Text(LocalVideo.titleOf(video.name), style = fonts.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${page + 1} of ${list.size}", style = fonts.label, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
            }
            if (loaded && state.status == PlayerStatus.READY && !state.playing) {
                Icon(CosmoIcons.Play, "Paused", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center).size(64.dp))
            }
        }
    }
}

private val SHELF_TILE = 168.dp
private val TILE_RADIUS = 14.dp
private const val FOLDER_ASPECT = 16f / 10f
/** Downloaded clips are mostly upright (phone video): the tiles stand upright too, cropped to fit. */
private const val VIDEO_ASPECT = 3f / 4f
