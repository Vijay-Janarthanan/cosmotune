package com.cosmotune.podio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.*
import com.cosmotune.ui.room.RoomPlayButton
import com.cosmotune.ui.room.roomItem

/** "1 episode", "12 episodes"; nothing for none. */
internal fun episodeCount(count: Int): String = when (count) { 0 -> ""; 1 -> "1 episode"; else -> "$count episodes" }

/** The episode's line under its title: (the show,) the date, the length, how much is left and the download state. */
internal fun episodeLine(episode: PodcastEpisode, item: PodioItem, env: PodioEnv, withShow: Boolean = false): String {
    val progress = env.saved.progress[item.id] ?: 0
    val downloaded = env.downloaded.any { it.id == item.id }
    return PodioFormat.episodeMeta(if (withShow) episode.show else "", PodioFormat.date(episode.published), PodioFormat.duration(episode.durationSeconds),
        PodioFormat.heard(item.id in env.saved.completedEpisodes, progress, episode.durationSeconds * 1000),
        if (downloaded) "Downloaded" else env.downloadStatus[item.id].orEmpty())
}

/** How much of an episode has been heard, 0..1, for the faint orbit round its disc. */
private fun heardFraction(env: PodioEnv, id: String, durationMs: Long): Float =
    PodioFormat.progressFraction(id in env.saved.completedEpisodes, env.saved.progress[id] ?: 0, durationMs)

@Composable private fun EpisodeItemRow(m: PodioModel, env: PodioEnv, entry: InboxEpisode, withShow: Boolean, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    val item = entry.item()
    val now = env.playback.id == item.id
    EpisodeRow(entry.episode.title, episodeLine(entry.episode, item, env, withShow), item.artwork, heardFraction(env, item.id, entry.episode.durationSeconds * 1000),
        now = now, playing = now && env.playback.playing, progress = if (now) ({ env.playedFraction() }) else null,
        download = m.downloadMark(item, env.downloaded, env.downloadsRunning),
        onPlay = { if (now) env.onToggle() else m.playEpisode(entry) }, onDownload = { m.host.onDownloadEpisode?.invoke(item) }, onMore = { m.episodeSheet = entry },
        fonts = fonts, look = look, modifier = modifier)
}

/** The started and not finished episodes of [entries] (what "Continue listening" lists), and the rest. */
private fun splitStarted(entries: List<InboxEpisode>, env: PodioEnv): Pair<List<InboxEpisode>, List<InboxEpisode>> =
    entries.partition { heardFraction(env, it.item().id, it.episode.durationSeconds * 1000) > 0f }

/** A followed show as a record: its cover as a disc, the title and the publisher; a tap opens it. */
@Composable private fun ShowRecord(show: PodcastShow, m: PodioModel, fonts: OrbitFonts, look: OrbitLook, size: Dp = 140.dp) {
    OrbitRecord(show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), fonts, look, onClick = { m.open(show) }, onPlay = null, size = size)
}

// ---- Podcasts ----------------------------------------------------------------------------------------------------------------

/**
 * The podcasts home (board section C): the heading with Search and Add by address, the region and language chips, what you were
 * listening to as rows, your shows as records, then the regional chart by genre as ranked rows (two columns when wide).
 */
@Composable internal fun PodcastsPage(m: PodioModel, env: PodioEnv, onSearch: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:podcasts")) {
        val wide = maxWidth >= 840.dp
        val side = orbitPageSide(maxWidth)
        val started = splitStarted(m.inbox, env).first.take(if (wide) 4 else 3)
        val ranked = m.language.isBlank() && m.query.isBlank()
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "head") {
                PodioHead("Podcasts", m.showsTitle.ifBlank { "Top charts" }, fonts, look, side, env.onMusic) {
                    OrbitPageButton(CosmoIcons.Search, "Search", onSearch)
                    OrbitPageButton(CosmoIcons.Plus, "Add by RSS address", { m.addFeed = true })
                }
            }
            item(key = "filters") { OrbitChipRow(side, Modifier.padding(top = 18.dp)) { FilterChips(m, fonts, look) } }
            if (started.isNotEmpty()) {
                item(key = "continue-title") { OrbitPageSection("Continue listening", Modifier.padding(horizontal = side + 4.dp).padding(top = 28.dp, bottom = 12.dp), subtitle = "Pick up where you stopped") }
                items(started, key = { "c-" + it.episode.id }) { EpisodeItemRow(m, env, it, withShow = true, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 8.dp)) }
            }
            if (env.saved.shows.isNotEmpty()) item(key = "yours") {
                Column {
                    OrbitPageSection("Your shows", Modifier.padding(horizontal = side + 4.dp).padding(top = 28.dp, bottom = 14.dp),
                        subtitle = "${env.saved.shows.size} followed", action = "All", onAction = { env.chooseSection(PodioSection.FOLLOWING) })
                    OrbitShelfRow(side + 4.dp, 18.dp) { items(env.saved.shows, key = { "show-" + it.id }) { show -> ShowRecord(show, m, fonts, look, if (wide) 150.dp else 128.dp) } }
                }
            }
            item(key = "genres") {
                OrbitChipRow(side, Modifier.padding(top = 30.dp)) {
                    OrbitSelectChip("Popular", m.genre.isBlank(), fonts, look, { m.browse(); m.genre = "" }, haptic = false)
                    PODCAST_GENRES.forEach { (id, name) -> OrbitSelectChip(name, m.genre == id, fonts, look, { m.browse(); m.genre = id }, haptic = false) }
                }
            }
            if (m.showsTitle.isNotBlank() && m.shows.isNotEmpty()) item(key = "title") {
                OrbitPageSection(m.showsTitle, Modifier.padding(horizontal = side + 4.dp).padding(top = 22.dp, bottom = 8.dp),
                    subtitle = if (ranked) "Top charts" else "${m.shows.size} shows")
            }
            when {
                m.directoryLoading && m.shows.isEmpty() -> item(key = "loading") { PodioSkeletonRows(6, look, side, Modifier.testTag("podio:loading")) }
                m.error != null && m.shows.isEmpty() -> item(key = "error") {
                    PodioMessage("Podcasts", "Couldn't reach the directory", m.error.orEmpty(), fonts, look, side, "Retry") { m.request++ }
                }
                m.shows.isEmpty() -> item(key = "empty") {
                    PodioMessage("Podcasts", "Nothing found", "Try another search, language or topic, or add a show by its RSS address.", fonts, look, side,
                        "Add by RSS address") { m.addFeed = true }
                }
                else -> {
                    val columns = if (wide) 2 else 1
                    val rows = m.shows.withIndex().chunked(columns)
                    items(rows.size, key = { "chart-" + rows[it].first().value.id }) { index ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            rows[index].forEach { (rank, show) ->
                                val open = { m.open(show) }
                                if (ranked) OrbitRankedRow(rank + 1, show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), fonts, look, open, Modifier.weight(1f))
                                else OrbitSongRow(show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), fonts, look, open, Modifier.weight(1f).padding(vertical = 4.dp))
                            }
                            if (rows[index].size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item(key = "credit") { PodioCredit("Discovery: Apple Podcasts, fyyd and YouTube Music · Audio: publisher feeds or public YouTube episodes", fonts, look, side) }
        }
    }
}

// ---- A show ----------------------------------------------------------------------------------------------------------------

/**
 * A show (board section C: the show page is the album page): the colour card in the show's cover colour with the cover as a disc,
 * "PODCAST · 212 EPISODES", the title and the publisher, Follow and Refresh, and play in the bite (the latest episode, or play and
 * pause while one of the show's episodes is loaded). Then All, Unplayed and Downloaded, and the episodes as rows with their length,
 * date and how much is heard, the playing one in peach. Older episodes load on demand at the end.
 */
@Composable internal fun ShowPage(m: PodioModel, env: PodioEnv) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val list = rememberLazyListState()
    val feed = m.opened
    val requested = m.requestedFeed
    val show = feed?.show ?: requested
    var filter by remember(requested?.id) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:show")) {
        val side = orbitPageSide(maxWidth)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "back") { PodioBackHead("Podcast", m::browse, fonts, look, side) }
            if (show != null) item(key = "card") {
                val following = env.saved.shows.any { it.id == show.id }
                val episodes = feed?.episodes.orEmpty()
                val loaded = episodes.firstOrNull { it.item().id == env.playback.id }
                val latest = episodes.firstOrNull()
                OrbitReleaseCard(
                    cover = show.artwork.takeIf(String::isNotBlank),
                    label = PodioFormat.episodeMeta("Podcast", episodeCount(episodes.size), show.source),
                    title = show.title.ifBlank { "Podcast" },
                    credit = show.author.ifBlank { null },
                    onCredit = null,
                    playing = loaded != null && env.playback.playing,
                    onPlay = when { loaded != null -> env.onToggle; latest != null -> ({ m.playEpisode(InboxEpisode(latest, show)) }); else -> null },
                    modifier = Modifier.padding(horizontal = side).padding(top = 14.dp).fillMaxWidth(),
                    below = { colors ->
                        val line = if (loaded != null) "Playing: ${loaded.title}" else latest?.let { "Latest: ${it.title}" }
                        if (line != null) Text(line, style = fonts.body, color = colors.ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    },
                ) { colors ->
                    if (feed != null) OrbitCardChip(if (following) "Following" else "Follow", colors, { m.toggleFollow(feed.show) }, icon = if (following) CosmoIcons.Check else CosmoIcons.Plus, solid = !following)
                    OrbitCardButton(CosmoIcons.Replay, "Refresh", colors, { m.open(show) })
                }
            }
            if (feed == null) {
                if (m.error != null) item(key = "error") {
                    PodioMessage("Podcast", "Couldn't open this show", m.error.orEmpty(), fonts, look, side, "Retry") { requested?.let(m::open) }
                } else item(key = "loading") { PodioSkeletonRows(6, look, side, Modifier.testTag("podio:loading")) }
                return@LazyColumn
            }
            if (m.error != null) item(key = "refresh-error") { PodioBanner(m.error.orEmpty(), "Retry", { m.open(feed.show) }, danger = true, look, fonts) }
            if (feed.episodes.isEmpty()) {
                item(key = "none") { PodioMessage("Episodes", "No episodes", "This feed contains no playable audio episodes.", fonts, look, side) }
                return@LazyColumn
            }
            item(key = "episodes-title") {
                OrbitPageSection("Episodes", Modifier.padding(horizontal = side + 4.dp).padding(top = 28.dp, bottom = 12.dp), subtitle = episodeCount(feed.episodes.size))
            }
            item(key = "episode-filters") {
                OrbitChipRow(side, Modifier.padding(bottom = 12.dp)) {
                    listOf("All", "Unplayed", "Downloaded").forEachIndexed { index, name -> OrbitSelectChip(name, filter == index, fonts, look, { filter = index }, haptic = false) }
                }
            }
            val shown = feed.episodes.filter { episode ->
                val id = episode.item().id
                when (filter) {
                    1 -> id !in env.saved.completedEpisodes && !PodioPlaybackPolicy.isFinished(env.saved.progress[id] ?: 0, episode.durationSeconds * 1000)
                    2 -> env.downloaded.any { it.id == id }
                    else -> true
                }
            }
            if (shown.isEmpty()) item(key = "filtered-none") {
                Text(if (filter == 2) "No downloaded episodes of this show." else "Every episode here has been played.", style = fonts.body, color = look.dim,
                    modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 16.dp))
            }
            items(shown, key = { it.id }) { episode -> EpisodeItemRow(m, env, InboxEpisode(episode, feed.show), withShow = false, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 8.dp)) }
            if (feed.nextPage != null) item(key = "older") {
                Column(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 8.dp)) {
                    m.archiveError?.let { Text(it, color = look.accent, style = fonts.body, modifier = Modifier.padding(horizontal = 4.dp)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.archiveLoading) CircularProgressIndicator(Modifier.size(18.dp), color = look.dim, strokeWidth = 2.dp)
                        PodioTextAction(if (m.archiveLoading) "Loading older episodes…" else if (m.archiveError != null) "Retry older episodes" else "Load older episodes", look, fonts,
                            m::moreEpisodes, Modifier.testTag("podio:older-episodes"))
                    }
                }
            }
        }
    }
}

// ---- Following ---------------------------------------------------------------------------------------------------------------

/**
 * Following (board section C): what was started and not finished first, then the newest episodes of every followed show, your shows
 * as records, your stations as discs, and the subscription tools (add by address, OPML import, paste, export and share).
 */
@Composable internal fun FollowingPage(m: PodioModel, env: PodioEnv, onRefresh: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val list = rememberLazyListState()
    var allNew by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:following")) {
        val wide = maxWidth >= 840.dp
        val side = orbitPageSide(maxWidth)
        val shows = env.saved.shows
        val stations = env.saved.stations
        val (started, fresh) = splitStarted(m.inbox, env)
        val label = PodioFormat.episodeMeta(if (shows.isEmpty()) "" else "${shows.size} ${if (shows.size == 1) "show" else "shows"}",
            if (stations.isEmpty()) "" else "${stations.size} ${if (stations.size == 1) "station" else "stations"}").ifBlank { "Shows and stations" }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "head") {
                PodioHead("Following", label, fonts, look, side, env.onMusic) {
                    if (shows.isNotEmpty()) OrbitPageButton(CosmoIcons.Replay, "Refresh episodes", { if (!m.inboxLoading) onRefresh() })
                }
            }
            if (shows.isEmpty() && stations.isEmpty()) item(key = "empty") {
                PodioMessage("Following", "Nothing followed yet", "Follow a podcast to see its new episodes here, or save a station with its heart.", fonts, look, side,
                    "Browse podcasts") { env.chooseSection(PodioSection.PODCASTS) }
            }
            if (shows.isNotEmpty()) {
                when {
                    m.inboxLoading && m.inbox.isEmpty() -> item(key = "loading") { PodioSkeletonRows(4, look, side, Modifier.testTag("podio:loading")) }
                    m.inboxError != null && m.inbox.isEmpty() -> item(key = "error") {
                        PodioMessage("New episodes", "Couldn't refresh your shows", m.inboxError.orEmpty(), fonts, look, side, "Retry", onRefresh)
                    }
                    else -> {
                        if (m.inboxError != null) item(key = "banner") { PodioBanner(m.inboxError.orEmpty(), "Retry", onRefresh, danger = true, look, fonts) }
                        if (started.isNotEmpty()) {
                            item(key = "continue") { OrbitPageSection("Continue listening", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 12.dp), subtitle = "Pick up where you stopped") }
                            items(started, key = { "c-" + it.episode.id }) { EpisodeItemRow(m, env, it, withShow = true, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 8.dp)) }
                        }
                        item(key = "new") {
                            OrbitPageSection("New episodes", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 12.dp),
                                subtitle = "From ${shows.size} followed ${if (shows.size == 1) "show" else "shows"}")
                        }
                        if (fresh.isEmpty() && !m.inboxLoading) item(key = "none") {
                            Text("Nothing new from your shows.", style = fonts.body, color = look.dim, modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 8.dp))
                        }
                        val visible = if (allNew) fresh else fresh.take(8)
                        items(visible, key = { "n-" + it.episode.id }) { EpisodeItemRow(m, env, it, withShow = true, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 8.dp)) }
                        if (fresh.size > 8) item(key = "all-new") { OrbitShowAll(allNew, fresh.size, fonts, look, { allNew = !allNew }, Modifier.padding(horizontal = side)) }
                    }
                }
                item(key = "shows") {
                    Column {
                        OrbitPageSection("Your shows", Modifier.padding(horizontal = side + 4.dp).padding(top = 28.dp, bottom = 14.dp), subtitle = "${shows.size} followed")
                        OrbitShelfRow(side + 4.dp, 18.dp) { items(shows, key = { "show-" + it.id }) { show -> ShowRecord(show, m, fonts, look, if (wide) 150.dp else 128.dp) } }
                    }
                }
            }
            if (stations.isNotEmpty()) item(key = "stations") {
                Column {
                    OrbitPageSection("Your stations", Modifier.padding(horizontal = side + 4.dp).padding(top = 28.dp, bottom = 12.dp), subtitle = "${stations.size} saved")
                    OrbitShelfRow(side, 8.dp) { items(stations, key = { "fav-" + it.id }) { station -> StationTile(station, env, m, fonts, look, if (wide) 104.dp else 88.dp) } }
                }
            }
            item(key = "tools") { SubscriptionTools(m, env, fonts, look, side) }
        }
    }
}

/** The subscription tools as chips: add by address, import (file or paste), export and share. */
@Composable private fun SubscriptionTools(m: PodioModel, env: PodioEnv, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    val hasShows = env.saved.shows.isNotEmpty()
    var editingPlayback by remember { mutableStateOf(false) }
    Column {
        OrbitPageSection("Subscriptions", Modifier.padding(horizontal = side + 4.dp).padding(top = 30.dp, bottom = 12.dp),
            subtitle = if (hasShows) "OPML keeps your follows and listening positions" else "Bring your shows from another podcast app")
        if (hasShows) {
            Text("Latest episodes per show in Following", style = fonts.body, color = look.dim,
                modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 8.dp))
            OrbitChipRow(side) {
                for (limit in listOf(6, 12, 24)) OrbitSelectChip("$limit episodes", env.saved.inboxEpisodesPerShow == limit,
                    fonts, look, { m.setInboxEpisodeLimit(limit) }, haptic = false)
            }
        }
        OrbitChipRow(side) {
            if (hasShows) OrbitSelectChip("Podcast playback", false, fonts, look, { editingPlayback = true }, haptic = false)
            OrbitSelectChip("Add by RSS address", false, fonts, look, { m.addFeed = true }, icon = Icons.Outlined.Link, haptic = false)
            OrbitSelectChip("Import OPML", false, fonts, look, { if (!m.pickingFile) m.importOpml { } }, icon = Icons.Outlined.FileDownload, haptic = false)
            if (m.host.onImportOpmlFile != null) OrbitSelectChip("Paste OPML", false, fonts, look, { m.pasteOpml() }, icon = Icons.Outlined.ContentPaste, haptic = false)
            OrbitSelectChip("Export OPML", false, fonts, look, { if (hasShows && !m.pickingFile) m.exportOpml() }, Modifier.alpha(if (hasShows) 1f else 0.45f),
                icon = Icons.Outlined.FileUpload, haptic = false)
            if (m.host.onShareOpmlFile != null) OrbitSelectChip("Share OPML", false, fonts, look, { if (hasShows) m.shareOpml() }, Modifier.alpha(if (hasShows) 1f else 0.45f),
                icon = Icons.Outlined.RssFeed, haptic = false)
        }
    }
    if (editingPlayback) {
        var selected by remember { mutableStateOf(emptySet<String>()) }
        var choice by remember { mutableStateOf<Boolean?>(true) }
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { editingPlayback = false }, title = "Podcast playback", scroll = false,
            content = {
                Column {
                    OrbitChipRow(0.dp) {
                        OrbitSelectChip("Skip silence", choice == true, fonts, look, { choice = true }, haptic = false)
                        OrbitSelectChip("Keep silence", choice == false, fonts, look, { choice = false }, haptic = false)
                        OrbitSelectChip("App default", choice == null, fonts, look, { choice = null }, haptic = false)
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(env.saved.shows, key = { it.id }) { show ->
                            OrbitSwitchRow(show.title, show.id in selected, { checked ->
                                selected = if (checked) selected + show.id else selected - show.id
                            }, detail = when (env.saved.skipSilenceByShow[show.id]) {
                                true -> "Skip silence"; false -> "Keep silence"; null -> "App default"
                            })
                        }
                    }
                }
            },
            confirmLabel = "Apply", confirmEnabled = selected.isNotEmpty(), onConfirm = {
                m.setShowSkipSilence(selected, choice); editingPlayback = false
            }, dismissLabel = "Cancel", onDismiss = { editingPlayback = false },
        )
    }
}

// ---- Downloads ---------------------------------------------------------------------------------------------------------------

/** Downloads (board section C): the downloaded episodes as rows; each plays from the saved file with the same identity and place. */
@Composable internal fun DownloadsPage(m: PodioModel, env: PodioEnv) {
    val fonts = orbitFonts()
    val look = orbitLook()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:downloads")) {
        val side = orbitPageSide(maxWidth)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "head") { PodioHead("Downloads", episodeCount(env.downloaded.size).ifBlank { "On this device" }, fonts, look, side, env.onMusic) }
            if (env.downloaded.isEmpty()) item(key = "empty") {
                PodioMessage("Downloads", "No downloads", "Download an episode to listen here without a connection.", fonts, look, side, "Browse podcasts") { env.chooseSection(PodioSection.PODCASTS) }
            } else item(key = "title") {
                OrbitPageSection("Downloaded episodes", Modifier.padding(horizontal = side + 4.dp).padding(top = 26.dp, bottom = 12.dp), subtitle = "Play without a connection")
            }
            items(env.downloaded, key = { it.id }) { episode -> DownloadedRow(m, env, episode, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 8.dp)) }
        }
    }
}

/** A downloaded episode: it plays from the saved file with the same identity and resume position. */
@Composable private fun DownloadedRow(m: PodioModel, env: PodioEnv, episode: PodioItem, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val resume = env.saved.progress[episode.id] ?: 0
    val completed = episode.id in env.saved.completedEpisodes
    val state = PodioFormat.heard(completed, resume, episode.durationSeconds * 1000).ifBlank { "Downloaded" }
    val now = env.playback.id == episode.id
    EpisodeRow(episode.title, PodioFormat.episodeMeta(episode.author, PodioFormat.duration(episode.durationSeconds), state), episode.artwork,
        heardFraction(env, episode.id, episode.durationSeconds * 1000), now = now, playing = now && env.playback.playing,
        progress = if (now) ({ env.playedFraction() }) else null, download = DownloadMark.NONE,
        onPlay = { if (now) env.onToggle() else m.play(episode) }, onDownload = {}, onMore = null, fonts = fonts, look = look, modifier = modifier)
}

// ---- Search ----------------------------------------------------------------------------------------------------------------

/**
 * Podio's search: back and the search pill, Stations or Shows, the region and language chips, then the results. Stations show their
 * count ("40 stations · India") and are what the globe draws; shows open their page.
 */
@Composable internal fun SearchContent(m: PodioModel, env: PodioEnv, focus: FocusRequester, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val stations = !m.searchShows
    BoxWithConstraints(modifier.fillMaxSize().testTag("podio:search")) {
        val side = orbitPageSide(maxWidth)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = side, end = side, top = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrbitPageButton(CosmoIcons.ChevronLeft, "Close search", onClose)
                OrbitSearchPill(m.query, { m.query = it }, { m.browse(); m.request++ }, if (stations) "Find a station" else "Find a podcast", fonts, look, focus, Modifier.weight(1f))
            }
            OrbitChipRow(side, Modifier.padding(top = 14.dp)) {
                OrbitSelectChip("Stations", stations, fonts, look, { m.browse(); m.searchShows = false }, icon = CosmoIcons.Radio, haptic = false)
                OrbitSelectChip("Shows", !stations, fonts, look, { m.browse(); m.searchShows = true }, icon = Icons.Outlined.Podcasts, haptic = false)
                FilterChips(m, fonts, look)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = env.bottom + 16.dp)) {
                if (stations) {
                    if (!m.directoryLoading) item(key = "count") {
                        Text(m.stationCount(), style = fonts.body, color = look.dim, modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 10.dp))
                    }
                    when {
                        m.directoryLoading && m.radio.isEmpty() -> item(key = "loading") { PodioSkeletonRows(5, look, side, Modifier.testTag("podio:loading")) }
                        m.error != null && m.radio.isEmpty() -> item(key = "error") {
                            PodioMessage("Radio Browser", "Couldn't reach the stations", m.error.orEmpty(), fonts, look, side, "Retry") { m.request++ }
                        }
                        m.radio.isEmpty() -> item(key = "empty") { PodioMessage("Stations", "No stations found", "Try another name, country or language.", fonts, look, side) }
                        else -> items(m.radio.take(400), key = { it.id }) { station ->
                            val current = env.playback.id == station.item().id
                            StationRow(station, env.saved.stations.any { it.id == station.id }, current, current && env.playback.playing, { m.playStation(station) },
                                { m.toggleFavourite(station) }, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 8.dp))
                        }
                    }
                    item(key = "credit") { PodioCredit("Stations: Radio Browser · Imagery: NASA GIBS", fonts, look, side) }
                } else {
                    if (m.showsTitle.isNotBlank() && m.shows.isNotEmpty()) item(key = "title") {
                        OrbitPageSection(m.showsTitle, Modifier.padding(horizontal = side + 4.dp).padding(top = 10.dp, bottom = 10.dp))
                    }
                    when {
                        m.directoryLoading && m.shows.isEmpty() -> item(key = "loading") { PodioSkeletonRows(5, look, side, Modifier.testTag("podio:loading")) }
                        m.error != null && m.shows.isEmpty() -> item(key = "error") {
                            PodioMessage("Podcasts", "Couldn't reach the directory", m.error.orEmpty(), fonts, look, side, "Retry") { m.request++ }
                        }
                        m.shows.isEmpty() -> item(key = "empty") {
                            PodioMessage("Podcasts", "Nothing found", "Try another search, language or topic, or add a show by its RSS address.", fonts, look, side)
                        }
                        else -> items(m.shows, key = { it.id }) { show ->
                            OrbitSongRow(show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), fonts, look, { m.open(show) },
                                Modifier.fillMaxWidth().padding(horizontal = side).padding(bottom = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---- An episode's sheet ----------------------------------------------------------------------------------------------------

/**
 * An episode's page as a sheet: the colour card in its cover's colour with the cover as a disc, "EPISODE · 12 OCT · 48 MIN", the
 * title and the show, play (or resume) in the bite; then Download and the room as chips, and the show notes as text.
 */
@Composable internal fun EpisodeSheet(m: PodioModel, env: PodioEnv, entry: InboxEpisode, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val item = entry.item()
    val episode = entry.episode
    val progress = env.saved.progress[item.id] ?: 0
    val completed = item.id in env.saved.completedEpisodes
    val resumable = PodioFormat.progressFraction(completed, progress, episode.durationSeconds * 1000) > 0f
    val now = env.playback.id == item.id
    val notes = remember(episode.id) { PodioFormat.plainText(episode.description) }
    val art = item.artwork.takeIf(String::isNotBlank)
    val (fill, ink) = coverFill(art, episode.title)
    KitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
            .testTag("podio:episode-sheet"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OrbitBiteCard(fill, Modifier.fillMaxWidth(), bite = {
                OrbitPlayButton(now && env.playback.playing, { if (now) env.onToggle() else { m.playEpisode(entry); onDismiss() } }, look)
            }) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 22.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val heard = heardFraction(env, item.id, episode.durationSeconds * 1000)
                        Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
                            Disc(art, 96.dp)
                            if (heard > 0f) OrbitRing(108.dp, ink.copy(alpha = 0.8f), progress = { heard }, spinning = false, stroke = 1.5.dp, moon = 3.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            OrbitLabel(PodioFormat.episodeMeta("Episode", PodioFormat.date(episode.published), PodioFormat.duration(episode.durationSeconds)), fonts, ink.copy(alpha = 0.75f))
                            if (episode.show.isNotBlank()) Text(episode.show, style = fonts.body.copy(fontSize = 14.sp), color = ink.copy(alpha = 0.85f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Text(episode.title, style = fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 26.sp), color = ink, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 16.dp, end = 70.dp))
                    val state = when { now -> "Playing now"; resumable -> PodioFormat.episodeMeta("Resume", PodioFormat.remaining(progress, episode.durationSeconds * 1000)); completed -> "Played"; else -> "" }
                    if (state.isNotBlank()) Text(state, style = fonts.body, color = ink.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp, end = 70.dp))
                }
            }
            OrbitChipRow(0.dp) {
                when (m.downloadMark(item, env.downloaded, env.downloadsRunning)) {
                    DownloadMark.AVAILABLE -> OrbitSelectChip("Download", false, fonts, look, { m.host.onDownloadEpisode?.invoke(item) }, icon = CosmoIcons.Download, haptic = false)
                    DownloadMark.RUNNING -> OrbitSelectChip("Cancel download", false, fonts, look, { m.host.onDownloadEpisode?.invoke(item) }, icon = CosmoIcons.Close, haptic = false)
                    DownloadMark.DONE -> OrbitSelectChip("Downloaded", true, fonts, look, {}, icon = CosmoIcons.Downloaded, haptic = false)
                    DownloadMark.NONE -> {}
                }
                // The episode for everyone in the room, each device fetching it itself; hidden where rooms are not offered.
                RoomPlayButton(remember(item.id) { item.roomItem() })
            }
            if (notes.isNotBlank()) {
                OrbitLabel("Show notes", fonts, look.dim, Modifier.padding(top = 6.dp))
                Text(notes, style = fonts.body.copy(fontSize = 14.sp, lineHeight = 21.sp), color = look.ink)
            }
        }
    }
}
