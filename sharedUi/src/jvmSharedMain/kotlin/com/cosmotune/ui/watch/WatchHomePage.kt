package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitSectionHeader
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.WatchBadge
import com.cosmotune.ui.orbit.WatchCard
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchCardChipRow
import com.cosmotune.ui.orbit.WatchLiveLine
import com.cosmotune.ui.orbit.WatchLogoTile
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchPicture
import com.cosmotune.ui.orbit.WatchRowFrame
import com.cosmotune.ui.orbit.WatchSkeleton
import com.cosmotune.ui.orbit.channelMark
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.logoFill
import com.cosmotune.watch.ContinueItem
import com.cosmotune.watch.Featured
import com.cosmotune.watch.HomeFilter
import com.cosmotune.watch.HomeRow
import com.cosmotune.watch.LiveNowItem
import com.cosmotune.watch.WatchHome
import com.cosmotune.ui.states.RefreshFailedNote
import com.cosmotune.ui.states.collectOnPage
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.cosmotune.watch.WatchRepository

/** The home wash (board section 1): a night blue. */
private val HOME_WASH = Color(0xFF3C508C)

/**
 * Watch home (board section 1), or the Films and Series tabs when [fixed] is set: the date and "Tonight.", chips for All, Films,
 * Series and Live TV, the featured colour card with play in its bite, Continue watching with progress, Live now, then the rows.
 */
@Composable
internal fun WatchHomePage(fixed: HomeFilter?, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = actions.repository
    var chosen by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    // Live TV is a tab of its own, not one of Home's filters.
    val filter = fixed ?: chosen.takeIf { it != HomeFilter.LIVE } ?: HomeFilter.ALL
    val viewer by repository.activeProfile.collectAsState()
    // The rows of each tab stay for the process (and on the disk): coming back shows them at once while they refresh.
    val entry = remember(repository, filter, viewer) { watchHomeEntry(repository, filter, viewer) }
    // Where the page was scrolled to stays too: opening a title and coming back finds the same place.
    val listState = remember(filter, viewer) { WatchHomeMemory.page(filter.name + ":" + viewer) }
    val cached by entry.collectOnPage()
    val live by repository.continueWatching.collectAsState()
    val myList by repository.myList.collectAsState()
    val guideDismissed by repository.setupGuideDismissed.collectAsState()
    val clock = LocalWatchClock.current
    val side = frame.side
    val data = cached.data
    Box(Modifier.fillMaxSize()) {
        WatchWash(data?.featured?.let { coverFill(it.summary.backdropUrl ?: it.summary.posterUrl, it.summary.key.value).first } ?: HOME_WASH, look)
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "watch:header") { header() } }
            item(key = "watch:heading") {
                val label = remember(filter) {
                    Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE dd MMM — HH:mm", Locale.ENGLISH))
                }
                val title = when (fixed) { HomeFilter.FILMS -> "Films"; HomeFilter.SERIES -> "Series"; else -> "Tonight" }
                val chips: @Composable () -> Unit = {
                    if (fixed == null && frame.filterChips) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(HomeFilter.ALL to "All", HomeFilter.FILMS to "Films", HomeFilter.SERIES to "Series").forEach { (f, name) ->
                                OrbitSelectChip(name, filter == f, fonts, look, onClick = { chosen = f }, haptic = false)
                            }
                        }
                    }
                }
                if (frame.wide) {
                    OrbitPageHeading(title, label, fonts, look, Modifier.padding(start = side + 6.dp, end = side, top = 24.dp)) { chips() }
                } else {
                    Column(Modifier.padding(top = 18.dp)) {
                        OrbitPageHeading(title, label, fonts, look, Modifier.padding(horizontal = side + 6.dp))
                        Box(Modifier.padding(start = side, top = 16.dp)) { chips() }
                    }
                }
            }
            if (fixed == null && !guideDismissed) item(key = "watch:guide") {
                WatchSetupCard(repository, fonts, look, Modifier.padding(horizontal = side).padding(top = 22.dp))
            }
            if (cached.refreshFailed) item(key = "watch:refresh-failed") {
                RefreshFailedNote(onRetry = { entry.refresh() }, modifier = Modifier.padding(start = side + 6.dp, top = 14.dp))
            }
            when {
                cached.showPlaceholders -> item(key = "watch:loading") { HomeSkeleton(look, frame) }
                cached.failedEmpty -> item(key = "watch:error") {
                    WatchMessage("Watch", "Could not load", cached.error ?: "Something went wrong.", fonts, look,
                        Modifier.padding(horizontal = side).padding(top = 24.dp), action = "Try again", onAction = { entry.refresh() })
                }
                data != null && data.empty -> item(key = "watch:empty") {
                    WatchMessage("Nothing yet", "Nothing to watch yet", "Every plugin is off or offline. Switch some on in Plugins.", fonts, look,
                        Modifier.padding(horizontal = side).padding(top = 24.dp), action = "Open Plugins", onAction = { WatchNav.go(WatchTab.PLUGINS) })
                }
                data != null -> {
                    val going = live.filter {
                        when (filter) { HomeFilter.FILMS -> it.summary.type == TitleType.FILM; HomeFilter.SERIES -> it.summary.type == TitleType.SERIES; HomeFilter.LIVE -> false; else -> true }
                    }.ifEmpty { if (filter == HomeFilter.LIVE) emptyList() else data.continueWatching }
                    if (going.isNotEmpty()) item(key = "watch:continue") {
                        Column {
                            Section("Continue watching", null, fonts, look, side)
                            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                itemsIndexed(going, key = { _, it -> it.key.value }) { _, item -> ContinueCard(item, frame.wide, fonts, look, actions) }
                            }
                        }
                    }
                    if (filter == HomeFilter.ALL || filter == HomeFilter.SERIES) item(key = "watch:new-episodes") {
                        WatchNewEpisodesRow(actions, fonts, look, side, frame.wide)
                    }
                    data.featured?.let { featured ->
                        item(key = "watch:featured") {
                            WatchFeaturedCard(
                                featured, frame.wide, fonts, look,
                                inList = myList.any { it.key == featured.summary.key },
                                onPlay = { actions.play(featured.summary) },
                                onOpen = { actions.open(featured.summary) },
                                onMyList = { actions.setInMyList(featured.summary, it) },
                                watchTogether = actions.watchTogetherReady,
                                onWatchTogether = { actions.watchTogether(featured.summary, null, null) },
                                modifier = Modifier.padding(horizontal = side).padding(top = if (going.isEmpty()) 22.dp else 26.dp),
                            )
                        }
                    }
                    if (data.liveNow.isNotEmpty()) item(key = "watch:live") {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(end = side), verticalAlignment = Alignment.Bottom) {
                                Section("Live now", null, fonts, look, side, Modifier.weight(1f))
                                WatchCardChip("See all", look.ink, fonts, onClick = { WatchNav.go(WatchTab.LIVE) }, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            LiveNow(data.liveNow, frame.wide, fonts, look, actions, clock, Modifier.padding(horizontal = side))
                        }
                    }
                    // How far each title in progress is, for the line under its poster in the rows.
                    val watched = (live.ifEmpty { data.continueWatching }).associate { it.summary.key to it.progress.fraction }
                    data.rows.forEach { row ->
                        if (row.items.isNotEmpty() || row.deferred) item(key = "watch:row:" + row.id) { HomeRowShelf(row, fonts, look, side, actions, watched) }
                    }
                }
            }
            item(key = "watch:end") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
internal fun Section(title: String, subtitle: String?, fonts: OrbitFonts, look: OrbitLook, side: Dp, modifier: Modifier = Modifier) {
    OrbitSectionHeader(title, subtitle.orEmpty(), fonts, look, modifier.padding(horizontal = side + 6.dp).padding(top = 30.dp, bottom = 14.dp))
}

/** "Featured · Film · 1h 58m · 2019". */
internal fun summaryLine(summary: TitleSummary, lead: String? = null): String = listOfNotNull(
    lead,
    if (summary.type == TitleType.FILM) "Film" else "Series",
    if (summary.type == TitleType.FILM) runtimeText(summary.runtimeMin) else null,
    summary.year?.toString(),
).joinToString(" · ")

/**
 * First use: what Watch already plays from, and the optional extras (more sources, a viewer for each
 * person, Trakt or Simkl), each with the page that sets it up. Built from this device's own state,
 * so a step that is done says so; put away with Hide, or gone once every step is done.
 */
@Composable
internal fun WatchSetupCard(repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val plugins by repository.plugins.collectAsState()
    val profiles by repository.profiles.collectAsState()
    val dismissed by repository.setupGuideDismissed.collectAsState()
    val tracking = repository.tracking
    val trackers = tracking?.statuses?.collectAsState()?.value
    val steps = com.cosmotune.watch.WatchSetupGuide.steps(plugins, profiles.size, trackers)
    if (!com.cosmotune.watch.WatchSetupGuide.shouldShow(dismissed, steps)) return
    Column(modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(26.dp)).background(look.row).padding(22.dp)) {
        OrbitLabel("Get started", fonts, look.dim)
        Text("Set up Watch your way", style = fonts.section, color = look.ink, modifier = Modifier.padding(top = 10.dp))
        steps.forEach { step ->
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = fonts.title, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(step.detail, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 2.dp))
                }
                if (step.done) OrbitLabel("Done", fonts, look.dim)
                else WatchCardChip(step.action, look.ink, fonts, onClick = {
                    WatchNav.go(if (step.target == com.cosmotune.watch.WatchSetupTarget.PLUGINS) WatchTab.PLUGINS else WatchTab.SETTINGS)
                })
            }
        }
        Spacer(Modifier.height(16.dp))
        WatchCardChip("Hide guide", look.ink, fonts, onClick = { scope.launch { repository.dismissSetupGuide() } })
    }
}

/** The line under a poster: the year and the badge (or the first genre). */
internal fun posterLine(summary: TitleSummary): String =
    listOfNotNull(summary.year?.toString(), summary.rating?.takeIf { it.isFinite() && it in 0.0..10.0 }?.let {
        when (summary.origin) { "cinemeta" -> "IMDb %.1f".format(it); "tmdb" -> "TMDB %.1f".format(it); else -> null }
    }, summary.badge ?: summary.genres.firstOrNull()).joinToString(" · ")

/**
 * The featured card (board frame 6): about 220 dp in the colour of its picture, the picture faded into the card at its end; the
 * mono line, the title, the badges, then My list and Watch together as round actions. Play in the bite is its one main action;
 * a tap on the card opens the title's page.
 */
@Composable
private fun WatchFeaturedCard(
    featured: Featured,
    wide: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    inList: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onMyList: (Boolean) -> Unit,
    watchTogether: Boolean,
    onWatchTogether: () -> Unit,
    modifier: Modifier,
) {
    val s = featured.summary
    val (fill, ink) = coverFill(s.backdropUrl ?: s.posterUrl, s.key.value)
    // Play sits in the bite, inside the card's bounds, so a D-pad's Right from the card would never find it: it is named.
    val cardFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    OrbitBiteCard(fill, modifier.fillMaxWidth().height(if (wide) 250.dp else 220.dp), onClick = onOpen,
        cardModifier = Modifier.focusRequester(cardFocus).focusProperties { right = playFocus },
        bite = { OrbitPlayButton(false, onPlay, look, Modifier.focusRequester(playFocus).focusProperties { left = cardFocus }) }) {
        Box(Modifier.fillMaxSize()) {
            WatchCardBanner(s.backdropUrl ?: s.posterUrl, s.key.value + "b", fill, share = if (wide) 0.5f else 0.62f)
            Column(Modifier.fillMaxHeight().padding(start = if (wide) 26.dp else 20.dp, top = if (wide) 26.dp else 20.dp, end = 20.dp, bottom = 16.dp).widthIn(max = 520.dp)) {
                OrbitLabel(summaryLine(s, "Featured"), fonts, ink.copy(alpha = 0.75f))
                Text(s.name, style = fonts.heroTitle.copy(fontSize = if (wide) 34.sp else 28.sp, lineHeight = if (wide) 36.sp else 30.sp), color = ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, end = if (wide) 0.dp else 40.dp))
                if (featured.badges.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    featured.badges.forEach { WatchBadge(it, fonts, ink) }
                }
                Spacer(Modifier.weight(1f))
                WatchCardActions(listOf(
                    WatchCardAction(if (inList) CosmoIcons.Check else CosmoIcons.Plus, "My list", on = inList,
                        description = if (inList) "Remove from My list" else "Add to My list") { onMyList(!inList) },
                    WatchCardAction(CosmoIcons.Together, "Together", enabled = watchTogether, description = "Watch together", onClick = onWatchTogether),
                ), ink, fill, fonts)
            }
        }
    }
}

/** A Continue watching card: the still with Play hanging off it, how far, "Harbour Town · S2 E4", "24 min left". */
@Composable
private fun ContinueCard(item: ContinueItem, wide: Boolean, fonts: OrbitFonts, look: OrbitLook, actions: WatchActions) {
    val title = listOfNotNull(item.summary.name, item.episodeLabel).joinToString(" · ")
    val play = { item.episode?.let { actions.playEpisode(item.summary, it) } ?: actions.play(item.summary) }
    WatchCard(
        title, leftText(item.progress.remainingMs), item.summary.backdropUrl ?: item.summary.posterUrl, item.key.value, fonts, look,
        onClick = { play() }, width = if (wide) 260.dp else 224.dp, wide = true, progress = item.progress.fraction, onPlay = { play() },
    )
}

        menu = listOf(
            com.cosmotune.ui.orbit.OrbitMenuItem(if (item.episode != null) "Mark ${item.episodeLabel} as watched" else "Mark as watched", "It leaves Continue watching", CosmoIcons.Check) {
                actions.launch { actions.repository.setWatched(item.key, item.summary, watched = true, atMs = System.currentTimeMillis(), episodeName = item.episode?.name) }
            },
            com.cosmotune.ui.orbit.OrbitMenuItem("Remove from Continue watching", "Forgets where you were", CosmoIcons.Close) {
                actions.launch { actions.repository.clearProgress(item.key) }
            },
        ),
/** Live now: three across on a wide page, stacked on the phone; a tap plays the channel. */
@Composable
private fun LiveNow(items: List<LiveNowItem>, wide: Boolean, fonts: OrbitFonts, look: OrbitLook, actions: WatchActions, clock: () -> Long, modifier: Modifier) {
    val shown = items.take(if (wide) 6 else 3)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.chunked(if (wide) 3 else 1).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEach { item -> LiveRow(item, fonts, look, clock, Modifier.weight(1f)) { actions.playChannel(item.channel) } }
                repeat((if (wide) 3 else 1) - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun LiveRow(item: LiveNowItem, fonts: OrbitFonts, look: OrbitLook, clock: () -> Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = item.channel
    WatchRowFrame(look, modifier, onClick = onClick) {
        WatchLogoTile(channelMark(c.name), logoFill(c.categories.firstOrNull() ?: c.name), fonts, logoUrl = c.logoUrl)
        Column(Modifier.weight(1f)) {
            Text(c.name, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WatchLiveLine(item.now?.title?.substringAfter(": "), fonts, look)
        }
    }
}

/**
 * What Watch home keeps for the process, past the page itself: each tab's scroll, and each row's scroll and the titles it loaded
 * beyond its first ones. Leaving for a title (or another tab) and coming back finds the rows as they were, not reloaded.
 */
internal object WatchHomeMemory {
    private val pages = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    private val rows = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    private val loaded = HashMap<String, androidx.compose.runtime.snapshots.SnapshotStateMap<Int, List<TitleSummary>>>()

    fun page(key: String) = synchronized(this) { pages.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() } }
    fun row(id: String) = synchronized(this) { rows.getOrPut(id) { androidx.compose.foundation.lazy.LazyListState() } }
    /** A row's titles by the skip they were asked from: 0 is a [HomeRow.deferred] row's first page. */
    fun loaded(id: String) = synchronized(this) { loaded.getOrPut(id) { mutableStateMapOf() } }

    private var sources: List<String>? = null

    /**
     * The switched-on sources now; true when they differ from the last ones seen in this run (an add-on installed here, or one
     * that arrived by cloud sync), and the rows and channels kept from before should then be fetched again.
     */
    fun sourcesChanged(now: List<String>): Boolean = synchronized(this) {
        val changed = sources != null && sources != now
        sources = now
        if (changed) loaded.clear()
        changed
    }
}

/**
 * A row of Watch home: posters (or wide stills), loading more as the end comes into view when the row says it can. A
 * [HomeRow.deferred] row loads its first titles when it is scrolled to (placeholders meanwhile), and is not drawn if it has none.
 */
@Composable
private fun HomeRowShelf(row: HomeRow, fonts: OrbitFonts, look: OrbitLook, side: Dp, actions: WatchActions, watched: Map<com.cosmotune.watch.model.WatchKey, Float> = emptyMap()) {
    val more = remember(row.id) { WatchHomeMemory.loaded(row.id) }
    if (row.deferred) LaunchedEffect(row.id) {
        if (more[0] == null) more[0] = runCatching { actions.repository.rowPage(row.id, 0) }.getOrDefault(emptyList())
    }
    val items = (row.items + more.entries.sortedBy { it.key }.flatMap { it.value }).distinctBy { it.key }
    if (row.deferred && more[0] == null) {
        Column {
            Section(row.title, row.subtitle, fonts, look, side)
            Row(Modifier.padding(horizontal = side), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(3) { WatchSkeleton(look, Modifier.width(if (row.wide) 250.dp else 142.dp).height(if (row.wide) 140.dp else 213.dp)) }
            }
        }
        return
    }
    if (items.isEmpty()) return
    val state = remember(row.id) { WatchHomeMemory.row(row.id) }
    if (row.pageable) LaunchedEffect(state, row.id) {
        var asked = -1
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to state.layoutInfo.totalItemsCount }.collect { (last, total) ->
            if (total > 0 && last >= total - 3 && asked != total) {
                asked = total
                val page = runCatching { actions.repository.rowPage(row.id, total) }.getOrDefault(emptyList())
                if (page.isNotEmpty()) more[total] = page
            }
        }
    }
    Column {
        Section(row.title, row.subtitle, fonts, look, side)
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(items, key = { i, it -> it.key.value + "#" + i }) { index, s ->
                WatchCard(
                    s.name, (if (row.id.contains(":regional10:") || row.id.contains(":today:")) "${index + 1} · " else "") + posterLine(s), if (row.wide) s.backdropUrl ?: s.posterUrl else s.posterUrl ?: s.backdropUrl, s.key.value, fonts, look,
                    onClick = { actions.open(s) }, width = if (row.wide) 250.dp else 142.dp, wide = row.wide, onPlay = { actions.play(s) },
                    progress = watched[s.key]?.takeIf { it > 0f && it < 1f },
                )
            }
        }
    }
}

@Composable
private fun HomeSkeleton(look: OrbitLook, frame: WatchFrame) {
    Column(Modifier.testTag("watch:home:loading").padding(horizontal = frame.side).padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        WatchSkeleton(look, Modifier.fillMaxWidth().height(if (frame.wide) 250.dp else 360.dp), radius = 34.dp)
        repeat(2) {
            WatchSkeleton(look, Modifier.width(200.dp).height(22.dp), radius = 11.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { repeat(if (frame.wide) 6 else 3) { WatchSkeleton(look, Modifier.width(132.dp).height(198.dp)) } }
        }
    }
}


/**
 * Watch home's rows for [filter] (Home, Films, Series, Live TV), kept for the process and refreshed after [FEED_FRESH_MS]. An
 * answer with nothing in it (every plugin offline) does not replace rows that were shown: it is a failed refresh.
 */
internal fun watchHomeEntry(repository: com.cosmotune.watch.WatchRepository, filter: HomeFilter, viewer: String = "default"): com.cosmotune.cache.CachedLoad<WatchHome> {
    val pages = watchPages(repository)
    val key = "watch:home:" + filter.name.lowercase() + ":" + viewer
    return pages.entry(key, com.cosmotune.cache.FEED_FRESH_MS, com.cosmotune.cache.CacheCodec.json(WatchHome.serializer())) {
        repository.home(filter).also { answer ->
            val shown = pages.peek<WatchHome>(key)?.state?.value?.data
            if (answer.empty && shown != null && !shown.empty) error("Nothing answered")
        }
    }
}
