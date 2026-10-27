package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.foundation.Image
import com.cosmotune.podio.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a click on the bottom bar opens for a live station: the map held on the station's place, the time there, the weather there,
 * what the neighbourhood is known for (Wikipedia), the song on air and the controls, and the other stations of the same place.
 */
@Composable
internal fun RadioDetail(station: RadioStation, playback: PodioPlayback, song: String?, saved: Boolean, catalogue: PodioCatalogue, neighbours: List<RadioStation>,
    volume: Float, onVolume: ((Float) -> Unit)?, onToggle: () -> Unit, onSave: () -> Unit, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?,
    onPlayStation: (RadioStation) -> Unit, onBack: () -> Unit, wide: Boolean, modifier: Modifier = Modifier) {
    val point = remember(station.id) { station.mapPoint() }
    val weather by produceState<PodioWeather?>(null, point) { value = point?.let { catalogue.weather(it.latitude, it.longitude) } }
    val highlights by produceState(emptyList<PodioHighlight>(), point) { value = point?.let { catalogue.highlights(it.latitude, it.longitude) }.orEmpty() }
    val photos by produceState(emptyList<PodioPhoto>(), point) { value = point?.let { catalogue.photos(it.latitude, it.longitude) }.orEmpty() }
    // The place's own clock, kept current while the page is open.
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(15_000) } }
    val zoned = weather?.let { now.atOffset(ZoneOffset.ofTotalSeconds(it.utcOffsetSeconds)) }
    val clock = zoned?.let { DateTimeFormatter.ofPattern("HH:mm").format(it) } ?: PodioPlaces.localTime(station.countryCode, point?.longitude ?: 0.0, now)
    val date = zoned?.let { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(it) }.orEmpty()
    val uri = LocalUriHandler.current
    val placeName = remember(station.id) { station.placeLine().ifBlank { station.country }.ifBlank { "Somewhere on Earth" } }

    Surface(modifier.fillMaxSize().testTag("podio:detail"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 28.dp else 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to the map" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
                Text("Now playing", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val map: @Composable (Modifier) -> Unit = { box ->
                Box(box.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    PodioGlobe(listOf(station), station.id, {}, Modifier.fillMaxSize(), focus = station, tiles = catalogue.tiles, locked = true)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .68f)), startY = 120f)))
                    Row(Modifier.align(Alignment.BottomStart).padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(placeName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (point?.approximate == true) Text("Approximate place", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .75f))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(clock, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.testTag("podio:detail-clock"))
                            if (date.isNotBlank()) Text(date, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f))
                        }
                    }
                }
            }
            val card: @Composable (Modifier) -> Unit = { box ->
                Surface(box, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StationLogo(station, 76.dp)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                                    Text("LIVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(station.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOf(station.language, listOf(station.codec, station.bitrate.takeIf { it > 0 }?.let { "$it kbps" }.orEmpty()).filter(String::isNotBlank).joinToString(" ")).filter(String::isNotBlank).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onSave) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (saved) "Remove favourite" else "Save favourite") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                            Text(song ?: "On air now", style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        val tags = station.tags.split(',').map(String::trim).filter(String::isNotBlank).take(6)
                        if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) { Icon(Icons.Outlined.SkipPrevious, "Previous station") }
                            if (playback.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(32.dp))
                            else FilledIconButton(onClick = onToggle, modifier = Modifier.size(60.dp)) { Icon(if (playback.playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, if (playback.playing) "Stop" else "Play", Modifier.size(32.dp)) }
                            IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Icon(Icons.Outlined.SkipNext, "Next station") }
                        }
                        onVolume?.let { change -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.VolumeUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(volume, change, valueRange = 0f..1f, modifier = Modifier.weight(1f).semantics { contentDescription = "Volume" })
                        } }
                        if (playback.error != null) Text("Playback failed. Try another station.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                map(Modifier.weight(1.25f).height(360.dp)); card(Modifier.weight(1f))
            } else { map(Modifier.fillMaxWidth().height(260.dp)); card(Modifier.fillMaxWidth()) }

            // Right now there.
            weather?.let { w ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoChip(weatherIcon(w), "${Math.round(w.temperatureC)}°C · ${w.summary}")
                    InfoChip(if (w.isDay) Icons.Outlined.WbSunny else Icons.Outlined.NightsStay, if (w.isDay) "Daytime" else "Night")
                    if (w.sunrise.isNotBlank()) InfoChip(Icons.Outlined.WbTwilight, "Sunrise ${w.sunrise} · Sunset ${w.sunset}")
                    InfoChip(Icons.Outlined.Air, "Wind ${Math.round(w.windKmh)} km/h")
                    if (station.country.isNotBlank()) InfoChip(Icons.Outlined.Public, station.country)
                }
            }
            if (photos.isNotEmpty()) {
                Text("Around here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    photos.forEach { photo -> PhotoCard(photo, catalogue) { uri.openUri(photo.page.ifBlank { photo.image }) } }
                }
                Text("Photographs from Wikimedia Commons, each under its author's licence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (highlights.isNotEmpty()) {
                Text("About the area", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    highlights.take(6).forEach { place -> ArticleRow(place, catalogue) { uri.openUri(place.url) } }
                }
                Text("Articles from Wikipedia (CC BY-SA 4.0), places within 10 km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (neighbours.isNotEmpty()) {
                Text("More stations here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    neighbours.take(12).forEach { other ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPlayStation(other) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StationLogo(other, 42.dp)
                            Column(Modifier.weight(1f)) {
                                Text(other.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(other.language, other.tags.split(',').firstOrNull().orEmpty()).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Outlined.PlayArrow, null)
                        }
                    }
                }
            }
            Text("Weather: Open-Meteo.com (CC BY 4.0) · Imagery: NASA GIBS · Stations: Radio Browser", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
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

/** A photograph with its credit written on it; the picture is fetched by the catalogue, which sends the app's own client name (Wikimedia refuses the default). */
@Composable private fun PhotoCard(photo: PodioPhoto, catalogue: PodioCatalogue, onClick: () -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, photo.image) {
        value = catalogue.picture(photo.image)?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
    }
    Box(Modifier.width(260.dp).height(170.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)) {
        bitmap?.let { Image(it, photo.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)))).padding(10.dp)) {
            Column {
                Text(photo.title, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (photo.credit.isNotBlank()) Text(photo.credit, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** An article as a row: its picture when it has one, else the first letter on a tint (most small places have no picture on Wikipedia). */
@Composable private fun ArticleRow(place: PodioHighlight, catalogue: PodioCatalogue, onClick: () -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, place.image) {
        value = place.image.takeIf(String::isNotBlank)?.let { address -> catalogue.picture(address)?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() } }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(place.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(place.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(place.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
