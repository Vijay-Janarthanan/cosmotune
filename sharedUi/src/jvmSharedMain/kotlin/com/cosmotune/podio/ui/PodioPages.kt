package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
internal fun heardFraction(env: PodioEnv, id: String, durationMs: Long): Float =
    PodioFormat.progressFraction(id in env.saved.completedEpisodes, env.saved.progress[id] ?: 0, durationMs)

/** What a swipe to the right does on an episode row: line it up to play next (a show, the home), or put it away as seen (Following). */
internal enum class EpisodeSwipe { PLAY_NEXT, SEEN, NONE }

@Composable internal fun EpisodeItemRow(m: PodioModel, env: PodioEnv, entry: InboxEpisode, withShow: Boolean, fonts: OrbitFonts, look: OrbitLook,
    modifier: Modifier = Modifier, fresh: Boolean = false, swipe: EpisodeSwipe = EpisodeSwipe.PLAY_NEXT) {
    val item = entry.item()
    val now = env.playback.id == item.id
    PodioSwipe(modifier.testTag("podio:episode:${entry.episode.id}"),
        startLabel = if (swipe == EpisodeSwipe.SEEN) "Seen" else "Play next",
        onStart = when { now -> null; swipe == EpisodeSwipe.SEEN -> ({ m.markSeen(listOf(item.id)) }); swipe == EpisodeSwipe.PLAY_NEXT -> ({ m.playNext(entry) }); else -> null }) {
        EpisodeRow(entry.episode.title, episodeLine(entry.episode, item, env, withShow), item.artwork, heardFraction(env, item.id, entry.episode.durationSeconds * 1000),
            now = now, playing = now && env.playback.playing, progress = if (now) ({ env.playedFraction() }) else null,
            download = m.downloadMark(item, env.downloaded, env.downloadsRunning),
            onPlay = { if (now) env.onToggle() else m.playEpisode(entry) }, onDownload = { m.host.onDownloadEpisode?.invoke(item) }, onMore = { m.episodeSheet = entry },
            fonts = fonts, look = look, fresh = fresh, onToggle = env.onToggle)
    }
}

/** The started and not finished episodes of [entries] (what "Continue listening" lists), and the rest. */
private fun splitStarted(entries: List<InboxEpisode>, env: PodioEnv): Pair<List<InboxEpisode>, List<InboxEpisode>> =
    entries.partition { heardFraction(env, it.item().id, it.episode.durationSeconds * 1000) > 0f }

/** The new episodes of the followed shows: in Following's list and neither heard, started nor seen. */
internal fun newEpisodes(m: PodioModel, env: PodioEnv): List<InboxEpisode> = m.inbox.filter { env.isNew(it.item().id) }

/** A followed show as a record: its cover as a disc, the title and the publisher; a tap opens it. [count] new episodes in peach. */
@Composable private fun ShowRecord(show: PodcastShow, m: PodioModel, fonts: OrbitFonts, look: OrbitLook, size: Dp = 96.dp, count: Int = 0, subtitle: String = show.author,
    onClick: () -> Unit = { m.open(show) }) {
    Box {
        OrbitRecord(show.title.ifBlank { "Podcast" }, subtitle, show.artwork.takeIf(String::isNotBlank), fonts, look, onClick = onClick, onPlay = null, size = size)
        if (count > 0) PodioCount(count, look, fonts, Modifier.align(Alignment.TopEnd))
    }
}

/** A count in peach on a cover's corner (new episodes of a show). */
@Composable internal fun PodioCount(count: Int, look: OrbitLook, fonts: OrbitFonts, modifier: Modifier = Modifier) {
    Text(if (count > 99) "99+" else count.toString(), style = fonts.label.copy(fontSize = 11.sp), color = if (look.dark) androidx.compose.ui.graphics.Color(0xFF2A100A) else look.ground,
        maxLines = 1, modifier = modifier.clip(CircleShape).background(look.accent).padding(horizontal = 7.dp, vertical = 2.dp))
}

// ---- Podcasts ----------------------------------------------------------------------------------------------------------------

/**
 * The podcasts home (board frame 1): the heading with Search and Add by address; the episode you are in the middle of as the one
 * colour card; New episodes with their count and All ›; your shows as small discs; then Discover with its own chips (genres,
 * region, language) over the chart as ranked rows (two columns when wide, ten and Show all).
 */
@Composable internal fun PodcastsPage(m: PodioModel, env: PodioEnv, onSearch: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val list = rememberLazyListState()
    var allChart by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:podcasts")) {
        val wide = maxWidth >= 840.dp
        val side = orbitPageSide(maxWidth)
        val started = splitStarted(m.inbox, env).first
        val fresh = newEpisodes(m, env)
        val ranked = m.language.isBlank() && m.query.isBlank()
        val label = PodioFormat.episodeMeta(env.saved.shows.size.takeIf { it > 0 }?.let { if (it == 1) "1 show" else "$it shows" }.orEmpty(),
            fresh.size.takeIf { it > 0 }?.let { if (it == 1) "1 new episode" else "$it new episodes" }.orEmpty()).ifBlank { m.showsTitle.ifBlank { "Top charts" } }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "head") {
                PodioHead("Podcasts", label, fonts, look, side, env.onMusic) {
                    OrbitPageButton(CosmoIcons.Search, "Search", onSearch)
                    OrbitPageButton(CosmoIcons.Plus, "Add by RSS address", { m.addFeed = true })
                }
            }
            started.firstOrNull()?.let { entry -> item(key = "continue") { ContinueCard(m, env, entry, Modifier.padding(horizontal = side).padding(top = 20.dp)) } }
            if (fresh.isNotEmpty()) {
                item(key = "new-title") {
                    OrbitPageSection("New episodes", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 10.dp),
                        subtitle = fresh.size.toString(), action = "All ›", onAction = { env.chooseSection(PodioSection.FOLLOWING) })
                }
                items(fresh.take(if (wide) 4 else 3), key = { "n-" + it.episode.id }) {
                    EpisodeItemRow(m, env, it, withShow = true, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 6.dp), fresh = true)
                }
            }
            if (env.saved.shows.isNotEmpty()) item(key = "yours") {
                Column {
                    OrbitPageSection("Your shows", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 12.dp),
                        subtitle = "${env.saved.shows.size} followed", action = "All ›", onAction = { env.chooseSection(PodioSection.FOLLOWING) })
                    OrbitShelfRow(side + 4.dp, 16.dp) { items(env.saved.shows, key = { "show-" + it.id }) { show ->
                        ShowRecord(show, m, fonts, look, if (wide) 112.dp else 88.dp, count = fresh.count { it.show.id == show.id }, subtitle = "")
                    } }
                }
            }
            item(key = "discover") {
                Column {
                    OrbitPageSection("Discover", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 10.dp),
                        subtitle = if (m.showsTitle.isNotBlank() && m.shows.isNotEmpty()) m.showsTitle else if (ranked) "Top charts" else "")
                    OrbitChipRow(side) {
                        OrbitSelectChip("Top shows", m.genre.isBlank(), fonts, look, { m.browse(); m.genre = "" }, haptic = false)
                        PODCAST_GENRES.forEach { (id, name) -> OrbitSelectChip(name, m.genre == id, fonts, look, { m.browse(); m.genre = id }, haptic = false) }
                    }
                    OrbitChipRow(side, Modifier.padding(top = 8.dp, bottom = 6.dp)) { FilterChips(m, fonts, look) }
                }
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
                    val shown = if (allChart) m.shows else m.shows.take(10)
                    val rows = shown.withIndex().chunked(columns)
                    items(rows.size, key = { "chart-" + rows[it].first().value.id }) { index ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            rows[index].forEach { (rank, show) ->
                                val open = { m.open(show) }
                                if (ranked) OrbitRankedRow(rank + 1, show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), fonts, look, open, Modifier.weight(1f))
                                else OrbitSongRow(show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), fonts, look, open, Modifier.weight(1f).padding(vertical = 4.dp), height = 60.dp)
                            }
                            if (rows[index].size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (m.shows.size > 10) item(key = "chart-all") { OrbitShowAll(allChart, m.shows.size, fonts, look, { allChart = !allChart }, Modifier.padding(horizontal = side)) }
                }
            }
            item(key = "credit") { PodioCredit("Discovery: Apple Podcasts, fyyd and YouTube Music · Audio: publisher feeds or public YouTube episodes", fonts, look, side) }
        }
    }
}

/** The episode you were in the middle of, as the home's one colour card: its disc and how far you are, the show, play in the bite. */
@Composable private fun ContinueCard(m: PodioModel, env: PodioEnv, entry: InboxEpisode, modifier: Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val item = entry.item()
    val art = item.artwork.takeIf(String::isNotBlank)
    val (fill, ink) = coverFill(art, entry.episode.title)
    val now = env.playback.id == item.id
    val left = PodioFormat.remaining(env.saved.progress[item.id] ?: 0, entry.episode.durationSeconds * 1000)
    OrbitBiteCard(fill, modifier.fillMaxWidth().testTag("podio:continue"), onClick = { m.episodeSheet = entry }, bite = {
        OrbitPlayButton(now && env.playback.playing, { if (now) env.onToggle() else m.playEpisode(entry) }, look)
    }) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 18.dp, end = 84.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                Disc(art, 70.dp)
                OrbitRing(80.dp, ink.copy(alpha = 0.8f), progress = { heardFraction(env, item.id, entry.episode.durationSeconds * 1000) }, spinning = false, stroke = 1.5.dp, moon = 3.dp)
            }
            Column(Modifier.weight(1f)) {
                OrbitLabel(PodioFormat.episodeMeta("Continue", left), fonts, ink.copy(alpha = 0.75f))
                Text(entry.episode.title, style = fonts.heroTitle.copy(fontSize = 20.sp, lineHeight = 23.sp), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
                Text(entry.show.title.ifBlank { entry.episode.show }, style = fonts.body, color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---- A show ----------------------------------------------------------------------------------------------------------------

/**
 * A show (board frame 2): the compact colour card (the cover beside the title, "PODCAST · 48 EPISODES", the publisher, Follow,
 * Download and More, play in the bite), then "Episodes · 48 · 6 new" with All, Unplayed and Downloaded and the order, and the
 * episodes as rows with one button each, so several show on opening. Older episodes load on demand at the end.
 */
@Composable internal fun ShowPage(m: PodioModel, env: PodioEnv) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val list = rememberLazyListState()
    val feed = m.opened
    val requested = m.requestedFeed
    val show = feed?.show ?: requested
    var filter by remember(requested?.id) { mutableIntStateOf(0) }
    var oldestFirst by remember(requested?.id) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:show")) {
        val side = orbitPageSide(maxWidth)
        val episodes = feed?.episodes.orEmpty()
        val unplayed = episodes.filter { !env.isPlayed(it.item().id, it.durationSeconds * 1000) }
        val inboxIds = remember(m.inbox) { m.inbox.map { it.episode.id }.toSet() }
        val following = show != null && env.saved.shows.any { it.id == show.id }
        val fresh = unplayed.filter { following && it.id in inboxIds && env.isNew(it.item().id) }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "back") { PodioBackHead("Podcast", m::browse, fonts, look, side) }
            if (show != null) item(key = "card") {
                val loaded = episodes.firstOrNull { it.item().id == env.playback.id }
                val latest = unplayed.firstOrNull() ?: episodes.firstOrNull()
                OrbitCoverTitleCard(
                    cover = show.artwork.takeIf(String::isNotBlank), colourUrl = show.artwork.takeIf(String::isNotBlank), key = show.id,
                    label = PodioFormat.episodeMeta("Podcast", episodeCount(episodes.size)),
                    title = show.title.ifBlank { "Podcast" },
                    line = when { loaded != null -> "Playing: ${loaded.title}"; else -> show.author.ifBlank { null } },
                    onContinue = when { loaded != null -> env.onToggle; latest != null -> ({ m.playEpisode(InboxEpisode(latest, show)) }); else -> null },
                    modifier = Modifier.padding(horizontal = side).padding(top = 14.dp).fillMaxWidth().testTag("podio:show-card"),
                ) { colors ->
                    if (feed != null) OrbitCardChip(if (following) "Following" else "Follow", colors, { m.toggleFollow(feed.show) },
                        icon = if (following) CosmoIcons.Check else CosmoIcons.Plus, solid = !following)
                    val toDownload = fresh.firstOrNull { m.downloadMark(it.item(), env.downloaded, env.downloadsRunning) == DownloadMark.AVAILABLE }
                    if (toDownload != null) OrbitCardButton(CosmoIcons.Download, "Download the newest episode", colors, { m.host.onDownloadEpisode?.invoke(toDownload.item()) })
                    Box {
                        OrbitCardButton(CosmoIcons.MoreVertical, "More", colors, { more = true })
                        ShowMenu(m, env, show, feed, more, { more = false })
                    }
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
                OrbitPageSection("Episodes", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 10.dp),
                    subtitle = PodioFormat.episodeMeta(feed.episodes.size.toString(), if (fresh.isEmpty()) "" else "${fresh.size} new"))
            }
            item(key = "episode-filters") {
                OrbitChipRow(side, Modifier.padding(bottom = 10.dp)) {
                    OrbitSelectChip("All", filter == 0, fonts, look, { filter = 0 }, haptic = false)
                    OrbitSelectChip("Unplayed" + if (unplayed.isEmpty()) "" else " ${unplayed.size}", filter == 1, fonts, look, { filter = 1 }, haptic = false)
                    OrbitSelectChip("Downloaded", filter == 2, fonts, look, { filter = 2 }, haptic = false)
                    Spacer(Modifier.width(4.dp))
                    OrbitSelectChip(if (oldestFirst) "Oldest first" else "Newest first", false, fonts, look, { oldestFirst = !oldestFirst }, icon = CosmoIcons.Sort, haptic = false,
                        modifier = Modifier.testTag("podio:show:order"))
                }
            }
            val shown = feed.episodes.filter { episode ->
                val id = episode.item().id
                when (filter) {
                    1 -> !env.isPlayed(id, episode.durationSeconds * 1000)
                    2 -> env.downloaded.any { it.id == id }
                    else -> true
                }
            }.let { if (oldestFirst) it.reversed() else it }
            if (shown.isEmpty()) item(key = "filtered-none") {
                Text(if (filter == 2) "No downloaded episodes of this show." else "Every episode here has been played.", style = fonts.body, color = look.dim,
                    modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 16.dp))
            }
            val freshIds = fresh.map { it.id }.toSet()
            items(shown, key = { it.id }) { episode ->
                EpisodeItemRow(m, env, InboxEpisode(episode, feed.show), withShow = false, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 6.dp),
                    fresh = episode.id in freshIds)
            }
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

/** The show's More menu: refresh, and (for a followed show) its own playback choices, which used to sit at the foot of Following. */
@Composable private fun ShowMenu(m: PodioModel, env: PodioEnv, show: PodcastShow, feed: PodcastFeed?, open: Boolean, onDismiss: () -> Unit) {
    val following = env.saved.shows.any { it.id == show.id }
    val skip = env.saved.skipSilenceByShow[show.id]
    val unplayedNew = feed?.episodes.orEmpty().map { it.item().id }.filter { env.isNew(it) }
    OrbitMenu(open, onDismiss, listOfNotNull(
        OrbitMenuItem("Refresh", icon = CosmoIcons.Replay) { m.open(show) },
        OrbitMenuItem("Mark every episode seen", enabled = unplayedNew.isNotEmpty()) { m.markSeen(unplayedNew) }.takeIf { following },
        OrbitMenuItem("Skip silence", detail = when (skip) { true -> "On for this show"; false -> "Off for this show"; null -> "App default" }, selected = skip == true) {
            m.setShowSkipSilence(setOf(show.id), if (skip == true) false else true)
        }.takeIf { following },
        OrbitMenuItem("Copy feed address") { m.host.copyText(show.feed); m.notice = "Copied the feed address." },
    ), orbitFonts(), orbitLook(), header = show.title.ifBlank { "Podcast" })
}

// ---- Following ---------------------------------------------------------------------------------------------------------------

/**
 * Following (board frame 5): "14 SHOWS · CHECKED 8 MIN AGO" and Refresh; New and Shows as two segments with their counts. New is
 * the inbox: the shows with new episodes as discs with peach counts (a tap shows only that show's), then the new episodes by day
 * with "Mark all seen", a swipe to the right putting one away. Shows is every followed show as a grid of covers with its count.
 */
@Composable internal fun FollowingPage(m: PodioModel, env: PodioEnv, onRefresh: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val list = rememberLazyListState()
    var segment by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var only by remember { mutableStateOf<String?>(null) }
    var byName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:following")) {
        val side = orbitPageSide(maxWidth)
        val (columns, cell) = stationCells(maxWidth, side, maxWidth >= 840.dp)
        val shows = env.saved.shows
        val fresh = newEpisodes(m, env)
        val counts = fresh.groupingBy { it.show.id }.eachCount()
        val checked = env.saved.lastRefreshMs.takeIf { it > 0 }?.let { "Checked " + PodioFormat.ago(it) }.orEmpty()
        val label = PodioFormat.episodeMeta(if (shows.isEmpty()) "" else "${shows.size} ${if (shows.size == 1) "show" else "shows"}", checked).ifBlank { "Shows you follow" }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "head") {
                PodioHead("Following", label, fonts, look, side, env.onMusic) {
                    if (shows.isNotEmpty()) OrbitPageButton(CosmoIcons.Replay, "Refresh episodes", { if (!m.inboxLoading) onRefresh() })
                }
            }
            if (shows.isEmpty()) {
                item(key = "empty") {
                    PodioMessage("Following", "Nothing followed yet", "Follow a podcast to see its new episodes here, or bring your shows from another podcast app.",
                        fonts, look, side, "Browse podcasts") { env.chooseSection(PodioSection.PODCASTS) }
                }
                item(key = "import") {
                    OrbitChipRow(side, Modifier.padding(top = 12.dp)) {
                        OrbitSelectChip("Import from another app", false, fonts, look, { if (!m.pickingFile) m.importOpml { } }, icon = Icons.Outlined.FileDownload, haptic = false,
                            modifier = Modifier.testTag("podio:import-opml"))
                    }
                }
                return@LazyColumn
            }
            item(key = "segments") {
                OrbitChipRow(side, Modifier.padding(top = 16.dp)) {
                    OrbitSelectChip("New" + if (fresh.isEmpty()) "" else " ${fresh.size}", segment == 0, fonts, look, { segment = 0 }, haptic = false, modifier = Modifier.testTag("podio:following:new"))
                    OrbitSelectChip("Shows ${shows.size}", segment == 1, fonts, look, { segment = 1 }, haptic = false, modifier = Modifier.testTag("podio:following:shows"))
                }
            }
            if (segment == 1) {
                item(key = "order") {
                    OrbitChipRow(side, Modifier.padding(top = 10.dp)) {
                        OrbitSelectChip("Latest", !byName, fonts, look, { byName = false }, haptic = false)
                        OrbitSelectChip("Name", byName, fonts, look, { byName = true }, haptic = false)
                    }
                }
                val ordered = if (byName) shows.sortedBy { it.title.lowercase() } else shows.sortedByDescending { show ->
                    m.inbox.firstOrNull { it.show.id == show.id }?.let { PodioFormat.publishedAt(it.episode.published) } ?: Long.MIN_VALUE
                }
                val rows = ordered.chunked(columns)
                items(rows.size, key = { "shows-" + rows[it].first().id }) { index ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        rows[index].forEach { show -> ShowRecord(show, m, fonts, look, cell, count = counts[show.id] ?: 0) }
                        repeat(columns - rows[index].size) { Spacer(Modifier.width(cell)) }
                    }
                }
                return@LazyColumn
            }
            when {
                m.inboxLoading && m.inbox.isEmpty() -> item(key = "loading") { PodioSkeletonRows(4, look, side, Modifier.testTag("podio:loading")) }
                m.inboxError != null && m.inbox.isEmpty() -> item(key = "error") {
                    PodioMessage("New episodes", "Couldn't refresh your shows", m.inboxError.orEmpty(), fonts, look, side, "Retry", onRefresh)
                }
                else -> {
                    if (m.inboxError != null) item(key = "banner") { PodioBanner(m.inboxError.orEmpty(), "Retry", onRefresh, danger = true, look, fonts) }
                    if (counts.isNotEmpty()) item(key = "with-new") {
                        OrbitShelfRow(side + 4.dp, 14.dp) {
                            items(shows.filter { it.id in counts }, key = { "nshow-" + it.id }) { show ->
                                val chosen = only == show.id
                                Box(Modifier.padding(top = 14.dp)) {
                                    ShowRecord(show, m, fonts, look, 64.dp, count = counts[show.id] ?: 0, subtitle = "", onClick = { only = if (chosen) null else show.id })
                                    if (chosen) OrbitRing(72.dp, look.accent, progress = null, spinning = false, modifier = Modifier.offset((-4).dp, (-4).dp), stroke = 2.dp, moon = 0.dp)
                                }
                            }
                        }
                    }
                    val shown = fresh.filter { only == null || it.show.id == only }
                    if (shown.isEmpty() && !m.inboxLoading) item(key = "none") {
                        Text("Nothing new from your shows. New episodes appear here as the refresh finds them.", style = fonts.body, color = look.dim,
                            modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 16.dp))
                    }
                    PodioFormat.byDay(shown) { PodioFormat.publishedAt(it.episode.published) }.forEach { (day, entries) ->
                        item(key = "day-$day") {
                            Row(Modifier.fillMaxWidth().padding(start = side + 4.dp, end = side, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OrbitLabel("$day · ${entries.size} new", fonts, look.dim, Modifier.weight(1f))
                                PodioTextAction("Mark all seen", look, fonts, { m.markSeen(entries.map { it.item().id }) })
                            }
                        }
                        items(entries, key = { "n-" + it.episode.id }) {
                            EpisodeItemRow(m, env, it, withShow = true, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 6.dp), fresh = true, swipe = EpisodeSwipe.SEEN)
                        }
                    }
                }
            }
        }
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
                OrbitPageSection("Downloaded episodes", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 10.dp), subtitle = "Play without a connection")
            }
            items(env.downloaded, key = { it.id }) { episode -> DownloadedRow(m, env, episode, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 6.dp)) }
        }
    }
}

/** A downloaded episode: it plays from the saved file with the same identity and resume position; the cross at its end removes it. */
@Composable private fun DownloadedRow(m: PodioModel, env: PodioEnv, episode: PodioItem, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val resume = env.saved.progress[episode.id] ?: 0
    val completed = episode.id in env.saved.completedEpisodes
    val state = PodioFormat.heard(completed, resume, episode.durationSeconds * 1000).ifBlank { "Downloaded" }
    val now = env.playback.id == episode.id
    val remove = m.host.onRemoveDownload?.let { { m.downloadToRemove = episode } }
    EpisodeRow(episode.title, PodioFormat.episodeMeta(episode.author, PodioFormat.duration(episode.durationSeconds), state), episode.artwork,
        heardFraction(env, episode.id, episode.durationSeconds * 1000), now = now, playing = now && env.playback.playing,
        progress = if (now) ({ env.playedFraction() }) else null, download = DownloadMark.NONE,
        onPlay = { if (now) env.onToggle() else m.play(episode) }, onDownload = {}, onMore = remove,
        fonts = fonts, look = look, modifier = modifier, onToggle = env.onToggle,
        end = if (remove != null && !now) ({ OrbitRoundButton(CosmoIcons.Close, "Remove downloaded episode", look.ink, remove, size = 36.dp) }) else null)
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
            }
            OrbitChipRow(side, Modifier.padding(top = 8.dp)) { FilterChips(m, fonts, look) }
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
                                { m.toggleFavourite(station) }, fonts, look, Modifier.padding(horizontal = side).padding(bottom = 6.dp))
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
                                Modifier.fillMaxWidth().padding(horizontal = side).padding(bottom = 6.dp), height = 60.dp)
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
 * title and the show, play (or resume) in the bite; then one row of actions (download, play next, add to the queue, mark played,
 * the room) and Notes, Chapters and Transcript as tabs in the sheet itself.
 */
@Composable internal fun EpisodeSheet(m: PodioModel, env: PodioEnv, entry: InboxEpisode, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val item = entry.item()
    val episode = entry.episode
    val progress = env.saved.progress[item.id] ?: 0
    val completed = item.id in env.saved.completedEpisodes
    val played = env.isPlayed(item.id, episode.durationSeconds * 1000)
    val resumable = PodioFormat.progressFraction(completed, progress, episode.durationSeconds * 1000) > 0f
    val now = env.playback.id == item.id
    val queued = env.queue.entries.any { it.id == item.id }
    val art = item.artwork.takeIf(String::isNotBlank)
    val (fill, ink) = coverFill(art, episode.title)
    KitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
            .testTag("podio:episode-sheet"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OrbitBiteCard(fill, Modifier.fillMaxWidth(), bite = {
                OrbitPlayButton(now && env.playback.playing, { if (now) env.onToggle() else { m.playEpisode(entry); onDismiss() } }, look)
            }) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 22.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val heard = heardFraction(env, item.id, episode.durationSeconds * 1000)
                        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                            Disc(art, 84.dp)
                            if (heard > 0f) OrbitRing(96.dp, ink.copy(alpha = 0.8f), progress = { heard }, spinning = false, stroke = 1.5.dp, moon = 3.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            OrbitLabel(PodioFormat.episodeMeta("Episode", PodioFormat.date(episode.published), PodioFormat.duration(episode.durationSeconds)), fonts, ink.copy(alpha = 0.75f))
                            if (episode.show.isNotBlank()) Text(episode.show, style = fonts.body.copy(fontSize = 14.sp), color = ink.copy(alpha = 0.85f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Text(episode.title, style = fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 26.sp), color = ink, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 16.dp, end = 70.dp))
                    val state = when { now -> "Playing now"; resumable -> PodioFormat.episodeMeta("Resume", PodioFormat.remaining(progress, episode.durationSeconds * 1000)); played -> "Played"; else -> "" }
                    if (state.isNotBlank()) Text(state, style = fonts.body, color = ink.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp, end = 70.dp))
                }
            }
            OrbitChipRow(0.dp) {
                when (m.downloadMark(item, env.downloaded, env.downloadsRunning)) {
                    DownloadMark.AVAILABLE -> OrbitSelectChip("Download", false, fonts, look, { m.host.onDownloadEpisode?.invoke(item) }, icon = CosmoIcons.Download, haptic = false)
                    DownloadMark.RUNNING -> OrbitSelectChip("Cancel download", false, fonts, look, { m.host.onDownloadEpisode?.invoke(item) }, icon = CosmoIcons.Close, haptic = false)
                    DownloadMark.DONE -> if (m.host.onRemoveDownload != null) {
                        OrbitSelectChip("Remove download", false, fonts, look, {
                            m.downloadToRemove = env.downloaded.firstOrNull { it.id == item.id }
                        }, icon = CosmoIcons.Close, haptic = false)
                    } else Text("Downloaded", style = fonts.body, color = look.dim)
                    DownloadMark.NONE -> {}
                }
                if (!now) OrbitSelectChip("Play next", false, fonts, look, { m.playNext(entry); onDismiss() }, icon = CosmoIcons.Queue, haptic = false,
                    modifier = Modifier.testTag("podio:episode:play-next"))
                if (!now && !queued) OrbitSelectChip("Add to queue", false, fonts, look, { m.addToQueue(entry); onDismiss() }, icon = CosmoIcons.Plus, haptic = false,
                    modifier = Modifier.testTag("podio:episode:queue"))
                OrbitSelectChip(if (played) "Mark unplayed" else "Mark played", false, fonts, look, { m.markPlayed(item, episode.durationSeconds * 1000, !played) },
                    icon = CosmoIcons.Check, haptic = false)
                // The episode for everyone in the room, each device fetching it itself; hidden where rooms are not offered.
                RoomPlayButton(remember(item.id) { item.roomItem() })
            }
            EpisodeExtras(episode, m.catalogue, positionMs = if (now) env.playback.positionMs else null,
                onSeek = { position -> if (now) env.onSeek(position) else m.playEpisodeAt(entry, position) },
                start = ExtrasTab.NOTES, listHeight = 360.dp)
        }
    }
}
