package com.cosmotune.ui.watch

import com.cosmotune.ui.orbit.OrbitCollapsedHeader
import com.cosmotune.ui.orbit.OrbitCollapsingHeaderBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitChipRow
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
import com.cosmotune.ui.orbit.WatchCardChipRow
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

/** The tabs under a title's card. */
private enum class TitleTab(val label: String) { EPISODES("Episodes"), DETAILS("Details and cast"), MORE("More like this") }

/**
 * A film page (board frame 7) or a series page (frame 8): the colour card in the picture's colours with Play in its bite, the
 * page's one main action (Resume where you were, the next episode, or the first episode of a series not started), and the other
 * actions as one row of round buttons. A film then has its description folded to three lines, the best three copies to play from
 * (Show all for the rest), and the tabs Details and cast / More like this; a series has the tabs Episodes / More like this /
 * Details, the seasons as chips with Download episodes at their end, and the episodes as rows with how far each was watched.
 */
@Composable
internal fun WatchTitlePage(summary: TitleSummary, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    var animeMapping by remember { mutableStateOf(false) }
    var playlistPicker by remember { mutableStateOf(false) }
    val repository = actions.repository
    var attempt by remember { mutableStateOf(0) }
    val title by produceState<Result<Title?>?>(null, summary.key, attempt) { value = runCatching { repository.title(summary.key) } }
    val page = title?.getOrNull()
    val shown = summary.withTitleDetails(page)
    LaunchedEffect(page, shown.type) { if (page != null && shown.type != summary.type) WatchNav.retype(summary.key, shown.type) }
    val myList by repository.myList.collectAsState()
    val inList = myList.any { it.key == summary.key }
    val (fill, ink) = coverFill(shown.posterUrl ?: shown.backdropUrl, shown.key.value)
    val series = shown.type == TitleType.SERIES
    val nextUp by produceState<NextUp?>(null, shown.key, series) { if (series) value = runCatching { repository.nextUp(shown.key) }.getOrNull() }
    // A series nobody has started plays its first episode: the first of the first numbered season.
    val firstSeason = page?.seasons?.map { it.number }?.filter { it > 0 }?.minOrNull() ?: page?.seasons?.firstOrNull()?.number
    val firstEpisode by produceState<Episode?>(null, shown.key, series, firstSeason) {
        if (series && firstSeason != null) value = runCatching { repository.episodes(shown.key, firstSeason).minByOrNull { it.number } }.getOrNull()
    }
    val target = remember(shown) { repository.targetOf(shown) }
    val sources by (if (series) remember { kotlinx.coroutines.flow.flowOf<SourcesState?>(null) } else remember(target) { repository.sources(target) })
        .collectAsState(null)
    val resume by produceState<WatchProgress?>(null, summary.key) { if (!series) value = runCatching { repository.progress(summary.key) }.getOrNull() }
    val side = frame.side
    var tab by rememberSaveable(summary.key.value) { mutableStateOf(if (series) TitleTab.EPISODES else TitleTab.DETAILS) }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchTitleFacts(shown, page, fonts, look, showMissingPlot = title != null)
        }
    }

    val play: () -> Unit = {
        val n = nextUp
        val first = firstEpisode
        when {
            series && n != null && page != null -> actions.playEpisode(page, n.episode)
            series && n != null -> actions.playEpisode(shown, n.episode)
            series && first != null && page != null -> actions.playEpisode(page, first)
            series && first != null -> actions.playEpisode(shown, first)
            series -> Unit
            else -> actions.play(shown)
        }
    }
    val downloads = actions.downloads
    val best = sources?.best
    val download: (() -> Unit)? = if (!series && downloads != null && best != null && downloads.canDownload(best)) ({
        downloads.start(target, shown, best, listOfNotNull(best.source.pluginName, best.label).joinToString(" · "))
    }) else null

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        WatchWash(fill, look)
        LazyColumn(Modifier.fillMaxSize().testTag("watch:title:page"), state = list, contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "title:header") { header() } }
            item(key = "title:card") {
                TitleCard(
                    shown, page, series, fonts, look, fill, ink, frame.wide, inList, nextUp, firstEpisode, resume?.takeIf { !it.completed && it.positionMs > 60_000 },
                    best, onPlay = play, onMyList = { actions.setInMyList(shown, it) }, watchTogether = actions.watchTogetherReady,
                    onWatchTogether = { actions.watchTogether(shown, page, best) },
                    onDownload = download,
                    onTrailer = { actions.playTrailer(shown, it) },
                    onAnimeMapping = { animeMapping = true },
                    onPlaylist = { playlistPicker = true },
                    onRandom = { page?.let { p -> actions.launch { randomEpisode(p, actions.repository)?.let { e -> actions.playEpisode(p.summary, e) } } } },
                    modifier = Modifier.padding(horizontal = side).padding(top = 12.dp),
                )
            }
            if (!series) {
                shown.overview?.takeIf { it.isNotBlank() }?.let { text ->
                    item(key = "title:overview") { FoldedText(text, fonts, look, Modifier.padding(horizontal = side + 4.dp).padding(top = 14.dp).widthIn(max = 780.dp)) }
                }
                item(key = "title:sources") { Column(Modifier.padding(horizontal = side).widthIn(max = 780.dp)) { PlayFrom(sources, shown, actions, fonts, look) } }
            }
            item(key = "title:tabs") {
                val tabs = if (series) listOf(TitleTab.EPISODES, TitleTab.MORE, TitleTab.DETAILS) else listOf(TitleTab.DETAILS, TitleTab.MORE)
                OrbitChipRow(side, Modifier.padding(top = 22.dp)) {
                    tabs.forEach { t ->
                        val count = if (t == TitleTab.EPISODES) page?.seasons?.filter { it.number > 0 }?.sumOf { it.episodeCount }?.takeIf { it > 0 } else null
                        OrbitSelectChip(if (t == TitleTab.DETAILS && series) "Details" else t.label + (count?.let { " · $it" } ?: ""), tab == t, fonts, look,
                            onClick = { tab = t }, haptic = false)
                    }
                }
            }
            when {
                series && tab == TitleTab.EPISODES -> when {
                    title == null -> item(key = "title:loading") {
                        Column(Modifier.padding(horizontal = side).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { WatchSkeleton(look, Modifier.fillMaxWidth().height(81.dp), radius = 14.dp) }
                        }
                    }
                    page == null -> item(key = "title:error") {
                        WatchMessage("Episodes", "Could not load episodes", "Episode details are unavailable from this catalogue.", fonts, look,
                            Modifier.padding(horizontal = side).padding(top = 18.dp), action = "Try again", onAction = { attempt++ })
                    }
                    else -> item(key = "title:episodes") { SeriesBody(page, nextUp, actions, fonts, look, side) }
                }
                tab == TitleTab.MORE -> item(key = "title:more") {
                    val more = page?.moreLikeThis.orEmpty()
                    if (more.isEmpty()) Text(if (title == null) "Loading…" else "Nothing like this yet.", style = fonts.body, color = look.dim,
                        modifier = Modifier.padding(horizontal = side + 6.dp).padding(top = 18.dp))
                    else MoreLikeThis(more, actions, fonts, look, side)
                }
                else -> item(key = "title:details") {
                    Column {
                        details(Modifier.padding(horizontal = side + 6.dp).widthIn(max = 780.dp))
                        page?.let { Cast(it, fonts, look, side) }
                    }
                }
            }
            item(key = "title:end") { Spacer(Modifier.height(40.dp)) }
        }
        // Once the card has scrolled away, the title and Play stand at the top of the page (the board's scrolled series).
        val playable = !series || nextUp != null || firstEpisode != null
        OrbitCollapsingHeaderBar(list, OrbitCollapsedHeader(shown.posterUrl ?: shown.backdropUrl, shown.name, pageLine(shown, page).ifBlank { null }, false,
            play.takeIf { playable }, null), after = if (frame.header != null) 2 else 1, side = side)
    }
    if (animeMapping) {
        var season by remember { mutableStateOf("1") }
        var malId by remember { mutableStateOf("") }
        com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { animeMapping = false }, title = "Match anime season",
            message = "Enter the MyAnimeList ID for this exact season. Episode numbering must match that entry. Skips are offered only when the video's duration also matches. Leave the ID blank to clear a match.",
            confirmLabel = "Save match", dismissLabel = "Cancel", confirmEnabled = season.toIntOrNull()?.let { it in 1..100 } == true && (malId.isBlank() || malId.toIntOrNull()?.let { it in 1..1_000_000 } == true),
            onConfirm = { actions.launch { actions.repository.setAnimeMapping(summary.key, season.toInt(), malId.toIntOrNull()); animeMapping = false } }) {
            WatchField("Season", season, { season = it.filter(Char::isDigit).take(3) }, fonts, look)
            WatchField("MyAnimeList ID", malId, { malId = it.filter(Char::isDigit).take(7) }, fonts, look)
        }
    }
    if (playlistPicker) WatchPlaylistPicker(summary, actions, fonts, look) { playlistPicker = false }
}

/** A description folded to three lines, with More to open it (and Less to fold it again) when it runs longer. */
@Composable
private fun FoldedText(text: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    var long by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(text, style = fonts.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = look.dim, maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!open) long = it.hasVisualOverflow })
        if (long || open) Text(if (open) "Less" else "More", style = fonts.title.copy(fontSize = 14.sp), color = look.ink,
            modifier = Modifier.padding(top = 4.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open })
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

/**
 * What Play in the bite will do, said under the title: "Resume at 40:12 · 1h 12m left" for a film part-watched, "Resume S1 E3 ·
 * 24 min left" or "Play S2 E4 · …" for a series under way, "Play Season 1, Episode 1 · 24 episodes" for one not started.
 */
internal fun playLine(series: Boolean, nextUp: NextUp?, first: Episode?, resume: WatchProgress?, episodes: Int?): String? = when {
    series && nextUp != null -> nextUp.label + (nextUp.progress?.takeIf { nextUp.resume }?.let { " · " + leftText(it.remainingMs) } ?: " · ${nextUp.episode.name}")
    series && first != null -> "Play Season ${first.season}, Episode ${first.number}" + (episodes?.let { " · $it episodes" } ?: "")
    series -> null
    resume != null -> "Resume at ${longClock(resume.positionMs)} · " + leftText(resume.remainingMs)
    else -> null
}

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
    firstEpisode: Episode?,
    resume: WatchProgress?,
    best: Stream?,
    onPlay: () -> Unit,
    onMyList: (Boolean) -> Unit,
    watchTogether: Boolean,
    onWatchTogether: () -> Unit,
    onDownload: (() -> Unit)?,
    onTrailer: (com.cosmotune.watch.model.Trailer) -> Unit,
    onAnimeMapping: () -> Unit,
    onPlaylist: () -> Unit,
    onRandom: () -> Unit,
    modifier: Modifier,
) {
    var more by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val trailer = page?.trailers?.firstOrNull()
    val episodes = page?.seasons?.filter { it.number > 0 }?.sumOf { it.episodeCount }?.takeIf { it > 0 }
    val line = playLine(series, nextUp, firstEpisode, resume, episodes) ?: page?.tagline
    val badges = listOfNotNull(page?.certification) + streamBadges(best)
    val menu: @Composable () -> Unit = {
        OrbitMenu(
            more, onDismiss = { more = false }, fonts = fonts, look = look, header = s.name,
            items = buildList {
                if (series) trailer?.let { t -> add(OrbitMenuItem("Trailer", t.name, CosmoIcons.Video) { onTrailer(t) }) }
                if (series && episodes != null) add(OrbitMenuItem("Play a random episode", "From any season that has aired", CosmoIcons.Shuffle) { onRandom() })
                if (series) add(OrbitMenuItem("Anime opening and ending skips", "Match this season to MyAnimeList", CosmoIcons.Settings) { onAnimeMapping() })
                add(OrbitMenuItem("Add to Watch playlist", null, CosmoIcons.Plus) { onPlaylist() })
                page?.whereToWatch?.forEach { w -> add(OrbitMenuItem("On ${w.name}", "Opens outside the app", CosmoIcons.Share, enabled = w.link != null) { w.link?.let(uri::openUri) }) }
            },
        )
    }
    val cardActions = buildList {
        add(WatchCardAction(if (inList) CosmoIcons.Check else CosmoIcons.Plus, "My list", on = inList, description = if (inList) "Remove from My list" else "Add to My list") { onMyList(!inList) })
        add(WatchCardAction(CosmoIcons.Together, "Together", enabled = watchTogether, description = "Watch together", onClick = onWatchTogether))
        if (!series) add(WatchCardAction(CosmoIcons.Download, "Download", enabled = onDownload != null, description = "Download the best copy") { onDownload?.invoke() })
        if (!series && trailer != null) add(WatchCardAction(CosmoIcons.Video, "Trailer", description = "Play the trailer") { onTrailer(trailer) })
        add(WatchCardAction(CosmoIcons.More, "More", description = "More", anchor = { menu() }) { more = true })
    }
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            OrbitLabel(pageLine(s, page).removePrefix("Film · ").removePrefix("Series · "), fonts, ink.copy(alpha = 0.75f))
            Text(s.name, style = fonts.heroTitle.copy(fontSize = if (wide) 40.sp else 28.sp, lineHeight = if (wide) 42.sp else 30.sp), color = ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            line?.let {
                Text(it, style = fonts.body.copy(fontSize = 13.5.sp, lineHeight = 19.sp), color = ink.copy(alpha = 0.88f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).widthIn(max = 620.dp))
            }
            if (badges.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { badges.take(5).forEach { WatchBadge(it, fonts, ink) } }
        }
    }
    // The page's one main action is Play in the bite: there for a film always, and for a series once it knows which episode.
    val playable = !series || nextUp != null || firstEpisode != null
    // How far a film is, on its poster (the time is in the line under the title).
    val watched = resume?.fraction?.takeIf { !series && it > 0f }
    OrbitBiteCard(fill, modifier.fillMaxWidth(), bite = { if (playable) OrbitPlayButton(false, onPlay, look) }) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 18.dp)) {
            Row {
                Box(Modifier.size(if (wide) 132.dp else 88.dp, if (wide) 198.dp else 132.dp)) {
                    WatchPicture(s.posterUrl ?: s.backdropUrl, s.key.value, Modifier.fillMaxSize(), radius = 14.dp)
                    watched?.let { WatchPictureProgress(it, look.accent) }
                }
                text(Modifier.padding(start = 14.dp, top = 2.dp).weight(1f))
            }
            WatchCardActions(cardActions, ink, fill, fonts, Modifier.padding(top = 14.dp))
        }
    }
}

/** "Play from" (board frame 7): the best three copies the plugins found, best first, the first highlighted; Show all for the rest. */
@Composable
private fun PlayFrom(sources: SourcesState?, summary: TitleSummary, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook) {
    val uri = LocalUriHandler.current
    var filter by remember(summary.key) { mutableStateOf(WatchStreamFilter()) }
    var all by rememberSaveable(summary.key.value) { mutableStateOf(false) }
    val streams = sources?.streams.orEmpty()
    val matching = filter.applyTo(streams)
    Column(Modifier.padding(top = 22.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text("Play from", style = fonts.section, color = look.ink)
                val pending = sources?.pending.orEmpty()
                val sub = when {
                    sources == null -> "Asking your plugins…"
                    sources.notFound -> "Not on your sources"
                    pending.isNotEmpty() -> "${streams.size} found · ${pending.size} more still answering"
                    streams.size == 1 -> "1 copy"
                    else -> "${streams.size} copies · best first"
                }
                Text(sub, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, modifier = Modifier.padding(top = 2.dp))
            }
            if (matching.size > PLAY_FROM_SHOWN) WatchCardChip(if (all) "Show fewer" else "Show all ${matching.size}", look.ink, fonts, onClick = { all = !all })
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (sources == null) repeat(3) { com.cosmotune.ui.orbit.OrbitRowSkeleton(it) }
            if (all && streams.isNotEmpty()) {
                WatchSourceFilters(streams, filter, { filter = it }, fonts, look)
                if (matching.isEmpty()) WatchCardChip("No matches · Reset filters", look.ink, fonts, onClick = { filter = WatchStreamFilter() })
            }
            (if (all) matching else matching.take(PLAY_FROM_SHOWN)).forEach { stream ->
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
            if (all) sources?.errors.orEmpty().forEach { (plugin, message) ->
                Text("$plugin: $message", style = fonts.body.copy(fontSize = 12.sp), color = look.dim, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** How many copies "Play from" lists before Show all. */
private const val PLAY_FROM_SHOWN = 3

@Composable
internal fun SourceRow(
    stream: Stream, best: Boolean, fonts: OrbitFonts, look: OrbitLook,
    titleRuntimeMin: Int? = null, onClick: (() -> Unit)? = null,
) {
    // A flat row: the best copy is said by its peach name and "Best", not a wash.
    WatchRowFrame(look, height = 76.dp, highlighted = best, onClick = onClick) {
        WatchLogoTile(stream.source.badge, logoFill(stream.source.badge), fonts)
        Column(Modifier.weight(1f)) {
            Text("${stream.source.pluginName} · ${stream.label}",
                style = fonts.title.copy(fontSize = 14.sp), color = if (best) look.accent else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detailStyle = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp)
            Text(stream.watchMetadata(titleRuntimeMin = titleRuntimeMin), style = detailStyle, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            stream.description?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let {
                Text(it, style = detailStyle, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (best) OrbitLabel("Best", fonts, look.accent)
    }
}

/** The cast as portraits and more like this as wide stills; in a column beside the player on a wide page. */
@Composable
internal fun PeopleAndMore(page: Title, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame, inColumn: Boolean = false) {
    val side = if (inColumn) 0.dp else frame.side
    Column {
        Cast(page, fonts, look, side)
        if (page.moreLikeThis.isNotEmpty()) {
            Text("More like this", style = fonts.section, color = look.ink, modifier = Modifier.padding(start = side + 6.dp, top = 30.dp, bottom = 14.dp))
            MoreLikeThis(page.moreLikeThis, actions, fonts, look, side)
        }
    }
}

/** The cast as portraits in a sideways row (they have no page of their own, so a portrait is not a button). */
@Composable
private fun Cast(page: Title, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    if (page.cast.isEmpty()) return
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

/** More like this as wide stills in a sideways row. */
@Composable
private fun MoreLikeThis(items: List<TitleSummary>, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    LazyRow(Modifier.padding(top = 16.dp), contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(items, key = { i, it -> it.key.value + i }) { _, s ->
            WatchCard(s.name, posterLine(s), s.backdropUrl ?: s.posterUrl, s.key.value, fonts, look, onClick = { actions.open(s) }, width = 170.dp, wide = true)
        }
    }
}

/**
 * The seasons as chips with Download episodes at their end, then the chosen season's episodes as rows (board frame 8): the still
 * with how far it was watched along its foot (a tick when it was watched to the end), the number and name, and the length with
 * one line of what happens, or the time left on the one in progress; the next one up is highlighted.
 */
@Composable
private fun SeriesBody(page: Title, nextUp: NextUp?, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    var batchOpen by remember { mutableStateOf(false) }
    val repository = actions.repository
    val seasons = page.seasons
    var season by rememberSaveable(page.key.value) { mutableStateOf<Int?>(null) }
    LaunchedEffect(nextUp, seasons) { if (season == null) season = nextUp?.episode?.season ?: seasons.firstOrNull { it.number > 0 }?.number ?: seasons.firstOrNull()?.number }
    val chosen = season ?: return
    val episodes by produceState<List<Episode>?>(null, page.key, chosen) { value = null; value = runCatching { repository.episodes(page.key, chosen) }.getOrDefault(emptyList()) }
    // Read again after Mark as watched changes one.
    var marks by remember { mutableStateOf(0) }
    val progress by produceState<Map<WatchKey, WatchProgress>>(emptyMap(), episodes, marks) {
        value = episodes.orEmpty().mapNotNull { e -> runCatching { repository.progress(e.key) }.getOrNull()?.let { e.key to it } }.toMap()
    }
    val mark: (List<Episode>, Boolean) -> Unit = { list, watched ->
        actions.launch {
            list.forEach { e -> repository.setWatched(e.key, page.summary, watched, System.currentTimeMillis(), e.name) }
            marks++
        }
    }
    if (batchOpen) WatchBatchSheet(page, chosen, episodes.orEmpty(), actions, fonts, look) { batchOpen = false }
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = side), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                seasons.forEach { s -> OrbitSelectChip(if (s.number == 0) "Specials" else s.name, s.number == chosen, fonts, look, onClick = { season = s.number }, haptic = false) }
            }
            if (actions.downloads != null && !episodes.isNullOrEmpty()) {
                WatchRoundButton(CosmoIcons.Download, "Download episodes", look, onClick = { batchOpen = true }, modifier = Modifier.padding(start = 8.dp))
            }
            if (!episodes.isNullOrEmpty()) Box(Modifier.padding(start = 8.dp)) {
                var seasonMenu by remember { mutableStateOf(false) }
                WatchRoundButton(CosmoIcons.More, "More for this season", look, onClick = { seasonMenu = true })
                val aired = episodes.orEmpty().filter { e -> e.airDate.let { it == null || it <= java.time.LocalDate.now().toString() } }
                OrbitMenu(seasonMenu, { seasonMenu = false }, fonts = fonts, look = look, header = if (chosen == 0) "Specials" else "Season $chosen", items = listOf(
                    OrbitMenuItem("Mark season as watched", "${aired.size} episodes", CosmoIcons.Check, enabled = aired.isNotEmpty()) { mark(aired, true) },
                    OrbitMenuItem("Mark season as not watched", null, CosmoIcons.Close) { mark(episodes.orEmpty(), false) },
                ))
            }
        }
        Column(Modifier.padding(horizontal = side).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val list = episodes
            if (list == null) repeat(4) { WatchSkeleton(look, Modifier.fillMaxWidth().height(81.dp), radius = 14.dp) }
            else if (list.isEmpty()) Text("No episodes listed for this season.", style = fonts.body, color = look.dim, modifier = Modifier.padding(6.dp))
            else list.forEach { e ->
                val next = nextUp?.episode?.key == e.key
                val p = progress[e.key]
                val before = nextUp != null && nextUp.episode.season == e.season && e.number < nextUp.episode.number
                val watched = p?.completed == true || (p == null && before)
                EpisodeRow(e, p, next, watched, fonts, look, onMark = { mark(listOf(e), !watched) }) { actions.playEpisode(page, e) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, progress: WatchProgress?, next: Boolean, watched: Boolean, fonts: OrbitFonts, look: OrbitLook, onMark: () -> Unit, onClick: () -> Unit) {
    WatchRowFrame(look, height = 97.dp, highlighted = next, onClick = onClick) {
        Box(Modifier.size(144.dp, 81.dp)) {
            WatchPicture(e.stillUrl, e.key.value, Modifier.fillMaxSize(), radius = 14.dp)
            val fraction = when { watched -> 1f; progress != null && progress.fraction > 0f -> progress.fraction; else -> null }
            fraction?.let { WatchPictureProgress(it, look.accent) }
            if (watched) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(look.accent), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(CosmoIcons.Check, "Watched", tint = look.ground, modifier = Modifier.size(14.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text("${e.number}. ${e.name}", style = fonts.title.copy(fontSize = 14.sp, lineHeight = 18.sp), color = if (next) look.accent else look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val inProgress = progress != null && !progress.completed && progress.fraction > 0f
            val second = if (inProgress) leftText(progress!!.remainingMs) else e.overview?.lineSequence()?.firstOrNull { it.isNotBlank() }
            Text(listOfNotNull(runtimeText(e.runtimeMin), second).joinToString(" · "), style = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = if (next) look.accent else look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        WatchRoundButton(CosmoIcons.Check, if (watched) "Mark episode ${e.number} as not watched" else "Mark episode ${e.number} as watched", look,
            onClick = onMark, on = watched, size = 34.dp)
    }
}

/** A random episode of [page] that has aired, from a random numbered season (specials left out); null when none is listed. */
internal suspend fun randomEpisode(page: Title, repository: com.cosmotune.watch.WatchRepository, random: kotlin.random.Random = kotlin.random.Random.Default): Episode? {
    val today = java.time.LocalDate.now().toString()
    val seasons = page.seasons.filter { it.number > 0 && it.episodeCount > 0 }.shuffled(random)
    for (season in seasons) {
        val aired = runCatching { repository.episodes(page.key, season.number) }.getOrDefault(emptyList())
            .filter { e -> e.airDate.let { it == null || it <= today } }
        if (aired.isNotEmpty()) return aired.random(random)
    }
    return null
}
