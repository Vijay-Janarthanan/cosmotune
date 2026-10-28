package com.cosmotune.music.desktop

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
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
private val CARD_RADIUS = 18.dp
private const val ROW_DP = 36
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
            DesktopTrayFlyoutContent(current.mode, now, DesktopMiniPlayer.actions, entries, card, DesktopTrayFlyout::close)
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
    return 12 + rows + separators + if (header) HEADER_DP + SEPARATOR_DP else 0
}

/** The card, on its own so it can be drawn off screen. */
@Composable
internal fun DesktopTrayFlyoutContent(
    mode: DesktopTrayFlyout.Mode,
    now: DesktopMiniPlayer.NowPlaying,
    actions: DesktopMiniPlayer.Actions,
    entries: List<DesktopTrayMenu.Entry>,
    card: DpSize,
    onClose: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().padding(SHADOW_PAD), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(card.width, card.height)
                    .shadow(14.dp, RoundedCornerShape(CARD_RADIUS))
                    .clip(RoundedCornerShape(CARD_RADIUS))
                    .background(Color(0xFF15151C))
                    .testTag("tray:${mode.name.lowercase()}"),
            ) {
                when (mode) {
                    DesktopTrayFlyout.Mode.PLAYER -> PlayerCard(now, actions, onClose)
                    DesktopTrayFlyout.Mode.MENU -> MenuCard(now, entries, onClose)
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(now: DesktopMiniPlayer.NowPlaying, actions: DesktopMiniPlayer.Actions, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // The cover, blown up and blurred, is the card's background.
        if (now.artworkUrl != null) {
            DesktopArtwork(
                now.artworkUrl,
                Modifier.fillMaxSize().graphicsLayer { scaleX = 1.6f; scaleY = 1.6f }.blur(28.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x59000000), Color(0xE6000000)))))
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DesktopArtwork(now.artworkUrl, Modifier.size(66.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title.ifBlank { "Nothing playing" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("tray:title"),
                    )
                    Text(now.artist, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onClose(); actions.openMain() }, modifier = Modifier.size(36.dp).testTag("tray:open")) {
                    Icon(Icons.Rounded.OpenInNew, "Open Cosmotune", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            LinearProgressIndicator(
                progress = { now.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.25f),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.previous, modifier = Modifier.size(44.dp).testTag("tray:previous")) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color.White).clickable(onClick = actions.playPause).testTag("tray:playpause"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (now.playing) "Pause" else "Play", tint = Color(0xFF15151C), modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                IconButton(onClick = actions.next, modifier = Modifier.size(44.dp).testTag("tray:next")) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun MenuCard(now: DesktopMiniPlayer.NowPlaying, entries: List<DesktopTrayMenu.Entry>, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(6.dp)) {
        if (now.title.isNotBlank()) {
            Row(Modifier.fillMaxWidth().height(HEADER_DP.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                DesktopArtwork(now.artworkUrl, Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(now.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Divider()
        }
        entries.forEach { entry ->
            if (entry.separator) Divider() else MenuRow(entry, onClose)
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(SEPARATOR_DP.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.12f)))
    }
}

@Composable
private fun MenuRow(entry: DesktopTrayMenu.Entry, onClose: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(ROW_DP.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(hover)
            .clickable {
                onClose()
                entry.action?.let { EventQueue.invokeLater(it) }
            }
            .padding(horizontal = 10.dp)
            .testTag("tray:item:${entry.label}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(entry), null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(entry.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
