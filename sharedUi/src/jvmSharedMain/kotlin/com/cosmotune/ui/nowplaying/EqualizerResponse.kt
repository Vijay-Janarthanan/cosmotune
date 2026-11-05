package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.music.playback.EqCurve
import com.cosmotune.music.playback.responseDb
import kotlin.math.pow
import kotlin.math.roundToInt

/** Shared presentation: a measured filter calculation, never an audio-spectrum animation. */
@Composable
fun EqualizerResponse(curve: EqCurve, sampleRate: Int = 44_100, modifier: Modifier = Modifier) {
    val rate = sampleRate.coerceAtLeast(8_000)
    val maximum = minOf(20_000f, rate * 0.49f)
    val response = remember(curve, rate) { FloatArray(160) { point ->
        curve.responseDb((20.0 * (maximum / 20.0).pow(point / 159.0)).toFloat(), rate)
    } }
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Equalizer response", style = MaterialTheme.typography.titleSmall)
        Text("Estimated preamp ${curve.preampDb.roundToInt()} dB · ${(rate / 100f).roundToInt() / 10f} kHz reference",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics {
            contentDescription = "Equalizer frequency response from 20 hertz to ${maximum.roundToInt()} hertz, including preamp compensation"
        }) {
            for (db in listOf(-12f, 0f, 12f)) {
                val y = size.height * (24f - db) / 48f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), if (db == 0f) 2f else 1f)
            }
            val path = Path()
            response.forEachIndexed { i, db ->
                val x = size.width * i / response.lastIndex
                val y = size.height * (24f - db.coerceIn(-24f, 24f)) / 48f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20 Hz", style = MaterialTheme.typography.labelSmall)
            Text("0 dB centre · ±24 dB", style = MaterialTheme.typography.labelSmall)
            Text("${(maximum / 1000f).roundToInt()} kHz", style = MaterialTheme.typography.labelSmall)
        }
    }
}
