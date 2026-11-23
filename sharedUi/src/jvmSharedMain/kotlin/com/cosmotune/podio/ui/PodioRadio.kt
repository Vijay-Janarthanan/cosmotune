@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.cosmotune.podio.ui

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.outlined.ContentCopy

import com.cosmotune.ui.collectAsUiState

import androidx.compose.material.icons.outlined.Stop

import androidx.compose.material.icons.outlined.FiberManualRecord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Satellite
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioPlaces
import com.cosmotune.podio.RadioStation
import com.cosmotune.podio.item
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.*
import com.cosmotune.ui.watch.WatchWash

/** What the radio pages need of the station that is on. */
internal class OnAir(val station: RadioStation?, val song: String?, val onPrevious: (() -> Unit)?, val onNext: (() -> Unit)?, val onOpen: () -> Unit,
    /** The station Next goes to, named on the card under the buttons ("Next: Station two"). */
    val nextName: String? = null)

/** The deep colour of a station's card and the ink on it: its logo's colour once known, else the deep form of its own colour. */
@Composable internal fun stationCardColors(station: RadioStation): OrbitCardColors {
    val (fill, ink) = coverFill(station.artwork.takeIf(String::isNotBlank), station.name)
    if (station.artwork.isNotBlank()) return OrbitCardColors(fill, ink)
    val own = remember(station.name) { stationFill(station.name) }
    return OrbitCardColors(own.copy(red = own.red * 0.62f, green = own.green * 0.62f, blue = own.blue * 0.62f), CARD_INK)
}

/**
 * The station as the colour card (board section C): its own colour, the logo as a disc at the top end wearing the orbit while it
 * plays, "● LIVE · Chennai · Tamil", the name, what is on air, previous, next and the heart (and [extra]: the sleep timer, the
 * room), with play in the bite. [live] is the station that is loaded; any other card is a way to tune in ([label] says why) and its
 * bite plays it. [large] is the station page's card: from 600 dp the disc stands at the start and the words beside it.
 */
@Composable internal fun StationCard(station: RadioStation, env: PodioEnv, m: PodioModel, live: Boolean, song: String?, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?,
    modifier: Modifier = Modifier, label: String = "", large: Boolean = false, onClick: (() -> Unit)? = null, nextName: String? = null,
    extra: @Composable RowScope.(OrbitCardColors) -> Unit = {}) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val colors = stationCardColors(station)
    val ink = colors.ink
    val playing = live && env.playback.playing
    val saved = env.saved.stations.any { it.id == station.id }
    val place = listOf(station.state.ifBlank { station.country }, station.language.replaceFirstChar { it.uppercase() }).filter(String::isNotBlank).joinToString(" · ")
    val now = when {
        live && env.playback.error != null -> "Playback failed. Try another station."
        live && env.playback.loading -> "Tuning in…"
        live -> song?.let { "Now: $it" } ?: "On air now"
        else -> PodioFormat.stationMeta(station.placeLine(), "", station.codec, station.bitrate)
    }
    BoxWithConstraints(modifier) {
        val row = large && maxWidth >= 600.dp
        val disc = when { row -> 196.dp; maxWidth < 420.dp -> 96.dp; large -> 150.dp; else -> 140.dp }
        OrbitBiteCard(colors.fill, Modifier.fillMaxWidth(), onClick = onClick, bite = {
            OrbitPlayButton(playing, { if (live) env.onToggle() else m.playStation(station) }, look, Modifier.testTag("podio:card:play"))
        }) {
            val logo: @Composable (Modifier) -> Unit = { at ->
                Box(at.size(disc + 22.dp), contentAlignment = Alignment.Center) {
                    OrbitLogoDisc(station.artwork, station.name, disc, fonts)
                    if (live) OrbitRing(disc + 22.dp, ink, progress = null, spinning = playing)
                }
            }
            val words: @Composable ColumnScope.() -> Unit = {
                if (live) OrbitLiveLabel(place, ink.copy(alpha = 0.8f), LIVE_ON_CARD, fonts)
                else OrbitLabel(label.ifBlank { "Tune in" }, fonts, ink.copy(alpha = 0.75f))
                Text(station.name, style = if (row) fonts.greeting.copy(fontSize = 38.sp, lineHeight = 40.sp) else fonts.heroTitle.copy(fontSize = 26.sp, lineHeight = 28.sp),
                    color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                if (now.isNotBlank()) Text(now, style = fonts.body.copy(fontSize = 14.sp), color = ink.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp))
                if (live && onNext != null && !nextName.isNullOrBlank()) OrbitLabel("Next: $nextName", fonts, ink.copy(alpha = 0.7f), Modifier.padding(top = 8.dp).testTag("podio:card:next"))
            }
            val buttons: @Composable (Modifier) -> Unit = { at ->
                // A row that wraps, so on a narrow phone no control is pushed off the card.
                FlowRow(at, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (onPrevious != null) OrbitCardButton(CosmoIcons.Previous, "Previous station", colors, onPrevious)
                    if (onNext != null) OrbitCardButton(CosmoIcons.Next, "Next station", colors, onNext)
                    OrbitCardButton(if (saved) CosmoIcons.HeartFill else CosmoIcons.Heart, if (saved) "Remove favourite" else "Save favourite", colors, { m.toggleFavourite(station) }, on = saved)
                    if (live) RecordButton(m, station, colors)
                    extra(colors)
                }
            }
            if (row) {
                Row(Modifier.fillMaxWidth().heightIn(min = 250.dp).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    logo(Modifier)
                    Column(Modifier.weight(1f).padding(start = 28.dp, end = 72.dp)) {
                        words()
                        buttons(Modifier.padding(top = 18.dp))
                    }
                }
            } else if (large) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    logo(Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(16.dp))
                    words()
                    buttons(Modifier.padding(top = 18.dp, end = 76.dp))
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 232.dp)) {
                    logo(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp))
                    Column(Modifier.padding(start = 22.dp, top = 24.dp, end = disc + 40.dp), content = words)
                    buttons(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 18.dp, end = 92.dp))
                }
            }
        }
    }
}

/** How many station records fit a row of [width] with [side] gutters, and how wide each disc is. */
internal fun stationCells(width: Dp, side: Dp, wide: Boolean): Pair<Int, Dp> {
    val room = width - side * 2
    val least = if (wide) 112.dp else 92.dp
    val columns = ((room + 12.dp) / (least + 24.dp)).toInt().coerceIn(2, 9)
    return columns to (room - 12.dp * (columns - 1)) / columns - 12.dp
}

/** The region and language chips (and Clear while a filter is on), shared by Radio, Podcasts and the search. */
@Composable internal fun FilterChips(m: PodioModel, fonts: OrbitFonts, look: OrbitLook) {
    OrbitChoiceChip("Region", m.country, REGIONS, { m.browse(); m.country = it }, fonts, look, icon = Icons.Outlined.Public)
    OrbitChoiceChip("Language", m.language, LANGUAGES, { m.browse(); m.language = it }, fonts, look, icon = Icons.Outlined.Translate)
    if (m.filtered) PodioTextAction("Clear", look, fonts, m::clearFilters)
}

/** What the station list says of itself while searched or filtered (the heading above already gives the plain count): the search and filters in force, and the count. */
private fun stationsLine(m: PodioModel): String {
    val typed = m.query.trim().takeIf(String::isNotBlank)?.let { "\"$it\"" }
    val language = LANGUAGES.firstOrNull { it.first == m.language && it.first.isNotBlank() }?.second
    return listOfNotNull(typed, language, m.stationCount()).joinToString(" · ")
}

/**
 * Radio (board frame 6): the heading with Search; the Globe, region and language chips; the station on air as the colour card (only
 * while one plays) with "Next: …" under previous and next, the heart and the sleep timer; your stations as small discs; then every
 * station, as a grid of discs or as rows with place, language and bitrate (the switch beside the title).
 */
@Composable internal fun RadioPage(m: PodioModel, env: PodioEnv, onAir: OnAir, onSearch: () -> Unit, timer: PodioTimer? = null, onGlobe: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val list = rememberLazyListState()
    var rows by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:radio")) {
        val wide = maxWidth >= 840.dp
        val side = orbitPageSide(maxWidth)
        val (columns, cell) = stationCells(maxWidth, side, wide)
        val favourites = env.saved.stations
        val featured = onAir.station
        if (featured != null) WatchWash(stationCardColors(featured).fill, look)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "head") {
                PodioHead("Radio", if (m.radio.isEmpty()) "Radio Browser" else m.stationCount(), fonts, look, side, env.onMusic) {
                    OrbitPageButton(CosmoIcons.Search, "Search", onSearch)
                    OrbitPageButton(CosmoIcons.Plus, "Add a station", m::addStation)
                }
            }
            item(key = "chips") {
                OrbitChipRow(side, Modifier.padding(top = 18.dp)) {
                    OrbitSelectChip("Globe", false, fonts, look, onGlobe, icon = Icons.Outlined.Public, haptic = false)
                    FilterChips(m, fonts, look)
                }
            }
            if (featured != null) item(key = "card") {
                StationCard(featured, env, m, live = true, onAir.song, onAir.onPrevious, onAir.onNext,
                    Modifier.padding(horizontal = side).padding(top = 20.dp).fillMaxWidth().then(if (wide) Modifier.widthIn(max = 760.dp) else Modifier),
                    onClick = onAir.onOpen, nextName = onAir.nextName) { colors -> if (timer != null) SleepTimerChip(timer, episode = false, card = colors) }
            }
            if (favourites.isNotEmpty()) item(key = "yours") {
                Column {
                    OrbitPageSection("Your stations", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 10.dp), subtitle = "${favourites.size} saved")
                    OrbitShelfRow(side, 8.dp) { items(favourites, key = { "fav-" + it.id }) { station -> StationTile(station, env, m, fonts, look, if (wide) 84.dp else 68.dp) } }
                }
            }
            val recent = env.saved.recentStations.filter { station -> favourites.none { it.id == station.id } }.take(12)
            if (recent.isNotEmpty()) item(key = "recent") {
                Column {
                    OrbitPageSection("Recently played", Modifier.padding(horizontal = side + 4.dp).padding(top = 24.dp, bottom = 10.dp),
                        action = "Copy as M3U", onAction = {
                            m.host.copyText(com.cosmotune.podio.PodioStationLinks.m3u(env.saved.recentStations)); m.notice = "Copied your recent stations as an M3U playlist."
                        })
                    OrbitShelfRow(side, 8.dp) { items(recent, key = { "recent-" + it.id }) { station -> StationTile(station, env, m, fonts, look, if (wide) 84.dp else 68.dp) } }
                }
            }
            if (env.saved.heardSongs.isNotEmpty()) item(key = "heard") {
                Column(Modifier.padding(horizontal = side)) {
                    OrbitPageSection("Heard on the radio", Modifier.padding(horizontal = 4.dp).padding(top = 24.dp, bottom = 10.dp),
                        subtitle = env.saved.heardSongs.size.toString(), action = "All ›", onAction = { m.songsOpen = true })
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { env.saved.heardSongs.take(3).forEach { HeardSongRow(m, it, fonts, look) } }
                }
            }
            item(key = "stations-title") {
                Row(Modifier.fillMaxWidth().padding(start = side + 4.dp, end = side).padding(top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OrbitPageSection("Stations", Modifier.weight(1f),
                        subtitle = if (m.query.isNotBlank() || m.language.isNotBlank()) stationsLine(m) else "")
                    OrbitPageButton(if (rows) CosmoIcons.Grid else CosmoIcons.List, if (rows) "Show as a grid" else "Show as a list", { rows = !rows },
                        Modifier.testTag("podio:stations:layout"))
                }
            }
            when {
                m.directoryLoading && m.radio.isEmpty() -> item(key = "loading") {
                    if (rows) PodioSkeletonRows(6, look, side, Modifier.testTag("podio:loading")) else DiscSkeletonGrid(columns, cell, side, look, Modifier.testTag("podio:loading"))
                }
                m.error != null && m.radio.isEmpty() -> item(key = "error") {
                    PodioMessage("Radio Browser", "Couldn't reach the stations", m.error.orEmpty(), fonts, look, side, "Retry") { m.request++ }
                }
                m.radio.isEmpty() -> item(key = "empty") { PodioMessage("Stations", "No stations found", "Try another name, country or language.", fonts, look, side) }
            }
            if (rows) items(m.radio.take(400), key = { "r-" + it.id }) { station ->
                val current = env.playback.id == station.item().id
                StationRow(station, env.saved.stations.any { it.id == station.id }, current, current && env.playback.playing, { m.playStation(station) },
                    { m.toggleFavourite(station) }, fonts, look, Modifier.padding(horizontal = side), onAir = if (current) onAir.song else null)
            } else stationGrid("s", m.radio.take(400), columns, cell, side, env, m, fonts, look)
            item(key = "credit") { PodioCredit("Stations: Radio Browser · Imagery: NASA GIBS", fonts, look, side) }
        }
    }
}

/** Discs standing in while the station grid loads: the grid's own geometry, not rows. */
@Composable private fun DiscSkeletonGrid(columns: Int, cell: Dp, side: Dp, look: OrbitLook, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = side).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(columns) {
                    Column(Modifier.width(cell + 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatchSkeleton(look, Modifier.size(cell), radius = cell / 2)
                        WatchSkeleton(look, Modifier.fillMaxWidth(0.7f).height(10.dp), radius = 5.dp)
                    }
                }
            }
        }
    }
}

// ---- the globe ---------------------------------------------------------------------------------------------------------------

/** The place under the globe's ring as a quiet card: the count and the time there in mono, then its name. */
@Composable internal fun PlaceHeader(m: PodioModel, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    val grouped = remember(m.nearby) { PodioPlaces.group(m.nearby) }
    val title = when { grouped.size == 1 -> grouped[0].name; grouped.size > 1 -> "Stations nearby"; m.radio.isEmpty() -> "Stations"; else -> "Drag the globe to a place" }
    val meta = PodioFormat.episodeMeta(
        if (m.nearby.isEmpty()) "Under the ring" else "${m.nearby.size} ${if (m.nearby.size == 1) "station" else "stations"}",
        if (grouped.size == 1) PodioPlaces.localTime(grouped[0].stations.first().countryCode, grouped[0].longitude) else "",
        if (grouped.size > 1) "${grouped.size} places" else "",
        if (grouped.isNotEmpty() && grouped.all { it.approximate }) "Approximate" else "")
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(look.row).padding(horizontal = 20.dp, vertical = 16.dp)) {
        OrbitLabel(meta, fonts, look.dim)
        Text(title, style = fonts.section, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).testTag("podio:place"))
    }
}

/** The globe's own controls as chips beside it, never on it: satellite imagery, a random place, zoom, and (the phone) closing it. */
@Composable internal fun GlobeControls(m: PodioModel, view: GlobeView, fonts: OrbitFonts, look: OrbitLook, side: Dp, modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null) {
    OrbitChipRow(side, modifier) {
        if (onClose != null) OrbitPageButton(CosmoIcons.Close, "Close full screen map", onClose)
        OrbitSelectChip("Satellite", m.satellite, fonts, look, { m.satellite = !m.satellite }, icon = Icons.Outlined.Satellite, haptic = false)
        OrbitSelectChip("Random place", false, fonts, look, { view.random(m.radio) }, icon = CosmoIcons.Shuffle, haptic = false)
        OrbitPageButton(Icons.Outlined.Add, "Zoom in", view::zoomIn)
        OrbitPageButton(Icons.Outlined.Remove, "Zoom out", view::zoomOut)
    }
    if (m.host.remoteGlobeControls) {
        OrbitChipRow(side, Modifier.padding(top = 6.dp)) {
            OrbitPageButton(Icons.Outlined.ArrowBack, "Pan west", { view.pan(east = -1) })
            OrbitPageButton(Icons.Outlined.ArrowUpward, "Pan north", { view.pan(north = 1) })
            OrbitPageButton(Icons.Outlined.ArrowDownward, "Pan south", { view.pan(north = -1) })
            OrbitPageButton(Icons.Outlined.ArrowForward, "Pan east", { view.pan(east = 1) })
        }
    }
}

/** The globe in its frame: rounded like a card, on the page's quiet fill. */
@Composable internal fun GlobeFrame(m: PodioModel, onAir: OnAir, view: GlobeView, modifier: Modifier, radius: Dp = 34.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(orbitLook().chip)) {
        PodioGlobe(m.radio, onAir.station?.id ?: m.selected?.id, { m.nearby = it }, Modifier.fillMaxSize(), focus = m.selected, tiles = m.catalogue.tiles,
            view = view, imagery = m.satellite)
    }
}

/** The stations under the ring, with the loading, error and empty states, and your stations after them. */
private fun LazyListScope.stationsUnderRing(m: PodioModel, env: PodioEnv, onAir: OnAir, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    when {
        m.directoryLoading && m.radio.isEmpty() -> item(key = "loading") { PodioSkeletonRows(4, look, side, Modifier.testTag("podio:loading")) }
        m.error != null && m.radio.isEmpty() -> item(key = "error") {
            PodioMessage("Radio Browser", "Couldn't reach the stations", m.error.orEmpty(), fonts, look, side, "Retry") { m.request++ }
        }
        m.radio.isEmpty() -> item(key = "empty") { PodioMessage("Stations", "No stations found", "Try another name, country or language.", fonts, look, side) }
        m.nearby.isEmpty() -> item(key = "none") {
            Text(if (m.host.remoteGlobeControls) "No station under the ring. Pan the globe or choose Random place."
                else "No station under the ring. Drag the globe to a dot, or search for the full list.", style = fonts.body, color = look.dim,
                modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 12.dp))
        }
    }
    items(m.nearby.take(400), key = { "n-" + it.id }) { station ->
        val current = env.playback.id == station.item().id
        StationRow(station, env.saved.stations.any { it.id == station.id }, current, current && env.playback.playing, { m.playStation(station) }, { m.toggleFavourite(station) },
            fonts, look, Modifier.padding(horizontal = side), onAir = if (current) onAir.song else null)
    }
    val favourites = env.saved.stations
    if (favourites.isNotEmpty()) {
        item(key = "fav-title") {
            OrbitPageSection("Your stations", Modifier.padding(horizontal = side + 4.dp).padding(top = 26.dp, bottom = 4.dp), subtitle = "${favourites.size} saved",
                action = "All", onAction = { env.chooseSection(PodioSection.FOLLOWING) })
        }
        items(favourites.take(8), key = { "f-" + it.id }) { station ->
            val current = env.playback.id == station.item().id
            StationRow(station, true, current, current && env.playback.playing, { m.playStation(station) }, { m.toggleFavourite(station) }, fonts, look,
                Modifier.padding(horizontal = side))
        }
    }
    item(key = "credit") { PodioCredit("Stations: Radio Browser · Imagery: NASA GIBS", fonts, look, side) }
}

/**
 * The Globe (board section C, its own section on the desktop and a tablet): the heading, the globe's chips, the globe in its
 * frame, and beside it (wide) or under it the place under the ring, its stations and the station that is on.
 */
@Composable internal fun GlobePage(m: PodioModel, env: PodioEnv, onAir: OnAir, view: GlobeView) {
    val fonts = orbitFonts()
    val look = orbitLook()
    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:globe-page")) {
        val wide = maxWidth >= 840.dp
        val side = orbitPageSide(maxWidth)
        val label = when {
            m.radio.isNotEmpty() -> m.stationCount()
            m.host.remoteGlobeControls -> "Use the arrows to turn it"
            else -> "Drag to turn it"
        }
        val station = onAir.station
        if (wide) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp)) {
                PodioHead("Globe", label, fonts, look, side, env.onMusic)
                GlobeControls(m, view, fonts, look, side, Modifier.padding(top = 18.dp))
                GlobeFrame(m, onAir, view, Modifier.padding(start = side, top = 16.dp).fillMaxWidth().weight(1f))
            }
            Column(Modifier.width(400.dp).fillMaxHeight()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 30.dp, bottom = 12.dp)) {
                    item(key = "place") { PlaceHeader(m, fonts, look, Modifier.padding(horizontal = side)) }
                    stationsUnderRing(m, env, onAir, fonts, look, side)
                }
                if (station != null) StationCard(station, env, m, live = true, onAir.song, onAir.onPrevious, onAir.onNext,
                    Modifier.padding(start = side, end = side, bottom = 16.dp).fillMaxWidth(), onClick = onAir.onOpen)
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = env.bottom + 16.dp)) {
            item(key = "head") { PodioHead("Globe", label, fonts, look, side, env.onMusic) }
            item(key = "controls") { GlobeControls(m, view, fonts, look, side, Modifier.padding(top = 18.dp)) }
            item(key = "globe") { GlobeFrame(m, onAir, view, Modifier.padding(horizontal = side).padding(top = 16.dp).fillMaxWidth().height(minOf(maxWidth - side * 2, 460.dp))) }
            if (station != null) item(key = "on-air") {
                StationCard(station, env, m, live = true, onAir.song, onAir.onPrevious, onAir.onNext, Modifier.padding(horizontal = side).padding(top = 16.dp).fillMaxWidth(), onClick = onAir.onOpen)
            }
            item(key = "place") { PlaceHeader(m, fonts, look, Modifier.padding(horizontal = side).padding(top = 16.dp)) }
            stationsUnderRing(m, env, onAir, fonts, look, side)
        }
    }
}

/**
 * The globe on the whole phone (the dock steps aside): the globe, and a sheet over its foot with the globe's chips, the station that
 * is on, the place under the ring and its stations. Dragging the sheet up shows more.
 */
@Composable internal fun RadioFullScreen(m: PodioModel, env: PodioEnv, onAir: OnAir, view: GlobeView, height: Dp, onToggle: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var expand by remember { mutableIntStateOf(0) }
    LaunchedEffect(m.nearby.firstOrNull()?.id) { if (m.nearby.isNotEmpty()) expand++ }
    Box(Modifier.fillMaxSize().background(look.ground).testTag("podio:radio-full")) {
        PodioGlobe(m.radio, onAir.station?.id ?: m.selected?.id, { m.nearby = it }, Modifier.fillMaxSize().padding(bottom = 150.dp), focus = m.selected,
            tiles = m.catalogue.tiles, view = view, imagery = m.satellite)
        MapSheet(peek = 176.dp, maxHeight = height * .8f, modifier = Modifier.align(Alignment.BottomCenter), expand = expand, header = {
            GlobeControls(m, view, fonts, look, 16.dp, Modifier.padding(bottom = 6.dp), onClose = { m.globeFull = false })
            onAir.station?.let { station -> PodioMiniPlayer(env.playback, station, onAir.song, station.artwork, onToggle, onAir.onOpen) }
            PlaceHeader(m, fonts, look, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }) {
            LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                stationsUnderRing(m, env, onAir, fonts, look, 16.dp)
            }
        }
    }
}

/**
 * Record on a station's card: a tap starts saving the station to files (each song its own file when Settings › Podio says so); while it
 * records it shows the time so far, and a tap stops it. Nothing is drawn where the host keeps no recordings.
 */
@Composable internal fun RecordButton(m: PodioModel, station: RadioStation, colors: OrbitCardColors) {
    if (m.recordingsFolder == null) return
    val state by com.cosmotune.podio.PodioRecorder.state.collectAsUiState()
    val mine = state?.takeIf { it.stationId == station.id && it.error == null }
    if (mine == null) { OrbitCardButton(Icons.Outlined.FiberManualRecord, "Record this station", colors, { m.startRecording(station) }, Modifier.testTag("podio:record")); return }
    val elapsed by produceState(0L, mine.startedMs) { while (true) { value = System.currentTimeMillis() - mine.startedMs; kotlinx.coroutines.delay(1_000) } }
    OrbitCardChip(PodioFormat.episodeMeta(PodioFormat.clock(elapsed), if (mine.saved > 0) "${mine.saved} saved" else ""), colors, m::stopRecording,
        Modifier.testTag("podio:record:stop"), icon = Icons.Outlined.Stop, solid = true)
}

/** A song a station named: the title (selectable, to copy part of it), the station and when; Copy puts the title on the clipboard. */
@Composable internal fun HeardSongRow(m: PodioModel, song: com.cosmotune.podio.PodioHeardSong, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(look.row).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
        .testTag("podio:heard"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(song.title, style = fonts.body, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(PodioFormat.episodeMeta(song.station, PodioFormat.ago(song.atMs)), style = fonts.body.copy(fontSize = 13.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OrbitRoundButton(Icons.Outlined.ContentCopy, "Copy the song", look.ink, { m.host.copyText(song.title); m.notice = "Copied \"${song.title}\"." }, size = 36.dp)
    }
}

/** Every song the stations named, latest first, with Copy all and Clear. */
@Composable internal fun HeardSongsSheet(m: PodioModel, songs: List<com.cosmotune.podio.PodioHeardSong>, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    com.cosmotune.ui.kit.KitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
            .testTag("podio:heard-sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitLabel("Heard on the radio · ${songs.size}", fonts, look.dim)
            OrbitChipRow(0.dp, Modifier.padding(bottom = 4.dp)) {
                OrbitSelectChip("Copy all", false, fonts, look, { m.host.copyText(com.cosmotune.podio.PodioHistory.songsText(songs)); m.notice = "Copied ${songs.size} songs." },
                    icon = Icons.Outlined.ContentCopy, haptic = false)
                OrbitSelectChip("Clear history", false, fonts, look, { m.clearRadioHistory(); onDismiss() }, icon = CosmoIcons.Close, haptic = false)
            }
            songs.forEach { HeardSongRow(m, it, fonts, look) }
        }
    }
}
