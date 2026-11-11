package com.cosmotune.video.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.pip.DesktopPipBoard
import com.cosmotune.ui.pip.PipControls
import com.cosmotune.ui.pip.PipOwner
import com.cosmotune.ui.pip.PipWish
import com.cosmotune.video.library.FileLibraryFiles
import com.cosmotune.video.library.LibraryPlatform
import com.cosmotune.video.library.videoLibraryDir
import com.cosmotune.video.player.smooth.LocalVideoHostWindow
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_next
import com.cosmotune.video.resources.video_pip_back_10
import com.cosmotune.video.resources.video_pip_back_to_app
import com.cosmotune.video.resources.video_pip_close
import com.cosmotune.video.resources.video_pip_forward_10
import com.cosmotune.video.resources.video_pip_pause
import com.cosmotune.video.resources.video_pip_play
import com.cosmotune.video.resources.video_previous
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.player.SeekBackIcon
import com.cosmotune.video.ui.player.SubtitleOverlay
import com.cosmotune.video.ui.player.performPlayerKey
import com.cosmotune.video.ui.player.playerKeyName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import java.awt.AWTEvent
import java.awt.Cursor
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer

/*
 * The desktop's picture-in-picture: the picture in a small borderless window that stays above every other window, so it can be
 * watched while the app is out of the way. Video mode's video, Watch's film or channel and Now Playing's music video all use this one
 * window, one at a time (`DesktopPipBoard` in sharedUi says who is in it). For Video the picture is the engine's own surface (one
 * engine, one surface: the page it left shows a placeholder meanwhile); Watch and the music video hand their picture over in their
 * wish ([PipWish.picture]). The window keeps the picture's shape, can be dragged from anywhere and resized from its corners, and
 * remembers its size and place. "Back to app" returns the picture to the page and playback goes on; the close button returns it too
 * and pauses a video or a film (the song's video leaves the music playing). Its controls (play and pause, 10 seconds back and
 * forward, previous and next where the player has them, back to the app, close) show while the pointer is over the window and go a
 * moment after it stops or leaves. The window holds the picture and the controls only: the stats and the settings stay with the
 * page it left.
 */

/**
 * What the window shows of the player in it, read again whenever that changes: its title and shape, whether it plays, what its
 * buttons do (null: no such button), the picture and what is drawn over it (Video's subtitles), and its keys.
 */
internal class PipView(
    val title: String,
    val aspect: Float,
    val playing: Boolean,
    val durationMs: Long,
    /** Nothing left to show (the video was stopped, the player's page left): the window closes. */
    val gone: Boolean,
    val playPause: () -> Unit,
    val positionMs: () -> Long,
    val seekBy: ((Long) -> Unit)?,
    val previous: (() -> Unit)?,
    val next: (() -> Unit)?,
    val picture: @Composable (Modifier) -> Unit,
    val overlay: @Composable BoxScope.() -> Unit = {},
    /** The player's own keys; Escape (which closes the window) is the window's. */
    val onKey: (KeyEvent, close: () -> Unit) -> Boolean = { _, _ -> false },
    /** The window's close button (not "Back to app"): Video pauses (Watch and the music video say in their wish). */
    val onClose: () -> Unit = {},
)

/** The player in the window, as a composable read of its [PipView]. */
internal fun interface PipSource {
    @Composable
    fun view(): PipView
}

/** Video mode's video in the window: the engine's surface and subtitles, the player's keys, paused when the window is closed. */
internal fun videoPipSource(playback: VideoPlayback, surface: VideoSurfaceProvider, settings: VideoSettingsRepository) = PipSource {
    val state by playback.state.collectAsState()
    val cues by playback.engine.cues.collectAsState()
    val options by settings.settings.collectAsState()
    PipView(
        title = state.item?.title.orEmpty(),
        aspect = state.aspect,
        playing = state.playing,
        durationMs = state.durationMs,
        gone = state.item == null,
        playPause = playback::togglePlayPause,
        positionMs = playback::positionMs,
        seekBy = playback::seekBy,
        previous = null,
        next = null,
        picture = { m -> surface.Surface(m, VideoScale.FIT) },
        overlay = { SubtitleOverlay(cues, SUBTITLE_SP, options.captionsScale, options.captionsBackground, lift = { 0f }, liftDp = 0.dp) },
        onKey = { event, close -> onKey(event, playback, options, close) },
        onClose = { if (playback.state.value.playing) playback.togglePlayPause() },
    )
}

/** Watch's film or channel, or the song's music video, in the window: everything from its latest [PipWish] (null: gone). */
internal fun wishPipSource(wish: () -> PipWish?) = PipSource {
    val now = wish()
    val controls = now?.controls
    PipView(
        title = now?.title.orEmpty(),
        aspect = if (now != null && now.aspectHeight > 0) now.aspectWidth.toFloat() / now.aspectHeight else 0f,
        playing = now?.playing == true,
        durationMs = controls?.durationMs?.invoke() ?: 0L,
        gone = now == null,
        playPause = { wish()?.controls?.playPause?.invoke() },
        positionMs = { wish()?.controls?.positionMs?.invoke() ?: 0L },
        seekBy = if (controls?.seekBy == null) null else ({ delta: Long -> wish()?.controls?.seekBy?.invoke(delta); Unit }),
        previous = if (controls?.previous == null) null else ({ wish()?.controls?.previous?.invoke(); Unit }),
        next = if (controls?.next == null) null else ({ wish()?.controls?.next?.invoke(); Unit }),
        picture = now?.picture ?: { _ -> },
        onKey = { event, _ -> wishKey(event, wish()?.controls) },
    )
}

/** The keys of a window that shows a wish: space and K play and pause, J and L or the arrows go 10 seconds, N and P next and previous. */
private fun wishKey(event: KeyEvent, controls: PipControls?): Boolean {
    if (controls == null) return false
    val action: (() -> Unit)? = when (event.key) {
        Key.Spacebar, Key.K, Key.MediaPlayPause -> controls.playPause
        Key.J, Key.DirectionLeft -> controls.seekBy?.let { seek -> { seek(-SEEK_MS) } }
        Key.L, Key.DirectionRight -> controls.seekBy?.let { seek -> { seek(SEEK_MS) } }
        Key.N, Key.MediaNext -> controls.next
        Key.P, Key.MediaPrevious -> controls.previous
        else -> null
    }
    action?.invoke()
    return action != null
}

/** The window, made on first use and kept while it is open; every call is on the Compose (Swing) thread. */
internal object DesktopPip {
    /** Whether the window is open: snapshot state, so the page it left and the buttons follow it. */
    var isOpen: Boolean by mutableStateOf(false)
        private set

    /** Who is in the window while it is open: Video mode's video, Watch's film or channel, or the song's music video. */
    var owner: PipOwner? by mutableStateOf(null)
        private set

    private var frame: ComposeWindow? = null
    private var closing: (() -> Unit)? = null
    private var aspect = 16f / 9f

    /**
     * Opens the window for Video's [playback] drawing [surface]; [provide] wraps its content in the composition locals of the screen it
     * came from (the type). Does nothing when it is open, another player is in picture-in-picture, or the machine has no screen.
     */
    fun open(
        playback: VideoPlayback,
        surface: VideoSurfaceProvider?,
        settings: VideoSettingsRepository,
        provide: @Composable (@Composable () -> Unit) -> Unit,
    ) {
        if (isOpen || frame != null || surface == null || GraphicsEnvironment.isHeadless()) return
        if (!DesktopPipBoard.board.ask(PipOwner.VIDEO)) return
        aspect = playback.state.value.aspect.takeIf { it > 0f } ?: aspect
        if (openFor(PipOwner.VIDEO, videoPipSource(playback, surface, settings), playback.state.value.item?.title.orEmpty(), provide)) {
            DesktopPipBoard.opened(PipOwner.VIDEO)
        } else {
            DesktopPipBoard.board.cancelAsk()
        }
    }

    /** The shape the next window opens at (the wish's, before its picture has loaded). */
    fun shape(value: Float) {
        if (value > 0f && value.isFinite()) aspect = value
    }

    /** Opens the window for [owner] showing [source]; false when it is open already or the machine has no screen. */
    fun openFor(owner: PipOwner, source: PipSource, title: String, provide: @Composable (@Composable () -> Unit) -> Unit): Boolean {
        if (isOpen || frame != null || GraphicsEnvironment.isHeadless()) return false
        val window = ComposeWindow()
        frame = window
        this.owner = owner
        window.type = Window.Type.UTILITY
        window.isUndecorated = true
        window.isAlwaysOnTop = true
        window.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        window.title = title
        window.bounds = awtBounds(PipWindowMath.onScreen(PipBoundsStore.read() ?: PipWindowMath.initial(aspect, screens().first()), screens()))
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = close(returnToApp = false)
        })
        window.setContent {
            provide { CompositionLocalProvider(LocalVideoHostWindow provides window) { PipContent(window, source) } }
        }
        isOpen = true
        window.isVisible = true
        window.toFront()
        return true
    }

    /** What the window's close button does to the player in it ([PipView.onClose]), kept current by the window's content. */
    fun onClosing(action: () -> Unit) {
        closing = action
    }

    /**
     * Closes the window. With [returnToApp] ("Back to app") the picture goes back to the page and plays on, and the app's window is
     * brought forward too; without it (the close button, Escape) a video or a film pauses as it goes back ([PipView.onClose]).
     *
     * The window is hidden at once but taken apart a moment later, and the page it left takes the picture back only then ([isOpen]
     * stays true until the window's composition is gone): the close comes from this window's own click or effect, which must finish
     * before the window is disposed, and the picture then has one surface drawing it at a time on the way back.
     */
    fun close(returnToApp: Boolean) {
        val window = frame ?: return
        frame = null
        if (!returnToApp) runCatching { closing?.invoke() }
        closing = null
        PipBoundsStore.write(boundsOf(window))
        window.isVisible = false
        SwingUtilities.invokeLater {
            window.dispose()
            isOpen = false
            owner = null
            DesktopPipBoard.closed(returnToApp)
            if (returnToApp) bringAppForward()
        }
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

/** The corner grips' size, and the controls' button size. */
private val GRIP = 22.dp
private val BUTTON = 36.dp
private val BUTTON_BIG = 44.dp

/** How often the pointer is read from the screen: the backstop for the enter and exit events an unfocused window does not get. */
private const val POINTER_POLL_MS = 50

private fun nowMs(): Long = System.nanoTime() / 1_000_000

/**
 * What the window holds: the picture, what is drawn over it (Video's subtitles), the drag layer, the controls and the four corner
 * grips. The controls show while the pointer is over the window and go a moment after it stops or leaves (see [PipControlsTimer]);
 * where the pointer is comes from the screen itself (the AWT mouse events of the window, and a short poll), not from Compose's hover,
 * because an always-on-top window that does not have the focus often gets no exit event, and then the controls would stay for good.
 */
@Composable
private fun PipContent(window: ComposeWindow, source: PipSource) {
    val view = source.view()
    val focus = remember { FocusRequester() }
    val timer = remember { PipControlsTimer(nowMs()) }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    var shown by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    val close = { DesktopPip.close(returnToApp = false) }
    val back = { DesktopPip.close(returnToApp = true) }
    val lock = { timer.lock(); wake.trySend(Unit); Unit }
    val unlock = { timer.unlock(nowMs()); wake.trySend(Unit); Unit }

    SideEffect { DesktopPip.onClosing(view.onClose) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The picture loaded or changed shape: the window follows.
    LaunchedEffect(view.aspect) { DesktopPip.reshape(window, view.aspect) }
    // The player was stopped (or its page left): there is nothing left to show.
    LaunchedEffect(view.gone) { if (view.gone) close() }
    LaunchedEffect(view.title) { window.title = view.title }
    LaunchedEffect(view.playing) {
        timer.playing(nowMs(), view.playing)
        wake.trySend(Unit)
    }
    // The controls follow the timer: it says when they show and when it next needs to be asked.
    LaunchedEffect(timer) {
        while (true) {
            val now = nowMs()
            shown = timer.visible(now)
            val wait = timer.nextChange(now)
            if (wait == null) wake.receive() else withTimeoutOrNull(wait) { wake.receive() }
        }
    }
    // The pointer: read from the screen on every mouse event of the window and every few milliseconds.
    DisposableEffect(window) {
        var last: Point? = null
        val sync = { moved: Boolean ->
            val at = MouseInfo.getPointerInfo()?.location
            val over = window.isShowing && pointerOverWindow(at?.x, at?.y, window.x, window.y, window.width, window.height)
            val now = nowMs()
            if (over) timer.pointerInside(now, moved || at != last) else timer.pointerOutside(now)
            last = at
            wake.trySend(Unit)
            Unit
        }
        val listener = AWTEventListener { event ->
            if (event is MouseEvent && SwingUtilities.getWindowAncestor(event.component) === window) {
                sync(event.id == MouseEvent.MOUSE_MOVED || event.id == MouseEvent.MOUSE_DRAGGED || event.id == MouseEvent.MOUSE_PRESSED)
            }
        }
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK)
        val poll = Timer(POINTER_POLL_MS) { sync(false) }
        poll.start()
        onDispose {
            poll.stop()
            toolkit.removeAWTEventListener(listener)
        }
    }
    LaunchedEffect(shown, view.playing) {
        while (shown || view.playing) {
            position = view.positionMs()
            delay(POSITION_MS)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event -> windowKey(event, view, close) },
    ) {
        view.picture(Modifier.fillMaxSize())
        view.overlay(this)

        // The drag layer: the whole picture moves the window; a double tap goes back to the app.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(window) {
                    var windowStart = Point()
                    var pointerStart = Point()
                    detectDragGestures(
                        onDragStart = {
                            lock()
                            windowStart = window.location
                            pointerStart = MouseInfo.getPointerInfo()?.location ?: Point()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            DesktopPip.moveBy(window, windowStart, pointerStart)
                        },
                        onDragEnd = { DesktopPip.saveNow(window); unlock() },
                        onDragCancel = { unlock() },
                    )
                }
                .pointerInput(source) {
                    detectTapGestures(onDoubleTap = { back() })
                },
        )

        if (shown) {
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
                view.previous?.let { PipButton(CosmoIcons.Previous, stringResource(Res.string.video_previous), BUTTON, it) }
                view.seekBy?.let { seek -> PipButton(SeekBackIcon, stringResource(Res.string.video_pip_back_10), BUTTON) { seek(-SEEK_MS) } }
                PipButton(
                    if (view.playing) CosmoIcons.Pause else CosmoIcons.Play,
                    stringResource(if (view.playing) Res.string.video_pip_pause else Res.string.video_pip_play),
                    BUTTON_BIG,
                    view.playPause,
                )
                view.seekBy?.let { seek -> PipButton(VideoIcons.Forward, stringResource(Res.string.video_pip_forward_10), BUTTON) { seek(SEEK_MS) } }
                view.next?.let { PipButton(CosmoIcons.Next, stringResource(Res.string.video_next), BUTTON, it) }
            }
            if (view.durationMs > 0) {
                val fraction = (position.toFloat() / view.durationMs).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Color.White))
                }
            }
        }

        for (corner in PipCorner.entries) Grip(window, corner, Modifier.align(alignmentOf(corner)), lock, unlock)
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
private fun Grip(window: ComposeWindow, corner: PipCorner, modifier: Modifier, lock: () -> Unit, unlock: () -> Unit) {
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
                        lock()
                        startBounds = DesktopPip.boundsOf(window)
                        pointerStart = MouseInfo.getPointerInfo()?.location ?: Point()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        DesktopPip.resizeBy(window, startBounds, pointerStart, corner)
                    },
                    onDragEnd = { DesktopPip.saveNow(window); unlock() },
                    onDragCancel = { unlock() },
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

/** The window's keys: Escape closes it; the rest are the player's own ([PipView.onKey]). */
private fun windowKey(event: KeyEvent, view: PipView, close: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape) {
        close()
        return true
    }
    return view.onKey(event, close)
}

/** Video's keys in the window: P closes it; the player's other keys (space, J, L, arrows, M, C, ...) act on the video. */
private fun onKey(event: KeyEvent, playback: VideoPlayback, settings: com.cosmotune.video.settings.VideoSettings, close: () -> Unit): Boolean {
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
