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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.foundation.Image
import com.cosmotune.podio.*
import com.cosmotune.ui.room.RoomPlayButton
import com.cosmotune.ui.room.roomItem
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The station page (design system 6.3), what a click on the player opens for a live station: the map held on the station's place
 * with the place and its clock over its foot, the logo beside the name and the stream, Listen (the one filled button) with previous,
 * next, the heart and the sleep timer, the song on air, the weather there, photographs taken near it, what the neighbourhood is
 * known for (Wikipedia), and the other stations of the same place.
 */
@Composable
internal fun RadioDetail(station: RadioStation, playback: PodioPlayback, song: String?, saved: Boolean, catalogue: PodioCatalogue, neighbours: List<RadioStation>,
    volume: Float, onVolume: ((Float) -> Unit)?, onToggle: () -> Unit, onSave: () -> Unit, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?,
    onPlayStation: (RadioStation) -> Unit, onBack: () -> Unit, wide: Boolean, modifier: Modifier = Modifier, timer: PodioTimer? = null, bottom: Dp = 0.dp) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
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
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val headerPx = with(density) { 200.dp.toPx() }
    val margin = PaddingValues(horizontal = CosmoSpacing.margin)

    Box(modifier.fillMaxSize().background(Color(palette.backgroundBottom)).testTag("podio:detail")) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = bottom), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The map, held on the station's place, with the place and its clock over its foot.
            Box(Modifier.fillMaxWidth().height(if (wide) 300.dp else 240.dp)) {
                PodioGlobe(listOf(station), station.id, {}, Modifier.fillMaxSize(), focus = station, tiles = catalogue.tiles, locked = true)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)), startY = 120f)))
                Row(Modifier.align(Alignment.BottomStart).padding(CosmoSpacing.margin).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(placeName, style = type.forText(type.headline, placeName), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (point?.approximate == true) Text("Approximate place", style = type.meta, color = Color.White.copy(alpha = .8f))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(clock, style = type.displayLarge, color = Color.White, modifier = Modifier.testTag("podio:detail-clock"))
                        if (date.isNotBlank()) Text(date, style = type.meta, color = Color.White.copy(alpha = .85f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(margin), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StationLogo(station, if (wide) 120.dp else 96.dp, radius = CosmoRadii.rMd)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiveMark()
                    Text(station.name, style = type.forText(type.headline, station.name), color = Color(palette.textPrimary), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val stream = PodioFormat.stationMeta("", station.language, station.codec, station.bitrate)
                    if (stream.isNotBlank()) Text(stream, style = type.meta, color = Color(palette.textSecondary), maxLines = 1)
                }
            }
            // Listen is the one filled button; the rest are quiet.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(margin), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KitButton(if (playback.playing) "Stop" else "Listen", onToggle, icon = if (playback.playing) Icons.Outlined.Stop else CosmoIcons.Play, enabled = !playback.loading)
                CosmoIconButton(CosmoIcons.Previous, "Previous station", { onPrevious?.invoke() }, style = CosmoIconButtonStyle.TONAL, size = 40.dp, enabled = onPrevious != null)
                CosmoIconButton(CosmoIcons.Next, "Next station", { onNext?.invoke() }, style = CosmoIconButtonStyle.TONAL, size = 40.dp, enabled = onNext != null)
                HeartButton(saved, onSave)
                // Shown only where rooms are offered, and only for an https stream every member's device can open.
                RoomPlayButton(remember(station) { station.roomItem() }, dense = true)
                if (timer != null) SleepTimerPill(timer, episode = false)
            }
            if (playback.loading) MetaLine("Tuning in…")
            onVolume?.let { change -> Row(Modifier.padding(margin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CosmoIcons.Volume, null, Modifier.size(18.dp), tint = Color(palette.textSecondary))
                CosmoSlider(volume, change, Modifier.weight(1f), contentDescription = "Volume", compact = true)
            } }
            if (playback.error != null) Text("Playback failed. Try another station.", Modifier.padding(margin), color = Color(palette.danger), style = type.meta)
            Row(Modifier.padding(margin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.GraphicEq, null, Modifier.size(20.dp), tint = Color(palette.textSecondary))
                Text(song ?: "On air now", style = type.bodyText, color = Color(palette.textPrimary), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val tags = station.tags.split(',').map(String::trim).filter(String::isNotBlank).take(6)
            if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(margin), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { InfoChip(null, it) }
            }

            // Right now there.
            weather?.let { w ->
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(margin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(weatherIcon(w), "${Math.round(w.temperatureC)}°C · ${w.summary}")
                    InfoChip(if (w.isDay) Icons.Outlined.WbSunny else Icons.Outlined.NightsStay, if (w.isDay) "Daytime" else "Night")
                    if (w.sunrise.isNotBlank()) InfoChip(Icons.Outlined.WbTwilight, "Sunrise ${w.sunrise} · Sunset ${w.sunset}")
                    InfoChip(Icons.Outlined.Air, "Wind ${Math.round(w.windKmh)} km/h")
                    if (station.country.isNotBlank()) InfoChip(Icons.Outlined.Public, station.country)
                }
            }
            if (photos.isNotEmpty()) {
                KitSectionHeader("Around here")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(margin), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    photos.forEach { photo -> PhotoCard(photo, catalogue) { uri.openUri(photo.page.ifBlank { photo.image }) } }
                }
                MetaLine("Photographs from Wikimedia Commons, each under its author's licence", small = true)
            }
            if (highlights.isNotEmpty()) {
                KitSectionHeader("About this place")
                Column {
                    highlights.take(6).forEach { place -> ArticleRow(place, catalogue) { uri.openUri(place.url) } }
                }
                MetaLine("Articles from Wikipedia (CC BY-SA 4.0), places within 10 km", small = true)
            }
            if (neighbours.isNotEmpty()) {
                KitSectionHeader("More stations here")
                Column { neighbours.take(12).forEach { other -> StationRow(other, false, false, { onPlayStation(other) }, {}, showHeart = false) } }
            }
            MetaLine("Weather: Open-Meteo.com (CC BY 4.0) · Imagery: NASA GIBS · Stations: Radio Browser", Modifier.padding(bottom = 16.dp), small = true)
        }
        PodioSmallBar(station.name, { ((scroll.value - headerPx) / (headerPx / 2f)).coerceIn(0f, 1f) }, onBack, backLabel = "Back to the map")
    }
}

/** A neutral pill of information (design system 4.11): surface2, an 18 dp glyph in secondary ink, the text. */
@Composable private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(palette.surfaceHigh)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = Color(palette.textSecondary))
        Text(text, style = type.meta, color = Color(palette.textPrimary), maxLines = 1)
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
    Box(Modifier.width(260.dp).height(170.dp).clip(RoundedCornerShape(CosmoRadii.rMd)).background(Color(LocalCosmoColors.current.palette.surfaceHigh)).clickable(onClick = onClick)) {
        bitmap?.let { Image(it, photo.title, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)))).padding(10.dp)) {
            Column {
                Text(photo.title, color = Color.White, style = LocalCosmoType.current.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (photo.credit.isNotBlank()) Text(photo.credit, color = Color.White.copy(alpha = .85f), style = LocalCosmoType.current.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** An article as a row: its picture when it has one, else the first letter on a tint (most small places have no picture on Wikipedia). */
@Composable private fun ArticleRow(place: PodioHighlight, catalogue: PodioCatalogue, onClick: () -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, place.image) {
        value = place.image.takeIf(String::isNotBlank)?.let { address -> catalogue.picture(address)?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() } }
    }
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    // A row on the page (design system 4.6), no card: the 56 dp picture, the title over three lines of the article.
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = CosmoSpacing.margin, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(CosmoRadii.rSm)).background(Color(palette.surfaceHigh)), contentAlignment = Alignment.Center) {
            Text(place.title.take(1).uppercase(), style = type.titleLarge, color = Color(palette.textSecondary))
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(place.title, style = type.rowTitle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(place.summary, style = type.meta, color = Color(palette.textSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
