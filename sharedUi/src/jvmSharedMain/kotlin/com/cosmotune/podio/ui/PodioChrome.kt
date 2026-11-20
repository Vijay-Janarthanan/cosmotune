package com.cosmotune.podio.ui

import com.cosmotune.music.playback.LivePlayhead
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioItem
import com.cosmotune.podio.PodioSaved
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitModeSwitcher
import com.cosmotune.ui.orbit.OrbitBackHeader
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitRowSkeleton
import com.cosmotune.ui.orbit.WatchMessage

/*
 * The frame of Podio's pages in Orbit (board section C): the phone's head row (the mode pill, the round buttons, the account), the
 * page heading with its peach full stop, the head of a page opened over a section (back and the mono kind), the notice along the
 * foot, and the quiet states. Removals go to the window's "Removed · Undo" snackbar ([com.cosmotune.ui.orbit.LocalOrbitUndo]).
 */

/** What every Podio page reads besides the model: the library, the playback, the downloads and where the page ends. */
internal class PodioEnv(
    val saved: PodioSaved,
    val playback: PodioPlayback,
    val downloaded: List<PodioItem>,
    val downloadStatus: Map<String, String>,
    val downloadsRunning: Set<String>,
    /** Room under the last row: the dock (phone) or a gap. */
    val bottom: Dp,
    val onMusic: (() -> Unit)?,
    val chooseSection: (PodioSection) -> Unit,
    /** True on the desktop and tablets: the player is a bar along the foot. */
    val wide: Boolean,
    /** Play or pause what is loaded. */
    val onToggle: () -> Unit,
    /** The playhead run on between readings, so the playing row's orbit glides; without one the orbit follows the readings. */
    val head: LivePlayhead? = null,
    /** Seek the active episode without replacing it or its podcast rate. */
    val onSeek: (Long) -> Unit = {},
    /** Podio's queue as it stands (Play next, Up next). */
    val queue: com.cosmotune.podio.PodioQueueState = com.cosmotune.podio.PodioQueueState(),
) {
    /** Whether [id] (an episode's player id) is new: neither heard, started nor put away as seen. */
    fun isNew(id: String): Boolean = id !in saved.completedEpisodes && (saved.progress[id] ?: 0) <= 0 && id !in saved.seenEpisodes

    /** Whether [id] is played to its end. */
    fun isPlayed(id: String, durationMs: Long): Boolean =
        id in saved.completedEpisodes || com.cosmotune.podio.PodioPlaybackPolicy.isFinished(saved.progress[id] ?: 0, durationMs)

    /** How far through what is playing, 0..1, or null for a live stream or nothing known. Read while drawing. */
    fun playedFraction(): Float? = playback.durationMs.takeIf { it > 0 }?.let { head?.fraction(it) ?: (playback.positionMs.toFloat() / it).coerceIn(0f, 1f) }
}

/** Whether Podio's pages lead with the mode pill: on the phone yes; the rail and the desktop sidebar have their own. */
internal val LocalPodioModeSwitcher = staticCompositionLocalOf { false }

/**
 * A section's head (board frame 1, "Podcasts."): on the phone one row of the mode pill, the page's round buttons ([actions]) and the
 * account, then the mono [label] over the 30 sp [title] with its peach full stop ([OrbitPageHeading]). Where the rail or the sidebar
 * holds the mode and the account, the buttons stand at the end of the label's row instead.
 */
@Composable internal fun PodioHead(title: String, label: String, fonts: OrbitFonts, look: OrbitLook, side: Dp, onMusic: (() -> Unit)?,
    modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val phone = LocalPodioModeSwitcher.current
    Column(modifier.fillMaxWidth()) {
        if (phone) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = side, end = side, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitModeSwitcher()
                Spacer(Modifier.weight(1f))
                actions()
                val account = LocalModeProfileAction.current
                if (account != null) account() else if (onMusic != null) PodioTextAction("Music", look, fonts, onMusic)
            }
            com.cosmotune.ui.live.LiveNowStrip(Modifier.padding(horizontal = side).padding(top = 12.dp))
            OrbitPageHeading(title, label, fonts, look, Modifier.padding(start = side + 4.dp, end = side, top = 14.dp).testTag("podio:head"))
        } else {
            OrbitPageHeading(title, label, fonts, look, Modifier.padding(start = side + 4.dp, end = side, top = 30.dp).testTag("podio:head")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

/** The head of a page opened over a section (a show, a station, the queue): the shared [OrbitBackHeader], back and the mono [kind]. */
@Composable internal fun PodioBackHead(kind: String, onBack: () -> Unit, fonts: OrbitFonts, look: OrbitLook, side: Dp, modifier: Modifier = Modifier,
    backLabel: String = "Back", actions: @Composable RowScope.() -> Unit = {}) {
    OrbitBackHeader(kind, onBack, modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = side, end = side, top = 8.dp), backLabel = backLabel, trailing = actions)
}

/** A word in the accent that does something ("Music", "Clear", "Load older episodes"). */
@Composable internal fun PodioTextAction(text: String, look: OrbitLook, fonts: OrbitFonts, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Text(text, style = fonts.chip, color = if (enabled) look.accent else look.dim, maxLines = 1,
        modifier = modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp))
}

/** A notice or an error along the foot of the page, over the content: a raised rounded row, one line, one action. */
@Composable internal fun PodioBanner(text: String, actionLabel: String, onAction: () -> Unit, danger: Boolean, look: OrbitLook, fonts: OrbitFonts, modifier: Modifier = Modifier) {
    val ground = if (look.dark) Color(0xFF1C1418) else Color(0xFFFBF6F2)
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(22.dp)).background(ground)
        .border(1.dp, look.ink.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
        .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (danger) { Box(Modifier.size(8.dp).clip(CircleShape).background(look.accent)); Spacer(Modifier.width(10.dp)) }
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = fonts.body, color = look.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        PodioTextAction(actionLabel, look, fonts, onAction)
    }
}

/** An empty or problem state of a page: the mono label, the wide heading, a line and an optional action. */
@Composable internal fun PodioMessage(label: String, title: String, text: String, fonts: OrbitFonts, look: OrbitLook, side: Dp, action: String? = null, onAction: (() -> Unit)? = null) {
    WatchMessage(label, title, text, fonts, look, Modifier.padding(horizontal = side).padding(top = 18.dp).fillMaxWidth(), action, onAction)
}

/** Rows standing in while a list loads: the flat row's own shape ([OrbitRowSkeleton]), so nothing moves when the list arrives. */
@Composable internal fun PodioSkeletonRows(count: Int, @Suppress("UNUSED_PARAMETER") look: OrbitLook, side: Dp, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = side).padding(top = 8.dp)) {
        repeat(count) { OrbitRowSkeleton(it) }
    }
}

/** The quiet credit at the foot of a page ("Stations: Radio Browser"). */
@Composable internal fun PodioCredit(text: String, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    Text(text, style = fonts.body.copy(fontSize = 11.5.sp), color = look.dim.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 20.dp))
}
