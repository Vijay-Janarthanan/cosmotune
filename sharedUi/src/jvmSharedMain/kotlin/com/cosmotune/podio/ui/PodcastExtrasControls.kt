package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.*
import com.cosmotune.ui.orbit.*
import kotlinx.coroutines.CancellationException

/** The panels under an episode: its chapters, its transcript and its show notes. */
internal enum class ExtrasTab(val label: String) { CHAPTERS("Chapters"), TRANSCRIPT("Transcript"), NOTES("Notes") }

/** What the publisher's chapters of an episode are once fetched, and whether they are still coming. */
internal class EpisodeChapters(val list: List<PodcastChapter>, val loading: Boolean, val failed: Boolean)

private fun PodcastEpisode.hasChapterSource(localPath: String?) = chaptersUrl != null || inlineChapters.isNotEmpty() || localPath != null

/**
 * The chapters of [episode], fetched once per episode: the publisher's file, else those written into the downloaded copy
 * ([localPath]), else the ones in the feed (Podlove, or times in the show notes).
 */
@Composable internal fun rememberEpisodeChapters(episode: PodcastEpisode?, catalogue: PodioCatalogue, localPath: String? = null): EpisodeChapters {
    var list by remember(episode?.id) { mutableStateOf(emptyList<PodcastChapter>()) }
    var loading by remember(episode?.id) { mutableStateOf(episode?.hasChapterSource(localPath) == true) }
    var failed by remember(episode?.id) { mutableStateOf(false) }
    LaunchedEffect(episode?.id) {
        if (episode == null || !episode.hasChapterSource(localPath)) { loading = false; return@LaunchedEffect }
        try { list = catalogue.chapters(episode, localPath).take(2_000) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    return EpisodeChapters(list, loading, failed)
}

/**
 * Notes, Chapters and Transcript as tabs over one list (board frame 3, and the episode's sheet): a tap on a chapter or a line of
 * the transcript plays from there ([onSeek]). With [positionMs] (the episode is the one playing) the current chapter and line are
 * in the accent, and the transcript follows the playhead. [chapters] are handed in where the caller already has them (the
 * player's chapter line); else they are fetched here. [trailing] stands at the end of the tabs (the player's Up next).
 */
@Composable internal fun EpisodeExtras(episode: PodcastEpisode, catalogue: PodioCatalogue, positionMs: Long?, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, start: ExtrasTab = ExtrasTab.CHAPTERS, listHeight: Dp = 280.dp, chapters: EpisodeChapters? = null,
    localPath: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val fonts = orbitFonts(); val look = orbitLook()
    val notes = remember(episode.id) { PodioFormat.plainText(episode.description) }
    val ownChapters = if (chapters == null) rememberEpisodeChapters(episode, catalogue, localPath) else null
    val chapterState = chapters ?: ownChapters
    // A downloaded copy only earns the tab if its own tag turned out to hold chapters.
    val hasChapters = episode.chaptersUrl != null || episode.inlineChapters.isNotEmpty() || chapterState?.list?.isNotEmpty() == true
    val tabs = listOfNotNull(ExtrasTab.CHAPTERS.takeIf { hasChapters }, ExtrasTab.TRANSCRIPT.takeIf { episode.transcripts.isNotEmpty() },
        ExtrasTab.NOTES.takeIf { notes.isNotBlank() })
    var chosen by remember(episode.id) { mutableStateOf(start) }
    val tab = chosen.takeIf { it in tabs } ?: tabs.firstOrNull()
    var cues by remember(episode.id) { mutableStateOf<List<PodcastCue>?>(null) }
    var cueError by remember(episode.id) { mutableStateOf(false) }
    var attempt by remember(episode.id) { mutableIntStateOf(0) }
    LaunchedEffect(episode.id, tab == ExtrasTab.TRANSCRIPT, attempt) {
        if (tab != ExtrasTab.TRANSCRIPT || cues != null) return@LaunchedEffect
        cueError = false
        try { cues = catalogue.transcript(episode.transcripts.first()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { cueError = true }
    }
    if (tabs.isEmpty()) {
        Row(modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Spacer(Modifier.weight(1f)); trailing() }
        return
    }
    Column(modifier.fillMaxWidth().testTag("podio:extras")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEach { item -> OrbitSelectChip(item.label, item == tab, fonts, look, { chosen = item }, haptic = false, modifier = Modifier.testTag("podio:extras:${item.name}")) }
            }
            trailing()
        }
        val list = rememberLazyListState()
        val current = when (tab) {
            ExtrasTab.CHAPTERS -> positionMs?.let { PodioFormat.chapterAt(chapterState?.list.orEmpty(), it) } ?: -1
            ExtrasTab.TRANSCRIPT -> positionMs?.let { p -> cues.orEmpty().indexOfLast { it.timed && it.startMs <= p } } ?: -1
            else -> -1
        }
        // The transcript keeps the line being spoken in view while it plays.
        LaunchedEffect(tab, current) { if (tab == ExtrasTab.TRANSCRIPT && current > 0) runCatching { list.animateScrollToItem((current - 1).coerceAtLeast(0)) } }
        Box(Modifier.fillMaxWidth().height(listHeight).padding(top = 10.dp)) {
            when (tab) {
                ExtrasTab.NOTES -> LazyColumn(state = list) {
                    item { Text(notes, style = fonts.body.copy(fontSize = 14.sp, lineHeight = 21.sp), color = look.ink, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
                ExtrasTab.CHAPTERS -> when {
                    chapterState == null || chapterState.loading -> PodioSkeletonRows(4, look, 0.dp)
                    chapterState.list.isEmpty() -> Text(if (chapterState.failed) "The chapters could not be loaded." else "The publisher has not supplied any chapters.",
                        style = fonts.body, color = look.dim, modifier = Modifier.padding(4.dp))
                    else -> LazyColumn(state = list) {
                        itemsIndexed(chapterState.list) { index, chapter ->
                            ExtrasLine(PodioFormat.clock(chapter.startMs), chapter.title, index == current) { onSeek(chapter.startMs) }
                        }
                    }
                }
                ExtrasTab.TRANSCRIPT -> when {
                    cueError -> Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("The transcript could not be loaded.", style = fonts.body, color = look.dim)
                        OrbitSelectChip("Try again", false, fonts, look, { attempt++ }, haptic = false)
                    }
                    cues == null -> PodioSkeletonRows(4, look, 0.dp)
                    cues.orEmpty().isEmpty() -> Text("The publisher's transcript is empty.", style = fonts.body, color = look.dim, modifier = Modifier.padding(4.dp))
                    else -> LazyColumn(state = list) {
                        itemsIndexed(cues.orEmpty()) { index, cue ->
                            ExtrasLine(listOfNotNull(PodioFormat.clock(cue.startMs).takeIf { cue.timed }, cue.speaker).joinToString(" · "), cue.text, index == current,
                                onClick = if (cue.timed) ({ onSeek(cue.startMs) }) else null)
                        }
                    }
                }
                null -> {}
            }
        }
    }
}

/** A chapter or a line of the transcript: its time (and speaker) over its words; the one playing in the accent. A tap plays from it. */
@Composable private fun ExtrasLine(time: String, text: String, now: Boolean, onClick: (() -> Unit)?) {
    val fonts = orbitFonts(); val look = orbitLook()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).then(if (now) Modifier.background(look.accent.copy(alpha = 0.14f)) else Modifier)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Play from here", onClick = onClick) else Modifier)
        .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (time.isNotEmpty()) Text(time, style = fonts.label, color = if (now) look.accent else look.dim, modifier = Modifier.widthIn(min = 52.dp).padding(top = 2.dp))
        Text(text, style = fonts.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = if (now) look.accent else look.ink, modifier = Modifier.weight(1f))
    }
}
