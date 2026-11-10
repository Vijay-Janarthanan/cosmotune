package com.cosmotune.ui.watch

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitPortrait
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.WatchBadge
import com.cosmotune.ui.orbit.WatchCard
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchLogoTile
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchPicture
import com.cosmotune.ui.orbit.WatchProgressLine
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.WatchRowFrame
import com.cosmotune.ui.orbit.WatchSkeleton
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.logoFill
import com.cosmotune.watch.NextUp
import com.cosmotune.watch.SourcesState
import com.cosmotune.watch.WatchStreamFilter
import com.cosmotune.watch.watchMetadata
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProgress

/**
 * A film page (board section 2) or a series page (section 3): the colour card in the picture's colours with Play in the bite
 * (Resume where you were, or the next episode), My list, Watch together and more; then for a film the sources it can play from
 * (best first) with the cast and more like this, and for a series the seasons as chips and the episodes as rows.
 */
@Composable
internal fun WatchTitlePage(summary: TitleSummary, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = actions.repository
    var attempt by remember { mutableStateOf(0) }
    val title by produceState<Result<Title?>?>(null, summary.key, attempt) { value = runCatching { repository.title(summary.key) } }
    val page = title?.getOrNull()
    val shown = summary.withTitleDetails(page)
    val myList by repository.myList.collectAsState()
    val inList = myList.any { it.key == summary.key }
    val (fill, ink) = coverFill(shown.posterUrl ?: shown.backdropUrl, shown.key.value)
    val series = summary.type == TitleType.SERIES
    val nextUp by produceState<NextUp?>(null, summary.key, series) { if (series) value = runCatching { repository.nextUp(summary.key) }.getOrNull() }
    val target = remember(shown) { repository.targetOf(shown) }
    val sources by (if (series) remember { kotlinx.coroutines.flow.flowOf<SourcesState?>(null) } else remember(target) { repository.sources(target) })
        .collectAsState(null)
    val resume by produceState<WatchProgress?>(null, summary.key) { if (!series) value = runCatching { repository.progress(summary.key) }.getOrNull() }
    val side = frame.side
    val about: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (series) "About this series" else "About this film", style = fonts.section, color = look.ink)
            WatchTitleFacts(shown, page, fonts, look, showMissingPlot = title != null)
        }
    }

    val play: () -> Unit = {
        val n = nextUp
        when {
            series && n != null && page != null -> actions.playEpisode(page, n.episode)
            series && n != null -> actions.playEpisode(shown, n.episode)
            series -> Unit
            else -> actions.play(shown)
        }
    }

    Box(Modifier.fillMaxSize()) {
        WatchWash(fill, look)
        LazyColumn(Modifier.fillMaxSize().testTag("watch:title:page"), contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "title:header") { header() } }
            item(key = "title:card") {
                TitleCard(
                    shown, page, series, fonts, look, fill, ink, frame.wide, inList, nextUp, resume?.takeIf { !it.completed && it.positionMs > 60_000 },
                    sources?.best, onPlay = play, onMyList = { actions.setInMyList(shown, it) }, watchTogether = actions.watchTogetherReady,
                    onWatchTogether = { actions.watchTogether(shown, page, sources?.best) },
                    modifier = Modifier.padding(horizontal = side).padding(top = 18.dp),
                )
            }
            if (series) {
                item(key = "title:about") { about(Modifier.padding(horizontal = side).widthIn(max = 780.dp)) }
                when {
                    title == null -> item(key = "title:loading") {
                        Column(Modifier.padding(horizontal = side).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { WatchSkeleton(look, Modifier.fillMaxWidth().height(88.dp), radius = 22.dp) }
                        }
                    }
                    page == null -> item(key = "title:error") {
                        WatchMessage("Episodes", "Could not load episodes", "Episode details are unavailable from this catalogue.", fonts, look,
                            Modifier.padding(horizontal = side).padding(top = 24.dp), action = "Try again", onAction = { attempt++ })
                    }
                    else -> {
                        item(key = "title:episodes") { SeriesBody(page, nextUp, actions, fonts, look, side) }
                        item(key = "title:more") { PeopleAndMore(page, actions, fonts, look, frame) }
                    }
                }
            } else if (frame.wide) {
                item(key = "title:film") {
                    Row(Modifier.padding(horizontal = side), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(Modifier.weight(1f)) {
                            about(Modifier.widthIn(max = 780.dp))
                            page?.let { PeopleAndMore(it, actions, fonts, look, frame, inColumn = true) }
                        }
                        Column(Modifier.weight(1f).widthIn(max = 600.dp)) { PlayFrom(sources, shown, actions, fonts, look) }
                    }
                }
            } else {
                item(key = "title:about") { about(Modifier.padding(horizontal = side)) }
                page?.let { detail -> item(key = "title:more") { PeopleAndMore(detail, actions, fonts, look, frame) } }
                item(key = "title:sources") { Column(Modifier.padding(horizontal = side)) { PlayFrom(sources, shown, actions, fonts, look) } }
            }
            item(key = "title:end") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** "Film · 2021 · 1h 52m · Drama, Thriller · ★ 7.4 TMDB" or "Series · 3 seasons · 2019–2025 · ★ 8.1". */
internal fun pageLine(s: TitleSummary, page: Title?): String {
    val rating = s.rating?.let { "★ %.1f".format(it) + if (s.origin == "tmdb") " TMDB" else "" }
    return if (s.type == TitleType.SERIES) {
        val seasons = page?.seasons?.count { it.number > 0 }?.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }
        val years = page?.firstYear?.let { first -> page.lastYear?.takeIf { it != first }?.let { "$first–$it" } ?: "$first" } ?: s.year?.toString()
        listOfNotNull("Series", seasons, years, rating).joinToString(" · ")
    } else {
        listOfNotNull("Film", s.year?.toString(), runtimeText(s.runtimeMin), s.genres.take(2).joinToString(", ").ifBlank { null }, rating).joinToString(" · ")
    }
}

/** The badges of the best copy: its quality, its sound, how many subtitles. */
private fun streamBadges(best: Stream?): List<String> = if (best == null) emptyList() else listOfNotNull(
    best.qualityBadge, best.hdr, best.audioChannels, best.subtitles.size.takeIf { it > 0 }?.let { "CC · $it" },
)

@Composable
private fun TitleCard(
    s: TitleSummary,
    page: Title?,
    series: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    fill: androidx.compose.ui.graphics.Color,
    ink: androidx.compose.ui.graphics.Color,
    wide: Boolean,
    inList: Boolean,
    nextUp: NextUp?,
    resume: WatchProgress?,
    best: Stream?,
    onPlay: () -> Unit,
    onMyList: (Boolean) -> Unit,
    watchTogether: Boolean,
    onWatchTogether: () -> Unit,
    modifier: Modifier,
) {
    var more by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            // On the phone a film's line stands beside its poster instead.
            if (wide || series) OrbitLabel(pageLine(s, page), fonts, ink.copy(alpha = 0.75f))
            Text(s.name, style = fonts.heroTitle.copy(fontSize = if (wide) 42.sp else 30.sp, lineHeight = if (wide) 44.sp else 32.sp), color = ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            val line = if (series) nextUp?.let { n ->
                val left = n.progress?.takeIf { n.resume }?.let { " · " + leftText(it.remainingMs) }.orEmpty()
                "Next up: Season ${n.episode.season}, Episode ${n.episode.number} · “${n.episode.name}”$left"
            } ?: page?.tagline else page?.tagline
            line?.let {
                Text(it, style = fonts.body.copy(fontSize = 14.5.sp, lineHeight = 21.sp), color = ink.copy(alpha = 0.88f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp).widthIn(max = 620.dp))
            }
            val badges = listOfNotNull(page?.certification) + streamBadges(best)
            if (!series && badges.isNotEmpty()) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { badges.forEach { WatchBadge(it, fonts, ink) } }
            Row(
                Modifier.padding(top = 18.dp, end = if (wide) 0.dp else 84.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!series) WatchCardChip(resume?.let { "Resume ${longClock(it.positionMs)}" } ?: "Play", ink, fonts, onPlay, icon = CosmoIcons.Play, solid = true, onSolid = fill)
                WatchCardChip(if (inList) "In my list" else "My list", ink, fonts, onClick = { onMyList(!inList) }, icon = if (inList) CosmoIcons.Check else CosmoIcons.Plus)
                WatchCardChip("Watch together", ink, fonts, onClick = onWatchTogether, icon = CosmoIcons.Together, enabled = watchTogether)
                Box {
                    WatchRoundButton(CosmoIcons.More, "More", look, onClick = { more = true }, ink = ink)
                    OrbitMenu(
                        more, onDismiss = { more = false }, fonts = fonts, look = look, header = s.name,
                        items = buildList {
                            page?.trailers?.firstOrNull()?.let { t -> add(OrbitMenuItem("Trailer", t.name, CosmoIcons.Video) { uri.openUri("https://www.youtube.com/watch?v=${t.youTubeKey}") }) }
                            page?.whereToWatch?.forEach { w -> add(OrbitMenuItem("On ${w.name}", "Opens outside the app", CosmoIcons.Share, enabled = w.link != null) { w.link?.let(uri::openUri) }) }
                            add(OrbitMenuItem(if (inList) "Remove from My list" else "Add to My list", null, CosmoIcons.Plus) { onMyList(!inList) })
                        },
                    )
                }
            }
        }
    }
    OrbitBiteCard(fill, modifier.fillMaxWidth().then(if (wide) Modifier.height(if (series) 260.dp else 300.dp) else Modifier),
        bite = { if (!series || nextUp != null) OrbitPlayButton(false, onPlay, look) }) {
        if (wide) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (series) {
                    val w = (maxWidth * 0.36f).coerceAtMost(356.dp)
                    WatchPicture(s.backdropUrl ?: s.posterUrl, s.key.value + "b", Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 120.dp).size(w, w * 9f / 16f), radius = 22.dp)
                    text(Modifier.padding(start = 26.dp, top = 30.dp, end = w + 150.dp))
                } else {
                    WatchPicture(s.posterUrl, s.key.value, Modifier.padding(24.dp).size(168.dp, 252.dp), radius = 20.dp)
                    text(Modifier.padding(start = 220.dp, top = 34.dp, end = 120.dp))
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (series) WatchPicture(s.backdropUrl ?: s.posterUrl, s.key.value + "b", Modifier.padding(14.dp).fillMaxWidth().aspectRatio(16f / 9f), radius = 22.dp)
                else Row(Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp)) {
                    WatchPicture(s.posterUrl, s.key.value, Modifier.size(104.dp, 156.dp), radius = 18.dp)
                    Column(Modifier.padding(start = 16.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pageLine(s, page).split(" · ").forEach { OrbitLabel(it, fonts, ink.copy(alpha = 0.75f)) }
                    }
                }
                text(Modifier.padding(start = 20.dp, end = 20.dp, top = if (series) 0.dp else 16.dp, bottom = 22.dp))
            }
        }
    }
}

/** "Play from" (board section 2): the copies the plugins found, best first; the first has the accent wash and the mono "Best". */
@Composable
private fun PlayFrom(sources: SourcesState?, summary: TitleSummary, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook) {
    val uri = LocalUriHandler.current
    var filter by remember(summary.key) { mutableStateOf(WatchStreamFilter()) }
    val streams = sources?.streams.orEmpty()
    val matching = filter.applyTo(streams)
    Column(Modifier.padding(top = 26.dp)) {
        Text("Play from", style = fonts.section, color = look.ink, modifier = Modifier.padding(start = 6.dp))
        val pending = sources?.pending.orEmpty()
        val sub = when {
            sources == null -> "Asking your plugins…"
            sources.notFound -> "Not on your sources"
            pending.isNotEmpty() -> "Best first · ${pending.size} more still answering"
            else -> "Best first · tap to choose another"
        }
        Text(sub, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sources == null) repeat(3) { WatchSkeleton(look, Modifier.fillMaxWidth().height(62.dp), radius = 22.dp) }
            if (streams.isNotEmpty()) {
                WatchSourceFilters(streams, filter, { filter = it }, fonts, look)
                if (matching.isEmpty()) WatchCardChip("No matches · Reset filters", look.ink, fonts, onClick = { filter = WatchStreamFilter() })
            }
            matching.forEach { stream ->
                val onClick: (() -> Unit)? = when {
                    stream.isPlayable && sources != null -> ({ actions.playStream(summary, sources.target, stream) })
                    stream.url.startsWith("https://") -> ({ uri.openUri(stream.url) })
                    else -> null
                }
                SourceRow(stream, best = stream == sources?.best, fonts = fonts, look = look, titleRuntimeMin = summary.runtimeMin, onClick = onClick)
            }
            sources?.whereToWatch.orEmpty().takeIf { sources?.notFound == true }?.forEach { w ->
                WatchRowFrame(look, onClick = w.link?.let { link -> { uri.openUri(link) } }) {
                    WatchLogoTile(w.name.take(4).uppercase(), logoFill(w.name), fonts, logoUrl = w.logoUrl)
                    Column(Modifier.weight(1f)) {
                        Text(w.name, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1)
                        Text("Streams there · opens outside the app", style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1)
                    }
                }
            }
            sources?.errors.orEmpty().forEach { (plugin, message) ->
                Text("$plugin: $message", style = fonts.body.copy(fontSize = 12.sp), color = look.dim, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
internal fun SourceRow(
    stream: Stream, best: Boolean, fonts: OrbitFonts, look: OrbitLook,
    titleRuntimeMin: Int? = null, onClick: (() -> Unit)? = null,
) {
    WatchRowFrame(look, height = 76.dp, highlighted = best, onClick = onClick) {
        WatchLogoTile(stream.source.badge, logoFill(stream.source.badge), fonts)
        Column(Modifier.weight(1f)) {
            Text("${stream.source.pluginName} · ${stream.label}",
                style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detailStyle = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp)
            Text(stream.watchMetadata(titleRuntimeMin = titleRuntimeMin), style = detailStyle, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            stream.description?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let {
                Text(it, style = detailStyle, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (best) OrbitLabel("Best", fonts, look.accent)
    }
}

/** The cast as portraits and more like this as wide stills; in a column beside Play from on a wide film page. */
@Composable
internal fun PeopleAndMore(page: Title, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame, inColumn: Boolean = false) {
    val side = if (inColumn) 0.dp else frame.side
    Column {
        if (page.cast.isNotEmpty()) {
            Text("Cast", style = fonts.section, color = look.ink, modifier = Modifier.padding(start = side + 6.dp, top = 26.dp, bottom = 16.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(page.cast.take(16), key = { i, p -> p.name + i }) { _, person ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // The cast have no page of their own: the portrait is not a button that does nothing.
                        OrbitPortrait(person.name, person.photoUrl, 96.dp, fonts, look, onClick = null)
                        person.role?.let { Text(it, style = fonts.body.copy(fontSize = 11.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    }
                }
            }
        }
        if (page.moreLikeThis.isNotEmpty()) {
            Text("More like this", style = fonts.section, color = look.ink, modifier = Modifier.padding(start = side + 6.dp, top = 30.dp, bottom = 14.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(page.moreLikeThis, key = { i, it -> it.key.value + i }) { _, s ->
                    WatchCard(s.name, posterLine(s), s.backdropUrl ?: s.posterUrl, s.key.value, fonts, look, onClick = { actions.open(s) }, width = 170.dp, wide = true)
                }
            }
        }
    }
}

/** Seasons as chips, then the season's episodes as rows (board section 3). */
@Composable
private fun SeriesBody(page: Title, nextUp: NextUp?, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    val repository = actions.repository
    val seasons = page.seasons
    var season by rememberSaveable(page.key.value) { mutableStateOf<Int?>(null) }
    LaunchedEffect(nextUp, seasons) { if (season == null) season = nextUp?.episode?.season ?: seasons.firstOrNull { it.number > 0 }?.number ?: seasons.firstOrNull()?.number }
    val chosen = season ?: return
    val episodes by produceState<List<Episode>?>(null, page.key, chosen) { value = null; value = runCatching { repository.episodes(page.key, chosen) }.getOrDefault(emptyList()) }
    val progress by produceState<Map<WatchKey, WatchProgress>>(emptyMap(), episodes) {
        value = episodes.orEmpty().mapNotNull { e -> runCatching { repository.progress(e.key) }.getOrNull()?.let { e.key to it } }.toMap()
    }
    Column(Modifier.padding(top = 22.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.forEach { s -> OrbitSelectChip(if (s.number == 0) "Specials" else s.name, s.number == chosen, fonts, look, onClick = { season = s.number }, haptic = false) }
        }
        Column(Modifier.padding(horizontal = side).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val list = episodes
            if (list == null) repeat(4) { WatchSkeleton(look, Modifier.fillMaxWidth().height(88.dp), radius = 22.dp) }
            else if (list.isEmpty()) Text("No episodes listed for this season.", style = fonts.body, color = look.dim, modifier = Modifier.padding(6.dp))
            else list.forEach { e ->
                val next = nextUp?.episode?.key == e.key
                val p = progress[e.key]
                val before = nextUp != null && nextUp.episode.season == e.season && e.number < nextUp.episode.number
                EpisodeRow(e, p, next, watched = p?.completed == true || (p == null && before), fonts, look) { actions.playEpisode(page, e) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, progress: WatchProgress?, next: Boolean, watched: Boolean, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit) {
    WatchRowFrame(look, height = 88.dp, highlighted = next, onClick = onClick) {
        WatchPicture(e.stillUrl, e.key.value, Modifier.size(128.dp, 72.dp), radius = 14.dp)
        Column(Modifier.weight(1f)) {
            val label = listOfNotNull("E${e.number}", runtimeText(e.runtimeMin), if (next) "next up" else null).joinToString(" · ")
            OrbitLabel(label, fonts, if (next) look.accent else look.dim)
            Text(e.name, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            when {
                progress != null && !progress.completed && progress.fraction > 0f -> WatchProgressLine(progress.fraction, look, Modifier.padding(top = 8.dp).widthIn(max = 300.dp))
                watched -> Text("Watched", style = fonts.body.copy(fontSize = 12.sp), color = look.dim)
                else -> Text("New", style = fonts.body.copy(fontSize = 12.sp), color = look.dim)
            }
        }
    }
}
