package com.cosmotune.podio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.*

private data class GlobePoint(val latitude: Double, val longitude: Double)
private data class Projected(val point: Offset, val depth: Double)
private fun project(point: GlobePoint, latitude: Float, longitude: Float, width: Int, height: Int, zoom: Float): Projected {
    val lat = Math.toRadians(point.latitude)
    val lon = Math.toRadians(point.longitude - longitude)
    val tilt = Math.toRadians(latitude.toDouble())
    val radius = min(width, height) * .42f * zoom
    val x = cos(lat) * sin(lon)
    val y = cos(tilt) * sin(lat) - sin(tilt) * cos(lat) * cos(lon)
    return Projected(Offset(width / 2f + (radius * x).toFloat(), height / 2f - (radius * y).toFloat()),
        sin(tilt) * sin(lat) + cos(tilt) * cos(lat) * cos(lon))
}

/** Orthographic world view: bundled public-domain coastlines, no map account or tile service. */
@Composable internal fun PodioGlobe(stations: List<RadioStation>, selected: String?, onSelect: (RadioStation) -> Unit, modifier: Modifier = Modifier) {
    var latitude by remember { mutableFloatStateOf(20f) }
    var longitude by remember { mutableFloatStateOf(78f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val land by produceState<List<List<GlobePoint>>>(emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching {
            val text = requireNotNull(PodioCatalogue::class.java.getResourceAsStream("/podio/land.json")).bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonArray.map { ring -> ring.jsonArray.map { pair ->
                GlobePoint(pair.jsonArray[1].jsonPrimitive.double, pair.jsonArray[0].jsonPrimitive.double)
            } }
        }.getOrDefault(emptyList()) }
    }
    val accent = MaterialTheme.colorScheme.primary
    val coast = MaterialTheme.colorScheme.onSurfaceVariant
    val ocean = MaterialTheme.colorScheme.surfaceContainerHigh
    val located = remember(stations) { stations.filter { it.latitude != null && it.longitude != null } }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged { dimensions = it }
            .pointerInput(located) { detectTapGestures { tap ->
                located.map { station -> station to project(GlobePoint(station.latitude!!, station.longitude!!), latitude, longitude, dimensions.width, dimensions.height, zoom) }
                    .filter { it.second.depth >= 0 }.minByOrNull { (it.second.point - tap).getDistanceSquared() }
                    ?.takeIf { (it.second.point - tap).getDistance() <= 24.dp.toPx() }?.let { onSelect(it.first) }
            } }
            .pointerInput(Unit) { detectTransformGestures { _, pan, scale, _ ->
                longitude = ((longitude - pan.x * .35f + 540f) % 360f) - 180f
                latitude = (latitude + pan.y * .25f).coerceIn(-75f, 75f)
                zoom = (zoom * scale).coerceIn(.8f, 1.6f)
            } }) {
            val radius = min(size.width, size.height) * .42f * zoom
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Brush.radialGradient(listOf(ocean, ocean.copy(alpha = .6f)), center = center, radius = radius), radius, center)
            fun route(points: List<GlobePoint>, color: Color, stroke: Float) {
                val path = Path(); var drawing = false
                for (point in points) {
                    val p = project(point, latitude, longitude, size.width.toInt(), size.height.toInt(), zoom)
                    if (p.depth < 0) { drawing = false; continue }
                    if (!drawing) path.moveTo(p.point.x, p.point.y) else path.lineTo(p.point.x, p.point.y)
                    drawing = true
                }
                drawPath(path, color, style = Stroke(stroke))
            }
            for (lat in -60..60 step 30) route((-180..180 step 3).map { GlobePoint(lat.toDouble(), it.toDouble()) }, coast.copy(alpha = .10f), 1f)
            for (lon in -180 until 180 step 30) route((-90..90 step 3).map { GlobePoint(it.toDouble(), lon.toDouble()) }, coast.copy(alpha = .10f), 1f)
            for (ring in land) route(ring, coast.copy(alpha = .50f), 1.2f)
            drawCircle(coast.copy(alpha = .2f), radius, center, style = Stroke(1.2f))
            for (station in located) {
                val p = project(GlobePoint(station.latitude!!, station.longitude!!), latitude, longitude, size.width.toInt(), size.height.toInt(), zoom)
                if (p.depth < .02) continue
                val active = station.id == selected
                if (active) drawCircle(accent.copy(alpha = .18f), 13.dp.toPx(), p.point)
                drawCircle(accent.copy(alpha = if (active) 1f else .65f), if (active) 4.dp.toPx() else 2.dp.toPx(), p.point)
            }
        }
        Text("A world of live radio", Modifier.align(Alignment.TopCenter).padding(8.dp), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.align(Alignment.BottomCenter), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { zoom = (zoom - .15f).coerceAtLeast(.8f) }) { Text("−") }
            Text("Drag to explore · tap a station", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { zoom = (zoom + .15f).coerceAtMost(1.6f) }) { Text("+") }
        }
    }
}
