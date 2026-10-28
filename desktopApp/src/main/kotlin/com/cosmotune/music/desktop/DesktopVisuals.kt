package com.cosmotune.music.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.sqrt

internal data class DesktopVisualFrame(val wave: FloatArray = FloatArray(128), val energy: Float = 0f)

/** Original local visuals. Sampling is dormant unless the fullscreen window is observing. */
internal object DesktopVisualAudio {
    val frame = MutableStateFlow(DesktopVisualFrame())
    private var lastNs = 0L
    fun publish(samples: FloatArray, count: Int, gain: Float) {
        if (frame.subscriptionCount.value == 0 || count <= 0) return
        val now = System.nanoTime()
        if (now - lastNs < 40_000_000) return
        lastNs = now
        frame.value = visualFrame(samples, count, gain)
    }
}

internal fun visualFrame(samples: FloatArray, count: Int, gain: Float): DesktopVisualFrame {
    val n = count.coerceIn(0, samples.size)
    if (n == 0) return DesktopVisualFrame()
    val wave = FloatArray(128) { i -> (samples[(i.toLong() * n / 128).toInt().coerceAtMost(n - 1)] * gain).takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f }
    val energy = sqrt(wave.sumOf { it.toDouble() * it } / wave.size).toFloat()
    return DesktopVisualFrame(wave, energy)
}

@Composable
internal fun DesktopVisualsLauncher(song: Song?, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) { Text("Visuals", color = Color.White) }
    if (open) Window(
        onCloseRequest = { open = false }, title = "Cosmotune · Visuals",
        state = rememberWindowState(placement = WindowPlacement.Fullscreen), undecorated = true,
        onKeyEvent = { event -> if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { open = false; true } else false },
    ) {
        MaterialTheme {
            var scene by remember { mutableStateOf("Artwork") }
            val audio by DesktopVisualAudio.frame.collectAsState()
            Box(Modifier.fillMaxSize().background(Color(0xFF090C12))) {
                when (scene) {
                    "Artwork" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DesktopArtwork(song?.thumbnailUrl, Modifier.fillMaxHeight(.72f).aspectRatio(1f), px = 1200)
                    }
                    "Pulse" -> Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension * (.13f + audio.energy * .32f)
                        drawCircle(Brush.radialGradient(listOf(Color(0xFFDFB494).copy(alpha = .9f), Color(0xFF546E9E).copy(alpha = .35f), Color.Transparent), radius = radius * 2.2f), radius * 2.2f)
                        repeat(4) { index -> drawCircle(Color(0xFFEFCCAF).copy(alpha = .5f / (index + 1)), radius + index * 30.dp.toPx(), style = Stroke(1.5.dp.toPx())) }
                    }
                    else -> Canvas(Modifier.fillMaxSize().padding(horizontal = 64.dp)) {
                        val path = Path()
                        audio.wave.forEachIndexed { i, value ->
                            val point = Offset(size.width * i / (audio.wave.size - 1), size.height / 2 + value * size.height * .35f)
                            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        }
                        drawPath(path, Brush.horizontalGradient(listOf(Color(0xFF7DA4CD), Color(0xFFF2C2A0))), style = Stroke(3.dp.toPx()))
                    }
                }
                Row(Modifier.align(Alignment.TopCenter).padding(20.dp)) {
                    listOf("Artwork", "Pulse", "Waveform").forEach { name -> TextButton(onClick = { scene = name }) { Text(name, color = if (scene == name) Color(0xFFF2C2A0) else Color.White) } }
                    TextButton(onClick = { open = false }) { Text("Close · Esc", color = Color.White) }
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(song?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(song?.artist.orEmpty(), color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
