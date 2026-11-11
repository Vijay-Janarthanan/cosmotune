package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.BiteShape
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitWindowColours
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFloatEdge
import com.cosmotune.ui.orbit.orbitFloatGround
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.EventQueue
import java.awt.GraphicsConfiguration
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * What a click on the tray icon opens, in place of the system's own menu: a card that shows what is
 * playing with the controls (left click), or the menu, drawn in the app's own style (right click).
 * It opens beside the icon, closes when it loses focus or on Escape, and a second click on the icon
 * closes it too.
 */
internal object DesktopTrayFlyout {
    enum class Mode { PLAYER, MENU }

    class Request(val mode: Mode, val anchor: Point, val screen: GraphicsConfiguration)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private var closedMode: Mode? = null
    private var closedAt = 0L

    /** Opens [mode] at the pointer, or closes it when it is the one already open. */
    fun toggle(mode: Mode) {
        if (_request.value?.mode == mode) {
            close()
            return
        }
        // Clicking the icon takes the focus from an open flyout, which closes it before this click
        // arrives: that click was meant to close it, not to open it again.
        if (closedMode == mode && System.currentTimeMillis() - closedAt < REOPEN_GUARD_MS) {
            closedMode = null
            return
        }
        val pointer = runCatching { MouseInfo.getPointerInfo() }.getOrNull() ?: return
        _request.value = Request(mode, pointer.location, pointer.device.defaultConfiguration)
    }

    fun close() {
        _request.value?.let {
            closedMode = it.mode
            closedAt = System.currentTimeMillis()
        }
        _request.value = null
    }

    private const val REOPEN_GUARD_MS = 400L
}

/** Where a window of a given size goes when it opens from a click at a point on a screen. */
internal object DesktopTrayFlyoutPlacement {

    /**
     * The window's top-left corner. It sits against the edge of the screen the click is nearest to
     * (where the taskbar or the menu bar is), inside the usable area, centred on the click and
     * pushed back in if that would take it off the screen.
     */
    fun place(anchorX: Int, anchorY: Int, width: Int, height: Int, screen: Rectangle, usable: Rectangle, gap: Int = 4): Point {
        val toLeft = anchorX - screen.x
        val toRight = screen.x + screen.width - anchorX
        val toTop = anchorY - screen.y
        val toBottom = screen.y + screen.height - anchorY
        val nearest = minOf(toLeft, toRight, toTop, toBottom)
        var x: Int
        var y: Int
        when (nearest) {
            toBottom -> { x = anchorX - width / 2; y = usable.y + usable.height - height - gap }
            toTop -> { x = anchorX - width / 2; y = usable.y + gap }
            toRight -> { x = usable.x + usable.width - width - gap; y = anchorY - height / 2 }
            else -> { x = usable.x + gap; y = anchorY - height / 2 }
        }
        val minX = usable.x + gap
        val minY = usable.y + gap
        x = x.coerceIn(minX, maxOf(minX, usable.x + usable.width - width - gap))
        y = y.coerceIn(minY, maxOf(minY, usable.y + usable.height - height - gap))
        return Point(x, y)
    }

    fun usable(config: GraphicsConfiguration): Rectangle {
        val bounds = config.bounds
        val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(config) }.getOrNull()
            ?: return bounds
        return Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
    }
}

private val PLAYER_CARD = DpSize(344.dp, 172.dp)
private val MENU_CARD_WIDTH = 252.dp
private val SHADOW_PAD = 16.dp
/** The player card's corners and its bite: play (56 dp) stands in a 33 dp bite whose centre is 40 dp in from the corner. */
private val CARD_RADIUS = 30.dp
private val PLAY_DP = 56.dp
private val PLAYER_BITE = BiteShape(corner = CARD_RADIUS, bite = 33.dp, inset = 40.dp)
private val RING_DP = 72.dp
private val DISC_DP = 60.dp
private val MENU_RADIUS = 24.dp
private val MENU_PAD = 8.dp
private const val ROW_DP = 40
private const val SEPARATOR_DP = 9
private const val HEADER_DP = 58

/** The flyout's window; hosted beside the main window in the application scope. */
@Composable
internal fun DesktopTrayFlyoutWindow() {
    val request by DesktopTrayFlyout.request.collectAsState()
    val current = request ?: return
    val now by DesktopMiniPlayer.state.collectAsState()
    key(current) {
        val entries = remember(now.title, now.playing) { menuEntries(DesktopTrayMenu.entries()) }
        val card = when (current.mode) {
            DesktopTrayFlyout.Mode.PLAYER -> PLAYER_CARD
            DesktopTrayFlyout.Mode.MENU -> DpSize(MENU_CARD_WIDTH, menuHeight(entries, now.title.isNotBlank()).dp)
        }
        val size = DpSize(card.width + SHADOW_PAD * 2, card.height + SHADOW_PAD * 2)
        val position = remember(current, size) {
            DesktopTrayFlyoutPlacement.place(
                current.anchor.x, current.anchor.y, size.width.value.toInt(), size.height.value.toInt(),
                current.screen.bounds, DesktopTrayFlyoutPlacement.usable(current.screen),
            )
        }
        DialogWindow(
            onCloseRequest = DesktopTrayFlyout::close,
            state = rememberDialogState(position = WindowPosition(position.x.dp, position.y.dp), size = size),
            title = "Cosmotune",
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
            onPreviewKeyEvent = { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    DesktopTrayFlyout.close()
                    true
                } else {
                    false
                }
            },
        ) {
            val dialog = window
            DisposableEffect(dialog) {
                val listener = object : WindowAdapter() {
                    override fun windowLostFocus(event: WindowEvent?) = DesktopTrayFlyout.close()
                }
                dialog.addWindowFocusListener(listener)
                onDispose { dialog.removeWindowFocusListener(listener) }
            }
            LaunchedEffect(dialog) {
                dialog.toFront()
                dialog.requestFocus()
            }
            // The app's theme choice, as the main window draws it ([rememberDesktopDark]).
            DesktopTrayFlyoutContent(current.mode, now, DesktopMiniPlayer.actions, entries, card, dark = rememberDesktopDark(), onClose = DesktopTrayFlyout::close)
        }
    }
}

/**
 * The entries the card lists. The song's own line (disabled, with a separator under it) is the
 * card's header instead.
 */
internal fun menuEntries(all: List<DesktopTrayMenu.Entry>): List<DesktopTrayMenu.Entry> =
    all.filter { it.separator || it.enabled }.dropWhile { it.separator }

private fun menuHeight(entries: List<DesktopTrayMenu.Entry>, header: Boolean): Int {
    val rows = entries.count { !it.separator } * ROW_DP
    val separators = entries.count { it.separator } * SEPARATOR_DP
    return 16 + rows + separators + if (header) HEADER_DP + SEPARATOR_DP else 0
}

/**
 * The card, on its own so it can be drawn off screen. Orbit: the player is a flat card in the cover's own colour with the cover as
 * a disc whose orbit is the song's progress, round buttons in the card's ink and play in the card's bite (no blurred cover under
 * it); the menu is the Orbit popover's card (the float ground, radius 24) with its rows. The window stands outside the app's theme,
 * so it brings the colours of [dark] (the app's theme choice, [rememberDesktopDark]).
 */
@Composable
internal fun DesktopTrayFlyoutContent(
    mode: DesktopTrayFlyout.Mode,
    now: DesktopMiniPlayer.NowPlaying,
    actions: DesktopMiniPlayer.Actions,
    entries: List<DesktopTrayMenu.Entry>,
    card: DpSize,
    dark: Boolean = true,
    onClose: () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        OrbitWindowColours(dark) {
            Box(Modifier.fillMaxSize().padding(SHADOW_PAD), contentAlignment = Alignment.Center) {
                val sized = Modifier.size(card.width, card.height).testTag("tray:${mode.name.lowercase()}")
                when (mode) {
                    DesktopTrayFlyout.Mode.PLAYER -> PlayerCard(now, actions, onClose, sized)
                    DesktopTrayFlyout.Mode.MENU -> MenuCard(now, entries, onClose, sized)
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(now: DesktopMiniPlayer.NowPlaying, actions: DesktopMiniPlayer.Actions, onClose: () -> Unit, modifier: Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val (fill, ink) = coverFill(now.artworkUrl, now.title.ifBlank { "tray" })
    OrbitBiteCard(
        fill = fill,
        modifier = modifier,
        shape = PLAYER_BITE,
        biteInset = 12.dp,
        bite = { OrbitPlayButton(now.playing, actions.playPause, look, Modifier.testTag("tray:playpause"), size = PLAY_DP) },
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The cover as a disc, its orbit the song's progress and the moon where it is.
                Box(Modifier.size(RING_DP), contentAlignment = Alignment.Center) {
                    Disc(now.artworkUrl, DISC_DP)
                    OrbitRing(RING_DP, ink, progress = { now.progress.coerceIn(0f, 1f) }, spinning = now.playing, stroke = 2.dp, moon = 4.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title.ifBlank { "Nothing playing" }, style = fonts.title.copy(fontSize = 16.sp), color = ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("tray:title"),
                    )
                    if (now.artist.isNotBlank()) {
                        Text(now.artist, style = fonts.body, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                OrbitRoundButton(
                    Icons.Rounded.OpenInNew, "Open Cosmotune", ink, onClick = { onClose(); actions.openMain() },
                    modifier = Modifier.testTag("tray:open"), size = 36.dp, onInk = fill,
                )
            }
            Spacer(Modifier.weight(1f))
            // Previous and next along the card's foot, play in its bite.
            Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OrbitRoundButton(CosmoIcons.Previous, "Previous", ink, actions.previous, Modifier.testTag("tray:previous"), size = 44.dp, onInk = fill)
                OrbitRoundButton(CosmoIcons.Next, "Next", ink, actions.next, Modifier.testTag("tray:next"), size = 44.dp, onInk = fill)
            }
        }
    }
}

@Composable
private fun MenuCard(now: DesktopMiniPlayer.NowPlaying, entries: List<DesktopTrayMenu.Entry>, onClose: () -> Unit, modifier: Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val shape = RoundedCornerShape(MENU_RADIUS)
    Column(
        modifier.shadow(14.dp, shape, clip = false).clip(shape).background(orbitFloatGround(look)).border(1.dp, orbitFloatEdge(look), shape)
            .padding(MENU_PAD),
    ) {
        if (now.title.isNotBlank()) {
            Row(Modifier.fillMaxWidth().height(HEADER_DP.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Disc(now.artworkUrl, 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(now.artist, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Gap()
        }
        entries.forEach { entry ->
            if (entry.separator) Gap() else MenuRow(entry, onClose, fonts, look)
        }
    }
}

/** Where the system menu has a rule: a little air instead (Orbit draws no rules). */
@Composable
private fun Gap() {
    Spacer(Modifier.fillMaxWidth().height(SEPARATOR_DP.dp))
}

/** One entry, as the Orbit popover draws its items: the icon and the title, the chip's fill under the pointer. */
@Composable
private fun MenuRow(entry: DesktopTrayMenu.Entry, onClose: () -> Unit, fonts: OrbitFonts, look: OrbitLook) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(ROW_DP.dp).clip(RoundedCornerShape(16.dp))
            .background(if (hovered) look.chip else Color.Transparent)
            .hoverable(hover)
            .clickable {
                onClose()
                entry.action?.let { EventQueue.invokeLater(it) }
            }
            .padding(horizontal = 12.dp)
            .testTag("tray:item:${entry.label}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(entry), null, tint = look.ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(entry.label, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The menu names its icons the way a Linux desktop does; these are the app's own drawings of them. */
private fun iconFor(entry: DesktopTrayMenu.Entry): ImageVector = if (entry.id == DesktopTrayMenu.ID_OPEN_PLAYER) Icons.Rounded.OpenInNew else when (entry.icon) {
    "media-playback-pause" -> Icons.Rounded.Pause
    "media-playback-start" -> Icons.Rounded.PlayArrow
    "media-skip-forward" -> Icons.Rounded.SkipNext
    "media-skip-backward" -> Icons.Rounded.SkipPrevious
    "view-restore" -> Icons.Rounded.PictureInPictureAlt
    "preferences-system" -> Icons.Rounded.Settings
    "application-exit" -> Icons.Rounded.PowerSettingsNew
    else -> Icons.Rounded.OpenInNew
}
