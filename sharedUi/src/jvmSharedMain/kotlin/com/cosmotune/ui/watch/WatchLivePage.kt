package com.cosmotune.ui.watch

import com.cosmotune.cache.CacheCodec
import com.cosmotune.cache.LIVE_FRESH_MS
import com.cosmotune.cache.SLOW_FRESH_MS
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.states.RefreshFailedNote
import com.cosmotune.ui.states.collectOnPage
import com.cosmotune.watch.GuideRow
import kotlinx.serialization.builtins.ListSerializer
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.orbit.WatchLiveLine
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchLogoTile
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchSkeleton
import com.cosmotune.ui.orbit.WatchRowFrame
import com.cosmotune.ui.orbit.channelMark
import com.cosmotune.ui.orbit.logoFill
import com.cosmotune.watch.ChannelFacets
import com.cosmotune.watch.ChannelFilter
import com.cosmotune.watch.GuideWindow
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import kotlinx.coroutines.delay
import com.cosmotune.watch.plugin.Capability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The Live TV wash (board section 4): a deep red. */
private val LIVE_WASH = Color(0xFF962832)

/** How long the guide runs from now. */
private const val GUIDE_HOURS = 3

/**
 * Live TV and the guide (board section 4): the channel count and "Live TV.", chips for country, language, category and
 * favourites, then the guide: channels down, time across from now, what is on now in peach. A channel (or a programme on now)
 * plays when chosen; the star keeps it in Favourites.
 */
@Composable
internal fun WatchLivePage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = actions.repository
    val clock = LocalWatchClock.current
    // The chips, the channel list and the guide stay for the process (and on the disk): coming back shows them at once while
    // they refresh in the background.
    val pages = remember(repository) { watchPages(repository) }
    val facetsEntry = remember(pages) {
        pages.entry("watch:live:facets", SLOW_FRESH_MS, CacheCodec.json(ChannelFacets.serializer())) { repository.channelFacets() }
    }
    val facets = facetsEntry.collectOnPage().value.data
    val plugins by repository.plugins.collectAsState()
    val favourites by repository.favouriteChannels.collectAsState()
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var favouritesOnly by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableIntStateOf(60) }
    // The phone can show the guide as a plain list of what is on now instead of the grid.
    var asList by rememberSaveable { mutableStateOf(false) }
    fun showAllChannels() {
        country = null
        language = null
        category = null
        favouritesOnly = false
    }
    val filter = ChannelFilter(country, language, category, favouritesOnly)
    val filterKey = listOf(country, language, category, if (favouritesOnly) favourites.map { it.value }.sorted().hashCode() else null).joinToString("|")
    val channelsEntry = remember(pages, filterKey) {
        pages.entry("watch:live:channels:$filterKey", SLOW_FRESH_MS, CacheCodec.json(ListSerializer(Channel.serializer()))) { repository.channels(filter) }
    }
    val channelsState by channelsEntry.collectOnPage()
    // A list that could not load at all reads as empty, as before; one that loaded once stays.
    val channels = channelsState.data ?: if (channelsState.failedEmpty) emptyList() else null
    val now = remember { clock() }
    val from = now / 60_000 * 60_000
    val to = from + GUIDE_HOURS * 3_600_000L
    val guideEntry = remember(pages, filterKey, shown) {
        pages.entry<GuideWindow>("watch:live:guide:$filterKey:$shown", LIVE_FRESH_MS, CacheCodec.json(GuideWindow.serializer())) {
            val list = channelsEntry.state.value.data.orEmpty()
            runCatching { repository.guide(list.take(shown), from, to) }.getOrNull() ?: GuideWindow(from, to, emptyList())
        }
    }
    val guideState by guideEntry.state.collectAsUiState()
    val channelKeys = channels?.take(shown)?.map { it.key.value }
    LaunchedEffect(guideEntry, channelKeys) {
        if (channelKeys.isNullOrEmpty()) return@LaunchedEffect
        guideEntry.restore()
        // A guide kept for another list (the channels refreshed since) is fetched again at once.
        val held = guideEntry.state.value.data?.rows?.map { it.channel.key.value }
        if (held != null && held != channelKeys) guideEntry.refresh() else guideEntry.load()
    }
    // The guide as it was (if it still reaches past now), else the channels alone until their programmes arrive: the list never
    // waits on the guide.
    val guide = guideState.data?.takeIf { it.toMs > now && it.rows.isNotEmpty() }
        ?: channels?.let { list -> GuideWindow(from, to, list.take(shown).map { GuideRow(it, emptyList()) }) }
    val yours = plugins.count { !it.builtIn && it.enabled && Capability.LIVE in it.capabilities }
    val side = frame.side
    val scroll = rememberScrollState()
    // The channel last played from the guide: when a host keeps this page under its player (the TV), Back from the player
    // puts focus back on that channel's cell rather than on the top of the page.
    var played by rememberSaveable { mutableStateOf<String?>(null) }
    val playedFocus = remember { FocusRequester() }
    val playing = WatchNav.stack.collectAsState().value.lastOrNull() is WatchRoute.Play
    var wasPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(playing) {
        if (playing) wasPlaying = true
        else if (wasPlaying) {
            wasPlaying = false
            delay(200)
            runCatching { playedFocus.requestFocus() }
        }
    }
    val playChannel: (Channel) -> Unit = { channel ->
        played = channel.key.value
        actions.playChannel(channel)
    }
    Box(Modifier.fillMaxSize()) {
        WatchWash(LIVE_WASH, look)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "live:header") { header() } }
            item(key = "live:heading") {
                val total = facets?.total
                val label = listOfNotNull(total?.let { "%,d free channels".format(it) }, yours.takeIf { it > 0 }?.let { if (it == 1) "1 playlist of yours" else "$it playlists of yours" })
                    .joinToString(" · ").ifBlank { "Live TV" }
                OrbitPageHeading("Live TV", label, fonts, look, Modifier.padding(start = side + 6.dp, end = side, top = if (frame.wide) 24.dp else 18.dp),
                    trailing = if (frame.wide) null else ({ GuideOrList(asList, { asList = it }, look) }))
            }
            if (!frame.wide) item(key = "live:filters") {
                // The phone: three dropdowns and Favourites rather than one long row of mixed chips.
                val f = facets
                OrbitChipRow(side, Modifier.padding(top = 18.dp)) {
                    FacetChip("Country", Icons.Rounded.Public, country, f?.countries.orEmpty().sortedByDescending { it.yours }, { it }, fonts, look) { country = it }
                    FacetChip("Language", Icons.Rounded.Translate, language, f?.languages.orEmpty().sortedByDescending { it.yours }, { it }, fonts, look) { language = it }
                    FacetChip("Category", Icons.Rounded.Category, category, f?.categories.orEmpty(), ::categoryName, fonts, look) { category = it }
                    OrbitSelectChip("Favourites", favouritesOnly, fonts, look, onClick = { favouritesOnly = !favouritesOnly }, icon = Icons.Rounded.Star, haptic = false)
                }
            }
            if (frame.wide) item(key = "live:chips") {
                val f = facets
                OrbitChipRow(side, Modifier.padding(top = 18.dp)) {
                    OrbitSelectChip("All channels", country == null && language == null && category == null && !favouritesOnly,
                        fonts, look, onClick = ::showAllChannels, haptic = false)
                    // The viewer's own country and language first (India, Tamil), then the countries and languages with most channels.
                    val countryChip: @Composable (com.cosmotune.watch.Facet) -> Unit = { c -> OrbitSelectChip(c.name, country == c.id, fonts, look, onClick = { country = c.id.takeIf { country != it } }, haptic = false) }
                    val languageChip: @Composable (com.cosmotune.watch.Facet) -> Unit = { l -> OrbitSelectChip(l.name, language == l.id, fonts, look, onClick = { language = l.id.takeIf { language != it } }, haptic = false) }
                    f?.countries?.filter { it.yours }?.forEach { countryChip(it) }
                    f?.languages?.filter { it.yours }?.forEach { languageChip(it) }
                    f?.countries?.filterNot { it.yours }?.take(3 - (f.countries.count { it.yours }).coerceAtMost(1))?.forEach { countryChip(it) }
                    f?.languages?.filterNot { it.yours }?.take(3 - (f.languages.count { it.yours }).coerceAtMost(1))?.forEach { languageChip(it) }
                    if (f != null && f.languages.isNotEmpty()) OrbitSelectChip("All languages", language == null, fonts, look, onClick = { language = null }, haptic = false)
                    f?.categories?.take(6)?.forEach { c -> OrbitSelectChip(categoryName(c.name), category == c.id, fonts, look, onClick = { category = c.id.takeIf { category != it } }, haptic = false) }
                    OrbitSelectChip("Favourites", favouritesOnly, fonts, look, onClick = { favouritesOnly = !favouritesOnly }, icon = CosmoIcons.Pin, haptic = false)
                }
            }
            if (channelsState.refreshFailed) item(key = "live:refresh-failed") {
                RefreshFailedNote(onRetry = { channelsEntry.refresh() }, modifier = Modifier.padding(start = side + 6.dp, top = 14.dp))
            }
            item(key = "live:guide") {
                val list = channels
                val window = guide
                Box(Modifier.padding(horizontal = side).padding(top = 20.dp)) {
                    when {
                        list == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(6) { WatchSkeleton(look, Modifier.fillMaxWidth().height(64.dp), radius = 18.dp) }
                        }
                        list.isEmpty() -> WatchMessage("No channels", if (favouritesOnly) "No favourites yet" else "No channels here",
                            if (favouritesOnly) "Star a channel in the guide to keep it here." else "Try another country, language or category, or switch on more plugins.", fonts, look,
                            action = "Show every channel", onAction = ::showAllChannels)
                        window != null && asList && !frame.wide -> GuideList(window, now, fonts, look, clock, playChannel)
                        window != null -> GuideGrid(window, now, favourites, frame.wide, scroll, fonts, look, actions, played, playedFocus, playChannel)
                    }
                }
            }
            if ((channels?.size ?: 0) > shown) item(key = "live:more") {
                Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                    WatchCardChip("Show ${minOf(60, channels!!.size - shown)} more channels", look.ink, fonts, onClick = { shown += 60 })
                }
            }
            item(key = "live:end") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** "free" under a DD channel (Prasar Bharati's own streams), "YouTube" under a broadcaster's YouTube live; null for the rest. */
private fun liveTag(channel: Channel): String? = when (channel.pluginId) {
    com.cosmotune.watch.plugin.BuiltInPlugins.PRASAR_BHARATI -> "free"
    com.cosmotune.watch.plugin.BuiltInPlugins.YOUTUBE_LIVE -> "YouTube"
    else -> null
}

/** What an official YouTube live channel with no guide is showing all day: "Live news 24×7" for news, "Live 24×7" otherwise. */
private fun alwaysLiveLine(channel: Channel): String? =
    if (channel.pluginId != com.cosmotune.watch.plugin.BuiltInPlugins.YOUTUBE_LIVE) null
    else if (channel.categories.any { it.equals("news", ignoreCase = true) }) "Live news 24×7" else "Live 24×7"

/** The board's names for the categories sources give: Movies are Films, Sports is Sport. */
private fun categoryName(name: String): String = when (name.lowercase()) { "movies" -> "Films"; "sports" -> "Sport"; else -> name }

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME)

/**
 * The guide grid (board `.guide`): one card, a head row with "Now · 20:40" and the half hours, then a row per channel with its
 * logo and name and its programmes laid out to scale. Every row scrolls sideways together.
 */
@Composable
private fun GuideGrid(
    window: GuideWindow, now: Long, favourites: Set<com.cosmotune.watch.model.WatchKey>, wide: Boolean, scroll: ScrollState, fonts: OrbitFonts, look: OrbitLook,
    actions: WatchActions, played: String?, playedFocus: FocusRequester, playChannel: (Channel) -> Unit,
) {
    // A channel that keeps a catch-up archive: its programme on now opens the choice of live, from the start, or earlier today.
    var archive by remember { mutableStateOf<Channel?>(null) }
    archive?.let { channel -> WatchCatchupDialog(channel, now, actions, fonts, look, onLive = { archive = null; playChannel(channel) }) { archive = null } }
    val perMinute = if (wide) 8.5.dp else 5.dp
    // The phone's channel column is the logo alone (its name is the button's), so about two programmes show beside it.
    val channelWidth = if (wide) 220.dp else 68.dp
    val span = ((window.toMs - window.fromMs) / 60_000).toInt()
    val gridWidth = perMinute * span
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(look.row)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (wide) OrbitLabel("Channel", fonts, look.dim, Modifier.width(channelWidth).padding(start = 16.dp)) else Spacer(Modifier.width(channelWidth))
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                Box(Modifier.width(gridWidth).fillMaxHeight()) {
                    OrbitLabel("Now · ${hm(now)}", fonts, look.accent, Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
                    val half = 30 * 60_000L
                    var t = (window.fromMs / half + 1) * half
                    while (t < window.toMs) {
                        val x = perMinute * ((t - window.fromMs) / 60_000).toInt()
                        if (x > 110.dp) OrbitLabel(hm(t), fonts, look.dim, Modifier.align(Alignment.CenterStart).offset(x = x + 6.dp))
                        t += half
                    }
                }
            }
        }
        window.rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(look.ink.copy(alpha = 0.05f)))
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelCell(row.channel, row.channel.key in favourites, channelWidth, wide, fonts, look, onPlay = { playChannel(row.channel) },
                    onStar = { actions.favourite(row.channel, it) },
                    modifier = if (row.channel.key.value == played) Modifier.focusRequester(playedFocus) else Modifier)
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                    Box(Modifier.width(gridWidth).fillMaxHeight()) {
                        val always = alwaysLiveLine(row.channel)
                        if (row.programmes.isEmpty() && always != null) {
                            // An official YouTube live stream has no guide but is on all day: one cell on now across the window.
                            ProgrammeCell(null, always, "On air now", true, gridWidth, 0.dp, fonts, look) { playChannel(row.channel) }
                        } else if (row.programmes.isEmpty()) {
                            // A quiet line, not a cell: there is nothing to choose here.
                            Text("No guide", style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, maxLines = 1,
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 15.dp))
                        }
                        row.programmes.forEach { p ->
                            val start = maxOf(p.startMs, window.fromMs)
                            val stop = minOf(p.stopMs, window.toMs)
                            if (stop > start) {
                                val x = perMinute * ((start - window.fromMs) / 60_000).toInt()
                                val w = perMinute * ((stop - start) / 60_000).toInt()
                                val on = p.isOnAt(now)
                                ProgrammeCell(p, p.title.substringAfter(": "), if (on) "${hm(p.startMs)} – ${hm(p.stopMs)}" else hm(p.startMs), on, w, x, fonts, look) {
                                    if (on && row.channel.keepsArchive) archive = row.channel
                                    else if (on || p.startMs <= now) playChannel(row.channel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelCell(channel: Channel, favourite: Boolean, width: Dp, wide: Boolean, fonts: OrbitFonts, look: OrbitLook, onPlay: () -> Unit, onStar: (Boolean) -> Unit,
    modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.width(width).fillMaxHeight().cosmoFocusRing(interaction, 16.dp).clip(RoundedCornerShape(16.dp))
            .combinedClickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClickLabel = "Play ${channel.name}",
                onLongClickLabel = if (favourite) "Remove from Favourites" else "Add to Favourites", onLongClick = { onStar(!favourite) }, onClick = onPlay)
            .semantics { contentDescription = channel.name }
            .padding(start = if (wide) 12.dp else 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WatchLogoTile(channelMark(channel.name), logoFill(channel.categories.firstOrNull() ?: channel.name), fonts, logoUrl = channel.logoUrl, size = if (wide) 46.dp else 44.dp)
        val live = liveTag(channel)
        if (wide) Column(Modifier.weight(1f)) {
            Text(channel.name, style = fonts.title.copy(fontSize = 13.5.sp, lineHeight = 17.sp), color = look.ink, maxLines = if (live != null) 1 else 2, overflow = TextOverflow.Ellipsis)
            // The free Indian channels and the official YouTube live ones say so under their name (board section 2).
            if (live != null) WatchLiveLine(live, fonts, look, Modifier.padding(top = 2.dp))
        }
        if (wide) {
            val star = remember { MutableInteractionSource() }
            Box(
                Modifier.cosmoFocusRing(star, 14.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(interactionSource = star, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Checkbox,
                        onClickLabel = if (favourite) "Remove from Favourites" else "Add to Favourites") { onStar(!favourite) }
                    .padding(6.dp),
            ) { Text(if (favourite) "★" else "☆", style = fonts.title.copy(fontSize = 16.sp), color = if (favourite) look.accent else look.dim) }
        }
    }
}

@Composable
private fun ProgrammeCell(p: EpgProgramme?, title: String, time: String?, now: Boolean, width: Dp, x: Dp, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // The genre tints the cell softly (Kodi's guide colours), and its name follows the time, so colour is never the only sign.
    val genre = p?.let { com.cosmotune.watch.model.EpgGenre.of(it.categories) }
    Box(
        Modifier.offset(x = x).padding(horizontal = 3.dp, vertical = 6.dp).width((width - 6.dp).coerceAtLeast(12.dp)).fillMaxHeight()
            .cosmoFocusRing(interaction, 16.dp).clip(RoundedCornerShape(16.dp))
            .background(when {
                now -> look.accent.copy(alpha = if (look.dark) 0.18f else 0.16f)
                genre != null -> genreHue(genre).copy(alpha = if (look.dark) 0.16f else 0.13f)
                else -> look.ink.copy(alpha = 0.06f)
            })
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Column {
            Text(title, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = if (now) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium),
                color = if (now) look.accent else if (p == null) look.dim else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(time, genre?.label).joinToString(" · ").ifBlank { null }
            if (line != null) Text(line, style = fonts.body.copy(fontSize = 11.sp, lineHeight = 14.sp), color = look.dim, maxLines = 1)
        }
    }
}

/** Muted genre hues for the guide, the same in light and dark (only their strength changes). */
internal fun genreHue(genre: com.cosmotune.watch.model.EpgGenre): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(when (genre) {
    com.cosmotune.watch.model.EpgGenre.FILM -> 0xFFC97B84
    com.cosmotune.watch.model.EpgGenre.NEWS -> 0xFF7C93C3
    com.cosmotune.watch.model.EpgGenre.SPORT -> 0xFF6FAF8A
    com.cosmotune.watch.model.EpgGenre.KIDS -> 0xFFD9A85B
    com.cosmotune.watch.model.EpgGenre.MUSIC -> 0xFF9E86C8
    com.cosmotune.watch.model.EpgGenre.FACTUAL -> 0xFF5FA8A8
    com.cosmotune.watch.model.EpgGenre.SHOW -> 0xFFB89A7A
})

/**
 * A dropdown chip of the phone's filters (board frame 9): its [title] (or the chosen value's name), lit while a value is chosen; a
 * tap opens a menu of every value with how many channels it has, the viewer's own first, and "Any" to clear it.
 */
@Composable
private fun FacetChip(
    title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, chosen: String?, facets: List<com.cosmotune.watch.Facet>, name: (String) -> String,
    fonts: OrbitFonts, look: OrbitLook, onChoose: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = facets.firstOrNull { it.id == chosen }
    Box {
        OrbitSelectChip(current?.let { name(it.name) } ?: title, chosen != null, fonts, look, onClick = { open = true }, icon = icon, haptic = false)
        OrbitMenu(open, { open = false }, fonts = fonts, look = look, header = title, items =
            listOf(OrbitMenuItem("Any ${title.lowercase()}", selected = chosen == null) { onChoose(null) }) +
                facets.map { f -> OrbitMenuItem(name(f.name), "%,d channels".format(f.count), selected = f.id == chosen) { onChoose(f.id) } })
    }
}

/** The Guide / List switch beside the phone's heading: the grid of programmes, or a list of what is on now. */
@Composable
private fun GuideOrList(asList: Boolean, onChange: (Boolean) -> Unit, look: OrbitLook) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(look.row).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        OrbitRoundButton(Icons.Rounded.GridView, "Guide", look.ink, onClick = { onChange(false) }, size = 36.dp, on = !asList, onInk = look.ground)
        OrbitRoundButton(Icons.Rounded.ViewList, "List", look.ink, onClick = { onChange(true) }, size = 36.dp, on = asList, onInk = look.ground)
    }
}

/** The guide as a list (the phone's List): each channel with what is on now and how far through it is; a tap plays it. */
@Composable
private fun GuideList(window: GuideWindow, now: Long, fonts: OrbitFonts, look: OrbitLook, clock: () -> Long, playChannel: (Channel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        window.rows.forEach { row ->
            val on = row.programmes.firstOrNull { it.isOnAt(now) }
            val next = row.programmes.firstOrNull { it.startMs >= (on?.stopMs ?: now) }
            LiveRow(com.cosmotune.watch.LiveNowItem(row.channel, on, next), fonts, look, clock) { playChannel(row.channel) }
        }
    }
}

/** Whether this channel (or another source's copy of it) keeps a catch-up archive. */
internal val Channel.keepsArchive: Boolean get() = catchup != null || also.any { it.catchup != null }

/**
 * Catch-up for a channel that keeps an archive (Kodi IPTV Simple's idea): Watch live, Watch from the start of what is on now, and
 * the programmes of the last day as rows, newest first; each plays from the archive and seeks like a film.
 */
@Composable
private fun WatchCatchupDialog(channel: Channel, now: Long, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, onLive: () -> Unit, onDismiss: () -> Unit) {
    val repository = actions.repository
    val days = (listOf(channel) + channel.also).firstNotNullOfOrNull { it.catchup }?.days?.coerceIn(1, 30) ?: 1
    val past by androidx.compose.runtime.produceState<List<EpgProgramme>?>(null, channel) {
        val from = now - minOf(days, 1) * 86_400_000L
        value = runCatching { repository.guide(listOf(channel), from, now + 60_000).rows.firstOrNull()?.programmes.orEmpty() }.getOrDefault(emptyList())
            .filter { it.startMs < now }.sortedByDescending { it.startMs }
    }
    var message by remember { mutableStateOf<String?>(null) }
    fun play(p: EpgProgramme) = actions.launch {
        val found = runCatching { repository.catchup(channel, p) }.getOrNull()
        if (found == null) message = "This programme is not in the channel's archive." else {
            onDismiss()
            WatchNav.open(WatchRoute.Play(found.first, com.cosmotune.watch.model.TitleSummary(found.first.key, com.cosmotune.watch.model.TitleType.FILM,
                (found.first as? com.cosmotune.watch.model.PlayTarget.Item)?.name ?: channel.name, overview = channel.name, backdropUrl = channel.logoUrl, origin = "catchup"), found.second))
        }
    }
    WatchDialog(channel.name, "Catch-up", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchCardChip("Watch live", look.ink, fonts, onClick = onLive, solid = true, onSolid = look.ground, icon = CosmoIcons.Play)
            val list = past
            list?.firstOrNull { it.isOnAt(now) }?.let { on ->
                WatchCardChip("From the start · ${on.title.substringAfter(": ")}", look.ink, fonts, onClick = { play(on) }, icon = CosmoIcons.Replay)
            }
            Text(message ?: "Earlier on ${channel.name}", style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
            when {
                list == null -> repeat(3) { WatchSkeleton(look, Modifier.fillMaxWidth().height(56.dp), radius = 14.dp) }
                list.none { !it.isOnAt(now) } -> Text("The guide has nothing earlier for this channel.", style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp))
                else -> list.filter { !it.isOnAt(now) }.take(40).forEach { p ->
                    WatchRowFrame(look, height = 56.dp, onClick = { play(p) }) {
                        Text(hm(p.startMs), style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp).width(52.dp))
                        Text(p.title.substringAfter(": "), style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
