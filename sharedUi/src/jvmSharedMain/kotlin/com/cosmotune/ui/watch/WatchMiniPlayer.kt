package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.WatchPicture
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.watch.WatchPlayerStatus
import kotlinx.coroutines.delay

/**
 * True where Watch draws the small player ([WatchApp] on the phone and the desktop): the player page then leaves the film playing
 * when it closes, and the small player stops it. False elsewhere (the TV's own player, a render test): the page stops it as it goes.
 */
internal val LocalWatchKeepsPlaying = staticCompositionLocalOf { false }

/** The room the small player takes at the foot of a page: its 64 dp and 8 dp on each side of it. */
internal val WATCH_MINI_ROOM: Dp = 80.dp

/** What the small player knows past the pages: whether the film went on in picture-in-picture when its page closed. */
internal object WatchMini {
    @Volatile var keptInPip: Boolean = false
}

/** Whether the small player has something to show: a film, an episode or a channel loaded and not over. */
@Composable
internal fun watchMiniShown(playback: WatchPlayback): Boolean {
    val state by playback.player.state.collectAsState()
    return state.target != null && state.status !in MINI_HIDDEN
}

/** States the small player does not stand for: nothing loaded, the end, and a copy that would not play. */
private val MINI_HIDDEN = setOf(WatchPlayerStatus.IDLE, WatchPlayerStatus.ENDED, WatchPlayerStatus.NOT_FOUND, WatchPlayerStatus.ERROR)

/** Watch is leaving: what the small player held stops, unless picture-in-picture or the room keeps it. */
internal fun stopWhenLeaving(playback: WatchPlayback) {
    val target = playback.player.state.value.target ?: return
    if (WatchMini.keptInPip || playback.roomHolds(target.key)) return
    playback.player.stop()
}

/**
 * The small player after the player page closes (board: "Mini player after closing the player"): one 64 dp capsule in the
 * picture's colour above the foot of the page, with the still, the title over the episode line, play or pause, and close (which
 * stops the film and saves where it was); a thin line along its foot says how far. A tap reopens the player page on what plays.
 */
@Composable
internal fun WatchMiniPlayer(playback: WatchPlayback, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    val player = playback.player
    val state by player.state.collectAsState()
    val snapshot by player.engine.snapshot.collectAsState()
    val target = state.target ?: return
    val summary = state.summary
    val picture = summary?.backdropUrl ?: summary?.posterUrl
    val key = summary?.key?.value ?: target.key.value
    val (fill, ink) = coverFill(picture, key)
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player, state.isLive) {
        while (true) {
            val duration = state.durationMs.takeIf { it > 0 } ?: player.engine.snapshot.value.durationMs
            fraction = if (state.isLive || duration <= 0) 0f else (player.engine.positionMs().toFloat() / duration).coerceIn(0f, 1f)
            delay(1_000)
        }
    }
    Box(
        modifier.padding(horizontal = 12.dp).widthIn(max = 640.dp).fillMaxWidth().height(64.dp).testTag("watch:mini")
            .clip(RoundedCornerShape(30.dp)).background(fill)
            .clickable(onClickLabel = "Open the player", role = Role.Button) { WatchNav.showPlaying(target, summary) },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            WatchPicture(picture, key, Modifier.size(72.dp, 40.dp), radius = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(summary?.name ?: "Watch", style = fonts.title.copy(fontSize = 14.sp, lineHeight = 18.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = state.subtitleLine ?: if (state.isLive) "Live" else state.stream?.source?.pluginName
                line?.let { Text(it, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            OrbitRoundButton(if (snapshot.playing) CosmoIcons.Pause else CosmoIcons.Play, if (snapshot.playing) "Pause" else "Play", ink,
                onClick = { if (player.engine.snapshot.value.playing) player.engine.pause() else player.engine.play() }, touch = 48.dp)
            OrbitRoundButton(CosmoIcons.Close, "Stop and close", ink, onClick = { player.stop() }, touch = 48.dp)
        }
        if (!state.isLive) Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 26.dp).fillMaxWidth().height(2.dp).background(ink.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(ink))
        }
    }
}
