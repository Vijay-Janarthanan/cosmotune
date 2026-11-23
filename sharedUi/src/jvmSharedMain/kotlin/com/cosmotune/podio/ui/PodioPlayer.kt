@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.cosmotune.podio.ui

import com.cosmotune.music.playback.rememberLivePlayheadOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import com.cosmotune.music.ui.components.contextClick
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.podio.RadioStation
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.*
import com.cosmotune.ui.room.RoomPlayButton

/** What the players need besides the playback: the timer and its controls (null: the host has no timer). */
internal class PodioTimer(val minutes: Int?, val afterEpisode: Boolean, val onTimer: ((Int?) -> Unit)?, val onAfterEpisode: (() -> Unit)?)

/** The disc of what is on: the station's logo disc, or the episode's (or show's) cover; the orbit round it in [ring]. */
@Composable private fun NowDisc(station: RadioStation?, artUrl: String?, size: Dp, ring: Color, playback: PodioPlayback, fonts: OrbitFonts, outer: Dp = size + 12.dp) {
    val live = playback.id?.startsWith("podio:radio:") == true
    // The episode's playhead run on between readings, at its speed, so the orbit glides; read while the ring draws.
    val head = rememberLivePlayheadOf(playback.positionMs, playback.playing && !playback.loading, playback.rate, playback.id)
    Box(Modifier.size(outer), contentAlignment = Alignment.Center) {
        if (station != null) OrbitLogoDisc(station.artwork, station.name, size, fonts) else Disc(artUrl?.takeIf(String::isNotBlank), size)
        val progress: (() -> Float?)? = if (live || playback.durationMs <= 0) null else ({ head.fraction(playback.durationMs) })
        OrbitRing(outer, ring, progress = progress, spinning = playback.playing, stroke = if (outer > 120.dp) 2.dp else 1.5.dp, moon = if (outer > 120.dp) 5.dp else 3.dp)
    }
}

/**
 * Podio's mini player in the dock (the shared dock's `miniPlayer`, above the tabs): the disc wearing the orbit, the title over the
 * station's song on air ("● LIVE") or the episode's show, and play. A tap opens the station page or the episode's player.
 */
@Composable internal fun PodioMiniPlayer(playback: PodioPlayback, station: RadioStation?, onAir: String?, artUrl: String?, onToggle: () -> Unit, onOpen: () -> Unit,
    onSeek: ((Long) -> Unit)? = null, queued: Int = 0, onQueue: (() -> Unit)? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val live = playback.id?.startsWith("podio:radio:") == true
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("podio:mini").padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClickLabel = if (live) "Open the station page" else "Open the player", onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NowDisc(station, artUrl, 44.dp, look.accent, playback, fonts)
            Column(Modifier.weight(1f)) {
                Text(playback.title, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    playback.error != null -> Text("Playback failed", style = fonts.body, color = look.accent, maxLines = 1)
                    live -> OrbitLiveLabel(onAir ?: station?.placeLine().orEmpty(), look.dim, liveRed(look), fonts)
                    playback.author.isNotBlank() -> Text(playback.author, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!live && onSeek != null) OrbitRoundButton(Icons.Outlined.Replay, "Back 15 seconds", look.ink, { onSeek((playback.positionMs - 15_000).coerceAtLeast(0)) },
            size = 40.dp, onInk = look.ground)
        if (!live && onQueue != null) QueueButton(queued, onQueue, look.ink, look.ground, size = 40.dp)
        OrbitPlayButton(playback.playing, onToggle, look, size = 48.dp)
    }
}

/** The way to Up next: the queue's glyph, and how many are waiting in its label ("Up next · 3"). */
@Composable internal fun QueueButton(queued: Int, onQueue: () -> Unit, ink: Color, onInk: Color, size: Dp = 40.dp) {
    OrbitRoundButton(com.cosmotune.ui.components.CosmoIcons.Queue, if (queued > 0) "Up next · $queued" else "Up next", ink, onQueue, Modifier.testTag("podio:open-queue"),
        size = size, onInk = onInk)
}

/** The sleep timer as a chip with its menu: 15 to 60 minutes, the end of the episode, off. On a colour card when [card] is given. */
@Composable internal fun SleepTimerChip(timer: PodioTimer, episode: Boolean, card: OrbitCardColors? = null) {
    val onTimer = timer.onTimer ?: return
    val fonts = orbitFonts()
    val look = orbitLook()
    var open by remember { mutableStateOf(false) }
    val active = timer.afterEpisode || timer.minutes != null
    // The time left while one counts ("23:41"), from the host's timer; the length set where there is no host timer to read.
    val left = com.cosmotune.ui.sleep.rememberSleepTimer()?.remainingMs
    val text = when {
        timer.afterEpisode -> "After episode"
        left != null -> com.cosmotune.ui.sleep.sleepCountdown(left)
        timer.minutes != null -> "${timer.minutes} min"
        else -> "Sleep timer"
    }
    Box {
        if (card != null) OrbitCardChip(text, card, { open = true }, icon = Icons.Outlined.Bedtime, solid = active)
        else OrbitSelectChip(text, active, fonts, look, { open = true }, icon = Icons.Outlined.Bedtime, haptic = false)
        val extend = com.cosmotune.ui.sleep.SleepTimerUi.control?.takeIf { left != null }
        val items = listOfNotNull(extend?.let { control ->
            OrbitMenuItem("Extend", "Add ${com.cosmotune.ui.sleep.SleepTimerControl.EXTEND_MINUTES} minutes", keepOpen = true) { control.extend() }
        }) + listOf(15, 30, 45, 60).map { minutes -> OrbitMenuItem("$minutes minutes", selected = timer.minutes == minutes && !timer.afterEpisode) { onTimer(minutes) } } +
            listOfNotNull(timer.onAfterEpisode?.takeIf { episode }?.let { after -> OrbitMenuItem("End of episode", selected = timer.afterEpisode) { after() } }) +
            OrbitMenuItem("Turn off timer", enabled = active) { onTimer(null) }
        OrbitMenu(open, { open = false }, items, fonts, look, header = left?.let { "Sleep timer · ${com.cosmotune.ui.sleep.sleepCountdown(it)} left" } ?: "Sleep timer", width = 220.dp)
    }
}

/**
 * The speed of an episode as a chip: a tap goes on to the next of 1, 1.25, 1.5 and 2×; a hold (or a right click) opens every speed
 * from 0.75 to 3× and, given [showSpeed], "Use for this show", which the show's next episodes start at. On a colour card when
 * [card] is given.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun SpeedChip(playback: PodioPlayback, onSpeed: (Float) -> Unit, card: OrbitCardColors? = null, showSpeed: Float? = null,
    onShowSpeed: ((Float?) -> Unit)? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var speed by remember(playback.id) { mutableFloatStateOf(playback.rate) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(playback.rate) { speed = playback.rate }
    val choose = { value: Float -> speed = value; onSpeed(value) }
    val next = { choose(when { speed < 1.25f -> 1.25f; speed < 1.5f -> 1.5f; speed < 2f -> 2f; else -> 1f }) }
    Box {
        if (card != null) OrbitCardChip(PodioFormat.speed(speed), card, next, icon = Icons.Outlined.Speed, solid = speed != 1f)
        else OrbitSelectChip(PodioFormat.speed(speed), speed != 1f, fonts, look, next, icon = Icons.Outlined.Speed, haptic = false)
        // Over the chip, so a hold reaches the menu while a tap still steps the speed.
        Box(Modifier.matchParentSize().clip(CircleShape).testTag("podio:speed")
            .combinedClickable(role = Role.Button, onClickLabel = "Next speed", onLongClickLabel = "All speeds", onClick = next, onLongClick = { menu = true })
            .contextClick { menu = true })
        OrbitMenu(menu, { menu = false }, PodioFormat.SPEEDS.map { value -> OrbitMenuItem(PodioFormat.speed(value), selected = value == speed) { choose(value) } } +
            listOfNotNull(onShowSpeed?.let { set ->
                val kept = showSpeed != null && showSpeed == speed
                OrbitMenuItem(if (kept) "Used for this show" else "Use for this show", detail = if (kept) "Tap to start its episodes at 1×" else "Its next episodes start at ${PodioFormat.speed(speed)}",
                    selected = kept) { set(if (kept) null else speed) }
            }), fonts, look, header = "Speed", width = 220.dp)
    }
}

/** Back 15 seconds and forward 30, as the card's or the page's round buttons. */
@Composable private fun RowScope.Skips(playback: PodioPlayback, onSeek: (Long) -> Unit, ink: Color, onInk: Color, size: Dp = 40.dp) {
    OrbitRoundButton(Icons.Outlined.Replay, "Back 15 seconds", ink, { onSeek((playback.positionMs - 15_000).coerceAtLeast(0)) }, size = size, onInk = onInk)
    OrbitRoundButton(Icons.Outlined.Forward30, "Forward 30 seconds", ink, {
        onSeek((playback.positionMs + 30_000).let { if (playback.durationMs > 0) it.coerceAtMost(playback.durationMs) else it })
    }, size = size, onInk = onInk)
}

/** The seek line of an episode with its two times under it, in [ink]. */
@Composable private fun EpisodeLine(playback: PodioPlayback, onSeek: (Long) -> Unit, ink: Color, fonts: OrbitFonts, modifier: Modifier = Modifier,
    chapters: List<com.cosmotune.podio.PodcastChapter> = emptyList(), left: Boolean = false) {
    if (playback.durationMs <= 0) return
    val head = rememberLivePlayheadOf(playback.positionMs, playback.playing && !playback.loading, playback.rate, playback.id)
    Column(modifier) {
        // A small tick over the line where each chapter starts.
        if (chapters.size > 1) Box(Modifier.fillMaxWidth().height(5.dp).drawBehind {
            chapters.drop(1).forEach { chapter ->
                val x = size.width * (chapter.startMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
                drawLine(ink.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
            }
        })
        OrbitProgressLine({ head.fraction(playback.durationMs) }, ink, Modifier.fillMaxWidth(),
            onSeek = { onSeek((it * playback.durationMs).toLong()) })
        Row(Modifier.fillMaxWidth()) {
            Text(PodioFormat.clock(playback.positionMs), style = fonts.label, color = ink.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(if (left) "−" + PodioFormat.clock(playback.durationMs - playback.positionMs) else PodioFormat.clock(playback.durationMs), style = fonts.label, color = ink.copy(alpha = 0.7f))
        }
    }
}

/**
 * The player bar of the wide layouts (tablet and desktop) in Orbit, docked along the foot of the page: the colour card made slim
 * in what plays' own colour, the disc wearing the orbit, the title over the show (or "● LIVE"), back 15 and forward 30, the seek
 * line with its times, the speed and the sleep timer, and play standing in the bite cut out of its end. A live station has no line,
 * speed or skips.
 */
@Composable internal fun PodioPlayerBar(playback: PodioPlayback, station: RadioStation?, artUrl: String?, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit, timer: PodioTimer, onOpen: (() -> Unit)?, queued: Int = 0, onQueue: (() -> Unit)? = null, onBookmark: (() -> Unit)? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val live = playback.id?.startsWith("podio:radio:") == true
    val colors = if (station != null) stationCardColors(station) else coverFill(artUrl?.takeIf(String::isNotBlank), playback.title).let { OrbitCardColors(it.first, it.second) }
    val ink = colors.ink
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(OrbitBarMetrics.HEIGHT_DP.dp).testTag("podio:player")) {
        val roomy = maxWidth >= 900.dp
        Box(Modifier.fillMaxSize().clip(OrbitBarMetrics.shape).background(colors.fill)) {
            Row(Modifier.fillMaxSize().padding(start = OrbitBarMetrics.PAD_START_DP.dp, end = OrbitBarMetrics.END_PAD_DP.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).clip(CircleShape).then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the station page", onClick = onOpen) else Modifier),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NowDisc(station, artUrl, OrbitBarMetrics.DISC_DP.dp, ink, playback, fonts, outer = OrbitBarMetrics.RING_DP.dp)
                    Column(Modifier.weight(1f)) {
                        Text(playback.title, style = fonts.title, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            playback.error != null -> Text("Playback failed. Try another station or refresh this feed.", style = fonts.body, color = LIVE_ON_CARD, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            live -> OrbitLiveLabel(playback.author, ink.copy(alpha = 0.75f), LIVE_ON_CARD, fonts)
                            else -> Text(playback.author, style = fonts.body, color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!live) {
                    Skips(playback, onSeek, ink, colors.fill, size = 36.dp)
                    EpisodeLine(playback, onSeek, ink, fonts, Modifier.width(if (roomy) 260.dp else 160.dp))
                    SpeedChip(playback, onSpeed, colors)
                }
                SleepTimerChip(timer, episode = !live, card = colors)
                if (!live && onBookmark != null && roomy) OrbitRoundButton(Icons.Outlined.BookmarkAdd, "Bookmark this moment", ink, onBookmark, size = 36.dp, onInk = colors.fill)
                if (!live && onQueue != null) QueueButton(queued, onQueue, ink, colors.fill, size = 36.dp)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = (OrbitBarMetrics.BITE_INSET_DP - OrbitBarMetrics.PLAY_DP / 2).dp)) {
            OrbitPlayButton(playback.playing, onToggle, look, size = OrbitBarMetrics.PLAY_DP.dp)
        }
    }
}

/**
 * The phone's episode player, a sheet over the page (board frame 3): "FROM · SHOW", the cover as a disc wearing the episode's orbit,
 * the title and the show; the chapter playing with previous and next chapter; the seek line with a tick at each chapter and the
 * time gone and left; back 15, play, forward 30, the speed and the sleep timer; then Chapters, Transcript and Notes over one list,
 * and Up next with its count, which opens the queue.
 */
@Composable internal fun EpisodePlayerSheet(playback: PodioPlayback, artUrl: String?, onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit,
    timer: PodioTimer, roomItem: PartyTrack? = null, episode: com.cosmotune.podio.PodcastEpisode? = null, show: String = "",
    catalogue: com.cosmotune.podio.PodioCatalogue? = null, queued: Int = 0, onQueue: (() -> Unit)? = null,
    showSpeed: Float? = null, onShowSpeed: ((Float?) -> Unit)? = null, onBookmark: (() -> Unit)? = null, onShareAt: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val chapters = if (episode != null && catalogue != null) rememberEpisodeChapters(episode, catalogue) else null
    val list = chapters?.list.orEmpty()
    val index = PodioFormat.chapterAt(list, playback.positionMs)
    KitSheet(onDismiss, expanded = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).testTag("podio:episode-player"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (show.isNotBlank()) OrbitLabel("From · $show", fonts, look.dim)
            NowDisc(null, artUrl, 168.dp, look.accent, playback, fonts, outer = 192.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(playback.title, style = fonts.heroTitle.copy(fontSize = 21.sp, lineHeight = 25.sp), color = look.ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(playback.author, style = fonts.body.copy(fontSize = 14.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (list.isNotEmpty()) Row(Modifier.fillMaxWidth().testTag("podio:chapter"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrbitRoundButton(Icons.Outlined.SkipPrevious, "Previous chapter", look.ink, { onSeek(PodioFormat.previousChapterStart(list, playback.positionMs)) }, size = 36.dp, onInk = look.ground)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    OrbitLabel(if (index >= 0) "Chapter ${index + 1} of ${list.size}" else "${list.size} chapters", fonts, look.dim)
                    Text(list.getOrNull(index)?.title ?: list.first().title, style = fonts.title, color = look.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center)
                }
                val following = list.getOrNull(index + 1)
                OrbitRoundButton(Icons.Outlined.SkipNext, "Next chapter", look.ink, { following?.let { onSeek(it.startMs) } }, size = 36.dp, onInk = look.ground, enabled = following != null)
            }
            // Until the player has opened the episode it does not know the length; the feed does, so the line shows from the start.
            val known = episode?.durationSeconds?.takeIf { playback.durationMs <= 0 && it > 0 }?.let { playback.copy(durationMs = it * 1000) } ?: playback
            EpisodeLine(known, onSeek, look.ink, fonts, Modifier.fillMaxWidth(), chapters = list, left = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                OrbitRoundButton(Icons.Outlined.Replay, "Back 15 seconds", look.ink, { onSeek((playback.positionMs - 15_000).coerceAtLeast(0)) }, size = 50.dp, onInk = look.ground)
                OrbitPlayButton(playback.playing, onToggle, look, size = 68.dp)
                OrbitRoundButton(Icons.Outlined.Forward30, "Forward 30 seconds", look.ink, {
                    onSeek((playback.positionMs + 30_000).let { if (playback.durationMs > 0) it.coerceAtMost(playback.durationMs) else it })
                }, size = 50.dp, onInk = look.ground)
            }
            // A row that wraps, so on a narrow phone no control is pushed off.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                SpeedChip(playback, onSpeed, showSpeed = showSpeed, onShowSpeed = onShowSpeed)
                SleepTimerChip(timer, episode = true)
                if (onBookmark != null) OrbitSelectChip("Bookmark", false, fonts, look, onBookmark, icon = Icons.Outlined.BookmarkAdd, haptic = false,
                    modifier = Modifier.testTag("podio:bookmark-add"))
                if (onShareAt != null) OrbitSelectChip("Share from ${PodioFormat.clock(playback.positionMs)}", false, fonts, look, onShareAt,
                    icon = com.cosmotune.ui.components.CosmoIcons.Share, haptic = false, modifier = Modifier.testTag("podio:share-at"))
                // [roomItem] is the episode as the room would take it; nothing is drawn where rooms are not offered.
                RoomPlayButton(roomItem, dense = true)
            }
            if (playback.error != null) Text("Playback failed. Refresh this feed or try another episode.", color = look.accent, style = fonts.body)
            val upNext: @Composable RowScope.() -> Unit = {
                if (onQueue != null) OrbitSelectChip(if (queued > 0) "Up next · $queued" else "Up next", false, fonts, look, onQueue, icon = com.cosmotune.ui.components.CosmoIcons.Queue,
                    haptic = false, modifier = Modifier.testTag("podio:open-queue"))
            }
            if (episode != null && catalogue != null) EpisodeExtras(episode, catalogue, playback.positionMs, onSeek, listHeight = 220.dp, chapters = chapters, trailing = upNext)
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = upNext)
        }
    }
}
