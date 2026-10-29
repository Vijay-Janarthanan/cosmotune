package com.cosmotune.video.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.video.library.FileLibraryFiles
import com.cosmotune.video.library.LibraryPlatform
import com.cosmotune.video.library.videoLibraryDir
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_pip_back_10
import com.cosmotune.video.resources.video_pip_back_to_app
import com.cosmotune.video.resources.video_pip_close
import com.cosmotune.video.resources.video_pip_forward_10
import com.cosmotune.video.resources.video_pip_pause
import com.cosmotune.video.resources.video_pip_play
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.player.SeekBackIcon
import com.cosmotune.video.ui.player.SubtitleOverlay
import com.cosmotune.video.ui.player.performPlayerKey
import com.cosmotune.video.ui.player.playerKeyName
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.awt.Cursor
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import kotlin.math.roundToInt

/*
 * The desktop's picture-in-picture (Video mode): the video in a small borderless window that stays above every other window, so it
 * can be watched while the app is out of the way. The picture is the engine's own surface (one engine, one surface: the page it left
 * shows a placeholder meanwhile); the window keeps the video's shape, can be dragged from anywhere and resized from its corners, and
 * remembers its size and place. Closing it, or "Back to app", returns the video to the page and playback goes on. Its controls
 * (play and pause, 10 seconds back and forward, back to the app, close) show while the pointer moves and fade after a moment.
 */

/** The window, made on first use and kept while it is open; every call is on the Compose (Swing) thread. */
internal object DesktopPip {
    /** Whether the window is open: snapshot state, so the page it left and the buttons follow it. */
    var isOpen: Boolean by mutableStateOf(false)
        private set

    private var frame: ComposeWindow? = null
    private var aspect = 16f / 9f

    /**
     * Opens the window for [playback] drawing [surface]; [provide] wraps its content in the composition locals of the screen it
     * came from (the type). Does nothing when it is open or the machine has no screen.
     */
    fun open(
        playback: VideoPlayback,
        surface: VideoSurfaceProvider?,
        settings: VideoSettingsRepository,
        provide: @Composable (@Composable () -> Unit) -> Unit,
    ) {
        if (isOpen || surface == null || GraphicsEnvironment.isHeadless()) return
        aspect = playback.state.value.aspect.takeIf { it > 0f } ?: aspect
        val window = ComposeWindow()
        frame = window
        window.type = Window.Type.UTILITY
        window.isUndecorated = true
        window.isAlwaysOnTop = true
        window.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        window.title = playback.state.value.item?.title.orEmpty()
        window.bounds = awtBounds(PipWindowMath.onScreen(PipBoundsStore.read() ?: PipWindowMath.initial(aspect, screens().first()), screens()))
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = close(returnToApp = false)
        })
        window.setContent {
            provide { PipContent(window, playback, surface, settings) }
        }
        isOpen = true
        window.isVisible = true
        window.toFront()
    }

    /** Closes the window (the video keeps playing in the page it returns to); with [returnToApp] the app's window is brought forward too. */
    fun close(returnToApp: Boolean) {
        val window = frame ?: return
        frame = null
        PipBoundsStore.write(boundsOf(window))
        isOpen = false
        window.isVisible = false
        window.dispose()
        if (returnToApp) bringAppForward()
    }

    /** The video's shape changed (it loaded): the window keeps its width and takes the new height. */
    fun reshape(window: ComposeWindow, shape: Float) {
        if (shape <= 0f || kotlin.math.abs(shape - aspect) < SHAPE_EPSILON) return
        aspect = shape
        val next = PipWindowMath.reshaped(boundsOf(window), shape)
        window.setSize(next.width, next.height)
    }

    /** Moves the window by the distance the pointer has gone since the drag began (read from the screen: the window moves under the pointer). */
    fun moveBy(window: ComposeWindow, start: Point, startPointer: Point) {
        val now = MouseInfo.getPointerInfo()?.location ?: return
        window.setLocation(start.x + now.x - startPointer.x, start.y + now.y - startPointer.y)
    }

    fun resizeBy(window: ComposeWindow, startBounds: PipBounds, startPointer: Point, corner: PipCorner) {
        val now = MouseInfo.getPointerInfo()?.location ?: return
        val screen = screens().maxByOrNull { it.width }
        val next = PipWindowMath.resized(startBounds, corner, now.x - startPointer.x, now.y - startPointer.y, aspect, screen?.width ?: DEFAULT_MAX_WIDTH)
        window.bounds = awtBounds(next)
    }

    fun boundsOf(window: Window): PipBounds = PipBounds(window.x, window.y, window.width, window.height)

    fun saveNow(window: ComposeWindow) = PipBoundsStore.write(boundsOf(window))

    private fun awtBounds(b: PipBounds) = java.awt.Rectangle(b.x, b.y, b.width, b.height)

    /** The usable area of every screen (the task bar and menu bar left out). */
    private fun screens(): List<PipBounds> = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val configuration = device.defaultConfiguration
        val area = configuration.bounds
        val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        PipBounds(area.x + insets.left, area.y + insets.top, area.width - insets.left - insets.right, area.height - insets.top - insets.bottom)
    }.ifEmpty { listOf(PipBounds(0, 0, DEFAULT_MAX_WIDTH, DEFAULT_MAX_WIDTH * 9 / 16)) }

    /** The app's own window, shown again and put in front: the window on show (minimized counts), else one hidden in the tray. */
    private fun bringAppForward() {
        val windows = Frame.getFrames().filter { it !== frame && it.isDisplayable }
        val main = windows.firstOrNull { it.isVisible } ?: windows.firstOrNull() ?: return
        if (main.extendedState and Frame.ICONIFIED != 0) main.extendedState = main.extendedState and Frame.ICONIFIED.inv()
        main.isVisible = true
        main.toFront()
        main.requestFocus()
    }

    private const val SHAPE_EPSILON = 0.01f
    private const val DEFAULT_MAX_WIDTH = 1920
}

/** The size and place of the window, kept in `pip-window.json` of the video folder. */
internal object PipBoundsStore {
    private const val FILE = "pip-window.json"
    private val files by lazy { FileLibraryFiles(videoLibraryDir(LibraryPlatform.Desktop)) }

    fun read(): PipBounds? = runCatching { files.read(FILE)?.let(PipWindowMath::decode) }.getOrNull()

    fun write(bounds: PipBounds) {
        runCatching { files.write(FILE, PipWindowMath.encode(bounds)) }
    }
}

/** How long the controls stay after the pointer last moved. */
private const val CONTROLS_MS = 2_500L

/** The corner grips' size, and the controls' button size. */
private val GRIP = 22.dp
private val BUTTON = 36.dp
private val BUTTON_BIG = 44.dp

/** What the window holds: the picture, the subtitles, the drag layer, the controls and the four corner grips. */
@Composable
private fun PipContent(window: ComposeWindow, playback: VideoPlayback, surface: VideoSurfaceProvider, settings: VideoSettingsRepository) {
    val state by playback.state.collectAsState()
    val cues by playback.engine.cues.collectAsState()
    val options by settings.settings.collectAsState()
    val focus = remember { FocusRequester() }
    var lastMove by remember { mutableLongStateOf(System.nanoTime()) }
    var shown by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    val close = { DesktopPip.close(returnToApp = false) }
    val back = { DesktopPip.close(returnToApp = true) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The video loaded or changed shape: the window follows.
    LaunchedEffect(state.aspect) { DesktopPip.reshape(window, state.aspect) }
    // The video was stopped (or replaced by nothing): there is nothing left to show.
    LaunchedEffect(state.item) { if (state.item == null) close() }
    LaunchedEffect(lastMove, state.playing) {
        shown = true
        if (state.playing) {
            delay(CONTROLS_MS)
            shown = false
        }
    }
    LaunchedEffect(shown, state.playing) {
        while (shown || state.playing) {
            position = playback.positionMs()
            delay(POSITION_MS)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event -> onKey(event, playback, options, close) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastMove = System.nanoTime()
                    }
                }
            },
    ) {
        surface.Surface(Modifier.fillMaxSize(), VideoScale.FIT)
        SubtitleOverlay(cues, SUBTITLE_SP, options.captionsScale, options.captionsBackground, lift = { 0f }, liftDp = 0.dp)

        // The drag layer: the whole picture moves the window; a double tap goes back to the app.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(window) {
                    var windowStart = Point()
                    var pointerStart = Point()
                    detectDragGestures(
                        onDragStart = {
                            windowStart = window.location
                            pointerStart = MouseInfo.getPointerInfo()?.location ?: Point()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            DesktopPip.moveBy(window, windowStart, pointerStart)
                        },
                        onDragEnd = { DesktopPip.saveNow(window) },
                    )
                }
                .pointerInput(playback) {
                    detectTapGestures(onDoubleTap = { back() }, onTap = { lastMove = System.nanoTime() })
                },
        )

        if (shown || !state.playing) {
            Box(
                Modifier.fillMaxWidth().height(SCRIM_DP.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = SCRIM_ALPHA), Color.Transparent))),
            )
            Box(
                Modifier.fillMaxWidth().height(SCRIM_DP.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = SCRIM_ALPHA)))),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                PipButton(CosmoIcons.Expand, stringResource(Res.string.video_pip_back_to_app), BUTTON, back)
                PipButton(CosmoIcons.Close, stringResource(Res.string.video_pip_close), BUTTON, close)
            }
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
            ) {
                PipButton(SeekBackIcon, stringResource(Res.string.video_pip_back_10), BUTTON) { playback.seekBy(-SEEK_MS) }
                PipButton(
                    if (state.playing) CosmoIcons.Pause else CosmoIcons.Play,
                    stringResource(if (state.playing) Res.string.video_pip_pause else Res.string.video_pip_play),
                    BUTTON_BIG,
                ) { playback.togglePlayPause() }
                PipButton(VideoIcons.Forward, stringResource(Res.string.video_pip_forward_10), BUTTON) { playback.seekBy(SEEK_MS) }
            }
            val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Color.White))
            }
        }

        for (corner in PipCorner.entries) Grip(window, corner, Modifier.align(alignmentOf(corner)))
    }
}

/** A round dark button with a white glyph; the whole window is a video, so these colours are the documented exception (as the player's chrome). */
@Composable
private fun PipButton(icon: ImageVector, description: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A corner of the window that resizes it, with the cursor the corner calls for. */
@Composable
private fun Grip(window: ComposeWindow, corner: PipCorner, modifier: Modifier) {
    val cursor = when (corner) {
        PipCorner.TOP_LEFT -> Cursor.NW_RESIZE_CURSOR
        PipCorner.TOP_RIGHT -> Cursor.NE_RESIZE_CURSOR
        PipCorner.BOTTOM_LEFT -> Cursor.SW_RESIZE_CURSOR
        PipCorner.BOTTOM_RIGHT -> Cursor.SE_RESIZE_CURSOR
    }
    Box(
        modifier
            .size(GRIP)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
            .pointerInput(window, corner) {
                var startBounds = PipBounds(0, 0, 0, 0)
                var pointerStart = Point()
                detectDragGestures(
                    onDragStart = {
                        startBounds = DesktopPip.boundsOf(window)
                        pointerStart = MouseInfo.getPointerInfo()?.location ?: Point()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        DesktopPip.resizeBy(window, startBounds, pointerStart, corner)
                    },
                    onDragEnd = { DesktopPip.saveNow(window) },
                )
            },
    )
}

private fun alignmentOf(corner: PipCorner): Alignment = when (corner) {
    PipCorner.TOP_LEFT -> Alignment.TopStart
    PipCorner.TOP_RIGHT -> Alignment.TopEnd
    PipCorner.BOTTOM_LEFT -> Alignment.BottomStart
    PipCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
}

/** The keys of the window: Escape and P close it; the player's other keys (space, J, L, arrows, M, C, ...) act on the video. */
private fun onKey(event: androidx.compose.ui.input.key.KeyEvent, playback: VideoPlayback, settings: com.cosmotune.video.settings.VideoSettings, close: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape) {
        close()
        return true
    }
    val name = playerKeyName(event) ?: return false
    val action = PlayerKeyMap.action(name, event.isShiftPressed, event.isCtrlPressed || event.isMetaPressed) ?: return false
    return performPlayerKey(
        action = action,
        playback = playback,
        state = playback.state.value,
        settings = settings,
        platform = null,
        onPictureInPicture = close,
    )
}

private const val SEEK_MS = 10_000L
private const val POSITION_MS = 250L
private const val SUBTITLE_SP = 14f
private const val SCRIM_DP = 72
private const val SCRIM_ALPHA = 0.6f
