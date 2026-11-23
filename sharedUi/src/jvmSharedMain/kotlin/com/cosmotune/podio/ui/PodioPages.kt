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
import com.cosmotune.ui.search.CosmoRecent
import com.cosmotune.ui.search.CosmoRecentSearches
import com.cosmotune.ui.search.CosmoSearchChoice
import com.cosmotune.ui.search.CosmoSearchFilter
import com.cosmotune.ui.search.CosmoSearchFocus
import com.cosmotune.ui.search.CosmoSearchPage
import com.cosmotune.ui.search.CosmoSearchRecents
import com.cosmotune.ui.search.CosmoSearchStatus
import com.cosmotune.ui.search.searchCountLabel
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
@Composable internal fun PodcastsPage(m: PodioModel, env: PodioEnv) {
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
                    EpisodeItemRow(m, env, it, withShow = true, fonts, look, Modifier.padding(horizontal = side), fresh = true)
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
                    OrbitPageSection("Discover", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 10.dp))
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
                                else OrbitSongRow(show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), fonts, look, open, Modifier.weight(1f))
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

/**
 * The episode you were in the middle of, as the home's one colour card: the shared pick-up header ([OrbitPageHeader] at
 * [OrbitHeaderSize.PICK_UP], 160 dp) with the cover's disc and how far you are round it, the show, and play (pause while it plays) in
 * the bite.
 */
@Composable private fun ContinueCard(m: PodioModel, env: PodioEnv, entry: InboxEpisode, modifier: Modifier) {
    val item = entry.item()
    val art = item.artwork.takeIf(String::isNotBlank)
    val now = env.playback.id == item.id
    val left = PodioFormat.remaining(env.saved.progress[item.id] ?: 0, entry.episode.durationSeconds * 1000)
    val size = OrbitHeaderSize.PICK_UP
    OrbitPageHeader(
        cover = art, label = PodioFormat.episodeMeta("Continue", left), title = entry.episode.title,
        subtitle = entry.show.title.ifBlank { entry.episode.show }, playing = now && env.playback.playing,
        onPlay = { if (now) env.onToggle() else m.playEpisode(entry) },
        modifier = modifier.fillMaxWidth().testTag("podio:continue"), size = size, onClick = { m.episodeSheet = entry },
        lead = { colors ->
            Box(Modifier.size(size.disc), contentAlignment = Alignment.Center) {
                Disc(art, size.disc - 12.dp)
                OrbitRing(size.disc, colors.ink.copy(alpha = 0.8f), progress = { heardFraction(env, item.id, entry.episode.durationSeconds * 1000) }, spinning = false, stroke = 1.5.dp, moon = 3.dp)
            }
        },
    )
}

// ---- A show ----------------------------------------------------------------------------------------------------------------

/**
 * A show (board frame 2): the shared compact header ([OrbitPageHeader]: the cover's disc beside the title, "PODCAST · 48 EPISODES",
 * the publisher, Follow, Download and More, play in the bite showing pause while one of its episodes plays), which collapses into a
 * bar at the top once scrolled away ([OrbitCollapsingHeaderBar]), then "Episodes · 48 · 6 new" with All, Unplayed and Downloaded and the order, and the
 * episodes as rows with one button each, so several show on opening. Older episodes load on demand at the end.
 */
/** A show with this many episodes gets the Find an episode field. */
private const val FIND_EPISODE_FROM = 8

@Composable internal fun ShowPage(m: PodioModel, env: PodioEnv) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val list = rememberLazyListState()
    val feed = m.opened
    val requested = m.requestedFeed
    val show = feed?.show ?: requested
    var filter by remember(requested?.id) { mutableIntStateOf(0) }
    var oldestFirst by remember(requested?.id) { mutableStateOf(false) }
    // Find an episode (Metrolist's podcast-page search, own code): narrows the show's episodes by title and show notes.
    var episodeQuery by remember(requested?.id) { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:show")) {
        val side = orbitPageSide(maxWidth)
        val episodes = feed?.episodes.orEmpty()
        val unplayed = episodes.filter { !env.isPlayed(it.item().id, it.durationSeconds * 1000) }
        val inboxIds = remember(m.inbox) { m.inbox.map { it.episode.id }.toSet() }
        val following = show != null && env.saved.shows.any { it.id == show.id }
        val fresh = unplayed.filter { following && it.id in inboxIds && env.isNew(it.item().id) }
        // The show's square art as the header's disc; Play is the player's pause while one of its episodes plays.
        val art = show?.artwork?.takeIf(String::isNotBlank)
        val title = show?.title?.ifBlank { "Podcast" } ?: "Podcast"
        val loaded = episodes.firstOrNull { it.item().id == env.playback.id }
        val latest = unplayed.firstOrNull() ?: episodes.firstOrNull()
        val showsPause = loaded != null && env.playback.playing
        val play: (() -> Unit)? = when {
            loaded != null -> env.onToggle
            latest != null && show != null -> ({ m.playEpisode(InboxEpisode(latest, show)) })
            else -> null
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "back") { PodioBackHead("Podcast", m::browse, fonts, look, side) }
            if (show != null) item(key = "card") {
                OrbitPageHeader(
                    cover = art, label = PodioFormat.episodeMeta("Podcast", episodeCount(episodes.size)), title = title,
                    subtitle = loaded?.let { "Playing: ${it.title}" } ?: show.author.ifBlank { null },
                    playing = showsPause, onPlay = play,
                    modifier = Modifier.padding(horizontal = side).padding(top = 6.dp).fillMaxWidth().testTag("podio:show-card"),
                ) { colors ->
                    if (feed != null) OrbitHeaderButton(if (following) CosmoIcons.Check else CosmoIcons.Plus, if (following) "Following" else "Follow", colors,
                        { m.toggleFollow(feed.show) }, on = following)
                    val toDownload = fresh.firstOrNull { m.downloadMark(it.item(), env.downloaded, env.downloadsRunning) == DownloadMark.AVAILABLE }
                    if (toDownload != null) OrbitHeaderButton(CosmoIcons.Download, "Download the newest episode", colors, { m.host.onDownloadEpisode?.invoke(toDownload.item()) })
                    Box {
                        OrbitHeaderButton(CosmoIcons.MoreVertical, "More", colors, { more = true })
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
            if (feed.episodes.size >= FIND_EPISODE_FROM) item(key = "episode-find") {
                OrbitTextField(
                    value = episodeQuery, onValueChange = { episodeQuery = it }, placeholder = "Find an episode", fonts = fonts, look = look,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    trailing = if (episodeQuery.isNotEmpty()) {
                        { OrbitRoundButton(CosmoIcons.Close, "Clear", look.ink, { episodeQuery = "" }, size = 32.dp) }
                    } else null,
                    modifier = Modifier.padding(horizontal = side).padding(bottom = 8.dp).fillMaxWidth().testTag("podio:show:find"),
                )
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
                .let { list -> com.cosmotune.ui.sheets.findPlaylists(list, episodeQuery) { it.title + " " + it.description } }
            if (shown.isEmpty()) item(key = "filtered-none") {
                Text(when {
                    episodeQuery.isNotBlank() -> "No episodes match “${episodeQuery.trim()}”" + if (feed.nextPage != null) ". Older ones may: load them below." else "."
                    filter == 2 -> "No downloaded episodes of this show."
                    else -> "Every episode here has been played."
                }, style = fonts.body, color = look.dim,
                    modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 16.dp))
            }
            val freshIds = fresh.map { it.id }.toSet()
            items(shown, key = { it.id }) { episode ->
                EpisodeItemRow(m, env, InboxEpisode(episode, feed.show), withShow = false, fonts, look, Modifier.padding(horizontal = side),
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
        // Once the card has scrolled away, the show's disc, title and Play stand at the top of the page.
        if (show != null) OrbitCollapsingHeaderBar(list, OrbitCollapsedHeader(art, title, show.author.ifBlank { null }, showsPause, play, null), after = 2, side = side)
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
                            EpisodeItemRow(m, env, it, withShow = true, fonts, look, Modifier.padding(horizontal = side), fresh = true, swipe = EpisodeSwipe.SEEN)
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
            items(env.downloaded, key = { it.id }) { episode -> DownloadedRow(m, env, episode, fonts, look, Modifier.padding(horizontal = side)) }
        }
    }
}

/** A downloaded episode: it plays from the saved file with the same identity and resume position; the cross at its end removes it, with Undo. */
@Composable private fun DownloadedRow(m: PodioModel, env: PodioEnv, episode: PodioItem, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val resume = env.saved.progress[episode.id] ?: 0
    val completed = episode.id in env.saved.completedEpisodes
    val state = PodioFormat.heard(completed, resume, episode.durationSeconds * 1000).ifBlank { "Downloaded" }
    val now = env.playback.id == episode.id
    val remove = m.host.onRemoveDownload?.let { { m.removeDownload(episode) } }
    EpisodeRow(episode.title, PodioFormat.episodeMeta(episode.author, PodioFormat.duration(episode.durationSeconds), state), episode.artwork,
        heardFraction(env, episode.id, episode.durationSeconds * 1000), now = now, playing = now && env.playback.playing,
        progress = if (now) ({ env.playedFraction() }) else null, download = DownloadMark.NONE,
        onPlay = { if (now) env.onToggle() else m.play(episode) }, onDownload = {}, onMore = remove,
        fonts = fonts, look = look, modifier = modifier, onToggle = env.onToggle,
        end = if (remove != null && !now) ({ OrbitRoundButton(CosmoIcons.Close, "Remove downloaded episode", look.ink, remove, size = 36.dp) }) else null)
}

/// ---- Search ----------------------------------------------------------------------------------------------------------------

/** What Podio's search asks for: stations (Radio Browser), podcasts (Apple and fyyd) or episodes (Apple's episode index). */
internal enum class PodioSearchKind(val id: String, val label: String) {
    STATIONS("stations", "Stations"), PODCASTS("podcasts", "Podcasts"), EPISODES("episodes", "Episodes");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: STATIONS }
}

internal val PodioModel.searchKind: PodioSearchKind
    get() = when { searchEpisodes -> PodioSearchKind.EPISODES; searchShows -> PodioSearchKind.PODCASTS; else -> PodioSearchKind.STATIONS }

/** The key of Podio's recent searches, kept on this device. */
private const val PODIO_RECENT = "podio"

/**
 * Podio's search, on the shared page ([CosmoSearchPage]): Stations, Podcasts or Episodes, then the region (for all three: the
 * station directory's country, the podcast storefront) and, for stations only, the language (the directory's own language field;
 * the podcast directories have none to filter by). Before anything is searched: the recent searches, then regions and podcast
 * topics to browse. A station plays; a podcast opens its show page and an episode its own sheet.
 */
@Composable internal fun SearchContent(m: PodioModel, env: PodioEnv, focus: CosmoSearchFocus, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val kind = m.searchKind
    val recent by CosmoRecentSearches.of(PODIO_RECENT).collectAsState()
    // What was searched (blank: a browse, a region's stations or a topic's chart); null while nothing has been asked.
    var searched by rememberSaveable { mutableStateOf<String?>(null) }
    if (m.query.isBlank() && searched?.isNotBlank() == true) searched = null
    fun run(text: String = m.query) {
        m.query = text
        // A typed search is the words alone; a topic chosen while browsing is left behind.
        if (text.isNotBlank()) { CosmoRecentSearches.add(PODIO_RECENT, text); m.genre = "" }
        searched = text.trim()
        m.browse(); m.request++
    }
    fun choose(next: PodioSearchKind) {
        if (next == m.searchKind) return
        m.browse(); m.searchShows = next == PodioSearchKind.PODCASTS; m.searchEpisodes = next == PodioSearchKind.EPISODES
        if (searched != null) m.request++
    }
    LaunchedEffect(kind, m.request, m.country) {
        if (kind == PodioSearchKind.EPISODES && searched != null) withContext(Dispatchers.Main.immediate) { m.loadEpisodes() }
    }
    val filters = buildList {
        add(CosmoSearchFilter("country", "Region", REGIONS.map { (id, name) -> CosmoSearchChoice(id, name) }, m.country) { m.browse(); m.country = it })
        if (kind == PodioSearchKind.STATIONS) {
            add(CosmoSearchFilter("language", "Language", LANGUAGES.map { (id, name) -> CosmoSearchChoice(id, name) }, m.language) { m.browse(); m.language = it })
        }
    }
    val filtered = m.country.isNotBlank() || (kind == PodioSearchKind.STATIONS && m.language.isNotBlank())
    val status = when {
        searched == null -> CosmoSearchStatus.Idle
        kind == PodioSearchKind.STATIONS -> when {
            m.directoryLoading && m.radio.isEmpty() -> CosmoSearchStatus.Searching
            m.error != null && m.radio.isEmpty() -> CosmoSearchStatus.Failed("Couldn't reach the stations.") { m.request++ }
            m.radio.isEmpty() -> CosmoSearchStatus.Empty()
            else -> CosmoSearchStatus.Results(m.radio.size, m.stationCount())
        }
        kind == PodioSearchKind.PODCASTS -> when {
            m.directoryLoading && m.shows.isEmpty() -> CosmoSearchStatus.Searching
            m.error != null && m.shows.isEmpty() -> CosmoSearchStatus.Failed("Couldn't reach the podcast directory.") { m.request++ }
            m.shows.isEmpty() -> CosmoSearchStatus.Empty()
            else -> CosmoSearchStatus.Results(m.shows.size, searchCountLabel(m.shows.size, "podcast", "podcasts"))
        }
        else -> when {
            m.episodesLoading -> CosmoSearchStatus.Searching
            m.episodesError != null -> CosmoSearchStatus.Failed(m.episodesError.orEmpty()) { m.request++ }
            searched.isNullOrBlank() -> CosmoSearchStatus.Empty("Type what the episode is about")
            m.episodes.isEmpty() -> CosmoSearchStatus.Empty()
            else -> CosmoSearchStatus.Results(m.episodes.size, searchCountLabel(m.episodes.size, "episode", "episodes"))
        }
    }
    CosmoSearchPage(
        eyebrow = "Podio",
        query = m.query,
        onQueryChange = { m.query = it },
        onSubmit = { run() },
        placeholder = when (kind) { PodioSearchKind.STATIONS -> "Find a station"; PodioSearchKind.PODCASTS -> "Find a podcast"; else -> "Find an episode" },
        status = status,
        modifier = modifier.testTag("podio:search"),
        focus = focus,
        contentPadding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp, bottom = env.bottom + 16.dp),
        kinds = PodioSearchKind.entries.map { CosmoSearchChoice(it.id, it.label) },
        kind = kind.id,
        onKind = { choose(PodioSearchKind.of(it)) },
        filters = filters,
        onClearFilters = if (filtered) ({ m.browse(); m.country = ""; m.language = ""; m.request++ }) else null,
        recents = CosmoSearchRecents(
            recent.map { CosmoRecent(it, it) },
            onPick = { run(it.title) },
            onRemove = { CosmoRecentSearches.remove(PODIO_RECENT, it.title) },
            onClear = { CosmoRecentSearches.clear(PODIO_RECENT) },
        ),
        browse = { side ->
            item(key = "browse:regions") {
                Column {
                    Text("Stations by region", style = fonts.body, color = look.dim, modifier = Modifier.padding(start = side + 4.dp, bottom = 8.dp))
                    OrbitChipRow(side) {
                        REGIONS.drop(1).forEach { (id, name) ->
                            OrbitSelectChip(name, false, fonts, look, { choose(PodioSearchKind.STATIONS); m.country = id; run("") }, icon = CosmoIcons.Radio, haptic = false)
                        }
                    }
                }
            }
            item(key = "browse:topics") {
                Column(Modifier.padding(top = 20.dp)) {
                    Text("Podcasts by topic", style = fonts.body, color = look.dim, modifier = Modifier.padding(start = side + 4.dp, bottom = 8.dp))
                    OrbitChipRow(side) {
                        PODCAST_GENRES.forEach { (id, name) ->
                            OrbitSelectChip(name, false, fonts, look, { choose(PodioSearchKind.PODCASTS); m.genre = id; run("") }, icon = Icons.Outlined.Podcasts, haptic = false)
                        }
                    }
                }
            }
        },
    ) { listModifier, side ->
        LazyColumn(listModifier, contentPadding = PaddingValues(top = 4.dp, bottom = env.bottom + 16.dp)) {
            when (kind) {
                PodioSearchKind.STATIONS -> {
                    items(m.radio.take(400), key = { it.id }) { station ->
                        val current = env.playback.id == station.item().id
                        StationRow(station, env.saved.stations.any { it.id == station.id }, current, current && env.playback.playing, { m.playStation(station) },
                            { m.toggleFavourite(station) }, fonts, look, Modifier.padding(horizontal = side))
                    }
                    item(key = "credit") { PodioCredit("Stations: Radio Browser · Imagery: NASA GIBS", fonts, look, side) }
                }
                PodioSearchKind.PODCASTS -> items(m.shows, key = { it.id }) { show ->
                    OrbitSongRow(show.title.ifBlank { "Podcast" }, show.author, show.artwork.takeIf(String::isNotBlank), fonts, look, { m.open(show) },
                        Modifier.fillMaxWidth().padding(horizontal = side))
                }
                PodioSearchKind.EPISODES -> items(m.episodes, key = { "episode:${it.episode.id}" }) { entry ->
                    val episode = entry.episode
                    OrbitSongRow(episode.title, PodioFormat.episodeMeta(entry.show.title, PodioFormat.duration(episode.durationSeconds)),
                        entry.item().artwork.takeIf(String::isNotBlank), fonts, look, { m.episodeSheet = entry }, Modifier.fillMaxWidth().padding(horizontal = side))
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
                            env.downloaded.firstOrNull { it.id == item.id }?.let(m::removeDownload)
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
                start = ExtrasTab.NOTES, listHeight = 360.dp, localPath = env.downloaded.firstOrNull { it.id == item.id }?.downloadedPath)
        }
    }
}
