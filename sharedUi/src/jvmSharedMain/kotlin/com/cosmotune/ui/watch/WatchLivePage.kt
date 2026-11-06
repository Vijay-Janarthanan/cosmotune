package com.cosmotune.ui.watch

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
    val facets by produceState<ChannelFacets?>(null, repository) { value = runCatching { repository.channelFacets() }.getOrNull() }
    val plugins by repository.plugins.collectAsState()
    val favourites by repository.favouriteChannels.collectAsState()
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var favouritesOnly by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableIntStateOf(60) }
    val filter = ChannelFilter(country, language, category, favouritesOnly)
    val channels by produceState<List<Channel>?>(null, filter, favourites.takeIf { favouritesOnly }) { value = runCatching { repository.channels(filter) }.getOrDefault(emptyList()) }
    val now = remember { clock() }
    val from = now / 60_000 * 60_000
    val to = from + GUIDE_HOURS * 3_600_000L
    val guide by produceState<GuideWindow?>(null, channels, shown) {
        val list = channels ?: return@produceState
        value = runCatching { repository.guide(list.take(shown), from, to) }.getOrNull() ?: GuideWindow(from, to, emptyList())
    }
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
                OrbitPageHeading("Live TV", label, fonts, look, Modifier.padding(start = side + 6.dp, end = side, top = if (frame.wide) 24.dp else 18.dp))
            }
            item(key = "live:chips") {
                val f = facets
                OrbitChipRow(side, Modifier.padding(top = 18.dp)) {
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
            item(key = "live:guide") {
                val list = channels
                val window = guide
                Box(Modifier.padding(horizontal = side).padding(top = 20.dp)) {
                    when {
                        list == null || (window == null && list.isNotEmpty()) -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(6) { WatchSkeleton(look, Modifier.fillMaxWidth().height(64.dp), radius = 18.dp) }
                        }
                        list.isEmpty() -> WatchMessage("No channels", if (favouritesOnly) "No favourites yet" else "No channels here",
                            if (favouritesOnly) "Star a channel in the guide to keep it here." else "Try another country, language or category, or switch on more plugins.", fonts, look,
                            action = "Show every channel", onAction = { country = null; language = null; category = null; favouritesOnly = false })
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
    val perMinute = if (wide) 8.5.dp else 6.dp
    val channelWidth = if (wide) 220.dp else 136.dp
    val span = ((window.toMs - window.fromMs) / 60_000).toInt()
    val gridWidth = perMinute * span
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(look.row)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            OrbitLabel("Channel", fonts, look.dim, Modifier.width(channelWidth).padding(start = 16.dp))
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
                                    if (on || p.startMs <= now) playChannel(row.channel)
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
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WatchLogoTile(channelMark(channel.name), logoFill(channel.categories.firstOrNull() ?: channel.name), fonts, logoUrl = channel.logoUrl, size = if (wide) 46.dp else 38.dp)
        val live = liveTag(channel)
        Column(Modifier.weight(1f)) {
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
    Box(
        Modifier.offset(x = x).padding(horizontal = 3.dp, vertical = 6.dp).width((width - 6.dp).coerceAtLeast(12.dp)).fillMaxHeight()
            .cosmoFocusRing(interaction, 16.dp).clip(RoundedCornerShape(16.dp))
            .background(if (now) look.accent.copy(alpha = if (look.dark) 0.18f else 0.16f) else look.ink.copy(alpha = 0.06f))
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Column {
            Text(title, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = if (now) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium),
                color = if (now) look.accent else if (p == null) look.dim else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (time != null) Text(time, style = fonts.body.copy(fontSize = 11.sp, lineHeight = 14.sp), color = look.dim, maxLines = 1)
        }
    }
}
