package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioPlaces
import com.cosmotune.podio.RadioStation
import com.cosmotune.podio.item
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitEmptyState
import com.cosmotune.ui.kit.KitErrorState
import com.cosmotune.ui.kit.KitPill
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.ui.kit.KitSkeletonRows
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.kit.collapseFraction
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

/** What the radio pages need of the station that is on. */
internal class OnAir(val station: RadioStation?, val song: String?, val onPrevious: (() -> Unit)?, val onNext: (() -> Unit)?, val onOpen: () -> Unit)

/** The place under the globe's ring: its name (or "Stations nearby" for several), then the count, the time there and whether it is approximate. */
@Composable internal fun PlaceHeader(m: PodioModel, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val grouped = remember(m.nearby) { PodioPlaces.group(m.nearby) }
    val title = when { grouped.size == 1 -> grouped[0].name; grouped.size > 1 -> "Stations nearby"; m.radio.isEmpty() -> "Stations"; else -> "Drag the map to pick a place" }
    val meta = PodioFormat.episodeMeta(
        if (m.nearby.isEmpty()) "" else "${m.nearby.size} ${if (m.nearby.size == 1) "station" else "stations"}",
        if (grouped.size == 1) PodioPlaces.localTime(grouped[0].stations.first().countryCode, grouped[0].longitude) else "",
        if (grouped.size > 1) "${grouped.size} places under the ring" else "",
        if (grouped.isNotEmpty() && grouped.all { it.approximate }) "Approximate place" else "")
    Column(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = type.forText(type.titleLarge, title),
            color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("podio:place"))
        if (meta.isNotBlank()) Text(meta, style = type.meta, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The active search and filters as a pill (it reopens the search) and Clear. */
@Composable private fun FilterSummary(m: PodioModel, onSearch: () -> Unit) {
    if (!m.filtered) return
    val text = listOf(m.query.trim().takeIf(String::isNotBlank)?.let { "\"$it\"" }.orEmpty(), REGIONS.firstOrNull { it.first == m.country && it.first.isNotBlank() }?.second.orEmpty(),
        LANGUAGES.firstOrNull { it.first == m.language && it.first.isNotBlank() }?.second.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KitPill(PodioFormat.episodeMeta(text, m.stationCount()), Modifier.weight(1f, fill = false), icon = CosmoIcons.Search, onClick = onSearch)
        KitTextButton("Clear", m::clearFilters)
    }
}

/** The stations under the ring, with the loading, error and empty states, and the favourites after them. */
private fun LazyListScope.stationsUnderRing(m: PodioModel, env: PodioEnv, onAir: OnAir, favouritesAsRows: Boolean) {
    when {
        m.directoryLoading && m.radio.isEmpty() -> item(key = "loading") { KitSkeletonRows(5, Modifier.testTag("podio:loading")) }
        m.error != null && m.radio.isEmpty() -> item(key = "error") { KitErrorState(m.error.orEmpty(), { m.request++ }, retryLabel = "Retry") }
        m.radio.isEmpty() -> item(key = "empty") { KitEmptyState("No stations found", "Try another name, country or language.", icon = CosmoIcons.Radio) }
        m.nearby.isEmpty() -> item(key = "none") { MetaLine("No station under the ring. Drag the map to a dot, or search for the full list.", Modifier.padding(bottom = 8.dp)) }
    }
    items(m.nearby.take(400), key = { "n-" + it.id }) { station ->
        val current = env.playback.id == station.item().id
        StationRow(station, env.saved.stations.any { it.id == station.id }, current, { m.playStation(station) }, { m.toggleFavourite(station) }, onAir = if (current) onAir.song else null)
    }
    val favourites = env.saved.stations
    if (favourites.isNotEmpty()) {
        item(key = "fav-title") { KitSectionHeader("Favourites", onSeeAll = { m.libraryFilter = SavedFilter.STATIONS; env.chooseSection(PodioSection.LIBRARY) }) }
        if (favouritesAsRows) items(favourites.take(8), key = { "f-" + it.id }) { station ->
            StationRow(station, true, env.playback.id == station.item().id, { m.playStation(station) }, { m.toggleFavourite(station) })
        } else item(key = "fav-shelf") {
            LazyRow(contentPadding = PaddingValues(horizontal = CosmoSpacing.margin), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(favourites, key = { it.id }) { station -> StationCard(station, { m.playStation(station) }) }
            }
        }
    }
    item(key = "credit") { MetaLine("Stations: Radio Browser · Imagery: NASA GIBS", Modifier.padding(vertical = 16.dp), small = true) }
}

@Composable private fun Globe(m: PodioModel, onAir: OnAir, modifier: Modifier, controls: Alignment, onFullScreen: (() -> Unit)?, fullScreen: Boolean = false) {
    PodioGlobe(m.radio, onAir.station?.id ?: m.selected?.id, { m.nearby = it }, modifier, focus = m.selected, tiles = m.catalogue.tiles, controlsAlignment = controls,
        imagery = m.satellite, onImagery = { m.satellite = it }, onFullScreen = onFullScreen, fullScreen = fullScreen)
}

/**
 * Radio on a phone or a narrow tablet (design system 6.3, slot 1): the large title, the globe as a square (full screen from its
 * button), the place under the ring with its stations, then the favourites shelf. The search opens from the top bar.
 */
@Composable internal fun RadioPage(m: PodioModel, env: PodioEnv, onAir: OnAir, width: Dp, onSearch: () -> Unit) {
    val list = rememberLazyListState()
    var bar by rememberBarHeight()
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(m.nearby.firstOrNull()?.id) { if (list.firstVisibleItemIndex > 1) list.scrollToItem(1) }
    Box(Modifier.fillMaxSize().testTag("podio:radio")) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = bar, bottom = env.bottom)) {
            item(key = "globe") {
                Globe(m, onAir, Modifier.fillMaxWidth().height(minOf(width, 440.dp)), Alignment.CenterEnd, onFullScreen = { m.globeFull = true })
            }
            item(key = "place") { PlaceHeader(m) }
            item(key = "filters") { FilterSummary(m, onSearch) }
            stationsUnderRing(m, env, onAir, favouritesAsRows = false)
        }
        PodioTopBar("Radio", { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) }, onSearch, env.onMusic, { bar = it })
    }
}

/**
 * Radio in a wide window (design system 5.12, list-detail): a 380 dp pane (the title, the place and its stations, or the search) with
 * the station that is on at its foot, and the globe filling the rest.
 */
@Composable internal fun RadioSplit(m: PodioModel, env: PodioEnv, onAir: OnAir, volume: Float, onVolume: ((Float) -> Unit)?, onToggle: () -> Unit,
    timer: PodioTimer, focus: FocusRequester, onSearch: () -> Unit, onCloseSearch: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    Row(Modifier.fillMaxSize().testTag("podio:radio")) {
        Column(Modifier.width(380.dp).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (m.searching) SearchContent(m, env, focus, onCloseSearch)
                else {
                    val list = rememberLazyListState()
                    var bar by rememberBarHeight()
                    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
                    LaunchedEffect(m.nearby.firstOrNull()?.id) { list.scrollToItem(0) }
                    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = bar, bottom = env.bottom)) {
                        item(key = "place") { PlaceHeader(m) }
                        item(key = "filters") { FilterSummary(m, onSearch) }
                        stationsUnderRing(m, env, onAir, favouritesAsRows = true)
                    }
                    PodioTopBar("Radio", { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, threshold) }, onSearch, null, { bar = it })
                }
            }
            onAir.station?.let { station ->
                LiveCard(station, env.playback, onAir.song, env.saved.stations.any { it.id == station.id }, onToggle, { m.toggleFavourite(station) },
                    volume, onVolume, onAir.onOpen, onAir.onPrevious, onAir.onNext, timer)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(CosmoRadii.rLg)).background(Color(palette.surface))) {
            Globe(m, onAir, Modifier.fillMaxSize(), Alignment.BottomEnd, onFullScreen = null)
        }
    }
}

/**
 * The globe on the whole screen (the phone's full-screen map; the dock steps aside): the globe, its buttons on the end edge, and a
 * sheet over its foot with the station that is on, the place under the ring and its stations. Dragging the sheet up shows more.
 */
@Composable internal fun RadioFullScreen(m: PodioModel, env: PodioEnv, onAir: OnAir, height: Dp, onToggle: () -> Unit) {
    var expand by remember { mutableIntStateOf(0) }
    LaunchedEffect(m.nearby.firstOrNull()?.id) { if (m.nearby.isNotEmpty()) expand++ }
    Box(Modifier.fillMaxSize().testTag("podio:radio-full")) {
        Globe(m, onAir, Modifier.fillMaxSize().padding(bottom = 120.dp), Alignment.CenterEnd, onFullScreen = { m.globeFull = false }, fullScreen = true)
        MapSheet(peek = 132.dp, maxHeight = height * .8f, modifier = Modifier.align(Alignment.BottomCenter), expand = expand, header = {
            onAir.station?.let { station -> PodioMiniPlayer(env.playback, station, onAir.song, station.artwork, onToggle, onAir.onOpen) }
            PlaceHeader(m)
        }) {
            LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                stationsUnderRing(m, env, onAir, favouritesAsRows = true)
            }
        }
    }
}
