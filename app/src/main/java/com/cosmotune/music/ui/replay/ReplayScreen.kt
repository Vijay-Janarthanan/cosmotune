package com.cosmotune.music.ui.replay

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.cosmotune.ui.bridge.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.R
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.stats.ArtistFacts
import com.cosmotune.music.data.stats.ReplayPeriod
import com.cosmotune.music.data.stats.ReplaySummary
import com.cosmotune.music.ui.components.PAGE_GUTTER
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.replay.OrbitReplayEmpty
import com.cosmotune.ui.replay.OrbitReplayHeading
import com.cosmotune.ui.replay.OrbitReplayHero
import com.cosmotune.ui.replay.OrbitReplayNote
import com.cosmotune.ui.replay.OrbitReplayRow
import com.cosmotune.ui.replay.OrbitReplayShareButton
import com.cosmotune.ui.replay.OrbitReplayStat
import com.cosmotune.ui.replay.OrbitReplayStats
import com.cosmotune.ui.replay.OrbitReplayTile
import com.cosmotune.ui.replay.OrbitReplayTiles
import com.cosmotune.ui.replay.orbitReplayChart

/**
 * The Replay page, in Orbit: the heading with the period chips, the hero colour
 * card, the headline cards, four charts, the listening's shape and a way to play
 * or share the lot. The drawing is shared with the desktop
 * ([com.cosmotune.ui.replay]); this file keeps the phone's numbers and words.
 *
 * ## Two ways in to the same numbers
 *
 * The headline cards also live on the Library page, where each one opens this
 * page at its matching chart. They remain here as Replay's own visual summary;
 * the ranked lists below answer the follow-up questions those cards provoke.
 *
 * ## Why the hero wears the top song's colours
 *
 * This page is about one particular stretch of listening, and running its card
 * in the colours of the record that defined it ties the two together without a
 * line of copy saying so. It also means two people's Replays do not look alike,
 * which a fixed brand colour could never manage.
 */
@Composable
fun ReplayScreen(
    state: ReplayState,
    /** The signed-in account name embossed on the Replay cards. */
    holder: String,
    onPeriodChange: (ReplayPeriod) -> Unit,
    onOpenStory: (ReplayStoryPage) -> Unit,
    onPlaySong: (Song) -> Unit,
    /**
     * Opens the artist's page. The browse id is null for most rows — an artist
     * is counted by name, and the id only rides along when the track that
     * credited them carried one — so the caller has to be able to find the page
     * from the name alone. Same for the album below.
     */
    onOpenArtist: (String?, String) -> Unit,
    onOpenAlbum: (String?, String, String?, String?) -> Unit,
    onShare: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    landingPage: ReplayStoryPage = ReplayStoryPage.INTRO,
) {
    val context = LocalContext.current
    val summary = state.summary
    val side = PAGE_GUTTER + 4.dp
    val topSongs = stringResource(R.string.top_songs)
    val topArtists = stringResource(R.string.top_artists)
    val topAlbums = stringResource(R.string.top_albums)
    val topGenres = stringResource(R.string.top_genres)

    // Library cards open this same page, but category cards should land on the
    // corresponding chart rather than making the user find it again. Wait for
    // a loaded summary because the loading/empty layouts do not contain these
    // keyed sections yet.
    LaunchedEffect(landingPage, state.period, summary == null) {
        val target = when {
            landingPage == ReplayStoryPage.INTRO || landingPage == ReplayStoryPage.MINUTES -> 0
            summary == null || summary.isEmpty -> 0
            else -> replayChartIndex(summary, landingPage)
        }
        listState.scrollToItem(target)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item("heading") {
            OrbitReplayHeading(
                title = stringResource(R.string.replay),
                label = summary?.localizedLabel(context) ?: state.period.localizedChip(context),
                // The four stretches a Replay can cover. Weeks and months rather
                // than a date range, because that is the granularity the
                // listening is actually kept at — see
                // [com.cosmotune.music.data.stats.ListeningStats]. A "last 30
                // days" chip would have to be answered from monthly totals.
                chips = ReplayPeriod.entries.map { it.localizedChip(context) },
                selected = ReplayPeriod.entries.indexOf(state.period),
                onSelect = { onPeriodChange(ReplayPeriod.entries[it]) },
                side = side,
            )
        }

        when {
            state.loading && summary == null -> item("loading") {
                Box(Modifier.fillMaxWidth().padding(64.dp), Alignment.Center) {
                    CircularProgressIndicator(color = orbitLook().dim)
                }
            }
            summary == null || summary.isEmpty -> item("empty") {
                OrbitReplayEmpty(
                    title = stringResource(R.string.not_enough_listening),
                    body = when (state.period) {
                        ReplayPeriod.THIS_WEEK -> stringResource(R.string.not_enough_this_week)
                        ReplayPeriod.THIS_MONTH -> stringResource(R.string.not_enough_this_month)
                        else -> stringResource(R.string.replay_empty_description)
                    },
                    side = side,
                )
            }
            else -> {
                val cards = summary.cards(context)
                // The minutes are the hero's; Play in its bite opens the story,
                // the card itself opens the story at the minutes.
                item("hero") {
                    val minutes = cards.first { it.page == ReplayStoryPage.MINUTES }
                    OrbitReplayHero(
                        label = minutes.label,
                        value = minutes.value,
                        unit = null,
                        detail = minutes.detail,
                        cover = minutes.artworkUrl,
                        playLabel = stringResource(R.string.play_your_replay),
                        onPlay = { onOpenStory(ReplayStoryPage.INTRO) },
                        onClick = { onOpenStory(ReplayStoryPage.MINUTES) },
                        side = side,
                    )
                }
                item("cards") {
                    val tiles = cards.filter { it.page != ReplayStoryPage.MINUTES }
                    OrbitReplayTiles(
                        tiles = tiles.map { OrbitReplayTile(it.label, it.label, it.value, it.detail, it.artworkUrl) },
                        side = side,
                        onClick = { onOpenStory(tiles[it].page) },
                    )
                }

                orbitReplayChart(
                    key = "songs",
                    title = topSongs,
                    rows = summary.songRows(CHART_LENGTH).map { it.orbit(context) },
                    side = side,
                    onClick = { index -> summary.songs.getOrNull(index)?.let { onPlaySong(it.song) } },
                )
                orbitReplayChart(
                    key = "artists",
                    title = topArtists,
                    rows = summary.artistRows(CHART_LENGTH).map { it.orbit(context) },
                    side = side,
                    onClick = { index ->
                        summary.artists.getOrNull(index)?.let { onOpenArtist(it.browseId, it.title) }
                    },
                )
                orbitReplayChart(
                    key = "albums",
                    title = topAlbums,
                    rows = summary.albumRows(CHART_LENGTH).map { it.orbit(context) },
                    side = side,
                    onClick = { index ->
                        summary.albums.getOrNull(index)?.let {
                            onOpenAlbum(it.browseId, it.title, it.subtitle, it.artworkUrl)
                        }
                    },
                )
                if (summary.genres.isNotEmpty()) {
                    orbitReplayChart(
                        key = "genres",
                        title = topGenres,
                        rows = summary.genreRows(CHART_LENGTH).map { it.orbit(context) },
                        side = side,
                        onClick = null,
                        icon = CosmoIcons.Waveform,
                    )
                } else if (ArtistFacts.genresAvailable) {
                    item("genres-pending") { OrbitReplayNote(stringResource(R.string.genres_pending), side) }
                }

                item("habits") { Habits(summary, side) }
                item("share") { OrbitReplayShareButton(stringResource(R.string.share_my_replay), onShare, side) }
            }
        }
    }
}

/** [this] row as the shared chart's: the subtitle and the listening on one line. */
internal fun ReplayRow.orbit(context: Context) = OrbitReplayRow(
    key = key,
    rank = rank,
    title = title,
    subtitle = listOfNotNull(subtitle, formatListening(context, ms)).joinToString(" · "),
    cover = artworkUrl,
    plays = plays,
)

/**
 * Lazy-list index of a chart title after the heading, the hero and the card row.
 * Each preceding chart contributes its title, visible rows and trailing gap.
 */
private fun replayChartIndex(summary: ReplaySummary, page: ReplayStoryPage): Int {
    val songs = summary.songRows(CHART_LENGTH)
    val artists = summary.artistRows(CHART_LENGTH)
    fun span(rows: List<ReplayRow>): Int = if (rows.isEmpty()) 0 else rows.size + 2

    return when (page) {
        ReplayStoryPage.SONGS -> 3
        ReplayStoryPage.ARTISTS -> 3 + span(songs)
        ReplayStoryPage.ALBUMS -> 3 + span(songs) + span(artists)
        else -> 0
    }
}

// ── Tail ────────────────────────────────────────────────────────────────────

/** The listening's shape: the counts as stat tiles, the peak hour and the biggest day under them. */
@Composable
private fun Habits(summary: ReplaySummary, side: Dp) {
    val context = LocalContext.current
    val stats = listOf(
        OrbitReplayStat(stringResource(R.string.songs), grouped(summary.distinctSongs.toLong())),
        OrbitReplayStat(stringResource(R.string.artists), grouped(summary.distinctArtists.toLong())),
        OrbitReplayStat(stringResource(R.string.albums), grouped(summary.distinctAlbums.toLong())),
    )
    val notes = listOfNotNull(
        summary.peakHour?.let { stringResource(R.string.listen_most_around, formatHour(context, it)) },
        summary.busiestDay?.let {
            stringResource(R.string.biggest_day_note, formatDay(context, it), formatListening(context, summary.busiestDayMs))
        },
    )
    OrbitReplayStats(
        title = stringResource(R.string.listening_shape),
        stats = stats,
        notes = notes,
        dailyMs = summary.dailyMs,
        hourly = summary.hourOfDay,
        side = side,
    )
}

/**
 * How far down each chart the page goes.
 *
 * Five. Ten was the first guess and it made the page a scroll of forty rows
 * whose second half nobody has an opinion about — a top five is a result, and a
 * top ten is a list.
 */
private const val CHART_LENGTH = 5
