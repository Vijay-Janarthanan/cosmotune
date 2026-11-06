package com.cosmotune.podio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.*
import com.cosmotune.ui.room.RoomPlayButton
import com.cosmotune.ui.room.roomItem
import com.cosmotune.ui.watch.WatchWash
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.decodeToImageBitmap
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The station page (board section C), what a click on the station card or the player opens for a live station: the station's
 * colour card (the logo disc wearing the orbit, "● LIVE", the name, what is on air, previous, next, the heart, the sleep timer and
 * the room, play in the bite), the volume on the desktop, then where it is: the globe held on its place in a frame with the place's
 * clock and date in a card beside it (never on the map), the weather there as chips, photographs taken near it, what the
 * neighbourhood is known for (Wikipedia), and the other stations of the same place as discs.
 */
@Composable
internal fun RadioDetail(station: RadioStation, m: PodioModel, env: PodioEnv, song: String?, neighbours: List<RadioStation>, volume: Float, onVolume: ((Float) -> Unit)?,
    onPrevious: (() -> Unit)?, onNext: (() -> Unit)?, onBack: () -> Unit, timer: PodioTimer? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val catalogue = m.catalogue
    val point = remember(station.id) { station.mapPoint() }
    val weather by produceState<PodioWeather?>(null, point) { value = point?.let { catalogue.weather(it.latitude, it.longitude) } }
    val highlights by produceState(emptyList<PodioHighlight>(), point) { value = point?.let { catalogue.highlights(it.latitude, it.longitude) }.orEmpty() }
    val photos by produceState(emptyList<PodioPhoto>(), point) { value = point?.let { catalogue.photos(it.latitude, it.longitude) }.orEmpty() }
    // The place's own clock, kept current while the page is open.
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(15_000) } }
    val zoned = weather?.let { now.atOffset(ZoneOffset.ofTotalSeconds(it.utcOffsetSeconds)) }
    val clock = zoned?.let { DateTimeFormatter.ofPattern("HH:mm").format(it) } ?: PodioPlaces.localTime(station.countryCode, point?.longitude ?: 0.0, now)
    val date = zoned?.let { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH).format(it) }.orEmpty()
    val uri = LocalUriHandler.current
    val placeName = remember(station.id) { station.placeLine().ifBlank { station.country }.ifBlank { "Somewhere on Earth" } }
    val view = rememberGlobeView()

    BoxWithConstraints(Modifier.fillMaxSize().testTag("podio:detail")) {
        val wide = maxWidth >= 840.dp
        val side = orbitPageSide(maxWidth)
        WatchWash(stationCardColors(station).fill, look)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = env.bottom + 16.dp)) {
            PodioBackHead("Station", onBack, fonts, look, side, backLabel = "Back to the map")
            StationCard(station, env, m, live = true, song, onPrevious, onNext, Modifier.padding(horizontal = side).padding(top = 16.dp).fillMaxWidth(), large = true) { colors ->
                if (timer != null) SleepTimerChip(timer, episode = false, card = colors)
                // Shown only where rooms are offered, and only for an https stream every member's device can open.
                RoomPlayButton(remember(station) { station.roomItem() }, dense = true)
            }
            onVolume?.let { change ->
                Row(Modifier.padding(horizontal = side + 8.dp).padding(top = 16.dp).widthIn(max = 420.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(CosmoIcons.Volume, "Volume", Modifier.size(18.dp), tint = look.dim)
                    OrbitProgressLine({ volume }, look.ink, Modifier.weight(1f), onSeek = change, live = true)
                }
            }
            val tags = station.tags.split(',').map(String::trim).filter(String::isNotBlank).take(6)
            val stream = PodioFormat.stationMeta("", station.language, station.codec, station.bitrate)
            if (tags.isNotEmpty() || stream.isNotBlank()) OrbitChipRow(side, Modifier.padding(top = 16.dp)) {
                if (stream.isNotBlank()) PlaceChip(Icons.Outlined.GraphicEq, stream, fonts, look)
                tags.forEach { PlaceChip(null, it, fonts, look) }
            }

            // Where it is: the globe held on its place, and the place's clock beside it (nothing drawn on the map).
            OrbitPageSection("Where it is", Modifier.padding(horizontal = side + 4.dp).padding(top = 32.dp, bottom = 14.dp), subtitle = placeName)
            val map: @Composable (Modifier) -> Unit = { at ->
                Box(at.clip(RoundedCornerShape(30.dp)).background(look.chip)) {
                    PodioGlobe(listOf(station), station.id, {}, Modifier.fillMaxSize(), focus = station, tiles = catalogue.tiles, view = view, locked = true)
                }
            }
            val time: @Composable (Modifier) -> Unit = { at ->
                Column(at.clip(RoundedCornerShape(30.dp)).background(look.row).padding(22.dp)) {
                    OrbitLabel("Local time", fonts, look.dim)
                    Text(clock, style = fonts.number, color = look.ink, modifier = Modifier.padding(top = 10.dp).testTag("podio:detail-clock"))
                    if (date.isNotBlank()) Text(date, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 6.dp))
                    Text(placeName, style = fonts.section.copy(fontSize = 18.sp, lineHeight = 22.sp), color = look.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 18.dp))
                    if (point?.approximate == true) Text("Approximate place", style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (wide) Row(Modifier.padding(horizontal = side).height(300.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                map(Modifier.weight(1.6f).fillMaxHeight())
                time(Modifier.weight(1f).fillMaxHeight())
            } else Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                map(Modifier.fillMaxWidth().height(240.dp))
                time(Modifier.fillMaxWidth())
            }

            // Right now there.
            weather?.let { w ->
                OrbitChipRow(side, Modifier.padding(top = 14.dp)) {
                    PlaceChip(weatherIcon(w), "${Math.round(w.temperatureC)}°C · ${w.summary}", fonts, look)
                    PlaceChip(if (w.isDay) Icons.Outlined.WbSunny else Icons.Outlined.NightsStay, if (w.isDay) "Daytime" else "Night", fonts, look)
                    if (w.sunrise.isNotBlank()) PlaceChip(Icons.Outlined.WbTwilight, "Sunrise ${w.sunrise} · Sunset ${w.sunset}", fonts, look)
                    PlaceChip(Icons.Outlined.Air, "Wind ${Math.round(w.windKmh)} km/h", fonts, look)
                    if (station.country.isNotBlank()) PlaceChip(Icons.Outlined.Public, station.country, fonts, look)
                }
            }
            if (photos.isNotEmpty()) {
                OrbitPageSection("Around here", Modifier.padding(horizontal = side + 4.dp).padding(top = 32.dp, bottom = 14.dp),
                    subtitle = "Photographs from Wikimedia Commons, each under its author's licence")
                OrbitShelfRow(side, 14.dp) {
                    items(photos) { photo -> PhotoCard(photo, catalogue, fonts, look) { uri.openUri(photo.page.ifBlank { photo.image }) } }
                }
            }
            if (highlights.isNotEmpty()) {
                OrbitPageSection("About this place", Modifier.padding(horizontal = side + 4.dp).padding(top = 32.dp, bottom = 14.dp),
                    subtitle = "Articles from Wikipedia (CC BY-SA 4.0), places within 10 km")
                Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    highlights.take(6).forEach { place -> ArticleRow(place, catalogue, fonts, look) { uri.openUri(place.url) } }
                }
            }
            if (neighbours.isNotEmpty()) {
                OrbitPageSection("More stations here", Modifier.padding(horizontal = side + 4.dp).padding(top = 32.dp, bottom = 14.dp), subtitle = placeName)
                OrbitShelfRow(side, 8.dp) { items(neighbours.take(12), key = { "here-" + it.id }) { other -> StationTile(other, env, m, fonts, look, 96.dp) } }
            }
            PodioCredit("Weather: Open-Meteo.com (CC BY 4.0) · Imagery: NASA GIBS · Stations: Radio Browser", fonts, look, side)
        }
    }
}

/** A quiet pill of information (the weather, the stream, a tag): the chip's fill, an 18 dp glyph in the accent, the words. */
@Composable private fun PlaceChip(icon: ImageVector?, text: String, fonts: OrbitFonts, look: OrbitLook) {
    Row(Modifier.height(40.dp).clip(CircleShape).background(look.chip).padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = look.accent)
        Text(text, style = fonts.chip.copy(fontSize = 13.sp), color = look.ink, maxLines = 1)
    }
}

private fun weatherIcon(w: PodioWeather) = when (w.code) {
    0, 1 -> if (w.isDay) Icons.Outlined.WbSunny else Icons.Outlined.NightsStay
    2, 3 -> Icons.Outlined.Cloud
    45, 48 -> Icons.Outlined.Cloud
    in 51..67, in 80..82 -> Icons.Outlined.WaterDrop
    in 71..77, 85, 86 -> Icons.Outlined.AcUnit
    in 95..99 -> Icons.Outlined.Thunderstorm
    else -> Icons.Outlined.Cloud
}

/** A picture fetched by the catalogue, which sends the app's own client name (Wikimedia refuses the default). */
@Composable private fun rememberPicture(catalogue: PodioCatalogue, address: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, address) {
        value = address.takeIf(String::isNotBlank)?.let { catalogue.picture(it) }?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
    }
    return bitmap
}

/** A photograph with its title and credit under it (nothing written on the picture). */
@Composable private fun PhotoCard(photo: PodioPhoto, catalogue: PodioCatalogue, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit) {
    val bitmap = rememberPicture(catalogue, photo.image)
    Column(Modifier.width(260.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(26.dp)).background(look.chip)) {
            bitmap?.let { Image(it, photo.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(photo.title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        if (photo.credit.isNotBlank()) Text(photo.credit, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
    }
}

/** An article as a song row: its picture as the disc when it has one, else its first letter on the chip's fill. */
@Composable private fun ArticleRow(place: PodioHighlight, catalogue: PodioCatalogue, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit) {
    val bitmap = rememberPicture(catalogue, place.image)
    OrbitSongRow(place.title, place.summary, null, fonts, look, onClick, Modifier.fillMaxWidth(), height = 72.dp, discContent = {
        Box(Modifier.size(44.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
            Text(place.title.take(1).uppercase(), style = fonts.title, color = look.dim)
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    })
}
