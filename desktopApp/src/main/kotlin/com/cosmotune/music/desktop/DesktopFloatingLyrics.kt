package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitWindowColours
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The floating lyrics window (Namida's idea): the line being sung and the next one, in a small window that stays above other
 * applications and is dragged anywhere. Opened from the tray menu or Ctrl/Command+Shift+L; closing it leaves the player as it was.
 */
internal object DesktopFloatingLyrics {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()
    private val _lines = MutableStateFlow<List<LyricLine>>(emptyList())
    val lines: StateFlow<List<LyricLine>> = _lines.asStateFlow()

    fun toggle() { _open.value = !_open.value }
    fun close() { _open.value = false }
    /** The playing song's timed lyrics, published by the main window as they load (empty while there are none). */
    fun publish(lines: List<LyricLine>?) { _lines.value = lines.orEmpty() }

    /** The line sung at [positionMs] and the one after it; a gap or an untimed song shows nothing. */
    fun linesAt(lines: List<LyricLine>, positionMs: Long): Pair<String?, String?> {
        val timed = lines.filter { it.text.isNotBlank() }
        if (timed.size < 2 || timed.all { it.timeMs == timed.first().timeMs }) return null to null
        val at = timed.indexOfLast { it.timeMs <= positionMs }
        return timed.getOrNull(at)?.text to timed.getOrNull(at + 1)?.text
    }
}

@Composable
internal fun DesktopFloatingLyricsWindow() {
    val open by DesktopFloatingLyrics.open.collectAsState()
    if (!open) return
    val lines by DesktopFloatingLyrics.lines.collectAsState()
    val now by DesktopMiniPlayer.state.collectAsState()
    val shown by produceState<Pair<String?, String?>>(null to null, lines) {
        while (true) {
            // The last reading of the playhead, carried on by the time since it was taken while the song plays.
            val position = DesktopMiniPlayer.position
            val at = position?.let { p ->
                p.positionMs + if (DesktopMiniPlayer.state.value.playing && p.advancing && p.sampledAtNanos > 0) (System.nanoTime() - p.sampledAtNanos) / 1_000_000 else 0L
            } ?: 0L
            value = DesktopFloatingLyrics.linesAt(lines, at)
            delay(150)
        }
    }
    val state = rememberWindowState(size = DpSize(620.dp, 132.dp), position = WindowPosition(Alignment.BottomCenter))
    Window(onCloseRequest = DesktopFloatingLyrics::close, state = state, title = "Cosmotune lyrics",
        undecorated = true, transparent = true, alwaysOnTop = true, resizable = true) {
        val dark = rememberDesktopDark()
        androidx.compose.material3.MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) { OrbitWindowColours(dark) {
            val look = orbitLook()
            val fonts = orbitFonts()
            WindowDraggableArea {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(look.ground.copy(alpha = 0.88f)).testTag("floating-lyrics")) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 16.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val (line, next) = shown
                        Text(line ?: if (lines.isEmpty()) now.title.ifBlank { "Nothing is playing" } else "♪", color = look.ink,
                            style = fonts.title.copy(fontSize = 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        if (next != null) Text(next, color = look.dim, style = fonts.body.copy(fontSize = 15.sp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        else if (line == null && lines.isEmpty() && now.title.isNotBlank()) Text("No timed lyrics for this song", color = look.dim,
                            style = fonts.body.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        CosmoIconButton(CosmoIcons.Close, "Close the lyrics window", DesktopFloatingLyrics::close, size = 36.dp)
                    }
                }
            }
        } }
    }
}
