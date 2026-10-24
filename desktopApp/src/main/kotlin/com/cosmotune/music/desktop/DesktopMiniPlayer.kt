package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The compact always-on-top mini player: artwork, title, artist, progress and the three transport
 * buttons in a small window that stays above other applications. Opened from the tray menu or
 * Ctrl/Command+Shift+M; closing it leaves playback and the main window as they were.
 */
internal object DesktopMiniPlayer {
    data class NowPlaying(val title: String = "", val artist: String = "", val artworkUrl: String? = null,
        val playing: Boolean = false, val progress: Float = 0f)

    class Actions(val playPause: () -> Unit, val next: () -> Unit, val previous: () -> Unit, val openMain: () -> Unit)

    val SIZE = DpSize(380.dp, 124.dp)

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()
    @Volatile var actions: Actions = Actions({}, {}, {}, {})

    fun toggle() { _open.value = !_open.value }
    fun close() { _open.value = false }
    fun publish(next: NowPlaying) { _state.value = next.copy(progress = next.progress.coerceIn(0f, 1f)) }
}

@Composable
internal fun DesktopMiniPlayerContent(now: DesktopMiniPlayer.NowPlaying, actions: DesktopMiniPlayer.Actions) =
    com.cosmotune.ui.player.MiniPlayerPanel(now.title, now.artist, now.playing, now.progress,
        artwork = { modifier -> DesktopArtwork(now.artworkUrl, modifier) },
        onPlayPause = actions.playPause, onNext = actions.next, onPrevious = actions.previous, onOpen = actions.openMain)

/** The window itself; hosted beside the main window in the application scope. */
@Composable
internal fun DesktopMiniPlayerWindow() {
    val open by DesktopMiniPlayer.open.collectAsState()
    if (!open) return
    val now by DesktopMiniPlayer.state.collectAsState()
    val state = androidx.compose.ui.window.rememberWindowState(size = DesktopMiniPlayer.SIZE,
        position = androidx.compose.ui.window.WindowPosition(Alignment.BottomEnd))
    androidx.compose.ui.window.Window(
        onCloseRequest = DesktopMiniPlayer::close,
        state = state,
        title = "Cosmotune mini player",
        alwaysOnTop = true,
        resizable = false,
    ) {
        DesktopMiniPlayerContent(now, DesktopMiniPlayer.actions)
    }
}
