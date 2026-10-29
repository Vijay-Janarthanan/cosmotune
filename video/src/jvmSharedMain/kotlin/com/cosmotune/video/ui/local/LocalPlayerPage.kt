package com.cosmotune.video.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.local.LocalRecent
import com.cosmotune.video.local.LocalRecentStore
import com.cosmotune.video.local.LocalSubtitles
import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.LocalBundle
import com.cosmotune.video.player.LocalSubtitle
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.player.rememberPictureInPicture
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_local_open
import com.cosmotune.video.resources.video_local_recent
import com.cosmotune.video.resources.video_local_subtitle_load
import com.cosmotune.video.resources.video_local_up_next
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.VideoPlayerBox
import com.cosmotune.video.ui.player.rememberPlayerPlatform
import com.cosmotune.video.ui.library.LinkRow
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.watch.FullWindowLayer
import com.cosmotune.video.ui.watch.FullscreenContent
import com.cosmotune.video.ui.watch.FullscreenController
import com.cosmotune.video.ui.watch.PipPlaceholder
import com.cosmotune.video.ui.watch.PipUpdater
import com.cosmotune.video.ui.watch.VideoFullWindow
import com.cosmotune.video.ui.watch.WatchRules
import com.cosmotune.video.ui.watch.onWatchPageKey
import com.cosmotune.video.ui.watch.rememberPipEntry
import com.cosmotune.video.ui.watch.rotationEntersFullScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/*
 * The local player (Video mode's player for a file on the device): the page of VideoRoute.LocalPlayer. It makes no source call of any
 * kind (no comments, related videos, SponsorBlock, DeArrow or channel): the file plays through MediaInput.LocalFiles, with the subtitle
 * files found beside it or picked for it, from where it was left (when "Remember position" is on), and its position is kept in the
 * recent list. Files picked together play one after the other (Next and Previous move through them). It stands like the watch page:
 * the phone's page under the status bar with the list below, full screen, picture-in-picture; the desktop's page with the player and
 * a side column, the full window, the picture-in-picture window.
 */

/** How often the position is written to the recent list while a file plays. */
private const val SAVE_EVERY_MS = 5_000L

/** The page's top under the frame's bar or the status bar on the wide layout (as the watch page leaves it). */
private val WIDE_TOP = 16.dp

/** The wide player's corner (as the watch page's). */
private val PLAYER_SHAPE = SuperellipseShape(VideoMetrics.PLAYER_RADIUS_DP.dp)

@Composable
fun LocalPlayerPage(route: VideoRoute.LocalPlayer, contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val base = graph.playback
    val store = rememberLocalRecentStore()
    val access = rememberLocalFileAccess()
    val opener = rememberLocalVideoOpener()
    val recents by store.entries.collectAsState()
    val state by base.state.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val entry = recents.firstOrNull { it.id == route.id }

    // Starts the file (again, with a different start or subtitle file): off the main thread for the folder listing.
    val start: (Long?) -> Unit = { startMs ->
        scope.launch {
            if (!startLocal(base, store, access, route.id, startMs)) {
                if (store.get(route.id) != null) {
                    store.remove(route.id)
                    LocalPlayerSession.missingNotice = true
                }
                navigator.pop()
            }
        }
    }
    val picker = rememberVideoFilePicker(
        onVideos = { opener.open(it) },
        onSubtitle = { file ->
            store.setSubtitle(route.id, file.source)
            // The engines take their subtitle files with the media: the file starts again, at the same moment.
            val at = base.positionMs()
            base.stop()
            start(at)
        },
    )
    DisposableEffect(picker) {
        LocalPlayerSession.subtitlePicker = { picker.openSubtitle() }
        onDispose { LocalPlayerSession.subtitlePicker = null }
    }

    // Opening the page starts the file, unless it is the one already on (back from the mini capsule, a rotation).
    LaunchedEffect(route.id) {
        val now = base.state.value
        val playing = now.item?.id == route.id && now.status != PlayerStatus.IDLE && now.status != PlayerStatus.ENDED && now.error == null
        if (!playing) start(null)
    }
    SelectFirstSubtitle(route.id, base)
    KeepPosition(route.id, base, store)
    GoOnWhenEnded(route.id, base, navigator::replaceTop)

    val upNextIds = LocalPlayerSession.upNext(route.id)
    val upNext = remember(upNextIds, recents) { upNextIds.mapNotNull { id -> recents.firstOrNull { it.id == id } } }
    val playback = remember(base, route.id, upNext) {
        LocalQueuePlayback(
            base = base,
            upNext = upNext.map(LocalVideo::itemFor),
            onNext = { LocalPlayerSession.nextAfter(route.id)?.let { navigator.replaceTop(VideoRoute.LocalPlayer(it)) } },
            onPrevious = {
                val previous = LocalPlayerSession.previousBefore(route.id)
                if (previous != null && base.positionMs() <= RESTART_AFTER_MS) navigator.replaceTop(VideoRoute.LocalPlayer(previous)) else base.seekTo(0)
            },
            onReplay = { at -> start(at ?: 0L) },
        )
    }

    // Picture-in-picture: the phone enters it by itself when the app is left (the setting, the video playing); the desktop opens its window.
    val pip = rememberPictureInPicture()
    val pipUpdater = remember(pip) { PipUpdater(pip) }
    val pipEnabled = WatchRules.pipEnabled(settings.autoPip, state, onTop = navigator.top == route)
    SideEffect { pipUpdater.update(enabled = pipEnabled, aspect = state.aspect) }
    DisposableEffect(pipUpdater) { onDispose { pipUpdater.leave() } }
    val enterPip = rememberPipEntry(pip)
    val pipOpen = pip.manual && pip.active
    val onBounds = remember(pipUpdater) { { rect: Rect -> pipUpdater.bounds(rect) } }

    val title = entry?.title ?: state.item?.title.orEmpty()
    val content = LocalContent(entry, upNext, recents, store, opener, picker, navigator::replaceTop)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (wide) {
            LocalPageWide(playback, graph.surface, title, contentPadding, maxWidth.value, maxHeight.value, state.aspect, content, enterPip, pipOpen, pip::exit, onBounds)
        } else {
            LocalPagePhone(playback, graph.surface, title, contentPadding, maxWidth.value, maxHeight.value, state.aspect, content, enterPip, pipOpen, pip::exit, onBounds, navigator.canPop) { navigator.pop() }
        }
    }
}

/** A distance back in a file that Previous restarts the file instead of going to the one before (as the watch page's). */
private const val RESTART_AFTER_MS = 3_000L

// ---- starting a file ---------------------------------------------------------------------------------------------------------

/**
 * Hands the file of [id] to [base]: its subtitle files (the one picked for it first, then the ones found beside it) and its start
 * (the given one, else where it was left when remembering is on). False when the file is not in the list or no longer exists.
 */
private suspend fun startLocal(base: VideoPlayback, store: LocalRecentStore, access: LocalFileAccess, id: String, startMs: Long?): Boolean {
    val entry = store.get(id) ?: return false
    val bundle = withContext(Dispatchers.IO) {
        if (!access.exists(entry.source)) return@withContext null
        LocalBundle(files = MediaInput.LocalFiles(muxedPath = entry.source, videoPath = null, audioPath = null), subtitles = subtitleFilesFor(entry, access))
    } ?: return false
    val start = startMs ?: LocalVideo.resumeAt(entry.positionMs, entry.durationMs, store.rememberPositions.value)
    base.playLocal(LocalVideo.itemFor(entry), bundle, start)
    return true
}

/** The subtitle files of [entry] for the engines: the one picked for it (when still there), then the ones beside the video, one for each language. */
private fun subtitleFilesFor(entry: LocalRecent, access: LocalFileAccess): List<LocalSubtitle> {
    val picked = entry.subtitle?.takeIf { access.exists(it) }?.let { path ->
        val match = LocalSubtitles.forPickedFile(LocalVideo.fileName(path))
        LocalSubtitle(path = path, language = match.language, label = match.label, autoGenerated = false)
    }
    val beside = access.siblingSubtitles(entry.source).map { LocalSubtitle(it.path, it.match.language, it.match.label, autoGenerated = false) }
    return listOfNotNull(picked) + beside.filter { picked == null || it.language != picked.language }
}

/** Once the file's subtitles are known, the first is shown (the one picked, else the one that carries the video's name). */
@Composable
private fun SelectFirstSubtitle(id: String, playback: VideoPlayback) {
    LaunchedEffect(id, playback) {
        playback.state
            .map { if (it.item?.id == id) it.subtitles.firstOrNull()?.language else null }
            .distinctUntilChanged()
            .filter { it != null }
            .collect { language ->
                if (playback.state.value.subtitleLanguage == null) playback.selectSubtitles(language)
                // The choice is made once for a load: later changes (captions off) are the person's.
                return@collect
            }
    }
}

/** Writes where the file is now to the recent list every few seconds, when the window stops and when the page goes. */
@Composable
private fun KeepPosition(id: String, playback: VideoPlayback, store: LocalRecentStore) {
    val latest = remember(id, playback, store) {
        {
            val now = playback.state.value
            if (now.item?.id == id && (now.status == PlayerStatus.READY || now.status == PlayerStatus.BUFFERING || now.status == PlayerStatus.ENDED)) {
                // A file that ended starts from the beginning next time.
                store.updateProgress(id, if (now.status == PlayerStatus.ENDED) 0L else playback.positionMs(), now.durationMs)
            }
        }
    }
    LaunchedEffect(latest) {
        while (true) {
            delay(SAVE_EVERY_MS)
            latest()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, latest) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                latest()
                store.flush()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            latest()
            store.flush()
        }
    }
}

/** When the file ends, the next one of the queue is opened in its place. */
@Composable
private fun GoOnWhenEnded(id: String, playback: VideoPlayback, replaceTop: (VideoRoute) -> Unit) {
    LaunchedEffect(id, playback) {
        playback.state
            .map { it.item?.id == id && it.status == PlayerStatus.ENDED }
            .distinctUntilChanged()
            .filter { it }
            .collect { LocalPlayerSession.nextAfter(id)?.let { replaceTop(VideoRoute.LocalPlayer(it)) } }
    }
}

// ---- what the layouts share --------------------------------------------------------------------------------------------------

/** The lists under (or beside) the player: the title, the two buttons, the files after this one and the recent files. */
private class LocalContent(
    private val entry: LocalRecent?,
    private val upNext: List<LocalRecent>,
    private val recents: List<LocalRecent>,
    private val store: LocalRecentStore,
    private val opener: LocalVideoOpener,
    private val picker: VideoFilePicker,
    private val replaceTop: (VideoRoute) -> Unit,
) {
    fun LazyListScope.rows(side: androidx.compose.ui.unit.Dp, showTitle: Boolean) {
        if (showTitle) item(key = "title") { LocalTitle(entry?.title.orEmpty(), side) }
        item(key = "open") { LinkRow(CosmoIcons.Video, stringResource(Res.string.video_local_open), null, { picker.openVideos() }, Modifier.padding(horizontal = side)) }
        item(key = "subtitle") {
            LinkRow(CosmoIcons.Queue, stringResource(Res.string.video_local_subtitle_load), null, { picker.openSubtitle() }, Modifier.padding(horizontal = side))
        }
        if (upNext.isNotEmpty()) {
            item(key = "next-header") { CosmoSectionHeader(stringResource(Res.string.video_local_up_next), Modifier.padding(horizontal = side)) }
            items(upNext, key = { "next:" + it.id }) { file ->
                CosmoListRow(
                    title = file.title,
                    onClick = { replaceTop(VideoRoute.LocalPlayer(file.id)) },
                    modifier = Modifier.padding(horizontal = side),
                    leading = CosmoRowLeading.Icon(CosmoIcons.Video),
                )
            }
        }
        val others = recents.filter { it.id != entry?.id && upNext.none { next -> next.id == it.id } }
        if (others.isNotEmpty()) {
            item(key = "recent-header") { CosmoSectionHeader(stringResource(Res.string.video_local_recent), Modifier.padding(horizontal = side)) }
            items(others, key = { "recent:" + it.id }) { file ->
                RecentRow(file, present = true, onClick = { opener.openRecent(file) }, onRemove = { store.remove(file.id) }, modifier = Modifier.padding(horizontal = side))
            }
        }
    }
}

@Composable
private fun LocalTitle(title: String, side: androidx.compose.ui.unit.Dp) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val style = remember(type, title) { type.forText(type.title(TypeScale.TITLE_S), title) }
    Text(
        text = title,
        style = style,
        color = Color(palette.textPrimary),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = side, end = side, top = 12.dp, bottom = 8.dp),
    )
}

// ---- the phone's page --------------------------------------------------------------------------------------------------------

/**
 * The phone's page: the black band with the player under the status bar, then the list; full screen by the player's button or when
 * the phone is turned (Back leaves it).
 */
@Composable
private fun LocalPagePhone(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    title: String,
    contentPadding: PaddingValues,
    widthDp: Float,
    heightDp: Float,
    aspect: Float,
    content: LocalContent,
    enterPip: (() -> Unit)?,
    pipOpen: Boolean,
    onReturnFromPip: () -> Unit,
    onBounds: (Rect) -> Unit,
    canCollapse: Boolean,
    onCollapse: () -> Unit,
) {
    var fullscreenAsked by rememberSaveable { mutableStateOf(false) }
    var portraitLock by rememberSaveable { mutableStateOf(false) }
    val landscape = WatchRules.phoneLandscape(widthDp, heightDp, rotationEntersFullScreen)
    val fullscreen = WatchRules.fullscreenShown(fullscreenAsked, landscape, portraitLock)
    FullscreenController(
        orientation = WatchRules.orientation(fullscreenAsked, landscapeVideo = aspect >= 1f, portraitLock = portraitLock),
        lightStatusBar = !fullscreen,
    )
    val exitFullscreen = {
        fullscreenAsked = false
        if (landscape) portraitLock = true
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val playerHeight = VideoMetrics.playerHeightDp(widthDp, heightDp, aspect).dp

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        VideoFullWindow(onExit = exitFullscreen) {
            FullscreenContent(playback, surface, title.takeIf { it.isNotEmpty() }, exitFullscreen, enterPip, onBounds)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        // The status bar's area and the bands around the picture are black (the player's own black).
        Box(Modifier.fillMaxWidth().background(Color.Black).padding(top = statusTop)) {
            if (pipOpen) {
                Box(Modifier.fillMaxWidth().height(playerHeight)) { PipPlaceholder(playback, onReturn = onReturnFromPip) }
            } else {
                VideoPlayerBox(
                    playback = playback,
                    surface = surface,
                    layout = PlayerLayout.PAGE,
                    modifier = Modifier.fillMaxWidth().height(playerHeight),
                    onCollapse = if (canCollapse) onCollapse else null,
                    onToggleFullscreen = { fullscreenAsked = true; portraitLock = false },
                    onEnterPip = enterPip,
                    onBoundsChanged = onBounds,
                )
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            with(content) { rows(PHONE_SIDE, showTitle = true) }
        }
    }
}

private val PHONE_SIDE = 16.dp

// ---- the desktop's page ------------------------------------------------------------------------------------------------------

/**
 * The wide page: the player at the top of the main column with the title under it, and the lists in the side column. The full window
 * (key F, the player's button, a double click) and the picture-in-picture window (key P, the button) are the watch page's.
 */
@Composable
private fun LocalPageWide(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    title: String,
    contentPadding: PaddingValues,
    widthDp: Float,
    heightDp: Float,
    aspect: Float,
    content: LocalContent,
    enterPip: (() -> Unit)?,
    pipOpen: Boolean,
    onReturnFromPip: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val graph = LocalVideoGraph.current
    val platform = rememberPlayerPlatform(playback)
    var fullWindow by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(fullWindow) { if (!fullWindow) runCatching { focus.requestFocus() } }

    val margin = VideoMetrics.DESKTOP_MARGIN_DP.dp
    val gap = VideoMetrics.COLUMN_GAP_DP.dp
    val side = VideoMetrics.SIDE_COLUMN_DP.dp
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = maxOf(contentPadding.calculateTopPadding(), statusTop + WIDE_TOP)
    val bottom = contentPadding.calculateBottomPadding()
    val mainWidth = (widthDp.dp - margin * 2 - gap - side).coerceAtLeast(0.dp)
    val available = (heightDp.dp - top - bottom).coerceAtLeast(0.dp)
    val playerHeight = VideoMetrics.playerHeightDp(mainWidth.value, available.value, aspect).dp
    val openFullWindow = { fullWindow = true }
    val shownTitle = title.takeIf { it.isNotEmpty() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val name = com.cosmotune.video.ui.player.playerKeyName(event) ?: return@onPreviewKeyEvent false
                onWatchPageKey(
                    name = name,
                    shift = event.isShiftPressed,
                    ctrlOrCmd = event.isCtrlPressed || event.isMetaPressed,
                    playback = playback,
                    settings = graph.settings.settings.value,
                    platform = platform,
                    onFullWindow = openFullWindow,
                    onTheatre = {},
                    onPictureInPicture = enterPip,
                )
            },
    ) {
        Row(Modifier.fillMaxSize().padding(start = margin, end = margin, top = top)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // The black of the player around its picture (the documented exception).
                Box(Modifier.fillMaxWidth().height(playerHeight).clip(PLAYER_SHAPE).background(Color.Black)) {
                    if (pipOpen) {
                        PipPlaceholder(playback, onReturn = onReturnFromPip)
                    } else if (!fullWindow) {
                        VideoPlayerBox(
                            playback = playback,
                            surface = surface,
                            layout = PlayerLayout.WINDOW,
                            modifier = Modifier.fillMaxSize(),
                            onToggleFullscreen = openFullWindow,
                            onEnterPip = enterPip,
                            onBoundsChanged = onBounds,
                            title = shownTitle,
                        )
                    }
                }
                LocalTitle(title, 0.dp)
            }
            Spacer(Modifier.width(gap))
            LazyColumn(Modifier.width(side).fillMaxHeight(), contentPadding = PaddingValues(bottom = bottom)) {
                with(content) { rows(0.dp, showTitle = false) }
            }
        }
    }
    if (fullWindow) FullWindowLayer(playback, surface, shownTitle, onExit = { fullWindow = false }, onBoundsChanged = onBounds)
}
