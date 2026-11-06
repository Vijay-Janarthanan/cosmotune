package com.cosmotune.podio.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioItem
import com.cosmotune.podio.PodioSaved
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitModeSwitcher
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageButton
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchSkeleton

/*
 * The frame of Podio's pages in Orbit (board section C): the phone's mode pill, the page heading with its peach full stop and its
 * round buttons, the head of a page opened over a section (back and the mono kind), the notice along the foot, and the quiet states.
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
) {
    /** How far through what is playing, 0..1, or null for a live stream or nothing known. */
    fun playedFraction(): Float? = playback.durationMs.takeIf { it > 0 }?.let { (playback.positionMs.toFloat() / it).coerceIn(0f, 1f) }
}

/** Whether Podio's pages lead with the mode pill: on the phone yes; the rail and the desktop sidebar have their own. */
internal val LocalPodioModeSwitcher = staticCompositionLocalOf { false }

/**
 * A section's head (board section C, "Radio."): on the phone the mode pill and the account first; then the mono [label] over the
 * [title] in the wide face with its peach full stop, and the page's round buttons at the end ([actions]).
 */
@Composable internal fun PodioHead(title: String, label: String, fonts: OrbitFonts, look: OrbitLook, side: Dp, onMusic: (() -> Unit)?,
    modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val phone = LocalPodioModeSwitcher.current
    Column(modifier.fillMaxWidth()) {
        if (phone) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = side, end = side, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                KitModeSwitcher()
                Spacer(Modifier.weight(1f))
                val account = LocalModeProfileAction.current
                if (account != null) account() else if (onMusic != null) PodioTextAction("Music", look, fonts, onMusic)
            }
            com.cosmotune.ui.live.LiveNowStrip(Modifier.padding(horizontal = side).padding(top = 12.dp))
        }
        OrbitPageHeading(title, label, fonts, look, Modifier.padding(start = side + 4.dp, end = side, top = if (phone) 18.dp else 30.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** The head of a page opened over a section (a show, a station): the round back button and the mono [kind]. */
@Composable internal fun PodioBackHead(kind: String, onBack: () -> Unit, fonts: OrbitFonts, look: OrbitLook, side: Dp, modifier: Modifier = Modifier,
    backLabel: String = "Back", actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = side, end = side, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OrbitPageButton(CosmoIcons.ChevronLeft, backLabel, onBack)
        OrbitLabel(kind, fonts, look.dim, Modifier.weight(1f))
        actions()
    }
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

/** Rows standing in while a list loads. */
@Composable internal fun PodioSkeletonRows(count: Int, look: OrbitLook, side: Dp, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = side).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { WatchSkeleton(look, Modifier.fillMaxWidth().height(68.dp), radius = 22.dp) }
    }
}

/** The quiet credit at the foot of a page ("Stations: Radio Browser"). */
@Composable internal fun PodioCredit(text: String, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    Text(text, style = fonts.body.copy(fontSize = 11.5.sp), color = look.dim.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 20.dp))
}
