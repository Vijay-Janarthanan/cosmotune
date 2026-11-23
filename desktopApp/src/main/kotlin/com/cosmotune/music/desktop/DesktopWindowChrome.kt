package com.cosmotune.music.desktop

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import java.awt.MouseInfo

// The window's own frame, drawn by the application rather than by the system.

/** The strip where the system's title bar would have been. */
@Composable
internal fun DesktopTitleBar() {
    // Collected rather than read: [DesktopTitleBarSetting.active] is a plain call, so a composable
    // that only asks it never learns the answer changed.
    val enabled by DesktopTitleBarSetting.enabled.collectAsState()
    if (!DesktopPlatform.drawsOwnWindowFrame || !enabled) return
    // The light theme has no dark glass: the strip stands on the ambient ground, as the sidebar and the top bar do.
    val lightLook = !com.cosmotune.ui.theme.LocalCosmoColors.current.dark
    DesktopTitleBarDragArea(
        Modifier
            .fillMaxWidth()
            .height(if (DesktopPlatform.isMac) CAPTION_HEIGHT else WINDOWS_CAPTION_HEIGHT)
            .then(if (lightLook) Modifier else Modifier.desktopWindowGlass()),
    ) {
        Box(Modifier.fillMaxSize()) {
            // macOS: the traffic lights at the start. Windows: minimise, maximise and close at the end, as every Windows app has them.
            if (DesktopPlatform.isMac) DesktopWindowButtons(Modifier.align(Alignment.CenterStart))
            else WindowsCaptionButtons(Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * Windows' caption buttons over the window's top end corner when there is no title bar (and not in full screen): drawn last, over
 * every page and the player. [desktopCaptionClearance] keeps what stands there clear of them.
 */
@Composable
internal fun DesktopCaptionOverlay(modifier: Modifier = Modifier) {
    if (!windowsCaptionInline()) return
    WindowsCaptionButtons(modifier)
}

/** Whether Windows' caption buttons stand over the window's top end corner: Windows draws the frame, no title bar, not full screen. */
@Composable
private fun windowsCaptionInline(): Boolean {
    if (!DesktopPlatform.drawsOwnWindowFrame || DesktopPlatform.isMac) return false
    val titleBar by DesktopTitleBarSetting.enabled.collectAsState()
    val fullScreen by DesktopWindowMode.fullScreen.collectAsState()
    return !titleBar && !fullScreen
}

/**
 * Room at the end of a row at the top of the window for Windows' caption buttons: the end padding that keeps this row's content
 * out from under them, measured from where the row stands (none for a row that ends short of them or starts below them).
 */
@Composable
internal fun Modifier.desktopCaptionClearance(): Modifier {
    if (!windowsCaptionInline()) return this
    val density = androidx.compose.ui.platform.LocalDensity.current
    var overlap by remember { mutableStateOf(0.dp) }
    return this
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            val windowWidth = coordinates.findRootCoordinates().size.width
            val reach = with(density) { WINDOWS_CAPTION_WIDTH.toPx() }
            val below = bounds.top >= with(density) { WINDOWS_CAPTION_HEIGHT.toPx() }
            val px = if (below) 0f else (reach - (windowWidth - bounds.right)).coerceAtLeast(0f)
            val next = with(density) { px.toDp() }
            if (next != overlap) overlap = next
        }
        .padding(end = overlap)
}

/**
 * Minimise, maximise (restore when maximised) and close, Windows' own way: 46 by 32 dp each at the end of the caption, a thin glyph
 * in the ink, a faint wash on hover and Windows' red under close. Each names itself in a tooltip.
 */
@Composable
private fun WindowsCaptionButtons(modifier: Modifier = Modifier) {
    val actions = LocalDesktopWindowActions.current ?: return
    val maximized by DesktopWindowMode.maximized.collectAsState()
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val dark = com.cosmotune.ui.theme.LocalCosmoColors.current.dark
    val ink = (if (dark) Color.White else Color.Black).copy(alpha = if (focused) 0.9f else 0.45f)
    Row(modifier.height(WINDOWS_CAPTION_HEIGHT)) {
        WindowsCaptionButton("Minimize", ink, dark, close = false, onClick = actions.minimize) { glyph ->
            val reach = WINDOWS_GLYPH_HALF.toPx()
            drawLine(glyph, Offset(center.x - reach, center.y), Offset(center.x + reach, center.y), WINDOWS_GLYPH_LINE.toPx())
        }
        WindowsCaptionButton(if (maximized) "Restore" else "Maximize", ink, dark, close = false, onClick = actions.toggleMaximize) { glyph ->
            val reach = WINDOWS_GLYPH_HALF.toPx()
            val line = WINDOWS_GLYPH_LINE.toPx()
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(line)
            if (maximized) {
                // Restore: the front square, and the corner of the one behind it.
                val step = 2.dp.toPx()
                val front = androidx.compose.ui.geometry.Size(2 * reach - step, 2 * reach - step)
                drawRect(glyph, Offset(center.x - reach, center.y - reach + step), front, style = stroke)
                drawLine(glyph, Offset(center.x - reach + step, center.y - reach), Offset(center.x + reach, center.y - reach), line)
                drawLine(glyph, Offset(center.x + reach, center.y - reach), Offset(center.x + reach, center.y + reach - step), line)
            } else {
                drawRect(glyph, Offset(center.x - reach, center.y - reach), androidx.compose.ui.geometry.Size(2 * reach, 2 * reach), style = stroke)
            }
        }
        WindowsCaptionButton("Close", ink, dark, close = true, onClick = actions.close) { glyph ->
            val reach = WINDOWS_GLYPH_HALF.toPx()
            val line = WINDOWS_GLYPH_LINE.toPx()
            drawLine(glyph, Offset(center.x - reach, center.y - reach), Offset(center.x + reach, center.y + reach), line)
            drawLine(glyph, Offset(center.x + reach, center.y - reach), Offset(center.x - reach, center.y + reach), line)
        }
    }
}

@Composable
private fun WindowsCaptionButton(
    label: String,
    ink: Color,
    dark: Boolean,
    close: Boolean,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val wash = when {
        close && (hovered || pressed) -> if (pressed) WINDOWS_CLOSE_PRESSED else WINDOWS_CLOSE
        pressed -> (if (dark) Color.White else Color.Black).copy(alpha = 0.12f)
        hovered -> (if (dark) Color.White else Color.Black).copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val glyphColor = if (close && (hovered || pressed)) Color.White else ink
    com.cosmotune.ui.orbit.OrbitTooltip(label) {
        Box(
            Modifier
                .size(width = WINDOWS_CAPTION_BUTTON_WIDTH, height = WINDOWS_CAPTION_HEIGHT)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Default)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .semantics { contentDescription = label }
                .drawBehind {
                    drawRect(wash)
                    glyph(glyphColor)
                },
        )
    }
}

/** Whether there is a title bar at all. */
internal object DesktopTitleBarSetting {

    internal const val KEY = "window_title_bar"

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY, false))

    /** What Settings shows and writes. */
    val enabled: StateFlow<Boolean> = _enabled

    fun set(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY, value)
        _enabled.value = value
    }
}

/** What the caption buttons act on. */
internal class DesktopWindowActions(
    val minimize: () -> Unit,
    val toggleMaximize: () -> Unit,
    val close: () -> Unit,
)

internal val LocalDesktopWindowActions = staticCompositionLocalOf<DesktopWindowActions?> { null }

/** The window, for the drag area. */
internal val LocalDesktopWindowScope = staticCompositionLocalOf<WindowScope?> { null }

/** A region that moves the window, and maximizes it on a double click. */
@Composable
internal fun DesktopTitleBarDragArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val scope = LocalDesktopWindowScope.current
    val actions = LocalDesktopWindowActions.current
    if (!DesktopPlatform.drawsOwnWindowFrame || scope == null) {
        Box(modifier) { content() }
        return
    }
    // Behind the content rather than around it. Wrapped around it, the drag area saw every press
    // in the toolbar too — dragging the volume slider moved the window with it. As a sibling
    // underneath, anything with its own pointer input (buttons, sliders) is hit first and the
    // press never reaches here; only empty toolbar falls through to move the window.
    Box(modifier) {
        Box(Modifier.matchParentSize().captionPress(scope, actions))
        content()
    }
}

/**
 * macOS' traffic lights: in the title bar when there is one, otherwise at the head of the sidebar or the toolbar. Only on macOS:
 * Windows has its own caption buttons at the other end ([WindowsCaptionButtons]), and Linux keeps the system's frame.
 */
@Composable
internal fun DesktopWindowButtons(modifier: Modifier = Modifier) {
    if (!DesktopPlatform.drawsOwnWindowFrame || !DesktopPlatform.isMac) return
    val actions = LocalDesktopWindowActions.current ?: return
    val maximized by DesktopWindowMode.maximized.collectAsState()
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused
    // An unfocused window's dots: a faint wash of the ground's opposite, so they still read on the light theme.
    val onDark = com.cosmotune.ui.theme.LocalCosmoColors.current.dark
    val inactiveFill = if (!isFocused && DesktopPlatform.isMac) (if (onDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.16f)) else null

    Row(
        modifier = modifier.height(CAPTION_HEIGHT).padding(start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacCaptionButton("Close", MAC_CLOSE, onClick = actions.close, inactiveFill = inactiveFill) { glyph ->
            val reach = GLYPH_HALF.toPx()
            val line = HAIRLINE.toPx()
            drawLine(
                color = glyph,
                start = Offset(center.x - reach, center.y - reach),
                end = Offset(center.x + reach, center.y + reach),
                strokeWidth = line,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = glyph,
                start = Offset(center.x + reach, center.y - reach),
                end = Offset(center.x - reach, center.y + reach),
                strokeWidth = line,
                cap = StrokeCap.Round,
            )
        }
        MacCaptionButton("Minimize", MAC_MINIMIZE, onClick = actions.minimize, inactiveFill = inactiveFill) { glyph ->
            drawLine(
                color = glyph,
                start = Offset(center.x - GLYPH_HALF.toPx(), center.y),
                end = Offset(center.x + GLYPH_HALF.toPx(), center.y),
                strokeWidth = HAIRLINE.toPx(),
                cap = StrokeCap.Round,
            )
        }
        MacCaptionButton(
            if (maximized) "Restore" else "Maximize",
            MAC_MAXIMIZE,
            onClick = actions.toggleMaximize,
            inactiveFill = inactiveFill,
        ) { glyph ->
            val reach = GLYPH_HALF.toPx()
            val line = HAIRLINE.toPx()
            // The opposing corner marks used by macOS' green zoom control. They remain legible at
            // 100% Windows scaling, unlike a tiny outlined square.
            drawLine(glyph, Offset(center.x - reach, center.y + reach), Offset(center.x + reach, center.y - reach), line, StrokeCap.Round)
            drawLine(glyph, Offset(center.x + reach, center.y - reach), Offset(center.x + 1.dp.toPx(), center.y - reach), line, StrokeCap.Round)
            drawLine(glyph, Offset(center.x + reach, center.y - reach), Offset(center.x + reach, center.y - 1.dp.toPx()), line, StrokeCap.Round)
            drawLine(glyph, Offset(center.x - reach, center.y + reach), Offset(center.x - 1.dp.toPx(), center.y + reach), line, StrokeCap.Round)
            drawLine(glyph, Offset(center.x - reach, center.y + reach), Offset(center.x - reach, center.y + 1.dp.toPx()), line, StrokeCap.Round)
        }
    }
}

@Composable
private fun MacCaptionButton(
    label: String,
    fill: Color,
    onClick: () -> Unit,
    inactiveFill: Color? = null,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val buttonColor = if (inactiveFill != null && !hovered) inactiveFill else fill
    Box(
        Modifier
            .size(CAPTION_TOUCH_SIZE)
            .hoverable(interaction)
            // The arrow, not the hand: this is window furniture, and the pointer says so before the
            // click does.
            .pointerHoverIcon(PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label }
            .drawBehind {
                drawCircle(buttonColor, radius = MAC_DOT_RADIUS.toPx())
                if (hovered) glyph(MAC_GLYPH)
            },
        contentAlignment = Alignment.Center,
    ) {}
}

/**
 * The platform's own caption metrics, which is what makes these read as the window's controls
 * rather than the application's.
 */
private val CAPTION_TOUCH_SIZE = 22.dp
private val CAPTION_HEIGHT = 24.dp
private val MAC_DOT_RADIUS = 6.dp

/** Half the width of a glyph, and the weight every one of them is drawn at. */
private val GLYPH_HALF = 2.5.dp
private val HAIRLINE = 0.75.dp

private val MAC_CLOSE = Color(0xFFFF5F57)
private val MAC_MINIMIZE = Color(0xFFFFBD2E)
private val MAC_MAXIMIZE = Color(0xFF28C840)
private val MAC_GLYPH = Color(0xB3000000)

/** Windows 11's caption buttons: 46 by 32 each, a 10 dp glyph drawn 1 dp thin, and its red under close. */
private val WINDOWS_CAPTION_HEIGHT = 32.dp
private val WINDOWS_CAPTION_BUTTON_WIDTH = 46.dp
internal val WINDOWS_CAPTION_WIDTH = WINDOWS_CAPTION_BUTTON_WIDTH * 3
private val WINDOWS_GLYPH_HALF = 5.dp
private val WINDOWS_GLYPH_LINE = 1.dp
private val WINDOWS_CLOSE = Color(0xFFC42B1C)
private val WINDOWS_CLOSE_PRESSED = Color(0xFFB2271A)

/**
 * A press on empty caption: moves the window, and a second one in quick succession maximizes it,
 * the way the system caption always has.
 *
 * On Windows the move is handed to the system ([DesktopWindowsFrame.startDrag]), which runs its own
 * move loop — DWM slides the window, Aero Snap works, and no edge is left exposed to paint white.
 * On macOS it is handed to AppKit via [DesktopMacFrame.startDrag] for smooth ProMotion dragging.
 * The double click is counted here rather than left to Windows: the area is client area as far as
 * Windows knows, so it never sends the caption's own double click. Without the native frame the
 * window is moved from the pointer, as WindowDraggableArea did.
 */
private fun Modifier.captionPress(scope: WindowScope, actions: DesktopWindowActions?): Modifier =
    pointerInput(scope, actions) {
        var lastPressAt = 0L
        var lastPressPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown()
            // The empty caption owns this gesture; the player sheet below must not
            // also interpret a window move as dismiss/skip or a Canvas tap.
            down.consume()
            val double = down.uptimeMillis - lastPressAt <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastPressPosition).getDistance() <= viewConfiguration.touchSlop
            if (double) {
                lastPressAt = 0L
                down.consume()
                actions?.toggleMaximize()
                return@awaitEachGesture
            }
            lastPressAt = down.uptimeMillis
            lastPressPosition = down.position
            if (DesktopWindowsFrame.startDrag()) return@awaitEachGesture
            if (DesktopPlatform.isMac && DesktopMacFrame.startDrag()) return@awaitEachGesture
            val window = scope.window
            val windowAtStart = window.location
            val pointerAtStart = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            drag(down.id) {
                val pointer = MouseInfo.getPointerInfo()?.location ?: return@drag
                window.setLocation(
                    windowAtStart.x + pointer.x - pointerAtStart.x,
                    windowAtStart.y + pointer.y - pointerAtStart.y,
                )
            }
        }
    }
