package com.cosmotune.video.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.downloads.SubtitleFetcher
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_transcript_failed
import com.cosmotune.video.resources.video_transcript_loading
import com.cosmotune.video.transcript.Transcript
import com.cosmotune.video.transcript.TranscriptLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** A transcript as the tab shows it: loading, the lines, or failed. [current] is the line playing now (-1 before the first). */
@Stable
internal class TranscriptState {
    var lines by mutableStateOf<List<TranscriptLine>?>(null)
    var failed by mutableStateOf(false)
    var current by mutableIntStateOf(-1)
}

/**
 * The transcript of [videoId] from its caption [tracks] (the captions language first, else a person's track, else the first), read
 * once the tab opens; the line playing is followed twice a second.
 */
@Composable
internal fun rememberTranscript(videoId: String, tracks: List<SubtitleTrack>): TranscriptState {
    val graph = LocalVideoGraph.current
    val state = remember(videoId) { TranscriptState() }
    val track = remember(tracks) { Transcript.pick(tracks, graph.settings.settings.value.captionsLanguage ?: java.util.Locale.getDefault().language) }
    LaunchedEffect(videoId, track) {
        if (track == null || state.lines != null) return@LaunchedEffect
        state.failed = false
        state.lines = try {
            Transcript.parse(SubtitleFetcher().vtt(track))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state.failed = true
            null
        }
    }
    LaunchedEffect(videoId, state.lines) {
        val lines = state.lines ?: return@LaunchedEffect
        while (true) {
            val playing = graph.playback.state.value.item?.id == videoId
            state.current = if (playing) Transcript.indexAt(lines, graph.playback.positionMs()) else -1
            delay(500)
        }
    }
    return state
}

/** The transcript's rows for a list that holds other things too (the phone's page). */
internal fun LazyListScope.transcriptItems(state: TranscriptState, onSeek: (Long) -> Unit) {
    val lines = state.lines
    when {
        state.failed -> item(key = "transcript:failed") { TranscriptNote(stringResource(Res.string.video_transcript_failed)) }
        lines == null -> item(key = "transcript:loading") { TranscriptNote(stringResource(Res.string.video_transcript_loading)) }
        else -> itemsIndexed(lines, key = { index, line -> "transcript:$index:${line.startMs}" }) { index, line ->
            TranscriptRow(line, index == state.current, onSeek)
        }
    }
}

/** The transcript as its own list (the wide page's side column): it keeps the line playing in view. */
@Composable
internal fun TranscriptPanel(state: TranscriptState, onSeek: (Long) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(state.current) {
        val at = state.current
        if (at < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == at } && visible.lastOrNull()?.index != at
        if (!shown && !list.isScrollInProgress) list.animateScrollToItem((at - 2).coerceAtLeast(0))
    }
    LazyColumn(modifier, state = list) { transcriptItems(state, onSeek) }
}

@Composable
private fun TranscriptRow(line: TranscriptLine, current: Boolean, onSeek: (Long) -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        Modifier
            .padding(horizontal = CosmoSpacing.margin, vertical = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (current) look.accent.copy(alpha = 0.14f) else look.row.copy(alpha = 0f))
            .clickable(role = Role.Button) { onSeek(line.startMs) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(VideoText.duration(line.startMs / 1000), style = fonts.label, color = if (current) look.accent else look.dim, modifier = Modifier.widthIn(min = 44.dp).padding(top = 3.dp))
        Spacer(Modifier.width(12.dp))
        Text(line.text, style = fonts.body, color = look.ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TranscriptNote(text: String) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Text(text, style = fonts.body, color = look.dim, modifier = Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 12.dp))
}
