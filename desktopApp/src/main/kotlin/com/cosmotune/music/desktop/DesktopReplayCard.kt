package com.cosmotune.music.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Locale
import javax.imageio.ImageIO

/** What the recap card says, kept apart from the drawing so it can be checked without pixels. */
internal data class DesktopReplayCardText(val title: String, val period: String, val headline: String, val stats: List<String>,
    val songs: List<String>, val artists: List<String>)

internal fun DesktopReplaySummary.cardText(holder: String): DesktopReplayCardText = DesktopReplayCardText(
    title = if (holder.isBlank()) "Replay" else "$holder's Replay",
    period = label.ifBlank { period.chip },
    headline = "%,d minutes".format(Locale.ROOT, minutes),
    stats = listOfNotNull(
        "%,d plays".format(Locale.ROOT, totalPlays),
        "$distinctSongs songs".takeIf { distinctSongs > 0 },
        "$distinctArtists artists".takeIf { distinctArtists > 0 },
    ),
    songs = songs.take(5).mapIndexed { i, entry -> "${i + 1}. ${entry.song.title} · ${entry.song.artist}" },
    artists = artists.take(5).mapIndexed { i, entry -> "${i + 1}. ${entry.title}" },
)

/** A 1080 x 1920 story-sized card: the period, minutes listened, plays and the top five songs and artists. */
internal fun renderReplayCard(text: DesktopReplayCardText): BufferedImage {
    val image = BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, java.awt.Color(0x2B1A5E), 1080f, 1920f, java.awt.Color(0x0E3B43))
        g.fillRect(0, 0, image.width, image.height)
        var y = 200
        fun line(value: String, size: Int, weight: Int, alpha: Int = 255, gap: Int = 24) {
            g.font = Font(Font.SANS_SERIF, weight, size)
            g.color = java.awt.Color(255, 255, 255, alpha)
            var shown = value
            // Long titles are shortened to the card's width rather than drawn past its edge.
            while (shown.length > 4 && g.fontMetrics.stringWidth(shown) > 920) shown = shown.dropLast(2).trimEnd() + "…"
            y += g.fontMetrics.ascent
            g.drawString(shown, 80, y)
            y += g.fontMetrics.descent + gap
        }
        line(text.title, 64, Font.BOLD)
        line(text.period, 40, Font.PLAIN, alpha = 190, gap = 80)
        line(text.headline, 120, Font.BOLD, gap = 16)
        line(text.stats.joinToString("  ·  "), 38, Font.PLAIN, alpha = 200, gap = 90)
        if (text.songs.isNotEmpty()) {
            line("Top songs", 46, Font.BOLD, gap = 20)
            text.songs.forEach { line(it, 36, Font.PLAIN, alpha = 230, gap = 14) }
            y += 60
        }
        if (text.artists.isNotEmpty()) {
            line("Top artists", 46, Font.BOLD, gap = 20)
            text.artists.forEach { line(it, 36, Font.PLAIN, alpha = 230, gap = 14) }
        }
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
        g.color = java.awt.Color(255, 255, 255, 150)
        g.drawString("Cosmotune", 80, 1840)
    } finally { g.dispose() }
    return image
}

/** The desktop's recap share card: a preview and a save through the system file picker, as the lyrics card does. */
@Composable
internal fun DesktopReplayShareDialog(summary: DesktopReplaySummary, holder: String, onDismiss: () -> Unit) {
    val text = remember(summary, holder) { summary.cardText(holder) }
    val image by produceState<BufferedImage?>(null, text) { value = withContext(Dispatchers.Default) { renderReplayCard(text) } }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.widthIn(max = 480.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Share your Replay", style = MaterialTheme.typography.titleLarge)
                image?.let { card ->
                    Image(remember(card) { card.toComposeImageBitmap() }, "Replay card preview",
                        Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 540.dp))
                } ?: Text("Preparing your card…")
                message?.let { Text(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Close") }
                    Button(enabled = image != null && !saving, onClick = {
                        scope.launch {
                            saving = true
                            try {
                                val target = com.cosmotune.desktop.chooseCosmotuneFile("cosmotune-replay.png", saving = true)
                                if (target != null) {
                                    val png = if (target.extension.equals("png", true)) target else java.io.File(target.path + ".png")
                                    withContext(Dispatchers.IO) { check(ImageIO.write(image, "png", png)) { "PNG export is unavailable" } }
                                    message = "Replay card saved"
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "The card could not be saved. Try another location." }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) "Saving…" else "Save image") }
                }
            }
        }
    }
}
