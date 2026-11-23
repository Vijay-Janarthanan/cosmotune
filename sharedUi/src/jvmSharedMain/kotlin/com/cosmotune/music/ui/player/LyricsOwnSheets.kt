package com.cosmotune.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.lyrics.LrcLib
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricsDraft
import com.cosmotune.music.data.lyrics.OwnLyrics
import com.cosmotune.music.playback.PlaybackPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitBusyRing
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitOptionRow
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.clock
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The song's own lyrics ([OwnLyrics]), read off the disk the first time the song is shown, and current after every save. */
@Composable
internal fun rememberOwnLyrics(videoId: String): OwnLyrics.Saved? {
    val all by OwnLyrics.saved.collectAsState()
    LaunchedEffect(videoId) { withContext(Dispatchers.IO) { OwnLyrics.lines(videoId) } }
    return all[videoId]
}

/** Where the song is now: the last reading of [position], carried forward by the time since it was taken while it plays. */
internal fun livePlayheadMs(position: PlaybackPosition, isPlaying: Boolean): Long {
    val since = if (isPlaying && position.advancing && position.sampledAtNanos > 0) (System.nanoTime() - position.sampledAtNanos) / 1_000_000 else 0L
    return position.positionMs + since.coerceIn(0L, 2_000L)
}

/** How many hits a lyrics search lists. */
private const val MAX_HITS = 20

/** How close in length a hit must be to be marked as the same recording. */
private const val SAME_LENGTH_MS = 3_000L

/**
 * "Search for lyrics": the listener types a title, an artist or a line, and picks the right lyrics from LRCLIB's answers. Opens
 * already searching for the song's own title and artist. A pick is kept as the song's own lyrics ([onPick]).
 */
@Composable
internal fun LyricsSearchSheet(
    hazeState: HazeState,
    title: String,
    artist: String,
    durationMs: Long,
    onPick: (LrcLib.Hit) -> Unit,
    onDismiss: () -> Unit,
    search: suspend (String) -> List<LrcLib.Hit> = LrcLib::search,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")) }
    var hits by remember { mutableStateOf<List<LrcLib.Hit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val run: () -> Unit = {
        val words = query
        if (words.isNotBlank()) scope.launch {
            searching = true
            hits = search(words).take(MAX_HITS)
            searching = false
        }
    }
    LaunchedEffect(Unit) { run() }

    PlayerDrawer(hazeState = hazeState, title = "Search for lyrics", onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OrbitInputField(query, { query = it }, "Title, artist or a line", fonts, look, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OrbitActionPill("Search", run, fonts, look, primary = true, enabled = query.isNotBlank() && !searching)
        }
        Spacer(Modifier.height(16.dp))
        val found = hits
        when {
            searching -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { OrbitBusyRing() }
            found == null -> Unit
            found.isEmpty() -> Text(
                "Nothing found. Try fewer words, or just the title.", style = fonts.body, color = look.dim,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
            )
            else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                found.forEach { hit ->
                    OrbitOptionRow(
                        title = hit.title,
                        subtitle = hitLine(hit, durationMs),
                        icon = Icons.Rounded.Lyrics,
                        onClick = { onPick(hit) },
                    )
                }
            }
        }
    }
}

/** "Artist · 3:21 · Timed lines · Same length". */
internal fun hitLine(hit: LrcLib.Hit, songMs: Long): String = buildList {
    if (hit.artist.isNotBlank()) add(hit.artist)
    if (hit.durationMs > 0) add(clock(hit.durationMs))
    add(if (hit.synced) "Timed lines" else "Words only")
    if (songMs > 0 && hit.durationMs > 0 && abs(hit.durationMs - songMs) <= SAME_LENGTH_MS) add("Same length")
}.joinToString(" · ")

/** The two steps of the editor: the words, then their timing. */
private enum class EditorStep { WORDS, TIMING }

/**
 * The lyrics editor: write or paste the words, then play the song and tap as each line (or each word) is sung. Opens with the
 * lyrics on screen when there are some, so a wrong line or a late stamp can be fixed without starting over. [livePositionMs]
 * reads the playhead at the moment of a tap; [onSave] keeps the lines as the song's own lyrics.
 */
@Composable
internal fun LyricsEditorSheet(
    hazeState: HazeState,
    initial: List<LyricLine>?,
    durationMs: Long,
    isPlaying: Boolean,
    livePositionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSave: (List<LyricLine>) -> Unit,
    onDismiss: () -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var draft by remember { mutableStateOf(initial?.takeIf { lines -> lines.any { !it.isGap } }?.let(LyricsDraft::fromLines) ?: LyricsDraft(emptyList())) }
    var step by remember { mutableStateOf(if (draft.lines.isEmpty()) EditorStep.WORDS else EditorStep.TIMING) }
    var words by remember { mutableStateOf(draft.text) }
    val save: () -> Unit = { onSave(draft.toLines(durationMs)); onDismiss() }

    PlayerDrawer(hazeState = hazeState, title = if (initial.isNullOrEmpty()) "Add lyrics" else "Edit lyrics", onDismiss = onDismiss) {
        when (step) {
            EditorStep.WORDS -> {
                Text("A line of the song on each line.", style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                OrbitInputField(words, { words = it }, "Type or paste the lyrics", fonts, look, Modifier.fillMaxWidth(), singleLine = false, sentences = true, minLines = 8)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrbitActionPill("Time the lines", {
                        draft = draft.withText(words)
                        step = EditorStep.TIMING
                    }, fonts, look, primary = true, enabled = words.isNotBlank())
                    OrbitActionPill("Save", {
                        draft = draft.withText(words)
                        onSave(draft.toLines(durationMs)); onDismiss()
                    }, fonts, look, enabled = words.isNotBlank())
                }
            }
            EditorStep.TIMING -> TimingStep(
                draft = draft,
                onDraft = { draft = it },
                isPlaying = isPlaying,
                livePositionMs = livePositionMs,
                onSeek = onSeek,
                onPlayPause = onPlayPause,
                onEditWords = { words = draft.text; step = EditorStep.WORDS },
                onSave = save,
            )
        }
    }
}

/** How many lines before and after the one being timed are listed. */
private const val LINES_BEFORE = 2
private const val LINES_AFTER = 4

/** How far before a line a tap on it starts the song, to hear it come in. */
private const val LEAD_IN_MS = 2_000L

@Composable
private fun TimingStep(
    draft: LyricsDraft,
    onDraft: (LyricsDraft) -> Unit,
    isPlaying: Boolean,
    livePositionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onEditWords: () -> Unit,
    onSave: () -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val now by produceState(livePositionMs()) {
        while (true) { value = livePositionMs(); delay(100) }
    }
    Text(
        if (draft.mode == LyricsDraft.Mode.WORDS) "Play the song and tap as each word is sung." else "Play the song and tap as each line starts.",
        style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitChip("Lines", fonts, look, { onDraft(draft.withMode(LyricsDraft.Mode.LINES)) }, selected = draft.mode == LyricsDraft.Mode.LINES, leading = null)
        OrbitChip("Words", fonts, look, { onDraft(draft.withMode(LyricsDraft.Mode.WORDS)) }, selected = draft.mode == LyricsDraft.Mode.WORDS, leading = null)
        Spacer(Modifier.weight(1f))
        Text(clock(now), style = fonts.label.copy(fontSize = 13.sp), color = look.dim)
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitRoundButton(CosmoIcons.Replay10, "Back 5 seconds", look.ink, { onSeek((livePositionMs() - 5_000).coerceAtLeast(0)) }, size = 44.dp)
        OrbitRoundButton(if (isPlaying) CosmoIcons.Pause else CosmoIcons.Play, if (isPlaying) "Pause" else "Play", look.ink, onPlayPause, size = 44.dp)
        OrbitActionPill(
            if (draft.finished) "All timed" else if (draft.mode == LyricsDraft.Mode.WORDS) "Tap for the word" else "Tap for the line",
            { onDraft(draft.tap(livePositionMs())) }, fonts, look, Modifier.weight(1f), primary = true, enabled = !draft.finished,
        )
        OrbitRoundButton(Icons.AutoMirrored.Rounded.Undo, "Undo the last tap", look.ink, { onDraft(draft.undo()) }, size = 44.dp, enabled = draft.started)
    }
    Spacer(Modifier.height(12.dp))
    val from = (draft.line - LINES_BEFORE).coerceAtLeast(0)
    val until = (draft.line + LINES_AFTER + 1).coerceAtMost(draft.lines.size)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (index in from until until) {
            val line = draft.lines[index]
            DraftLineRow(
                line = line,
                current = index == draft.line,
                word = if (index == draft.line && draft.mode == LyricsDraft.Mode.WORDS) draft.word else -1,
                onClick = {
                    onDraft(draft.moveTo(index))
                    line.startMs?.let { onSeek((it - LEAD_IN_MS).coerceAtLeast(0)) }
                },
            )
        }
        if (draft.finished) Text("Every line has its time. Save, or tap a line to time it again.", style = fonts.body, color = look.dim, modifier = Modifier.padding(6.dp))
    }
    Spacer(Modifier.height(12.dp))
    if (!draft.finished) {
        OrbitChip(
            if (draft.lines[draft.line].secondVoice) "Second voice" else "Main voice", fonts, look,
            { onDraft(draft.toggleSecondVoice()) }, selected = draft.lines[draft.line].secondVoice, leading = null,
        )
        Spacer(Modifier.height(12.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbitActionPill("Save", onSave, fonts, look, primary = true, enabled = draft.lines.isNotEmpty())
        OrbitActionPill("Edit the words", onEditWords, fonts, look)
    }
}

/** A line of the draft: its time (or a dash), the words, the one being timed raised, its next word underlined in word mode. */
@Composable
private fun DraftLineRow(line: LyricsDraft.Line, current: Boolean, word: Int, onClick: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(16.dp))
            .background(if (current) look.chip else look.row)
            .clickable(role = Role.Button, onClickLabel = "Time from this line", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.startMs?.let(::clock) ?: "–", style = fonts.label, color = look.dim, modifier = Modifier.widthIn(min = 44.dp))
        Spacer(Modifier.width(8.dp))
        val text = buildAnnotatedString {
            val words = line.words
            if (word < 0 || words.isEmpty()) append(line.text)
            else words.forEachIndexed { i, w ->
                if (i > 0) append(' ')
                if (i == word) withStyle(SpanStyle(color = look.accent, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold)) { append(w) }
                else append(w)
            }
            if (line.secondVoice) withStyle(SpanStyle(color = look.dim)) { append("  · second voice") }
        }
        Text(text, style = if (current) fonts.title else fonts.body, color = if (current) look.ink else look.dim, modifier = Modifier.weight(1f))
    }
}
