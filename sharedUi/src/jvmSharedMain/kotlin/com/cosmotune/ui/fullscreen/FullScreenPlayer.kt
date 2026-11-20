package com.cosmotune.ui.fullscreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.PEACH
import com.cosmotune.ui.orbit.clock
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.theme.LocalCosmoShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The one plain full-screen player of Cosmotune (the Now Playing board, sections C and V6), shared by Now Playing's Cinema (and the
 * phone turned sideways), Video's full screen and Watch's full screen. Always black with white controls, in light and dark alike;
 * peach only for play and the progress.
 */

/**
 * The host's window over the whole app for the full-screen player: [content] fills it, edge to edge into the camera cutout, with
 * the system bars hidden (Android: a dialog window; the desktop: the window's own full screen). Back and Escape call the first
 * argument (leave). The second asks the phone to hold landscape (a wide film); false leaves the rotation free.
 */
typealias FullScreenWindowHost = @Composable (onExit: () -> Unit, landscape: Boolean, content: @Composable () -> Unit) -> Unit

/** The app's [FullScreenWindowHost]; null (tests, previews) draws the player where it is composed. */
val LocalFullScreenWindow = staticCompositionLocalOf<FullScreenWindowHost?> { null }

/** [content] in the host's full window ([LocalFullScreenWindow]), or in place where there is none. */
@Composable
fun FullScreenWindow(onExit: () -> Unit, landscape: Boolean = false, content: @Composable () -> Unit) {
    val host = LocalFullScreenWindow.current
    if (host != null) host(onExit, landscape, content) else Box(Modifier.fillMaxSize()) { content() }
}

/** What the middle of the controls holds round play: the song's back and next (Music), or back and ahead 10 s (Video, Watch). */
sealed interface FullScreenTransport {
    class Tracks(val onPrevious: () -> Unit, val onNext: () -> Unit, val hasPrevious: Boolean = true, val hasNext: Boolean = true) : FullScreenTransport
    data object Skip : FullScreenTransport
}

/** One line of the ⋯ menu that opens a list of its own (captions, quality, sources): its [title], what is chosen now, the picks. */
class FullScreenMenu(val title: String, val detail: String? = null, val icon: ImageVector? = null, val items: List<OrbitMenuItem>)

/** How far a double tap skips, and how long the controls stay after the last touch. */
const val FULL_SCREEN_SKIP_MS = 10_000L
const val FULL_SCREEN_HIDE_MS = 3_000L

/** Which third of the width a double tap landed in: the left skips back, the right ahead, the middle only shows the controls. */
enum class TapThird { LEFT, MIDDLE, RIGHT }

fun tapThird(fraction: Float): TapThird = when {
    fraction < 1f / 3f -> TapThird.LEFT
    fraction > 2f / 3f -> TapThird.RIGHT
    else -> TapThird.MIDDLE
}

/**
 * Whether the full-screen picture fills the screen (cut top and bottom) or keeps its own shape (the default), remembered in the
 * look settings ([com.cosmotune.settings.CosmoLookSettings.fullScreenFill]) for all three players; without them, for this visit.
 */
@Composable
fun rememberFullScreenFill(): Pair<Boolean, (Boolean) -> Unit> {
    val settings = LocalCosmoShell.current?.settings
    if (settings != null) {
        val fill by settings.fullScreenFill.collectAsState()
        return fill to settings::setFullScreenFill
    }
    var local by rememberSaveable { mutableStateOf(false) }
    return local to { local = it }
}

/**
 * A two-finger pinch on the box: spread ([fill] true) or pinch in (false). A single finger passes through untouched, so a tap,
 * a double tap or a drag still reaches what is under it.
 */
fun Modifier.pinchToFill(onPinch: (fill: Boolean) -> Unit): Modifier = pointerInput(onPinch) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        var pinched = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                pinched = true
                zoom *= event.calculateZoom()
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinched && zoom > PINCH_STEP) onPinch(true)
        else if (pinched && zoom < 1f / PINCH_STEP) onPinch(false)
    }
}

private const val PINCH_STEP = 1.12f

/**
 * The plain full-screen player. The [picture] fills the window (it is told whether to fill the screen or keep its shape, which a
 * pinch switches and the look settings remember). Nothing is on it until a tap, which shows only: the title line at the top
 * ([title] · [subtitle]) with ⋯ at its end ([menus] and "Leave full screen"), back / play-pause / next ([transport]) in the
 * middle, and the seek bar with its times at the bottom (none while [live]). They hide [FULL_SCREEN_HIDE_MS] after the last
 * touch, not while the seek bar is dragged or a menu is open ([holdControls] keeps them up, a failure for example); a tap toggles
 * them, a mouse that moves shows them. A double tap on the left or right third skips [FULL_SCREEN_SKIP_MS] back or ahead, shown
 * as a soft half-circle with the seconds. A long press plays at 2x while the finger stays down ([speed]; the speed before comes
 * back on release, with a "2x speed" hint). On a touch screen ([rememberFullScreenDevice]) a vertical drag on the left half sets
 * the brightness and on the right half the volume, with the level meter at that edge. [actions] are direct lines of the ⋯ menu
 * (Picture in picture where the platform has it). Keys: Space or K plays and pauses, the arrows or J and L skip, F and Escape leave.
 * [overlay] is drawn over everything and stays when the controls hide (Watch's next-episode countdown and Skip intro, Video's
 * segment skip, a loading or failure note); it is told whether the controls show, so it can sit above the seek bar.
 */
@Composable
fun FullScreenPlayer(
    title: String,
    subtitle: String?,
    playing: Boolean,
    position: () -> Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onLeave: () -> Unit,
    transport: FullScreenTransport,
    picture: @Composable (fill: Boolean, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    menus: List<FullScreenMenu> = emptyList(),
    actions: List<OrbitMenuItem> = emptyList(),
    speed: FullScreenSpeed? = null,
    live: Boolean = false,
    holdControls: Boolean = false,
    overlay: @Composable BoxScope.(controlsShown: Boolean) -> Unit = {},
) {
    val (fill, setFill) = rememberFullScreenFill()
    var shown by remember { mutableStateOf(true) }
    var stamp by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(shown, stamp, scrubbing, menuOpen, holdControls) {
        if (!shown || scrubbing || menuOpen || holdControls) return@LaunchedEffect
        delay(FULL_SCREEN_HIDE_MS)
        shown = false
    }
    val canSkip = !live && durationMs > 0
    val skip: (Long) -> Unit = { delta -> if (canSkip) onSeek((position() + delta).coerceIn(0L, durationMs)) }
    val flash = remember { SkipFlash() }
    val device = rememberFullScreenDevice()
    val held = remember { mutableStateOf<Float?>(null) }
    val meter = remember { mutableStateOf<LevelKind?>(null) }
    val meterLevel = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val currentSpeed by androidx.compose.runtime.rememberUpdatedState(speed)
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier.fillMaxSize().background(Color.Black).testTag("fullscreen:player")
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.K -> { onPlayPause(); stamp++; true }
                    Key.DirectionLeft, Key.J -> { skip(-FULL_SCREEN_SKIP_MS); true }
                    Key.DirectionRight, Key.L -> { skip(FULL_SCREEN_SKIP_MS); true }
                    Key.F, Key.Escape -> { onLeave(); true }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                // A mouse that moves shows the controls again (the desktop); touches are the gestures' below.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move && event.changes.any { it.type == PointerType.Mouse }) { shown = true; stamp++ }
                    }
                }
            },
    ) {
        // The picture takes the whole window, into the cutout; only the controls keep clear of it.
        picture(fill, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().testTag("fullscreen:gestures")
                .pinchToFill { wanted -> setFill(wanted); shown = false }
                .pointerInput(canSkip) {
                    detectTapGestures(
                        onPress = {
                            tryAwaitRelease()
                            // A long press holds 2x only while the finger stays down: the speed before comes back.
                            held.value?.let { before -> currentSpeed?.set(before) }
                            held.value = null
                        },
                        onLongPress = {
                            currentSpeed?.let { s -> held.value = s.get(); s.set(FULL_SCREEN_HOLD_SPEED) }
                        },
                        onTap = { shown = !shown; stamp++ },
                        onDoubleTap = { at ->
                            when (tapThird(at.x / size.width)) {
                                TapThird.LEFT -> if (canSkip) { skip(-FULL_SCREEN_SKIP_MS); scope.launch { flash.show(false) } }
                                TapThird.RIGHT -> if (canSkip) { skip(FULL_SCREEN_SKIP_MS); scope.launch { flash.show(true) } }
                                TapThird.MIDDLE -> { shown = !shown; stamp++ }
                            }
                        },
                    )
                }
                // After the taps, so a drag is taken before the tap detector sees it (and cancels the tap).
                .levelDrags(device, meter, meterLevel),
        )
        SkipFlashes(flash)
        if (held.value != null) Text(
            "2x speed", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 24.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp).testTag("fullscreen:hold"),
        )
        meter.value?.let { kind ->
            LevelMeter(kind, meterLevel, Modifier.align(if (kind == LevelKind.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 24.dp))
        }
        AnimatedVisibility(shown || holdControls, Modifier.fillMaxSize(), enter = fadeIn(tween(200)), exit = fadeOut(tween(360))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(CONTROLS_SCRIM))
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 20.dp)) {
                    TitleLine(
                        title, subtitle, menus, actions, onLeave, onMenuOpen = { menuOpen = it; stamp++ },
                        Modifier.align(Alignment.TopStart).fillMaxWidth(),
                    )
                    CentreControls(transport, playing, canSkip, onPlayPause = { onPlayPause(); stamp++ }, skip = { skip(it); stamp++ }, Modifier.align(Alignment.Center))
                    if (!live && durationMs > 0) SeekRow(
                        position, durationMs, playing, onSeek = { onSeek(it); stamp++ }, onScrub = { scrubbing = it },
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { overlay(shown || holdControls) }
    }
}

/** The look of the full-screen controls: always the dark one (white ink, peach play), whatever the app's theme. */
private val FullScreenLook = OrbitLook(
    dark = true, ink = Color.White, dim = Color.White.copy(alpha = 0.72f), chip = Color.White.copy(alpha = 0.14f),
    row = Color.White.copy(alpha = 0.08f), accent = PEACH, ground = Color(0xFF141012),
)

private val CONTROLS_SCRIM = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.55f), 0.3f to Color.Black.copy(alpha = 0.25f), 0.7f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.6f),
)

/** "Title · subtitle" and ⋯ (the [menus], then "Leave full screen"). */
@Composable
private fun TitleLine(title: String, subtitle: String?, menus: List<FullScreenMenu>, actions: List<OrbitMenuItem>, onLeave: () -> Unit, onMenuOpen: (Boolean) -> Unit, modifier: Modifier) {
    val fonts = orbitFonts()
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<FullScreenMenu?>(null) }
    val setOpen: (Boolean) -> Unit = { open = it; if (!it) page = null; onMenuOpen(it) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(title)
                if (!subtitle.isNullOrBlank()) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.78f)))
                    append(" · $subtitle")
                    pop()
                }
            },
            style = TextStyle(fontFamily = fonts.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("fullscreen:title"),
        )
        Box {
            OrbitRoundButton(CosmoIcons.More, "More", Color.White, { setOpen(true) }, on = open, onInk = Color.Black, modifier = Modifier.testTag("fullscreen:more"))
            val shownPage = page
            val items = if (shownPage != null) shownPage.items.map { item ->
                OrbitMenuItem(item.title, item.detail, item.icon, item.selected, item.enabled, item.keepOpen, item.testTag) { item.onClick(); if (!item.keepOpen) setOpen(false) }
            } else buildList {
                menus.forEach { m -> add(OrbitMenuItem(m.title, m.detail, m.icon, keepOpen = true, enabled = m.items.isNotEmpty()) { page = m }) }
                actions.forEach { item -> add(OrbitMenuItem(item.title, item.detail, item.icon, item.selected, item.enabled, false, item.testTag) { setOpen(false); item.onClick() }) }
                add(OrbitMenuItem("Leave full screen", "Back, Esc or F", CosmoIcons.Expand, testTag = "fullscreen:leave") { setOpen(false); onLeave() })
            }
            OrbitMenu(open, { setOpen(false) }, items, fonts, FullScreenLook, header = shownPage?.title, width = 280.dp)
        }
    }
}

/** Back, play-pause and next (or back and ahead 10 s), centred. */
@Composable
private fun CentreControls(transport: FullScreenTransport, playing: Boolean, canSkip: Boolean, onPlayPause: () -> Unit, skip: (Long) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        when (transport) {
            is FullScreenTransport.Tracks -> RoundControl(CosmoIcons.Previous, "Previous", transport.hasPrevious, transport.onPrevious)
            FullScreenTransport.Skip -> RoundControl(CosmoIcons.Replay10, "Back 10 seconds", canSkip) { skip(-FULL_SCREEN_SKIP_MS) }
        }
        OrbitPlayButton(playing, onPlayPause, FullScreenLook, size = 76.dp)
        when (transport) {
            is FullScreenTransport.Tracks -> RoundControl(CosmoIcons.Next, "Next", transport.hasNext, transport.onNext)
            FullScreenTransport.Skip -> RoundControl(CosmoIcons.Forward10, "Ahead 10 seconds", canSkip) { skip(FULL_SCREEN_SKIP_MS) }
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    // White at 14 % behind white ink: OrbitRoundButton's own tone is 12 % of its ink.
    OrbitRoundButton(icon, label, Color.White, onClick, size = 56.dp, enabled = enabled)
}

/** The elapsed time, a straight seek bar (peach progress and thumb on white at 28 %) and the length. */
@Composable
private fun SeekRow(position: () -> Long, durationMs: Long, playing: Boolean, onSeek: (Long) -> Unit, onScrub: (Boolean) -> Unit, modifier: Modifier) {
    // Read a few times a second while playing, never in composition elsewhere.
    var now by remember { mutableLongStateOf(position()) }
    LaunchedEffect(playing) {
        now = position()
        while (playing) { delay(250); now = position() }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    val shownMs = drag?.let { (it * durationMs).toLong() } ?: now
    val time = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(clock(shownMs), style = time, color = Color.White)
        BoxWithConstraints(
            Modifier.weight(1f).height(32.dp).testTag("fullscreen:seek")
                .semantics { contentDescription = "Seek bar" }
                .pointerInput(durationMs) {
                    detectTapGestures { at -> onSeek(((at.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f); onScrub(true) },
                        onDragEnd = { drag?.let { onSeek((it * durationMs).toLong()) }; drag = null; onScrub(false) },
                        onDragCancel = { drag = null; onScrub(false) },
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val fraction = (shownMs.toFloat() / durationMs).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize().drawBehind {
                val line = 4.dp.toPx()
                val y = size.height / 2f
                val radius = CornerRadius(line / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y - line / 2f), Size(size.width, line), radius)
                drawRoundRect(PEACH, Offset(0f, y - line / 2f), Size(size.width * fraction, line), radius)
                drawCircle(PEACH, radius = 7.dp.toPx(), center = Offset(size.width * fraction, y))
            })
        }
        Text(clock(durationMs), style = time, color = Color.White)
    }
}

/** The last double-tap skip: which side, faded out over [SKIP_FLASH_MS]. */
private class SkipFlash {
    var forward by mutableStateOf(true)
    val alpha = Animatable(0f)

    suspend fun show(ahead: Boolean) {
        forward = ahead
        alpha.snapTo(1f)
        alpha.animateTo(0f, tween(SKIP_FLASH_MS))
    }
}

private const val SKIP_FLASH_MS = 650

/** The double tap's soft half-circle at the side it skipped to, with "+10 s" or "−10 s". */
@Composable
private fun BoxScope.SkipFlashes(flash: SkipFlash) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = flash.alpha.value }) {
        val wide = maxWidth * 0.42f
        val forward = flash.forward
        Box(
            Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .offset(x = if (forward) wide * 0.35f else -wide * 0.35f)
                .size(wide, maxHeight * 1.2f).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
        )
        Column(
            Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).width(wide * 0.65f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (forward) "›››" else "‹‹‹", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            Text(if (forward) "+10 s" else "−10 s", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A vertical drag on the left half sets the brightness and on the right half the volume ([device]'s; nothing where it is null),
 * showing [meter] at [level] while it lasts. A second finger is a pinch, left to the pinch; a sideways drag is left alone.
 */
private fun Modifier.levelDrags(device: FullScreenDevice?, meter: androidx.compose.runtime.MutableState<LevelKind?>, level: androidx.compose.runtime.MutableFloatState): Modifier =
    if (device == null) this else pointerInput(device) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val height = size.height.coerceAtLeast(1).toFloat()
            val kind = if (down.position.x < size.width / 2f) LevelKind.BRIGHTNESS else LevelKind.VOLUME
            val target = if (kind == LevelKind.BRIGHTNESS) device.brightness else device.volume
            var dragging = false
            var start = 0f
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2 || target == null) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dy = change.position.y - down.position.y
                    val dx = change.position.x - down.position.x
                    if (!dragging && kotlin.math.abs(dy) > viewConfiguration.touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        dragging = true
                        start = target.get()
                        level.floatValue = start
                        meter.value = kind
                    }
                    if (dragging) {
                        change.consume()
                        val next = (start - dy / height).coerceIn(0f, 1f)
                        level.floatValue = next
                        target.set(next)
                    }
                }
            } finally {
                if (dragging) meter.value = null
            }
        }
    }
