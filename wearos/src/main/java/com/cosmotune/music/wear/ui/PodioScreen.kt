package com.cosmotune.music.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.cosmotune.music.wear.data.Link
import com.cosmotune.music.wear.data.PhoneLink
import com.cosmotune.music.wear.WatchPodioWire as W

/** Public radio and the paired phone's followed podcasts; the phone owns URLs and playback. */
@Composable
fun PodioScreen(link: PhoneLink, onPlayer: () -> Unit, onOutput: () -> Unit) {
    val state by link.podio.collectAsStateWithLifecycle()
    val connection by link.link.collectAsStateWithLifecycle()
    val watchOutput by link.watchSpeaker.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf<W.Kind?>(null) }
    var parent by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<W.Row?>(null) }
    var playRequest by remember { mutableStateOf<Long?>(null) }
    var queued by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var consumedRefresh by remember { mutableIntStateOf(0) }
    val scroll = rememberScalingLazyListState(initialCenterItemIndex = 1)
    fun open(next: W.Kind, owner: String = "") { detail = null; kind = next; parent = owner; offset = 0 }
    LaunchedEffect(kind, parent, offset, refreshVersion) {
        val refresh = refreshVersion != consumedRefresh
        consumedRefresh = refreshVersion
        kind?.let { link.browsePodio(it, parent, offset, refresh) }
    }
    LaunchedEffect(state.lastAction?.id) {
        if (state.lastAction?.id == playRequest && state.lastAction?.success == true && !queued) { playRequest = null; onPlayer() }
    }
    val page = state.page?.takeIf { it.request.kind == kind && it.request.parent == parent && it.request.offset == offset }
    ScreenScaffold(scrollState = scroll) { padding ->
        ScalingLazyColumn(state = scroll, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.background(WearOrbit.Ground)) {
            item("title") {
                Text(detail?.title ?: when (kind) {
                    W.Kind.SAVED_RADIO -> "Saved radio"; W.Kind.POPULAR_RADIO -> "Popular radio"
                    W.Kind.PODCASTS -> "Podcasts"; W.Kind.EPISODES -> "Episodes"; null -> "Podio"
                }, style = WearType.header, color = WearOrbit.Ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            }
            if (kind == null) {
                link.lastSavedPodio()?.let { saved -> item("last-saved") { PodioCell("Last saved view", "Browse the saved catalogue") {
                    detail = null; kind = saved.kind; parent = saved.parent; offset = saved.offset
                } } }
                item("saved") { PodioCell("Saved radio", "Your phone's favourite stations") { open(W.Kind.SAVED_RADIO) } }
                item("popular") { PodioCell("Popular radio", "Public stations from Radio Browser") { open(W.Kind.POPULAR_RADIO) } }
                item("podcasts") { PodioCell("Podcasts", "Shows followed on your phone") { open(W.Kind.PODCASTS) } }
                item("output") { PodioCell("Playback output", if (watchOutput) "This watch · streamed through phone" else "Phone's selected output", onClick = onOutput) }
                item("player") { PodioCell("Now playing", "Playback controls and Up Next", onClick = onPlayer) }
            } else {
                item("back") { PodioCell("Back", if (detail != null) "Back to list" else "Podio library") {
                    if (detail != null) detail = null
                    else if (kind == W.Kind.EPISODES) open(W.Kind.PODCASTS)
                    else { kind = null; parent = ""; offset = 0 }
                } }
                if (connection != Link.Connected) item("offline") { PodioHint("Connect your paired phone to refresh or play. Saved listings stay readable.") }
                state.error?.let { error -> item("error") { PodioHint(error) } }
                state.lastAction?.takeIf { it.success && queued }?.let { action -> item("action") { PodioHint(action.message) } }
                val selected = detail
                if (selected != null) {
                    item("description") { PodioHint(listOf(selected.subtitle, when {
                        selected.kind == W.RowKind.STATION -> "Live radio"
                        selected.resumeMs > 0 -> "Resume at ${selected.resumeMs / 60_000} min"
                        selected.durationMs > 0 -> "${selected.durationMs / 60_000} min"
                        else -> "Podcast episode"
                    }).filter(String::isNotBlank).joinToString("\n")) }
                    item("play") { PodioCell(if (selected.resumeMs > 0) "Resume" else "Play",
                        if (watchOutput) "On this watch through your phone" else "On the phone's selected output",
                        enabled = !state.actionBusy && connection == Link.Connected, play = true) {
                        queued = false; playRequest = link.selectPodio(selected, enqueue = false)
                    } }
                    item("enqueue") { PodioCell("Add to Up Next", "Keep current playback", enabled = !state.actionBusy && connection == Link.Connected) {
                        queued = true; playRequest = link.selectPodio(selected, enqueue = true)
                    } }
                    if (state.actionBusy) item("acting") { PodioHint("Waiting for the phone…") }
                    item("output") { PodioCell("Playback output", if (watchOutput) "This watch" else "Phone", onClick = onOutput) }
                } else {
                    if (state.loading) item("loading") { PodioHint("Loading from phone…") }
                    if (page?.cached == true) item("cached") { PodioHint("Saved catalogue · refresh to check for changes") }
                    if (page != null && page.rows.isEmpty() && !state.loading) item("empty") { PodioHint(
                        if (kind == W.Kind.SAVED_RADIO) "Save stations in Podio on your phone, or browse Popular radio."
                        else if (kind == W.Kind.PODCASTS) "Follow a podcast in Podio on your phone to browse its episodes here."
                        else "No playable items in this page.") }
                    items(page?.rows.orEmpty(), key = { it.id }) { row ->
                        PodioCell(row.title, row.subtitle, mark = row.title,
                            heard = if (row.kind == W.RowKind.EPISODE && row.durationMs > 0 && row.resumeMs > 0) row.resumeMs.toFloat() / row.durationMs else null) {
                            if (row.kind == W.RowKind.SHOW) open(W.Kind.EPISODES, row.id) else detail = row
                        }
                    }
                    if (offset > 0) item("previous") { PodioCell("Previous page", enabled = !state.loading) { offset = (offset - W.PAGE_SIZE).coerceAtLeast(0) } }
                    if (page?.more == true) item("next") { PodioCell("Next page", enabled = !state.loading) { offset += W.PAGE_SIZE } }
                    item("refresh") { PodioCell("Refresh", "Ask your phone for the latest list", enabled = !state.loading) {
                        offset = 0; refreshVersion++
                    } }
                }
            }
        }
    }
}

/**
 * A Podio row in Orbit (rule 8): radius 22 on the ink fill. A station, show or episode has a disc at the start with its first
 * letters inside (the board's "FM 98" discs; the watch is sent no logos), and an episode already begun wears the orbit round it
 * for how much is heard ([heard]). [play] is the peach Play / Resume of an item's page.
 */
@Composable private fun PodioCell(
    title: String,
    subtitle: String = "",
    enabled: Boolean = true,
    mark: String? = null,
    heard: Float? = null,
    play: Boolean = false,
    onClick: () -> Unit,
) {
    val fill = when {
        play && enabled -> WearOrbit.Peach
        enabled -> WearOrbit.Row
        else -> WearOrbit.Ink.copy(alpha = .035f)
    }
    val ink = if (play && enabled) WearOrbit.OnPeach else WearOrbit.Ink
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(22.dp)).background(fill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(start = if (mark != null) 8.dp else 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (mark != null) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(WearOrbit.Round), contentAlignment = Alignment.Center) {
                    Text(initials(mark), style = WearType.label.copy(fontSize = 8.sp, letterSpacing = 0.4.sp), color = WearOrbit.Ink, maxLines = 1)
                }
                if (heard != null) WearOrbitRing(38.dp, fraction = heard, color = WearOrbit.Ink, stroke = 1.5.dp)
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = WearType.row, color = ink.copy(alpha = if (enabled) 1f else .4f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = WearType.rowSub, color = if (play && enabled) WearOrbit.OnPeach.copy(alpha = 0.7f) else WearOrbit.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The first letters of a name, for a disc with no logo. */
private fun initials(name: String): String =
    name.split(' ', '-', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(if (it.any(Char::isDigit)) 4 else 1) }.uppercase().take(4)
@Composable private fun PodioHint(text: String) {
    Text(text, style = WearType.rowSub, color = WearOrbit.Dim, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp))
}
