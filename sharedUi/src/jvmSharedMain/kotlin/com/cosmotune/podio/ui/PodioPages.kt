package com.cosmotune.podio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.*
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.*
import com.cosmotune.ui.room.RoomPlayButton
import com.cosmotune.ui.room.roomItem
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

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
    /** True on the desktop and tablets: sheets become menus, the player is a bar along the foot. */
    val wide: Boolean,
)

/** "1 episode", "12 episodes"; nothing for none. */
internal fun episodeCount(count: Int): String = when (count) { 0 -> ""; 1 -> "1 episode"; else -> "$count episodes" }

/** The room a list leaves above its first row for a top bar it scrolls under, measured; 120 dp until it is. */
@Composable internal fun rememberBarHeight(): MutableState<Dp> = remember { mutableStateOf(120.dp) }

/** The episode's line under its title: date, length, progress and download state. */
internal fun episodeLine(episode: PodcastEpisode, item: PodioItem, env: PodioEnv, withShow: Boolean = false): String {
    val progress = env.saved.progress[item.id] ?: 0
    val downloaded = env.downloaded.any { it.id == item.id }
    return PodioFormat.episodeMeta(if (withShow) episode.show else "", PodioFormat.date(episode.published), PodioFormat.duration(episode.durationSeconds),
        PodioFormat.progress(item.id in env.saved.completedEpisodes, progress, episode.durationSeconds * 1000),
        if (downloaded) "Downloaded" else env.downloadStatus[item.id].orEmpty())
}

@Composable private fun EpisodeItemRow(m: PodioModel, env: PodioEnv, entry: InboxEpisode, withShow: Boolean) {
    val item = entry.item()
    val progress = env.saved.progress[item.id] ?: 0
    EpisodeRow(entry.episode.title, episodeLine(entry.episode, item, env, withShow), item.artwork,
        PodioFormat.progressFraction(item.id in env.saved.completedEpisodes, progress, entry.episode.durationSeconds * 1000),
        current = env.playback.id == item.id, download = m.downloadMark(item, env.downloaded, env.downloadsRunning),
        onPlay = { m.play(item) }, onDownload = { m.host.onDownloadEpisode?.invoke(item) }, onMore = { m.episodeSheet = entry })
}

// ---- Podcasts (slot 2) -----------------------------------------------------------------------------------------------------

/**
 * The podcast charts (design system 6.3): the region and language chips, the genre chips, then "Popular · India" as ranked rows
 * (rank, cover, show, publisher). The top bar's action adds a show by its RSS, Spotify or YouTube Music address.
 */
@Composable internal fun PodcastsPage(m: PodioModel, env: PodioEnv, onSearch: () -> Unit) {
    val list = rememberLazyListState()
    var bar by rememberBarHeight()
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("podio:podcasts"), state = list, contentPadding = PaddingValues(top = bar, bottom = env.bottom)) {
            item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = CosmoSpacing.margin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuChip("Region", m.country, REGIONS, { m.browse(); m.country = it })
                    MenuChip("Language", m.language, LANGUAGES, { m.browse(); m.language = it })
                }
            }
            item(key = "genres") {
                val labels = listOf("Popular") + PODCAST_GENRES.map { it.second }
                KitChipRow(labels, (PODCAST_GENRES.indexOfFirst { it.first == m.genre } + 1).coerceAtLeast(0), { index ->
                    m.browse(); m.genre = if (index == 0) "" else PODCAST_GENRES[index - 1].first
                }, Modifier.padding(top = 4.dp))
            }
            if (m.showsTitle.isNotBlank() && m.shows.isNotEmpty()) item(key = "title") { KitSectionHeader(m.showsTitle, overline = if (m.language.isBlank()) "Top charts" else "Language") }
            when {
                m.directoryLoading && m.shows.isEmpty() -> item(key = "loading") { KitSkeletonRows(8, Modifier.testTag("podio:loading")) }
                m.error != null && m.shows.isEmpty() -> item(key = "error") { KitErrorState(m.error.orEmpty(), { m.request++ }, retryLabel = "Retry") }
                m.shows.isEmpty() -> item(key = "empty") { KitEmptyState("Nothing found", "Try another search, language or topic, or add a show by its RSS address.", icon = Icons.Outlined.Podcasts,
                    actionLabel = "Add by RSS address", onAction = { m.addFeed = true }) }
                else -> itemsIndexed(m.shows, key = { _, show -> show.id }) { index, show -> ShowRow(show, if (m.language.isBlank() && m.query.isBlank()) index + 1 else null) { m.open(show) } }
            }
            item(key = "credit") { MetaLine("Discovery: Apple Podcasts, fyyd and YouTube Music · Audio: publisher feeds or public YouTube episodes", Modifier.padding(vertical = 16.dp), small = true) }
        }
        PodioTopBar("Podcasts", { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) }, onSearch, env.onMusic, { bar = it },
            action = { BarButton(CosmoIcons.Plus, "Add by RSS address", { m.addFeed = true }) })
    }
}

// ---- A show ----------------------------------------------------------------------------------------------------------------

/**
 * A show (design system 6.3, Prism's podcast page and AntennaPod's feed): the small bar, the 120 dp cover beside the title, the
 * publisher and the source, then Latest episode (the one filled button), Follow and Refresh, and the episodes with All, Unplayed and
 * Downloaded chips. Older episodes load on demand at the end.
 */
@Composable internal fun ShowPage(m: PodioModel, env: PodioEnv) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val feed = m.opened
    val requested = m.requestedFeed
    var filter by remember(requested?.id) { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().testTag("podio:show")) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 56.dp + with(density) { androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp() }, bottom = env.bottom)) {
            if (feed == null) {
                if (m.error != null) item(key = "error") { KitErrorState(m.error.orEmpty(), { requested?.let(m::open) }, title = "Couldn't open this show", retryLabel = "Retry") }
                else item(key = "loading") { KitSkeletonRows(8, Modifier.testTag("podio:loading")) }
                return@LazyColumn
            }
            val show = feed.show
            val following = env.saved.shows.any { it.id == show.id }
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    KitArtwork(show.artwork.takeIf(String::isNotBlank), KitArt.SQUARE, 120.dp, radius = CosmoRadii.rMd)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(show.title.ifBlank { "Podcast" }, style = type.forText(type.headline, show.title),
                            color = Color(palette.textPrimary), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (show.author.isNotBlank()) Text(show.author, style = type.bodyText, color = Color(palette.textSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(PodioFormat.episodeMeta(show.source, episodeCount(feed.episodes.size)), style = type.meta,
                            color = Color(palette.textTertiary), maxLines = 1)
                    }
                }
            }
            item(key = "actions") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = CosmoSpacing.margin, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    feed.episodes.firstOrNull()?.let { latest -> KitButton("Latest episode", { m.play(InboxEpisode(latest, show).item()) }, icon = CosmoIcons.Play) }
                    KitButton(if (following) "Following" else "Follow", { m.toggleFollow(show) }, kind = KitButtonKind.TONAL, icon = if (following) CosmoIcons.Check else CosmoIcons.Plus)
                    KitButton("Refresh", { m.open(show) }, kind = KitButtonKind.TONAL, icon = CosmoIcons.Replay)
                }
            }
            if (m.error != null) item(key = "refresh-error") { PodioBanner(m.error.orEmpty(), "Retry", { m.open(show) }, danger = true) }
            if (feed.episodes.isEmpty()) { item(key = "none") { KitEmptyState("No episodes", "This feed contains no playable audio episodes.", icon = Icons.Outlined.Podcasts) }; return@LazyColumn }
            item(key = "episodes-title") { KitSectionHeader("Episodes", overline = episodeCount(feed.episodes.size)) }
            item(key = "episode-filters") { KitChipRow(listOf("All", "Unplayed", "Downloaded"), filter, { filter = it }, Modifier.padding(bottom = 4.dp)) }
            val shown = feed.episodes.filter { episode ->
                val id = episode.item().id
                when (filter) {
                    1 -> id !in env.saved.completedEpisodes && !PodioPlaybackPolicy.isFinished(env.saved.progress[id] ?: 0, episode.durationSeconds * 1000)
                    2 -> env.downloaded.any { it.id == id }
                    else -> true
                }
            }
            if (shown.isEmpty()) item(key = "filtered-none") { MetaLine(if (filter == 2) "No downloaded episodes of this show." else "Every episode here has been played.", Modifier.padding(vertical = 16.dp)) }
            items(shown, key = { it.id }) { episode -> EpisodeItemRow(m, env, InboxEpisode(episode, show), withShow = false) }
            if (feed.nextPage != null) item(key = "older") {
                Column(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 8.dp)) {
                    m.archiveError?.let { Text(it, color = Color(palette.danger), style = type.meta) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.archiveLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color(palette.textSecondary), strokeWidth = 2.dp)
                        KitTextButton(if (m.archiveLoading) "Loading older episodes…" else if (m.archiveError != null) "Retry older episodes" else "Load older episodes", m::moreEpisodes,
                            Modifier.testTag("podio:older-episodes"))
                    }
                }
            }
        }
        val threshold = with(density) { 140.dp.toPx() }
        PodioSmallBar(feed?.show?.title ?: requested?.title.orEmpty(), { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) }, m::browse)
    }
}

// ---- Episodes (slot 3) -----------------------------------------------------------------------------------------------------

/**
 * New from the followed shows (AntennaPod's inbox, design system 5.2 slot 3): what was started and not finished first ("Continue
 * listening"), then the newest episodes of every followed show, newest first. Refresh asks the feeds again.
 */
@Composable internal fun EpisodesPage(m: PodioModel, env: PodioEnv, onSearch: () -> Unit, onRefresh: () -> Unit) {
    val list = rememberLazyListState()
    var bar by rememberBarHeight()
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val started = m.inbox.filter { entry -> val id = entry.item().id
        PodioFormat.progressFraction(id in env.saved.completedEpisodes, env.saved.progress[id] ?: 0, entry.episode.durationSeconds * 1000) > 0f }
    val fresh = m.inbox.filter { it !in started }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("podio:episodes"), state = list, contentPadding = PaddingValues(top = bar, bottom = env.bottom)) {
            when {
                env.saved.shows.isEmpty() -> item(key = "empty") { KitEmptyState("No new episodes yet", "Follow a podcast to see its new episodes here.", icon = Icons.Outlined.Podcasts,
                    actionLabel = "Browse podcasts", onAction = { env.chooseSection(PodioSection.PODCASTS) }) }
                m.inboxLoading && m.inbox.isEmpty() -> item(key = "loading") { KitSkeletonRows(8, Modifier.testTag("podio:loading")) }
                m.inboxError != null && m.inbox.isEmpty() -> item(key = "error") { KitErrorState(m.inboxError.orEmpty(), onRefresh, retryLabel = "Retry") }
                else -> {
                    if (m.inboxError != null) item(key = "banner") { PodioBanner(m.inboxError.orEmpty(), "Retry", onRefresh, danger = true) }
                    if (started.isNotEmpty()) {
                        item(key = "continue") { KitSectionHeader("Continue listening") }
                        items(started, key = { "c-" + it.episode.id }) { EpisodeItemRow(m, env, it, withShow = true) }
                    }
                    item(key = "new") { KitSectionHeader("New episodes", overline = "${env.saved.shows.size} followed ${if (env.saved.shows.size == 1) "show" else "shows"}") }
                    if (fresh.isEmpty() && !m.inboxLoading) item(key = "none") { MetaLine("Nothing new from your shows.", Modifier.padding(vertical = 16.dp)) }
                    items(fresh, key = { "n-" + it.episode.id }) { EpisodeItemRow(m, env, it, withShow = true) }
                }
            }
        }
        PodioTopBar("Episodes", { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) }, onSearch, env.onMusic, { bar = it },
            action = { BarButton(CosmoIcons.Replay, "Refresh episodes", onRefresh, enabled = !m.inboxLoading) })
    }
}

// ---- Library (slot 4) ------------------------------------------------------------------------------------------------------

/**
 * Yours (design system 5.2 slot 4): favourite stations, followed shows and downloaded episodes, with All, Stations, Shows and
 * Downloaded chips, and the subscription tools (add by address, OPML import, paste, export and share).
 */
@Composable internal fun LibraryPage(m: PodioModel, env: PodioEnv, onSearch: () -> Unit, wide: Boolean) {
    var bar by rememberBarHeight()
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val scroll = rememberScrollState()
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    val filter = m.libraryFilter
    val collapse: () -> Float = { when (filter) {
        SavedFilter.ALL -> (scroll.value / threshold).coerceIn(0f, 1f)
        SavedFilter.SHOWS -> collapseFraction(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, threshold)
        else -> collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold)
    } }
    val chips: @Composable (PaddingValues) -> Unit = { padding ->
        KitChipRow(SavedFilter.entries.map { it.label }, filter.ordinal, { m.libraryFilter = SavedFilter.entries[it] }, Modifier.padding(bottom = 4.dp), contentPadding = padding)
    }
    val margin = PaddingValues(horizontal = CosmoSpacing.margin)
    val stations = env.saved.stations
    val shows = env.saved.shows
    Box(Modifier.fillMaxSize().testTag("podio:library")) {
        when (filter) {
            SavedFilter.ALL -> Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = bar, bottom = env.bottom)) {
                chips(margin)
                KitSectionHeader("Favourite stations", onSeeAll = if (stations.size > 5) ({ m.libraryFilter = SavedFilter.STATIONS }) else null)
                if (stations.isEmpty()) MetaLine("Save a station with the heart button.")
                stations.take(5).forEach { station -> StationRow(station, true, env.playback.id == station.item().id, { m.playStation(station) }, { m.toggleFavourite(station) }) }
                KitSectionHeader("Following", onSeeAll = if (shows.size > 4) ({ m.libraryFilter = SavedFilter.SHOWS }) else null)
                if (shows.isEmpty()) MetaLine("Follow a podcast to keep it here.")
                else LazyRow(contentPadding = PaddingValues(horizontal = CosmoSpacing.margin), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shows, key = { it.id }) { show -> KitCard(show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), { m.open(show) }) }
                }
                KitSectionHeader("Downloaded episodes", onSeeAll = if (env.downloaded.size > 4) ({ m.libraryFilter = SavedFilter.DOWNLOADED }) else null)
                if (env.downloaded.isEmpty()) MetaLine("Download an episode to listen here without a connection.")
                env.downloaded.take(4).forEach { episode -> DownloadedRow(m, env, episode) }
                SubscriptionTools(m, env)
            }
            SavedFilter.STATIONS -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = bar, bottom = env.bottom)) {
                item(key = "chips") { chips(margin) }
                if (stations.isEmpty()) item(key = "empty") { KitEmptyState("No favourite stations", "Save a station with the heart button.", icon = CosmoIcons.Heart) }
                items(stations, key = { "fav-" + it.id }) { station -> StationRow(station, true, env.playback.id == station.item().id, { m.playStation(station) }, { m.toggleFavourite(station) }) }
            }
            SavedFilter.SHOWS -> LazyVerticalGrid(GridCells.Adaptive(if (wide) 168.dp else 148.dp), Modifier.fillMaxSize(), state = grid,
                contentPadding = PaddingValues(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = bar, bottom = env.bottom),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "chips") { chips(PaddingValues(0.dp)) }
                if (shows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { KitEmptyState("Not following any shows", "Follow a podcast to keep it here.", icon = Icons.Outlined.Podcasts,
                    actionLabel = "Browse podcasts", onAction = { env.chooseSection(PodioSection.PODCASTS) }) }
                items(shows.size, key = { shows[it].id }) { index -> val show = shows[index]
                    KitCard(show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), { m.open(show) }, width = null) }
            }
            SavedFilter.DOWNLOADED -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = bar, bottom = env.bottom)) {
                item(key = "chips") { chips(margin) }
                item(key = "title") { KitSectionHeader("Downloaded episodes") }
                if (env.downloaded.isEmpty()) item(key = "empty") { KitEmptyState("No downloads", "Download an episode to listen here without a connection.", icon = CosmoIcons.Download) }
                items(env.downloaded, key = { it.id }) { episode -> DownloadedRow(m, env, episode) }
            }
        }
        PodioTopBar("Library", collapse, onSearch, env.onMusic, { bar = it })
    }
}

/** A downloaded episode: it plays from the saved file with the same identity and resume position. */
@Composable private fun DownloadedRow(m: PodioModel, env: PodioEnv, episode: PodioItem) {
    val resume = env.saved.progress[episode.id] ?: 0
    val state = when { episode.id in env.saved.completedEpisodes -> "Played"; resume > 0 -> "Resume at ${PodioFormat.clock(resume)}"; else -> "Downloaded" }
    EpisodeRow(episode.title, PodioFormat.episodeMeta(episode.author, PodioFormat.duration(episode.durationSeconds), state), episode.artwork, 0f,
        current = env.playback.id == episode.id, download = DownloadMark.NONE, onPlay = { m.play(episode) }, onDownload = {}, onMore = null)
}

/** The subscription tools, one hairline group: add by address, import (file or paste), export and share. */
@Composable private fun SubscriptionTools(m: PodioModel, env: PodioEnv) {
    val hasShows = env.saved.shows.isNotEmpty()
    KitGroup(title = "Subscriptions", modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)) {
        KitSettingsRow("Add by RSS address", icon = Icons.Outlined.Link, summary = "RSS, Spotify or YouTube Music link", onClick = { m.addFeed = true })
        KitDivider()
        KitSettingsRow("Import OPML", icon = Icons.Outlined.FileDownload, summary = "Existing follows and listening positions are kept",
            onClick = if (m.pickingFile) null else ({ m.importOpml { m.libraryFilter = SavedFilter.ALL } }))
        if (m.host.onImportOpmlFile != null) { KitDivider(); KitSettingsRow("Paste OPML", icon = Icons.Outlined.ContentPaste, onClick = { m.pasteOpml() }) }
        KitDivider()
        KitSettingsRow("Export OPML", icon = Icons.Outlined.FileUpload, summary = if (hasShows) "${env.saved.shows.size} followed shows" else "Follow a podcast first",
            onClick = if (hasShows && !m.pickingFile) ({ m.exportOpml() }) else null)
        if (m.host.onShareOpmlFile != null) { KitDivider(); KitSettingsRow("Share OPML", icon = Icons.Outlined.RssFeed, onClick = if (hasShows) ({ m.shareOpml() }) else null) }
    }
}

// ---- Search ----------------------------------------------------------------------------------------------------------------

/**
 * Podio's search (design system 5.9): the field, Stations or Shows, the region and language chips, then the results. Stations show
 * their count ("40 stations · India") and are what the globe draws; shows open their page. [compact] drops the page's own bar for a
 * pane (the wide radio layout keeps the globe beside it).
 */
@Composable internal fun SearchContent(m: PodioModel, env: PodioEnv, focus: FocusRequester, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val stations = !m.searchShows
    Column(modifier.fillMaxSize().testTag("podio:search")) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = CosmoSpacing.margin, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(CosmoIcons.ArrowLeft, "Close search", onClose)
            Spacer(Modifier.width(8.dp))
            CosmoSearchField(m.query, { m.query = it }, onSubmit = { m.browse(); m.request++ }, placeholder = if (stations) "Find a station" else "Find a podcast",
                modifier = Modifier.weight(1f), focusRequester = focus, height = 48.dp)
        }
        KitChipRow(listOf("Stations", "Shows"), if (stations) 0 else 1, { m.browse(); m.searchShows = it == 1 })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = CosmoSpacing.margin, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuChip("Region", m.country, REGIONS, { m.browse(); m.country = it })
            MenuChip("Language", m.language, LANGUAGES, { m.browse(); m.language = it })
            if (m.filtered) KitTextButton("Clear", m::clearFilters)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = env.bottom)) {
            if (stations) {
                if (!m.directoryLoading) item(key = "count") { MetaLine(m.stationCount(), Modifier.padding(vertical = 8.dp)) }
                when {
                    m.directoryLoading && m.radio.isEmpty() -> item(key = "loading") { KitSkeletonRows(6, Modifier.testTag("podio:loading")) }
                    m.error != null && m.radio.isEmpty() -> item(key = "error") { KitErrorState(m.error.orEmpty(), { m.request++ }, retryLabel = "Retry") }
                    m.radio.isEmpty() -> item(key = "empty") { KitEmptyState("No stations found", "Try another name, country or language.", icon = CosmoIcons.Radio) }
                    else -> items(m.radio.take(400), key = { it.id }) { station ->
                        StationRow(station, env.saved.stations.any { it.id == station.id }, env.playback.id == station.item().id, { m.playStation(station) }, { m.toggleFavourite(station) })
                    }
                }
                item(key = "credit") { MetaLine("Stations: Radio Browser · Imagery: NASA GIBS", Modifier.padding(vertical = 16.dp), small = true) }
            } else {
                if (m.showsTitle.isNotBlank() && m.shows.isNotEmpty()) item(key = "title") { KitSectionHeader(m.showsTitle) }
                when {
                    m.directoryLoading && m.shows.isEmpty() -> item(key = "loading") { KitSkeletonRows(6, Modifier.testTag("podio:loading")) }
                    m.error != null && m.shows.isEmpty() -> item(key = "error") { KitErrorState(m.error.orEmpty(), { m.request++ }, retryLabel = "Retry") }
                    m.shows.isEmpty() -> item(key = "empty") { KitEmptyState("Nothing found", "Try another search, language or topic, or add a show by its RSS address.", icon = Icons.Outlined.Podcasts) }
                    else -> items(m.shows, key = { it.id }) { show -> ShowRow(show, null) { m.open(show) } }
                }
            }
        }
    }
}

// ---- An episode's sheet ----------------------------------------------------------------------------------------------------

/**
 * An episode's details (design system 6.3 episode page, as a sheet): the 160 dp cover, the title, the show, date and length, Play or
 * "Resume · 31 min left" (filled) and Download (tonal), then the show notes as text.
 */
@Composable internal fun EpisodeSheet(m: PodioModel, env: PodioEnv, entry: InboxEpisode, onDismiss: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val item = entry.item()
    val episode = entry.episode
    val progress = env.saved.progress[item.id] ?: 0
    val completed = item.id in env.saved.completedEpisodes
    val resumable = PodioFormat.progressFraction(completed, progress, episode.durationSeconds * 1000) > 0f
    val notes = remember(episode.id) { PodioFormat.plainText(episode.description) }
    KitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = CosmoSpacing.margin).padding(bottom = 24.dp)
            .testTag("podio:episode-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                KitArtwork(item.artwork.takeIf(String::isNotBlank), KitArt.SQUARE, 112.dp, radius = CosmoRadii.rMd)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(episode.title, style = type.forText(type.titleLarge, episode.title),
                        color = Color(palette.textPrimary), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (episode.show.isNotBlank()) Text(episode.show, style = type.bodyText, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(episodeLine(episode, item, env), style = type.meta, color = Color(palette.textTertiary), maxLines = 2)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton(if (resumable) PodioFormat.episodeMeta("Resume", PodioFormat.remaining(progress, episode.durationSeconds * 1000)) else "Play",
                    { m.play(item); onDismiss() }, icon = CosmoIcons.Play)
                when (m.downloadMark(item, env.downloaded, env.downloadsRunning)) {
                    DownloadMark.AVAILABLE -> KitButton("Download", { m.host.onDownloadEpisode?.invoke(item) }, kind = KitButtonKind.TONAL, icon = CosmoIcons.Download)
                    DownloadMark.RUNNING -> KitButton("Cancel download", { m.host.onDownloadEpisode?.invoke(item) }, kind = KitButtonKind.TONAL, icon = CosmoIcons.Close)
                    DownloadMark.DONE -> KitButton("Downloaded", {}, kind = KitButtonKind.TONAL, icon = CosmoIcons.Downloaded, enabled = false)
                    DownloadMark.NONE -> {}
                }
                // The episode for everyone in the room, each device fetching it itself; hidden where rooms are not offered.
                RoomPlayButton(remember(item.id) { item.roomItem() })
            }
            if (notes.isNotBlank()) {
                Text("SHOW NOTES", style = type.overline, color = Color(palette.textSecondary))
                Text(notes, style = type.bodyText, color = Color(palette.textPrimary))
            }
        }
    }
}
