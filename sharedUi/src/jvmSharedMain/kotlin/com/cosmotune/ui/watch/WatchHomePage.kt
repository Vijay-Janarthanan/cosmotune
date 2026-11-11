package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
    val filter = fixed ?: chosen
    val viewer by repository.activeProfile.collectAsState()
    // The rows of each tab stay for the process (and on the disk): coming back shows them at once while they refresh.
    val entry = remember(repository, filter, viewer) { watchHomeEntry(repository, filter, viewer) }
    val cached by entry.collectOnPage()
    val live by repository.continueWatching.collectAsState()
    val myList by repository.myList.collectAsState()
    val clock = LocalWatchClock.current
    val side = frame.side
    val data = cached.data
    Box(Modifier.fillMaxSize()) {
        WatchWash(data?.featured?.let { coverFill(it.summary.backdropUrl ?: it.summary.posterUrl, it.summary.key.value).first } ?: HOME_WASH, look)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "watch:header") { header() } }
            item(key = "watch:heading") {
                val label = remember(filter) {
                    Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE dd MMM — HH:mm", Locale.ENGLISH))
                }
                val title = when (fixed) { HomeFilter.FILMS -> "Films"; HomeFilter.SERIES -> "Series"; else -> "Tonight" }
                val chips: @Composable () -> Unit = {
                    if (fixed == null && frame.filterChips) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(HomeFilter.ALL to "All", HomeFilter.FILMS to "Films", HomeFilter.SERIES to "Series", HomeFilter.LIVE to "Live TV").forEach { (f, name) ->
                                OrbitSelectChip(name, chosen == f, fonts, look, onClick = { chosen = f }, haptic = false)
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
                                modifier = Modifier.padding(horizontal = side).padding(top = 22.dp),
                            )
                        }
                    }
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
                    if (data.liveNow.isNotEmpty()) item(key = "watch:live") {
                        Column {
                            Section("Live now", null, fonts, look, side)
                            LiveNow(data.liveNow, frame.wide, fonts, look, actions, clock, Modifier.padding(horizontal = side))
                        }
                    }
                    data.rows.forEach { row ->
                        if (row.items.isNotEmpty()) item(key = "watch:row:" + row.id) { HomeRowShelf(row, fonts, look, side, actions) }
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

/** The line under a poster: the year and the badge (or the first genre). */
internal fun posterLine(summary: TitleSummary): String =
    listOfNotNull(summary.year?.toString(), summary.rating?.takeIf { it.isFinite() && it in 0.0..10.0 }?.let {
        when (summary.origin) { "cinemeta" -> "IMDb %.1f".format(it); "tmdb" -> "TMDB %.1f".format(it); else -> null }
    }, summary.badge ?: summary.genres.firstOrNull()).joinToString(" · ")

/**
 * The featured card (board section 1): the colour of its picture, the wide picture inside it at the end (above the text on the
 * phone), the mono line, the title, the overview, the badges, My list and Watch together; Play in the bite.
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
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            OrbitLabel(summaryLine(s, "Featured"), fonts, ink.copy(alpha = 0.75f))
            Text(s.name, style = fonts.heroTitle.copy(fontSize = if (wide) 34.sp else 28.sp, lineHeight = if (wide) 36.sp else 30.sp), color = ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            s.overview?.let {
                Text(it, style = fonts.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = ink.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp))
            }
            if (featured.badges.isNotEmpty()) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                featured.badges.forEach { WatchBadge(it, fonts, ink) }
            }
            Row(Modifier.padding(top = 16.dp, end = if (wide) 0.dp else 80.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchCardChip(if (inList) "In my list" else "My list", ink, fonts, onClick = { onMyList(!inList) }, icon = if (inList) CosmoIcons.Check else CosmoIcons.Plus)
                WatchCardChip("Watch together", ink, fonts, onClick = onWatchTogether, icon = CosmoIcons.Together, enabled = watchTogether)
            }
        }
    }
    // Play sits in the bite, inside the card's bounds, so a D-pad's Right from the card would never find it: it is named.
    val cardFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    OrbitBiteCard(fill, modifier.fillMaxWidth().then(if (wide) Modifier.height(250.dp) else Modifier), onClick = onOpen,
        cardModifier = Modifier.focusRequester(cardFocus).focusProperties { right = playFocus },
        bite = { OrbitPlayButton(false, onPlay, look, Modifier.focusRequester(playFocus).focusProperties { left = cardFocus }) }) {
        if (wide) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pictureWidth = (maxWidth * 0.36f).coerceAtMost(340.dp)
                WatchPicture(s.backdropUrl ?: s.posterUrl, s.key.value + "b", Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 112.dp).size(pictureWidth, 210.dp), radius = 24.dp)
                text(Modifier.padding(start = 26.dp, top = 30.dp, end = pictureWidth + 140.dp).widthIn(max = 470.dp))
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                WatchPicture(s.backdropUrl ?: s.posterUrl, s.key.value + "b", Modifier.padding(14.dp).fillMaxWidth().aspectRatio(16f / 9f), radius = 22.dp)
                text(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 22.dp))
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

/** Live now: three across on a wide page, stacked on the phone; a tap plays the channel. */
@Composable
private fun LiveNow(items: List<LiveNowItem>, wide: Boolean, fonts: OrbitFonts, look: OrbitLook, actions: WatchActions, clock: () -> Long, modifier: Modifier) {
    val shown = items.take(if (wide) 6 else 4)
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

/** A row of Watch home: posters (or wide stills), loading more as the end comes into view when the row says it can. */
@Composable
private fun HomeRowShelf(row: HomeRow, fonts: OrbitFonts, look: OrbitLook, side: Dp, actions: WatchActions) {
    val more = remember(row.id) { mutableStateMapOf<Int, List<TitleSummary>>() }
    val items = (row.items + more.values.flatten()).distinctBy { it.key }
    val state = rememberLazyListState()
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
