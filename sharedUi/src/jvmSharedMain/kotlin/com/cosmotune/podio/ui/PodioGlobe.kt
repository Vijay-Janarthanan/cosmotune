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
import com.cosmotune.podio.PodioTiles
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.clipPath
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
private const val RING_DP = 18f
/** Dots this close (in dp) to the nearest dot under the ring belong to the same crowd. */
private const val CROWD_DP = 12f

// A wide, short view shows a bigger globe, cut at the top and bottom, rather than a small one in the middle.
private fun radius(width: Int, height: Int, zoom: Float) = min(width.toFloat(), height * 1.5f) * .42f * zoom

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
    modifier: Modifier = Modifier, focus: RadioStation? = null, controlsPadding: PaddingValues = PaddingValues(10.dp), tiles: PodioTiles? = null,
    /** Where the zoom and random-place buttons sit (the phone puts them beside the globe, clear of the cards at the bottom). */
    controlsAlignment: Alignment = Alignment.BottomEnd,
    /** Held on [focus]: no dragging, zooming, ring or buttons (the station page). */ locked: Boolean = false) {
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
        latitude = place.latitude.toFloat().coerceIn(-75f, 75f); longitude = place.longitude.toFloat()
        zoom = if (locked) (if (place.approximate) 5f else 14f) else max(zoom, if (place.approximate) 4f else 9f)
    }
    // Whatever stands inside the ring is what the panel lists, once the view has stopped for a moment.
    LaunchedEffect(places, dimensions) {
        if (dimensions.width <= 0 || locked) return@LaunchedEffect
        snapshotFlow { Triple(latitude, longitude, zoom) }.collectLatest {
            delay(120)
            val middle = Offset(dimensions.width / 2f, dimensions.height / 2f)
            val shown = places.map { place -> place to project(place.latitude, place.longitude, latitude, longitude, dimensions.width, dimensions.height, zoom) }
                .filter { it.second.depth >= 0 }
            // The dot nearest the ring's middle, if it is inside the ring, and the dots crowded round it.
            val nearest = shown.minByOrNull { (it.second.point - middle).getDistanceSquared() }?.takeIf { (it.second.point - middle).getDistance() <= RING_DP * density }
            onCentre(if (nearest == null) emptyList() else shown.filter { (it.second.point - nearest.second.point).getDistance() <= CROWD_DP * density }
                .sortedBy { (it.second.point - nearest.second.point).getDistanceSquared() }.flatMap { it.first.stations })
        }
    }
    // Satellite tiles: the whole world at a coarse zoom first, then the zoom this view needs; a tile is drawn once it has arrived.
    val images = remember(tiles) { mutableStateMapOf<Long, ImageBitmap>() }
    LaunchedEffect(tiles, dimensions) {
        if (tiles == null || dimensions.width <= 0) return@LaunchedEffect
        snapshotFlow { Triple(latitude, longitude, zoom) }.collectLatest { (lat, lon, zm) ->
            delay(90)
            val r = radius(dimensions.width, dimensions.height, zm)
            val angle = min(hypot(dimensions.width / 2.0, dimensions.height / 2.0) / r, PI / 2)
            val z = PodioTiles.zoomFor(r)
            val wanted = (PodioTiles.visible(2, 0.0, 0.0, PI) + if (z > 2) PodioTiles.visible(z, lat.toDouble(), lon.toDouble(), angle) else emptyList()).distinct()
            coroutineScope {
                wanted.filter { it !in images }.forEach { key ->
                    launch {
                        val bytes = tiles.tile(PodioTiles.zOf(key), PodioTiles.xOf(key), PodioTiles.yOf(key)) ?: return@launch
                        val image = withContext(Dispatchers.Default) { runCatching { bytes.decodeToImageBitmap() }.getOrNull() } ?: return@launch
                        images[key] = image
                    }
                }
            }
            if (images.size > 220) images.keys.filter { PodioTiles.zOf(it) > 2 && it !in wanted }.forEach { images.remove(it) }
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
            .pointerInput(places, locked) { if (!locked) detectTapGestures(onDoubleTap = { zoomTo(zoom * 2f) }) { tap ->
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
            .pointerInput(locked) { if (!locked) detectTransformGestures { _, pan, scale, _ ->
                val r = radius(dimensions.width.coerceAtLeast(1), dimensions.height.coerceAtLeast(1), zoom).coerceAtLeast(1f)
                val degreesPerPixel = (180f / PI.toFloat()) / r
                longitude = ((longitude - pan.x * degreesPerPixel / cos(Math.toRadians(latitude.toDouble())).toFloat().coerceAtLeast(.25f) + 540f) % 360f) - 180f
                latitude = (latitude + pan.y * degreesPerPixel).coerceIn(-75f, 75f)
                zoomTo(zoom * scale)
            } }
            .pointerInput(locked) { if (!locked) awaitPointerEventScope { while (true) {
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
            fun drawTile(key: Long, image: ImageBitmap) {
                val z = PodioTiles.zOf(key); val x = PodioTiles.xOf(key); val y = PodioTiles.yOf(key)
                val steps = if (z <= 3) 12 else 6
                val columns = steps + 1
                val positions = ArrayList<Offset>(columns * columns); val textures = ArrayList<Offset>(columns * columns)
                val front = BooleanArray(columns * columns)
                for (j in 0..steps) for (i in 0..steps) {
                    val p = project(PodioTiles.yToLat(y + j / steps.toDouble(), z), PodioTiles.xToLon(x + i / steps.toDouble(), z), latitude, longitude, width, height, zoom)
                    // A corner round the back is pushed to the rim of the disc, so the picture ends on the rim and not on a ragged edge.
                    positions += if (p.depth >= 0) p.point else { val len = hypot(p.x, p.y).coerceAtLeast(1e-6); Offset(center.x + (r * p.x / len).toFloat(), center.y - (r * p.y / len).toFloat()) }
                    textures += Offset(i / steps.toFloat() * image.width, j / steps.toFloat() * image.height)
                    front[j * columns + i] = p.depth >= 0
                }
                val indices = ArrayList<Int>()
                for (j in 0 until steps) for (i in 0 until steps) {
                    val a = j * columns + i; val b = a + 1; val c = a + columns; val d = c + 1
                    if (front[a] || front[b] || front[c] || front[d]) indices += listOf(a, b, c, b, d, c)
                }
                if (indices.isEmpty()) return
                val paint = Paint().apply { shader = ImageShader(image); isAntiAlias = true }
                drawContext.canvas.drawVertices(Vertices(VertexMode.Triangles, positions, textures, List(positions.size) { Color.White }, indices), BlendMode.Modulate, paint)
            }
            val imagery = images.isNotEmpty()
            if (imagery) clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }) {
                val z = PodioTiles.zoomFor(r)
                val angle = min(hypot(width / 2.0, height / 2.0) / r, PI / 2)
                (PodioTiles.visible(2, 0.0, 0.0, PI) + if (z > 2) PodioTiles.visible(z, latitude.toDouble(), longitude.toDouble(), angle) else emptyList())
                    .distinct().forEach { key -> images[key]?.let { drawTile(key, it) } }
            }
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
            if (!imagery && zoom < 14f) {
                for (lat in -60..60 step 30) graticule((-180..180 step 3).map { GlobePoint(lat.toDouble(), it.toDouble()) }, .08f)
                for (lon in -180 until 180 step 30) graticule((-90..90 step 3).map { GlobePoint(it.toDouble(), lon.toDouble()) }, .08f)
            }
            if (!imagery) for (ring in land) ringPath(ring)?.let { path ->
                drawPath(path, landFill)
                drawPath(path, coast.copy(alpha = .55f), style = Stroke(1.1f))
            }
            drawCircle(coast.copy(alpha = .25f), r, center, style = Stroke(1.5f))
            // The ring that picks.
            if (!locked) drawCircle(Color.White.copy(alpha = .85f), RING_DP.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            // Dots: a place with several stations is a little larger; the chosen place glows.
            places.forEach { place ->
                val p = project(place.latitude, place.longitude, latitude, longitude, width, height, zoom)
                if (p.depth < .02 || p.point.x < -20 || p.point.y < -20 || p.point.x > size.width + 20 || p.point.y > size.height + 20) return@forEach
                val active = place === selectedPlace
                val base = (1.9f + ln(place.stations.size.toFloat() + 1f) * .7f).coerceAtMost(5.5f).dp.toPx()
                val tone = if (place.approximate) accent.copy(alpha = .55f) else accent
                if (imagery) drawCircle(Color.Black.copy(alpha = .35f), base * 1.5f, p.point)
                if (active) drawCircle(accent.copy(alpha = .22f), base * 3.4f, p.point)
                drawCircle(tone.copy(alpha = tone.alpha * .25f), base * 1.9f, p.point)
                drawCircle(if (active) Color.White else tone, base * if (active) 1.15f else 1f, p.point)
            }
        }
        if (images.isNotEmpty()) Text(PodioTiles.ATTRIBUTION, Modifier.align(Alignment.BottomEnd).padding(end = if (controlsAlignment == Alignment.BottomEnd) 62.dp else 10.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .6f))
        if (!locked) Column(Modifier.align(controlsAlignment).padding(controlsPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = {
                places.randomOrNull()?.let { latitude = it.latitude.toFloat().coerceIn(-75f, 75f); longitude = it.longitude.toFloat(); zoomTo(max(zoom, if (it.approximate) 4f else 8f)) }
            }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Random place" }) { Icon(Icons.Outlined.Shuffle, null) }
            FilledTonalIconButton(onClick = { zoomTo(zoom * 1.6f) }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Zoom in" }) { Icon(Icons.Outlined.Add, null) }
            FilledTonalIconButton(onClick = { zoomTo(zoom / 1.6f) }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Zoom out" }) { Icon(Icons.Outlined.Remove, null) }
        }
    }
}
