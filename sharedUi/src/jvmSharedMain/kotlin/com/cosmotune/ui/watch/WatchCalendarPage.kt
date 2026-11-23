package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.WatchCard
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchPicture
import com.cosmotune.ui.orbit.WatchRowFrame
import com.cosmotune.ui.orbit.WatchSkeleton
import com.cosmotune.watch.CalendarEntry
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Today on this device, as the Calendar's ISO date. */
internal fun watchToday(clock: () -> Long): String =
    java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

/** "Today", "Tomorrow", "Yesterday", "Sat 28 Nov". */
internal fun calendarDay(date: String, today: String): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val now = LocalDate.parse(today)
    return when (day) {
        now -> "Today"
        now.plusDays(1) -> "Tomorrow"
        now.minusDays(1) -> "Yesterday"
        else -> "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    }
}

/** What a Calendar row does: an episode that is out plays, one still to come opens its series. */
private fun openEntry(entry: CalendarEntry, today: String, actions: WatchActions) {
    if (entry.date <= today) actions.playEpisode(entry.summary, entry.episode) else actions.open(entry.summary)
}

/**
 * The Calendar (Stremio's idea): the episodes of the series in My list and Continue watching, out in the last week (marked New
 * until watched) and coming in the next two months, under their day. A tap plays one that is out or opens the series.
 */
@Composable
internal fun WatchCalendarPage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val clock = LocalWatchClock.current
    val today = remember(clock) { watchToday(clock) }
    val entries by produceState<List<CalendarEntry>?>(null, today) { value = runCatching { actions.repository.calendar(today) }.getOrDefault(emptyList()) }
    Box(Modifier.fillMaxSize()) {
        WatchWash(Color(0xFF1E3A5A), look)
        LazyColumn(Modifier.fillMaxSize().testTag("watch:calendar"), contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "cal:header") { header() } }
            item(key = "cal:heading") {
                val coming = entries?.count { it.date > today }
                OrbitPageHeading("Calendar", listOfNotNull("Your series", coming?.let { "$it coming" }).joinToString(" · "), fonts, look,
                    Modifier.padding(horizontal = frame.side + 6.dp).padding(top = if (frame.wide) 24.dp else 18.dp))
            }
            val list = entries
            when {
                list == null -> items(4) { WatchSkeleton(look, Modifier.padding(horizontal = frame.side).padding(top = 10.dp).fillMaxWidth().height(72.dp), radius = 16.dp) }
                list.isEmpty() -> item(key = "cal:empty") {
                    WatchMessage("Calendar", "Nothing new or coming", "Add series to My list, or start one, and their new episodes show here.", fonts, look,
                        Modifier.padding(horizontal = frame.side).padding(top = 22.dp))
                }
                else -> list.groupBy { it.date }.forEach { (date, day) ->
                    item(key = "cal:day:$date") {
                        OrbitLabel(calendarDay(date, today), fonts, if (date == today) look.accent else look.dim,
                            Modifier.padding(horizontal = frame.side + 6.dp).padding(top = 22.dp, bottom = 8.dp))
                    }
                    items(day, key = { "cal:${it.episode.key.value}" }) { entry ->
                        CalendarRow(entry, fonts, look, Modifier.padding(horizontal = frame.side).padding(bottom = 8.dp).widthIn(max = 760.dp)) { openEntry(entry, today, actions) }
                    }
                }
            }
            item(key = "cal:end") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun CalendarRow(entry: CalendarEntry, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier, onClick: () -> Unit) {
    val e = entry.episode
    WatchRowFrame(look, modifier, height = 76.dp, highlighted = entry.isNew, onClick = onClick) {
        WatchPicture(e.stillUrl ?: entry.summary.backdropUrl, e.key.value, Modifier.padding(start = 2.dp).size(104.dp, 58.dp), radius = 12.dp)
        Column(Modifier.weight(1f)) {
            Text(entry.summary.name, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(if (entry.isNew) "New" else null, "S${e.season} E${e.number}", e.name).joinToString(" · "),
                style = fonts.body.copy(fontSize = 12.sp), color = if (entry.isNew) look.accent else look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Home's "New episodes": the episodes of followed series out in the last week and not watched yet, as wide cards; See all opens
 * the Calendar. Nothing is drawn when there are none.
 */
@Composable
internal fun WatchNewEpisodesRow(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, side: Dp, wide: Boolean) {
    val clock = LocalWatchClock.current
    val today = remember(clock) { watchToday(clock) }
    val fresh by produceState(emptyList<CalendarEntry>(), today) {
        value = runCatching { actions.repository.calendar(today, pastDays = 7, days = 0) }.getOrDefault(emptyList()).filter { it.isNew }.reversed()
    }
    if (fresh.isEmpty()) return
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = side + 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("New episodes", style = fonts.section, color = look.ink, modifier = Modifier.weight(1f))
            com.cosmotune.ui.orbit.WatchCardChip("Calendar", look.ink, fonts, onClick = { WatchNav.open(WatchRoute.Calendar) })
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(fresh.take(20), key = { it.episode.key.value }) { entry ->
                WatchCard("${entry.summary.name} · S${entry.episode.season} E${entry.episode.number}", calendarDay(entry.date, today),
                    entry.episode.stillUrl ?: entry.summary.backdropUrl, entry.episode.key.value, fonts, look, onClick = { openEntry(entry, today, actions) },
                    width = if (wide) 260.dp else 224.dp, wide = true, onPlay = { openEntry(entry, today, actions) })
            }
        }
    }
}
