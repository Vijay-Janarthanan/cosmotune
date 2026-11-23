package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.audio.AudioLevelReading
import com.cosmotune.music.data.audio.AudioLevelsHub
import com.cosmotune.music.data.audio.SongWaveforms
import com.cosmotune.music.data.audio.WaveShape
import com.cosmotune.settings.Extras
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.video.account.TimedComment
import com.cosmotune.video.account.TimedComments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The player card's seek line. With Settings, Player, Waveform seek line on (the default) it is the song's waveform, learned
 * while the song plays and kept for the next play, with a dot at each moment a listener's comment names; the comment shows
 * under the line as the song passes it. Until a song has a shape or comments it is the plain Orbit line.
 */
@Composable
internal fun SongSeekLine(t: NowPlayingTransport, ink: Color, fonts: OrbitFonts, modifier: Modifier = Modifier) {
    val on by Extras.settings.waveformSeek.collectAsState()
    if (!on) {
        OrbitProgressLine(t::progress, ink, modifier, onSeek = t::seek)
        return
    }
    val id = t.song.videoId
    val latest by rememberUpdatedState(t)
    // Learns the shape from the levels the player already measures, only while it plays.
    LaunchedEffect(id, t.isPlaying) {
        if (!t.isPlaying) return@LaunchedEffect
        val source = AudioLevelsHub.source ?: return@LaunchedEffect
        val reading = AudioLevelReading()
        val hold = source.listen()
        try {
            while (isActive) {
                val now = latest
                if (now.durationMs > 0 && source.read(System.nanoTime(), reading)) {
                    val at = (now.live?.positionMs() ?: now.position.positionMs).toFloat() / now.durationMs
                    SongWaveforms.record(id, at, reading.bass * 0.5f + reading.mid * 0.35f + reading.high * 0.15f)
                }
                delay(SAMPLE_MS)
            }
        } finally { hold.close() }
    }
    DisposableEffect(id) { onDispose { SongWaveforms.save(id) } }
    val revision by SongWaveforms.revision.collectAsState()
    val bars = remember(id, revision) { SongWaveforms.bars(id) }
    val comments by produceState(emptyList<TimedComment>(), id, t.durationMs) {
        value = emptyList()
        if (id.matches(VIDEO_ID)) value = withContext(Dispatchers.IO) { TimedComments.forSong(id, t.durationMs) }
    }
    if (bars == null && comments.isEmpty()) {
        OrbitProgressLine(t::progress, ink, modifier, onSeek = t::seek)
        return
    }
    // The comment the song is passing: shown for a few seconds after its moment.
    val passing by produceState<TimedComment?>(null, comments) {
        while (isActive) {
            val at = latest.live?.positionMs() ?: latest.position.positionMs
            value = comments.lastOrNull { at >= it.atMs && at < it.atMs + SHOW_MS }
            delay(250)
        }
    }
    Column(modifier.fillMaxWidth()) {
        WaveLine(t, ink, bars, comments)
        if (comments.isNotEmpty()) {
            Text(
                passing?.let { "“${it.text}” · ${it.author}" }.orEmpty(),
                style = fonts.label, color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("nowplaying:card:comment"),
            )
        }
    }
}

@Composable
private fun WaveLine(t: NowPlayingTransport, ink: Color, bars: FloatArray?, comments: List<TimedComment>) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val input = Modifier
        .pointerInput(Unit) { detectTapGestures { t.seek((it.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { drag?.let(t::seek); drag = null },
                onDragCancel = { drag = null },
            ) { change, _ ->
                change.consume()
                drag = (change.position.x / size.width).coerceIn(0f, 1f)
            }
        }
    Canvas(Modifier.fillMaxWidth().height(30.dp).testTag("nowplaying:card:waveform").then(input)) {
        val p = (drag ?: t.progress()).coerceIn(0f, 1f)
        val dot = 2.5.dp.toPx()
        val waveHeight = size.height - dot * 4
        val mid = waveHeight / 2
        val count = WaveShape.BARS
        val slot = size.width / count
        val barWidth = (slot * 0.62f).coerceAtLeast(1.5f)
        for (bar in 0 until count) {
            val height = ((bars?.get(bar) ?: WaveShape.FLOOR) * waveHeight).coerceAtLeast(barWidth)
            val x = bar * slot + (slot - barWidth) / 2
            val played = (bar + 0.5f) / count <= p
            drawRoundRect(
                if (played) ink else ink.copy(alpha = 0.26f),
                Offset(x, mid - height / 2), Size(barWidth, height), CornerRadius(barWidth / 2),
            )
        }
        val duration = t.durationMs.takeIf { it > 0 } ?: return@Canvas
        for (comment in comments) {
            val at = comment.atMs.toFloat() / duration
            drawCircle(if (at <= p) ink else ink.copy(alpha = 0.5f), dot, Offset(size.width * at, size.height - dot))
        }
    }
}

private const val SAMPLE_MS = 120L
private const val SHOW_MS = 5_000L
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
