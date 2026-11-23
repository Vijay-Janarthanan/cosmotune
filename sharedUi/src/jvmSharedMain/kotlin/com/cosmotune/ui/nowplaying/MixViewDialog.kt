package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.smart.AnalysisStore
import com.cosmotune.music.playback.smart.MixOverview
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The mix view (an Automix overview, from the player menu): the song's tempo and key as Automix measured them, its loudness
 * along the song, where Automix brings it in and takes it out, and how the next song compares. Only songs Automix has analysed
 * (ones it has played) have one.
 */
@Composable
internal fun MixViewDialog(song: Song, next: Song?, onDismiss: () -> Unit) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val view by produceState<MixOverview?>(null, song.videoId, next?.videoId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val dir = PlayerPlatform.host.analysisDirectory() ?: return@runCatching null
                val store = AnalysisStore { dir }
                MixOverview.of(store.load(song.videoId), next?.let { store.load(it.videoId) })
            }.getOrNull()
        }
    }
    CosmoAlertDialog(onDismissRequest = onDismiss, title = "Mix view", confirmLabel = "Done", onConfirm = onDismiss, content = {
        val shown = view
        if (shown == null) {
            Text("Automix has not measured this song yet. It does once the song has played with Automix on.", style = fonts.body, color = look.dim)
            return@CosmoAlertDialog
        }
        Column(Modifier.fillMaxWidth().testTag("mix-view"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(shown.tempo, shown.key).joinToString(" · "), style = fonts.title, color = look.ink)
            val ink = look.ink; val dim = look.dim; val accent = look.accent; val row = look.row
            Canvas(Modifier.fillMaxWidth().height(72.dp).testTag("mix-view:chart")) {
                drawRoundRect(row, cornerRadius = CornerRadius(12.dp.toPx()))
                // Where the song comes in and goes out of a mix, as the lit stretch.
                val left = size.width * shown.fraction(shown.mixInS); val right = size.width * shown.fraction(shown.mixOutS)
                drawRect(accent.copy(alpha = 0.22f), Offset(left, 0f), Size((right - left).coerceAtLeast(0f), size.height))
                if (shown.energy.size > 1) {
                    val path = Path()
                    shown.energy.forEachIndexed { i, e ->
                        val x = size.width * i / (shown.energy.size - 1); val y = size.height * (1f - e.toFloat() * 0.85f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, ink, style = Stroke(width = 2.dp.toPx()))
                }
                for (at in listOf(shown.introEndS, shown.outroStartS)) {
                    val x = size.width * shown.fraction(at)
                    drawLine(dim, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                }
            }
            Text("Comes in at ${MixOverview.time(shown.mixInS)} and leaves from ${MixOverview.time(shown.mixOutS)} of ${MixOverview.time(shown.lengthS)}. " +
                "The intro ends at ${MixOverview.time(shown.introEndS)} and the outro starts at ${MixOverview.time(shown.outroStartS)}.",
                style = fonts.body, color = look.dim)
            shown.next?.let { Text(it, style = fonts.body, color = look.ink) }
        }
    })
}
