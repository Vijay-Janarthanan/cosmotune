package com.cosmotune.podio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.PodioPlace
import com.cosmotune.podio.PodioPlaces
import com.cosmotune.podio.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.*

private data class GlobePoint(val latitude: Double, val longitude: Double)
private data class Projected(val point: Offset, val depth: Double, val x: Double, val y: Double)

private const val MIN_ZOOM = .8f
private const val MAX_ZOOM = 90f
private const val RING_DP = 26f

private fun radius(width: Int, height: Int, zoom: Float) = min(width, height) * .42f * zoom

private fun project(latitude: Double, longitude: Double, centreLat: Float, centreLon: Float, width: Int, height: Int, zoom: Float): Projected {
    val lat = Math.toRadians(latitude)
    val lon = Math.toRadians(longitude - centreLon)
    val tilt = Math.toRadians(centreLat.toDouble())
    val r = radius(width, height, zoom)
    val x = cos(lat) * sin(lon)
    val y = cos(tilt) * sin(lat) - sin(tilt) * cos(lat) * cos(lon)
    return Projected(Offset(width / 2f + (r * x).toFloat(), height / 2f - (r * y).toFloat()),
        sin(tilt) * sin(lat) + cos(tilt) * cos(lat) * cos(lon), x, y)
}

/**
 * Orthographic world view (the way radio.garden shows it): bundled public-domain coastlines, no map account or tile service.
 * Stations are grouped into places. The ring in the middle of the view picks: [onCentre] gets every station of the places inside it
 * whenever the view stops moving, so two neighbouring places are both offered and the person chooses in the list. A tap on a dot
 * brings it under the ring (and zooms in on a crowd), the wheel and a pinch zoom, and the buttons jump to a random place.
 */
@Composable internal fun PodioGlobe(stations: List<RadioStation>, selected: String?, onCentre: (List<RadioStation>) -> Unit,
    modifier: Modifier = Modifier, focus: RadioStation? = null, controlsPadding: PaddingValues = PaddingValues(10.dp)) {
    var latitude by remember { mutableFloatStateOf(20f) }
    var longitude by remember { mutableFloatStateOf(78f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val land by produceState<List<List<GlobePoint>>>(emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching {
            val text = requireNotNull(PodioCatalogue::class.java.getResourceAsStream("/podio/land.json")).bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonArray.map { ring -> ring.jsonArray.map { pair ->
                GlobePoint(pair.jsonArray[1].jsonPrimitive.double, pair.jsonArray[0].jsonPrimitive.double)
            } }
        }.getOrDefault(emptyList()) }
    }
    val places = remember(stations) { PodioPlaces.group(stations) }
    val selectedPlace = remember(places, selected) { places.firstOrNull { place -> place.stations.any { it.id == selected } } }
    // Bring the chosen station into view when the list (not the map) chose it.
    LaunchedEffect(focus?.id) {
        val place = focus?.let { target -> places.firstOrNull { p -> p.stations.any { it.id == target.id } } } ?: return@LaunchedEffect
        latitude = place.latitude.toFloat().coerceIn(-75f, 75f); longitude = place.longitude.toFloat(); zoom = max(zoom, if (place.approximate) 4f else 9f)
    }
    // Whatever stands inside the ring is what the panel lists, once the view has stopped for a moment.
    LaunchedEffect(places, dimensions) {
        if (dimensions.width <= 0) return@LaunchedEffect
        snapshotFlow { Triple(latitude, longitude, zoom) }.collectLatest {
            delay(120)
            val reach = RING_DP * density
            val inside = places.map { place -> place to project(place.latitude, place.longitude, latitude, longitude, dimensions.width, dimensions.height, zoom) }
                .filter { it.second.depth >= 0 && (it.second.point - Offset(dimensions.width / 2f, dimensions.height / 2f)).getDistance() <= reach }
                .sortedBy { (it.second.point - Offset(dimensions.width / 2f, dimensions.height / 2f)).getDistanceSquared() }
            onCentre(inside.flatMap { it.first.stations })
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val coast = MaterialTheme.colorScheme.onSurfaceVariant
    val ocean = MaterialTheme.colorScheme.surfaceContainerHigh
    val landFill = MaterialTheme.colorScheme.surfaceContainerHighest
    val night = MaterialTheme.colorScheme.surfaceContainerLowest
    fun zoomTo(next: Float) { zoom = next.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    Box(modifier.clipToBounds().testTag("podio:globe-box")) {
        Canvas(Modifier.fillMaxSize().testTag("podio:globe").clipToBounds().onSizeChanged { dimensions = it }
            .pointerInput(places) { detectTapGestures(onDoubleTap = { zoomTo(zoom * 2f) }) { tap ->
                val reach = 14.dp.toPx()
                val hits = places.map { place -> place to project(place.latitude, place.longitude, latitude, longitude, dimensions.width, dimensions.height, zoom) }
                    .filter { it.second.depth >= 0 && (it.second.point - tap).getDistance() <= reach }
                    .sortedBy { (it.second.point - tap).getDistanceSquared() }
                if (hits.isEmpty()) return@detectTapGestures
                // Bring the dot under the ring; a crowd of neighbours is centred together and zoomed in until the dots separate.
                latitude = hits.map { it.first.latitude }.average().toFloat().coerceIn(-75f, 75f)
                longitude = hits.map { it.first.longitude }.average().toFloat()
                if (hits.size > 1) zoomTo(zoom * 2.2f) else zoomTo(max(zoom, 3f))
            } }
            .pointerInput(Unit) { detectTransformGestures { _, pan, scale, _ ->
                val r = radius(dimensions.width.coerceAtLeast(1), dimensions.height.coerceAtLeast(1), zoom).coerceAtLeast(1f)
                val degreesPerPixel = (180f / PI.toFloat()) / r
                longitude = ((longitude - pan.x * degreesPerPixel / cos(Math.toRadians(latitude.toDouble())).toFloat().coerceAtLeast(.25f) + 540f) % 360f) - 180f
                latitude = (latitude + pan.y * degreesPerPixel).coerceIn(-75f, 75f)
                zoomTo(zoom * scale)
            } }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) {
                    val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                    if (delta != 0f) { zoomTo(zoom * exp(-delta * .18f)); event.changes.forEach { it.consume() } }
                }
            } } }) {
            val width = size.width.toInt(); val height = size.height.toInt()
            val r = radius(width, height, zoom)
            val center = Offset(size.width / 2, size.height / 2)
            // The sea, with a soft rim of light where the globe turns away.
            drawCircle(Brush.radialGradient(listOf(ocean, night), center = center - Offset(r * .25f, r * .3f), radius = r * 1.5f), r, center)
            fun ringPath(points: List<GlobePoint>): Path? {
                val path = Path(); var anyFront = false
                points.forEachIndexed { index, point ->
                    val p = project(point.latitude, point.longitude, latitude, longitude, width, height, zoom)
                    // A point round the back is pushed to the edge of the disc, so the shape keeps closing along the rim.
                    val at = if (p.depth >= 0) { anyFront = true; p.point } else { val len = hypot(p.x, p.y).coerceAtLeast(1e-6)
                        Offset(center.x + (r * p.x / len).toFloat(), center.y - (r * p.y / len).toFloat()) }
                    if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                }
                path.close()
                return path.takeIf { anyFront }
            }
            fun graticule(points: List<GlobePoint>, alpha: Float) {
                val path = Path(); var drawing = false
                for (point in points) {
                    val p = project(point.latitude, point.longitude, latitude, longitude, width, height, zoom)
                    if (p.depth < 0) { drawing = false; continue }
                    if (!drawing) path.moveTo(p.point.x, p.point.y) else path.lineTo(p.point.x, p.point.y)
                    drawing = true
                }
                drawPath(path, coast.copy(alpha = alpha), style = Stroke(1f))
            }
            if (zoom < 14f) {
                for (lat in -60..60 step 30) graticule((-180..180 step 3).map { GlobePoint(lat.toDouble(), it.toDouble()) }, .08f)
                for (lon in -180 until 180 step 30) graticule((-90..90 step 3).map { GlobePoint(it.toDouble(), lon.toDouble()) }, .08f)
            }
            for (ring in land) ringPath(ring)?.let { path ->
                drawPath(path, landFill)
                drawPath(path, coast.copy(alpha = .55f), style = Stroke(1.1f))
            }
            drawCircle(coast.copy(alpha = .25f), r, center, style = Stroke(1.5f))
            // The ring that picks.
            drawCircle(Color.White.copy(alpha = .85f), RING_DP.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            // Dots: a place with several stations is a little larger; the chosen place glows.
            places.forEach { place ->
                val p = project(place.latitude, place.longitude, latitude, longitude, width, height, zoom)
                if (p.depth < .02 || p.point.x < -20 || p.point.y < -20 || p.point.x > size.width + 20 || p.point.y > size.height + 20) return@forEach
                val active = place === selectedPlace
                val base = (2.6f + ln(place.stations.size.toFloat() + 1f) * .9f).coerceAtMost(7f).dp.toPx()
                val tone = if (place.approximate) accent.copy(alpha = .55f) else accent
                if (active) drawCircle(accent.copy(alpha = .22f), base * 3.4f, p.point)
                drawCircle(tone.copy(alpha = tone.alpha * .25f), base * 1.9f, p.point)
                drawCircle(if (active) Color.White else tone, base * if (active) 1.15f else 1f, p.point)
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(controlsPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = {
                places.randomOrNull()?.let { latitude = it.latitude.toFloat().coerceIn(-75f, 75f); longitude = it.longitude.toFloat(); zoomTo(max(zoom, if (it.approximate) 4f else 8f)) }
            }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Random place" }) { Icon(Icons.Outlined.Shuffle, null) }
            FilledTonalIconButton(onClick = { zoomTo(zoom * 1.6f) }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Zoom in" }) { Icon(Icons.Outlined.Add, null) }
            FilledTonalIconButton(onClick = { zoomTo(zoom / 1.6f) }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Zoom out" }) { Icon(Icons.Outlined.Remove, null) }
        }
    }
}
