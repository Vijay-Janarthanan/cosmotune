package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.WatchCard
import com.cosmotune.ui.search.CosmoRecent
import com.cosmotune.ui.search.CosmoSearchChoice
import com.cosmotune.ui.search.CosmoSearchFilter
import com.cosmotune.ui.search.CosmoSearchPage
import com.cosmotune.ui.search.CosmoSearchRecents
import com.cosmotune.ui.search.CosmoSearchStatus
import com.cosmotune.ui.search.rememberCosmoSearchFocus
import com.cosmotune.watch.LiveNowItem
import com.cosmotune.watch.WatchFound
import com.cosmotune.watch.WatchGenre
import com.cosmotune.watch.WatchKind
import com.cosmotune.watch.WatchLanguage
import com.cosmotune.watch.WatchQuery
import com.cosmotune.watch.WatchSearchRules
import com.cosmotune.watch.WatchSort
import com.cosmotune.watch.WatchYears
import com.cosmotune.watch.model.TitleSummary
import kotlinx.coroutines.delay

/** Watch search's box, chips and last answers, kept for the process per repository: coming back from a title finds the same search. */
internal class WatchSearchMemory {
    val text = mutableStateOf("")
    val kind = mutableStateOf(WatchKind.ALL)
    val genre = mutableStateOf<WatchGenre?>(null)
    val years = mutableStateOf<WatchYears?>(null)
    val language = mutableStateOf<WatchLanguage?>(null)
    val source = mutableStateOf<String?>(null)
    @Volatile var found: WatchFound? = null
    @Volatile var live: List<LiveNowItem> = emptyList()
    /** "Popular now" of each kind, fetched once. */
    val popular = java.util.concurrent.ConcurrentHashMap<WatchKind, WatchFound>()

    companion object {
        private val all = java.util.WeakHashMap<Any, WatchSearchMemory>()
        fun of(owner: Any): WatchSearchMemory = synchronized(all) { all.getOrPut(owner) { WatchSearchMemory() } }
    }
}

/** The Browse chips before anything is typed: genres, then original languages, then years. Each runs a browse. */
internal val WATCH_BROWSE_GENRES = listOf(WatchGenre.ACTION, WatchGenre.COMEDY, WatchGenre.DRAMA, WatchGenre.THRILLER, WatchGenre.ANIMATION,
    WatchGenre.SCI_FI, WatchGenre.HORROR, WatchGenre.ROMANCE)
internal val WATCH_BROWSE_LANGUAGES = listOf(WatchLanguage.TAMIL, WatchLanguage.HINDI, WatchLanguage.TELUGU, WatchLanguage.MALAYALAM,
    WatchLanguage.KOREAN, WatchLanguage.JAPANESE)
internal val WATCH_BROWSE_YEARS = listOf(WatchYears.THIS_YEAR, WatchYears.TEENS, WatchYears.NINETIES)

/**
 * Search (search-spotlight board, items 2 and 3): the shared [CosmoSearchPage] with "N sources on" over the title, the box, the kind
 * chips (All, Films, Series, Anime, Live TV), then Genre, Year, Language and Source (Source only with more than one source on), the
 * count with how many sources answered and the Sort (kept per viewer), and the posters in a grid with their year and rating. A
 * filter a source cannot apply leaves it out, and the page says how many were. Before typing: recent searches, Browse chips that
 * browse a genre, a language or years, and Popular now. The Search button pressed again clears the box and shows the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchSearchPage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = actions.repository
    val clock = LocalWatchClock.current
    val memory = remember(repository) { WatchSearchMemory.of(repository) }
    var text by memory.text
    var kind by memory.kind
    var genre by memory.genre
    var years by memory.years
    var language by memory.language
    var source by memory.source
    val preferences by repository.preferences.collectAsState()
    val recent by repository.recentSearches.collectAsState()
    val plugins by repository.plugins.collectAsState()
    val sources = remember(repository, plugins) { repository.searchSources() }
    val year = remember(clock) { java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.systemDefault()).year }
    val query = WatchQuery(text, kind, genre, years, language, source?.let { setOf(it) } ?: emptySet(), preferences.searchSort)
    // A magnet link or an info hash pasted in the box is a torrent the listener has: it opens, it is not searched for.
    val torrent = remember(text) { if (repository.canOpenTorrents) com.cosmotune.watch.torrent.TorrentLinks.parse(text) else null }
    val pickTorrent = rememberTorrentFilePicker { bytes -> WatchNav.open(WatchRoute.Torrent(fileKey = WatchTorrentInbox.put(bytes))) }
    // Something to ask: words in the box, a filter set (an empty box then browses what matches), or Live TV (its channels).
    val active = torrent == null && (query.trimmed.isNotEmpty() || query.hasFilters || kind == WatchKind.LIVE)
    var found by remember { mutableStateOf(memory.found?.takeIf { it.query == query }) }
    var live by remember { mutableStateOf(if (found != null) memory.live else emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val focus = rememberCosmoSearchFocus(focusOnOpen = !active)

    fun keep(words: String) = actions.launch { repository.setRecentSearches(WatchSearchRules.remember(repository.recentSearches.value, words)) }
    fun open(summary: TitleSummary) {
        if (query.trimmed.isNotEmpty()) keep(query.trimmed)
        actions.open(summary)
    }
    fun clearFilters() { genre = null; years = null; language = null; source = null }

    LaunchedEffect(query, retry) {
        if (!active) { searching = false; return@LaunchedEffect }
        if (memory.found?.query == query && retry == 0) { found = memory.found; live = memory.live; return@LaunchedEffect }
        searching = true
        if (query.trimmed.isNotEmpty()) delay(300)
        val answer = runCatching { repository.find(query, year) }.getOrElse { WatchFound(query, emptyList(), failed = 1) }
        live = answer.channels.take(8).map { c -> runCatching { repository.nowNext(c) }.getOrDefault(LiveNowItem(c)) }
        found = answer
        memory.found = answer
        memory.live = live
        searching = false
    }
    // The Search button pressed again while on Search: the box is cleared and focused.
    val again by WatchNav.searchAgain.collectAsState()
    var seenAgain by remember { mutableIntStateOf(again) }
    LaunchedEffect(again) { if (again != seenAgain) { seenAgain = again; text = ""; focus.request() } }

    val popularKind = if (kind == WatchKind.LIVE) WatchKind.ALL else kind
    var popular by remember(popularKind) { mutableStateOf(memory.popular[popularKind]) }
    LaunchedEffect(popularKind, active) {
        if (active || popular != null) return@LaunchedEffect
        runCatching { repository.find(WatchQuery(kind = popularKind), year) }.getOrNull()?.takeIf { !it.isEmpty }?.let { memory.popular[popularKind] = it; popular = it }
    }

    val shown = found
    val status = when {
        torrent != null -> CosmoSearchStatus.Results(1, "A torrent link")
        !active -> CosmoSearchStatus.Idle
        searching || shown == null -> CosmoSearchStatus.Searching
        shown.error -> CosmoSearchStatus.Failed("Your sources didn't answer. Check the connection.") { retry++ }
        shown.isEmpty -> CosmoSearchStatus.Empty(shown.leftOutNote?.let { "Nothing found. $it." })
        else -> CosmoSearchStatus.Results(shown.titles.size + shown.channels.size,
            "${shown.countLabel} · ${shown.answered} ${if (shown.answered == 1) "source" else "sources"}")
    }
    val any = "any"
    val filters = buildList {
        if (kind != WatchKind.LIVE) {
            add(CosmoSearchFilter("genre", "Genre", listOf(CosmoSearchChoice(any, "Any genre")) + WatchGenre.entries.map { CosmoSearchChoice(it.name, it.label) },
                genre?.name ?: any) { id -> genre = WatchGenre.entries.firstOrNull { it.name == id } })
            add(CosmoSearchFilter("year", "Year", listOf(CosmoSearchChoice(any, "Any year")) + WatchYears.entries.map { CosmoSearchChoice(it.name, it.label) },
                years?.name ?: any) { id -> years = WatchYears.entries.firstOrNull { it.name == id } })
        }
        add(CosmoSearchFilter("language", "Language", listOf(CosmoSearchChoice(any, "Any language")) + WatchLanguage.entries.map { CosmoSearchChoice(it.name, it.label) },
            language?.name ?: any) { id -> language = WatchLanguage.entries.firstOrNull { it.name == id } })
        if (sources.size > 1) add(CosmoSearchFilter("source", "Source", listOf(CosmoSearchChoice(any, "All sources")) + sources.map { CosmoSearchChoice(it.id, it.name) },
            source ?: any) { id -> source = id.takeIf { it != any } })
    }
    val sort = CosmoSearchFilter("sort", "Sort", WatchSort.entries.map { CosmoSearchChoice(it.name, it.label) }, preferences.searchSort.name) { id ->
        val chosen = WatchSort.entries.first { it.name == id }
        actions.launch { repository.setPreferences(repository.preferences.value.copy(searchSort = chosen)) }
    }
    val cardWidth = (if (frame.wide) 150.dp else 104.dp) * com.cosmotune.ui.orbit.LocalWatchCardScale.current

    CosmoSearchPage(
        eyebrow = "${sources.size} ${if (sources.size == 1) "source" else "sources"} on",
        query = text, onQueryChange = { text = it }, onSubmit = { keep(text) },
        placeholder = "Films, series, channels",
        status = status,
        modifier = Modifier.testTag("watch:search"),
        focus = focus,
        contentPadding = PaddingValues(top = frame.padding.calculateTopPadding(), bottom = frame.padding.calculateBottomPadding()),
        leading = frame.header,
        kinds = WatchKind.entries.map { CosmoSearchChoice(it.name, it.label) },
        kind = kind.name,
        onKind = { id ->
            kind = WatchKind.valueOf(id)
            // Channels have no genre or year: Live TV drops them rather than finding nothing.
            if (kind == WatchKind.LIVE) { genre = null; years = null }
        },
        filters = filters,
        sort = if (kind == WatchKind.LIVE) null else sort,
        onClearFilters = if (query.hasFilters) ({ clearFilters() }) else null,
        recents = CosmoSearchRecents(
            items = recent.map { CosmoRecent(it, it) },
            onPick = { text = it.title },
            onRemove = { r -> actions.launch { repository.setRecentSearches(repository.recentSearches.value.filterNot { it == r.id }) } },
            onClear = { actions.launch { repository.setRecentSearches(emptyList()) } },
        ),
        browse = { side ->
            item(key = "watch:browse") {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = side).testTag("watch:browse"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WATCH_BROWSE_GENRES.forEach { g -> OrbitSelectChip(g.label, false, fonts, look, onClick = { genre = g }, haptic = false) }
                    WATCH_BROWSE_LANGUAGES.forEach { l -> OrbitSelectChip(l.label, false, fonts, look, onClick = { language = l }, haptic = false) }
                    WATCH_BROWSE_YEARS.forEach { y -> OrbitSelectChip(y.name(year), false, fonts, look, onClick = { years = y }, haptic = false) }
                }
            }
            if (repository.canOpenTorrents) item(key = "watch:torrent-file") {
                Column(Modifier.padding(horizontal = side).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.cosmotune.ui.orbit.WatchCardChip("Open a .torrent file", look.ink, fonts, onClick = pickTorrent, icon = com.cosmotune.ui.components.CosmoIcons.Download)
                    Text("Or paste a magnet link in the box.", style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp))
                }
            }
            popular?.takeIf { it.titles.isNotEmpty() }?.let { p ->
                item(key = "watch:popular") {
                    Column(Modifier.padding(top = 24.dp)) {
                        OrbitLabel("Popular now", fonts, look.dim, Modifier.padding(start = side + 4.dp, end = side, bottom = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(p.titles.take(20), key = { it.key.value }) { s ->
                                WatchCard(s.name, posterLine(s), s.posterUrl ?: s.backdropUrl, s.key.value, fonts, look, onClick = { actions.open(s) }, width = cardWidth)
                            }
                        }
                    }
                }
            }
        },
        results = { modifier, side ->
            if (torrent != null) {
                Column(modifier.padding(horizontal = side).padding(top = 4.dp)) {
                    com.cosmotune.ui.orbit.WatchMessage("Torrent", torrent.name ?: "A torrent link",
                        "Open it to see the files inside, play any of them or download them.", fonts, look,
                        action = "Open", onAction = { WatchNav.open(WatchRoute.Torrent(text = text.trim())) })
                }
                return@CosmoSearchPage
            }
            val r = shown ?: return@CosmoSearchPage
            LazyVerticalGrid(
                GridCells.Adaptive(cardWidth), modifier.testTag("watch:search:results"),
                contentPadding = PaddingValues(start = side, end = side, top = 4.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                r.leftOutNote?.let { note ->
                    item(key = "search:left-out", span = { GridItemSpan(maxLineSpan) }) {
                        Text(note, style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 4.dp).testTag("watch:search:left-out"))
                    }
                }
                items(r.titles, key = { "t:" + it.key.value }) { s ->
                    WatchCard(s.name, posterLine(s), s.posterUrl ?: s.backdropUrl, s.key.value, fonts, look, onClick = { open(s) }, width = null,
                        onPlay = { actions.play(s) })
                }
                if (live.isNotEmpty()) {
                    item(key = "search:channels", span = { GridItemSpan(maxLineSpan) }) {
                        OrbitLabel("Channels", fonts, look.dim, Modifier.padding(start = 4.dp, top = if (r.titles.isEmpty()) 0.dp else 8.dp))
                    }
                    items(live, key = { "c:" + it.channel.key.value }, span = { GridItemSpan(maxLineSpan) }) { item ->
                        LiveRow(item, fonts, look, clock) { actions.playChannel(item.channel) }
                    }
                }
            }
        },
    )
}
