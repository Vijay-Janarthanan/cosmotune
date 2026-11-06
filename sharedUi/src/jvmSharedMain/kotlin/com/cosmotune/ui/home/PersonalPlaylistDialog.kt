package com.cosmotune.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import com.cosmotune.music.data.recommendations.LocalPreferenceLearning
import com.cosmotune.music.data.recommendations.MusicCandidateService
import com.cosmotune.music.data.recommendations.MusicFeedback
import com.cosmotune.music.data.recommendations.MusicPlaylistKind
import com.cosmotune.music.data.recommendations.MusicPlaylistPreview
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoDialog
import com.cosmotune.ui.sheets.CosmoDialogHeading
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The size the mix may reach: 10 to 50 songs in steps of 10. */
private const val SIZE_MIN = 10f
private const val SIZE_MAX = 50f
private const val SIZE_STEP = 10f

/**
 * "Make my playlist" in Orbit: the kit's dialog ([CosmoDialog]) with the Orbit heading, the mix as a chip that opens its list in the
 * Orbit menu, the name in the Orbit field, the starting song as a chip and menu, the size as the moon line in steps of ten, the online
 * pools as the Orbit switch, Preview as a quiet pill (the orbit going round while it works), the preview as quiet cards with round
 * buttons to remove and move a song, and Close and the peach "Save locally" at the end. Both native roots save the reviewed order as
 * an ordinary editable local playlist.
 */
@Composable
fun PersonalPlaylistDialog(songs: List<Song>, seed: Song?, onDismiss: () -> Unit,
    save: suspend (String, List<Song>) -> Unit) {
    val choices = remember(songs, seed) { (listOfNotNull(seed) + songs).asSequence().filter { !it.isVideo && it.videoId.isNotBlank() && !it.videoId.startsWith("podio:") }
        .distinctBy { it.videoId }.take(2_000).toList() }
    var selected by remember(choices) { mutableStateOf(choices.firstOrNull()) }
    var kind by remember { mutableStateOf(MusicPlaylistKind.DAILY_MIX) }
    var choosingKind by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Daily mix") }
    var size by remember { mutableFloatStateOf(30f) }
    var discover by remember { mutableStateOf(true) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(emptyList<Song>()) }
    var result by remember { mutableStateOf<MusicPlaylistPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val region by DiscoveryPreferences.state.collectAsState()
    val learning by LocalPreferenceLearning.status.collectAsState()
    val scope = rememberCoroutineScope()
    val fonts = orbitFonts()
    val look = orbitLook()
    fun move(from: Int, to: Int) { preview = preview.toMutableList().apply { add(to, removeAt(from)) } }
    val note = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
    CosmoDialog(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
            CosmoDialogHeading(overline = null, title = "Make my playlist", message = null)
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp).padding(top = 14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Choose a mix, review its sources, then edit the order before saving. Ranking stays on this device; online pools use your enabled catalogues.", style = note, color = look.dim)
                Box {
                    PickerChip(kind.title, enabled = !busy, fonts, look, Modifier.testTag("playlist:kind")) { choosingKind = true }
                    OrbitMenu(
                        open = choosingKind,
                        onDismiss = { choosingKind = false },
                        items = MusicPlaylistKind.entries.map { option ->
                            OrbitMenuItem(option.title, selected = option == kind) { kind = option; title = option.title; preview = emptyList(); result = null }
                        },
                        fonts = fonts,
                        look = look,
                        header = "Mix",
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The field says its name to a screen reader itself; the label above it is for the eye.
                    OrbitLabel("Playlist name", fonts, look.dim, Modifier.clearAndSetSemantics { })
                    OrbitTextField(title, { if (!busy) title = it.take(128) }, "Playlist name", fonts, look, Modifier.fillMaxWidth())
                }
                if (kind == MusicPlaylistKind.SEED) Box {
                    PickerChip(
                        selected?.let { "Based on ${it.title} · ${it.artist}" } ?: "Play or save a song to choose a starting point",
                        enabled = !busy && choices.isNotEmpty(), fonts, look,
                    ) { choosing = true }
                    OrbitMenu(
                        open = choosing,
                        onDismiss = { choosing = false },
                        items = choices.take(100).map { song ->
                            OrbitMenuItem(song.title, detail = song.artist, selected = song.videoId == selected?.videoId) { selected = song; preview = emptyList() }
                        },
                        fonts = fonts,
                        look = look,
                        header = "Starting song",
                        width = 300.dp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Up to ${size.toInt()} songs", style = fonts.title.copy(fontSize = 14.sp), color = look.ink)
                    OrbitProgressLine(
                        progress = { (size - SIZE_MIN) / (SIZE_MAX - SIZE_MIN) },
                        ink = if (busy) look.ink.copy(alpha = 0.4f) else look.ink,
                        onSeek = if (busy) null else { fraction ->
                            val snapped = SIZE_MIN + ((fraction * (SIZE_MAX - SIZE_MIN)) / SIZE_STEP).roundToInt() * SIZE_STEP
                            if (snapped != size) { size = snapped; preview = emptyList() }
                        },
                        live = true,
                        modifier = Modifier.semantics { contentDescription = "Playlist size"; stateDescription = "${size.toInt()} songs" },
                    )
                }
                if (kind != MusicPlaylistKind.REDISCOVER) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include available online discovery pools", style = fonts.body, color = look.ink, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    OrbitSwitch(discover, { discover = it; preview = emptyList() }, look, "Include available online discovery pools", enabled = !busy)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OrbitPillButton(
                        if (busy) "Finding recordings…" else "Preview playlist",
                        onClick = {
                            busy = true; message = null
                            scope.launch {
                                try {
                                    val existing = MusicCandidateService.context()
                                    MusicCandidateService.updateContext(existing.copy(library = (choices + existing.library).distinctBy { it.videoId }))
                                    result = MusicCandidateService.preview(kind, selected, size.toInt(), discover && kind != MusicPlaylistKind.REDISCOVER, region)
                                    preview = result!!.songs
                                    message = "${preview.size} recordings ready. Small or unavailable catalogues may produce fewer songs than requested."
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { message = "Could not build this mix. Your saved playlists are unchanged; try again." }
                                finally { busy = false }
                            }
                        },
                        fonts = fonts,
                        look = look,
                        modifier = Modifier.testTag("playlist:generate"),
                        enabled = !busy && (kind != MusicPlaylistKind.SEED || selected != null),
                    )
                    // Orbit's progress: the ring with its moon going round.
                    if (busy) OrbitRing(28.dp, look.ink, progress = null, spinning = true, stroke = 1.5.dp, moon = 3.dp, modifier = Modifier.padding(start = 12.dp))
                }
                message?.let { Text(it, style = note, color = look.dim) }
                result?.let { generated ->
                    Text(generated.explanation, style = note, color = look.dim)
                    if (generated.unavailablePools.isNotEmpty()) Text("Some online pools were unavailable. This preview uses the sources that answered.", style = note, color = look.dim)
                }
                preview.forEachIndexed { index, song ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(look.row).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}. ${song.title}", style = fonts.title, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                result?.recordings?.firstOrNull { it.song.videoId == song.videoId }?.let { recording ->
                                    Text(recording.explanation.ifBlank { "Available catalogue recording" } + if (recording.alternatives.size > 1) " · ${recording.alternatives.size} source listings" else "",
                                        style = note, color = look.dim)
                                }
                            }
                            OrbitRoundButton(CosmoIcons.Close, "Remove ${song.title}", look.ink, { preview = preview.filterIndexed { row, _ -> row != index } }, size = 36.dp, enabled = !busy)
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OrbitRoundButton(CosmoIcons.ChevronUp, "Move ${song.title} up", look.ink, { move(index, index - 1) }, size = 36.dp, enabled = !busy && index > 0)
                            OrbitRoundButton(CosmoIcons.ChevronDown, "Move ${song.title} down", look.ink, { move(index, index + 1) }, size = 36.dp, enabled = !busy && index < preview.lastIndex)
                            OrbitPillButton(
                                "Less like this",
                                onClick = { LocalPreferenceLearning.onFeedback(song, MusicFeedback.LESS_LIKE_THIS); preview = preview.filter { it.videoId != song.videoId } },
                                fonts = fonts,
                                look = look,
                                enabled = !busy && learning.ready && learning.enabled && learning.error == null,
                                height = 36.dp,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                OrbitPillButton("Close", onDismiss, fonts, look, Modifier.testTag("playlist:close"))
                OrbitPillButton(
                    "Save locally",
                    onClick = {
                        busy = true
                        scope.launch {
                            try { save(title.trim(), preview); onDismiss() }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "Could not save the playlist. Your edited preview is still here." }
                            finally { busy = false }
                        }
                    },
                    fonts = fonts,
                    look = look,
                    modifier = Modifier.testTag("playlist:save"),
                    primary = true,
                    enabled = !busy && preview.isNotEmpty() && title.isNotBlank(),
                )
            }
        }
    }
}

/** A chip that names a choice with a chevron at its end; a tap opens its menu. Dimmed and inert while [enabled] is false. */
@Composable
private fun PickerChip(text: String, enabled: Boolean, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OrbitChip(
        text, fonts, look, onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.45f).then(if (enabled) Modifier else Modifier.semantics { stateDescription = "Unavailable" }),
        trailing = { Icon(CosmoIcons.ChevronDown, contentDescription = null, tint = look.dim, modifier = Modifier.size(16.dp)) },
        leading = null,
    )
}
