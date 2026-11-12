package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.listening.ListeningActivityPanel
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.replay.OrbitReplayChartRow
import com.cosmotune.ui.replay.OrbitReplayEmpty
import com.cosmotune.ui.replay.OrbitReplayHeading
import com.cosmotune.ui.replay.OrbitReplayHero
import com.cosmotune.ui.replay.OrbitReplayRow
import com.cosmotune.ui.replay.OrbitReplayStat
import com.cosmotune.ui.replay.OrbitReplayStats
import com.cosmotune.ui.replay.OrbitReplayTile
import com.cosmotune.ui.replay.OrbitReplayTiles
import com.cosmotune.ui.replay.orbitReplayChart
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── The page ────────────────────────────────────────────────────────────────

/**
 * Replay on the desktop, in Orbit and drawn from the phone's pieces ([com.cosmotune.ui.replay]): the heading with the period
 * chips and the share chip, the hero colour card in the top song's colours (Play in its bite plays it), the headline cards, the
 * ranked charts and the listening's shape as stat tiles. The desktop has no story, so a card opens its details.
 */
@Composable
internal fun DesktopReplayPage(
    summary: DesktopReplaySummary,
    period: DesktopReplayPeriod,
    holder: String,
    onPeriodChange: (DesktopReplayPeriod) -> Unit,
    onPlaySong: (Song) -> Unit,
    onOpenArtist: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    var sharing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DesktopReplayHeroCard?>(null) }
    if (sharing) DesktopReplayShareDialog(summary, holder, onDismiss = { sharing = false })
    detail?.let { card -> ReplayDetails(summary, card, { detail = null }, onPlaySong, onOpenArtist) }
    val fonts = orbitFonts()
    val look = orbitLook()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("heading") {
            // No arrow of its own: the top bar's back is the way out, as the gesture is on Android.
            OrbitReplayHeading(
                title = DesktopStrings["replay", "Replay"],
                label = summary.label.ifBlank { period.chip },
                chips = DesktopReplayPeriod.entries.map { it.chip },
                selected = DesktopReplayPeriod.entries.indexOf(period),
                onSelect = { onPeriodChange(DesktopReplayPeriod.entries[it]) },
                side = GUTTER,
                trailing = if (summary.isEmpty) null else {
                    {
                        OrbitChip(
                            "Share card", fonts, look, onClick = { sharing = true },
                            leading = { Icon(CosmoIcons.Share, contentDescription = null, modifier = Modifier.padding(2.dp)) },
                        )
                    }
                },
            )
        }

        if (summary.isEmpty) {
            item("empty") {
                OrbitReplayEmpty(
                    title = DesktopStrings["not_enough_listening", "Not enough listening yet"],
                    body = when (period) {
                        DesktopReplayPeriod.THIS_MONTH -> "Nothing this month. Play some music and it will fill up."
                        else -> "Play some music and your Replay will build itself."
                    },
                    side = GUTTER,
                )
            }
            return@LazyColumn
        }

        val cards = summary.heroCards()
        item("hero") {
            val minutes = cards.first()
            OrbitReplayHero(
                label = minutes.label,
                value = minutes.value,
                unit = null,
                detail = minutes.detail,
                cover = minutes.artworkUrl,
                playLabel = "Play your top song",
                onPlay = { summary.songs.firstOrNull()?.let { onPlaySong(it.song) } },
                onClick = { detail = minutes },
                side = GUTTER,
            )
        }
        item("cards") {
            val tiles = cards.filter { it.kind != DesktopReplayDetail.MINUTES }
            OrbitReplayTiles(
                tiles = tiles.map { OrbitReplayTile(it.label, it.label, it.value, it.detail, it.artworkUrl) },
                side = GUTTER,
                onClick = { detail = tiles[it] },
            )
        }

        orbitReplayChart(
            key = "songs",
            title = DesktopStrings["top_songs", "Top Songs"],
            rows = summary.songRows(CHART_LENGTH).map { it.orbit() },
            side = GUTTER,
            onClick = { index -> summary.songs.getOrNull(index)?.let { onPlaySong(it.song) } },
        )
        orbitReplayChart(
            key = "artists",
            title = DesktopStrings["top_artists", "Top Artists"],
            rows = summary.artistRows(CHART_LENGTH).map { it.orbit() },
            side = GUTTER,
            onClick = { index -> summary.artists.getOrNull(index)?.let { onOpenArtist(it.title) } },
        )
        orbitReplayChart(
            key = "albums",
            title = DesktopStrings["top_albums", "Top Albums"],
            rows = summary.albumRows(CHART_LENGTH).map { it.orbit() },
            side = GUTTER,
            onClick = { index ->
                summary.albums.getOrNull(index)?.let {
                    detail = DesktopReplayHeroCard(
                        "Album", it.title, "${formatListening(it.ms)} · ${plays(it.plays)}", it.artworkUrl, DesktopReplayDetail.ALBUMS,
                    )
                }
            },
        )

        item("habits") { Habits(summary) }
        item("tail") { Spacer(Modifier.height(32.dp)) }
    }
}

/** [this] row as the shared chart's: the subtitle and the listening on one line. */
private fun DesktopReplayRow.orbit() = OrbitReplayRow(
    key = key,
    rank = rank,
    title = title,
    subtitle = listOfNotNull(subtitle, formatListening(ms)).joinToString(" · "),
    cover = artworkUrl,
    plays = plays,
)

// ── Tail ────────────────────────────────────────────────────────────────────

/** The listening's shape: the counts as stat tiles, the peak hour and the biggest day under them, the daily activity. */
@Composable
private fun Habits(summary: DesktopReplaySummary) {
    OrbitReplayStats(
        title = DesktopStrings["d_listening_shape", "Listening shape"],
        stats = listOf(
            OrbitReplayStat("Songs", grouped(summary.distinctSongs.toLong())),
            OrbitReplayStat("Artists", grouped(summary.distinctArtists.toLong())),
            OrbitReplayStat("Albums", grouped(summary.distinctAlbums.toLong())),
        ),
        notes = listOfNotNull(
            summary.peakHour?.let { "You listen most around ${formatHour(it)}." },
            summary.busiestDay?.let { "Your biggest day was ${formatDay(it)} — ${formatListening(summary.busiestDayMs)}." },
        ),
        dailyMs = summary.dailyMs,
        hourly = summary.hourOfDay,
        side = GUTTER,
    )
}

// ── The mesh behind all of it ───────────────────────────────────────────────

// ── Rows and cards, derived from a summary ──────────────────────────────────

/** One row of a chart. */
data class DesktopReplayRow(
    val key: String,
    val rank: Int,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val ms: Long,
    val plays: Int,
)

/** One of the cards along the top of the page. */
data class DesktopReplayHeroCard(
    val label: String,
    val value: String,
    val detail: String?,
    val artworkUrl: String?,
    val kind: DesktopReplayDetail = DesktopReplayDetail.MINUTES,
)

enum class DesktopReplayDetail { MINUTES, ARTISTS, SONGS, ALBUMS }

/**
 * A headline card opened: the figure in the wide face, its detail and the period, then what it is made of as the kit's ranked
 * rows (the songs, the artists, an album's tracks) or, for the minutes, the daily activity.
 */
@Composable
private fun ReplayDetails(summary: DesktopReplaySummary, card: DesktopReplayHeroCard, close: () -> Unit,
    playSong: (Song) -> Unit, openArtist: (String) -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = close,
        title = card.label,
        scroll = false,
        content = {
            LazyColumn(Modifier.fillMaxWidth().height(440.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Text(card.value, style = fonts.section, color = look.ink)
                        card.detail?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 4.dp)) }
                        OrbitLabel(summary.label.ifBlank { summary.period.chip }, fonts, look.dim, Modifier.padding(top = 8.dp))
                    }
                }
                when (card.kind) {
                    DesktopReplayDetail.MINUTES -> item {
                        // The heat in the page's ink: peach is only for what plays.
                        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = look.ink), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                            ListeningActivityPanel(summary.dailyMs, summary.hourOfDay, textColor = look.ink)
                        }
                    }
                    DesktopReplayDetail.SONGS -> itemsIndexed(summary.songs.take(30), key = { _, it -> it.song.videoId }) { index, entry ->
                        OrbitReplayChartRow(
                            OrbitReplayRow(entry.song.videoId, index + 1, entry.song.title,
                                "${entry.song.artist} · ${formatListening(entry.listenedMs)} · ${plays(entry.plays)}", entry.song.thumbnailUrl, 0),
                            onClick = { close(); playSong(entry.song) },
                        )
                    }
                    DesktopReplayDetail.ARTISTS -> itemsIndexed(summary.artists.take(30)) { index, artist ->
                        OrbitReplayChartRow(
                            OrbitReplayRow(artist.title, index + 1, artist.title, "${formatListening(artist.ms)} · ${plays(artist.plays)}", artist.artworkUrl, 0),
                            onClick = { close(); openArtist(artist.title) },
                        )
                    }
                    DesktopReplayDetail.ALBUMS -> {
                        val tracks = summary.songs.filter { it.song.albumName == card.value }
                        if (tracks.isNotEmpty()) itemsIndexed(tracks, key = { _, it -> it.song.videoId }) { index, entry ->
                            OrbitReplayChartRow(
                                OrbitReplayRow(entry.song.videoId, index + 1, entry.song.title,
                                    "${formatListening(entry.listenedMs)} · ${plays(entry.plays)}", entry.song.thumbnailUrl, 0),
                                onClick = { close(); playSong(entry.song) },
                            )
                        } else itemsIndexed(summary.albums.take(30)) { index, album ->
                            OrbitReplayChartRow(
                                OrbitReplayRow(album.title, index + 1, album.title, "${formatListening(album.ms)} · ${plays(album.plays)}", album.artworkUrl, 0),
                                onClick = null,
                            )
                        }
                    }
                }
            }
        },
        confirmLabel = "Close",
        onConfirm = close,
    )
}

internal fun DesktopReplaySummary.songRows(limit: Int): List<DesktopReplayRow> =
    songs.take(limit).mapIndexed { index, entry ->
        DesktopReplayRow(
            key = entry.song.videoId,
            rank = index + 1,
            title = entry.song.title,
            subtitle = entry.song.artist.ifBlank { null },
            artworkUrl = entry.song.thumbnailUrl,
            ms = entry.listenedMs,
            plays = entry.plays,
        )
    }

internal fun DesktopReplaySummary.artistRows(limit: Int): List<DesktopReplayRow> =
    artists.take(limit).mapIndexed { index, entry -> entry.toRow(index) }

internal fun DesktopReplaySummary.albumRows(limit: Int): List<DesktopReplayRow> =
    albums.take(limit).mapIndexed { index, entry -> entry.toRow(index) }

private fun DesktopRankedEntry.toRow(index: Int) = DesktopReplayRow(
    key = title,
    rank = index + 1,
    title = title,
    subtitle = subtitle,
    artworkUrl = artworkUrl,
    ms = ms,
    plays = plays,
)

/**
 * The headline facts, in the order they are dealt.
 *
 * Minutes leads because it is the one figure that needs no context to mean something. A category
 * with nothing in it is left out rather than shown empty.
 */
internal fun DesktopReplaySummary.heroCards(): List<DesktopReplayHeroCard> = buildList {
    add(
        DesktopReplayHeroCard(
            label = DesktopStrings["minutes_listened", "Minutes listened"],
            value = grouped(minutes),
            detail = "${plays(totalPlays)} · ${label.ifBlank { period.chip }}",
            artworkUrl = songs.firstOrNull()?.song?.thumbnailUrl,
        ),
    )
    artists.firstOrNull()?.let {
        add(
            DesktopReplayHeroCard(
                label = DesktopStrings["top_artist", "Top artist"],
                value = it.title,
                detail = "${formatListening(it.ms)} · ${plays(it.plays)}",
                artworkUrl = it.artworkUrl,
                kind = DesktopReplayDetail.ARTISTS,
            ),
        )
    }
    songs.firstOrNull()?.let {
        add(
            DesktopReplayHeroCard(
                label = DesktopStrings["top_song", "Top song"],
                value = it.song.title,
                detail = "${it.song.artist} · ${plays(it.plays)}",
                artworkUrl = it.song.thumbnailUrl,
                kind = DesktopReplayDetail.SONGS,
            ),
        )
    }
    albums.firstOrNull()?.let {
        add(
            DesktopReplayHeroCard(
                label = DesktopStrings["top_album", "Top album"],
                value = it.title,
                detail = listOfNotNull(it.subtitle, formatListening(it.ms)).joinToString(" · "),
                artworkUrl = it.artworkUrl,
                kind = DesktopReplayDetail.ALBUMS,
            ),
        )
    }
}

/** `MM/YY` of the first month with anything in it. */
internal fun DesktopReplaySummary.memberSince(): String? = since
    ?.let { runCatching { java.time.YearMonth.parse(it) }.getOrNull() }
    ?.let { "%02d/%02d".format(it.monthValue, it.year % 100) }

// ── Formatting ──────────────────────────────────────────────────────────────

internal fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)

private fun plays(count: Int): String = if (count == 1) "1 play" else "${grouped(count.toLong())} plays"

internal fun formatListening(ms: Long): String {
    val minutes = ms / 60_000L
    if (minutes < 60) return if (minutes == 1L) "1 min" else "$minutes mins"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0L) "${grouped(hours)} hr" else "${grouped(hours)} hr $rest min"
}

private fun formatHour(hour: Int): String = when (hour) {
    0 -> "midnight"
    12 -> "midday"
    in 1..11 -> "$hour am"
    else -> "${hour - 12} pm"
}

private fun formatDay(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
}.getOrDefault(iso)

/** How far down each chart the page goes. A top five is a result; a top ten is a list. */
private const val CHART_LENGTH = 5

private const val ROW_ART_PX = 120
private const val MESH_SOURCE_PX = 120
/** The page margin, shared with every other page — see [DesktopPageGutter]. */
private val GUTTER = DesktopPageGutter
