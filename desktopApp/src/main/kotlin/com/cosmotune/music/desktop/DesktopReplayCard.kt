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
import com.cosmotune.ui.theme.CosmoRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Font
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

/**
 * A 1080 x 1920 story-sized card in the look of the Orbit Replay stories: one flat deep colour for the ground (the kit's stand-in
 * colours, picked by the holder so a person's card keeps its colour), the headings in the wide face ([wide], Archivo, when it could
 * be read), a disc in its orbit at the top, then the period, minutes listened, plays and the top five songs and artists in the pale
 * ink of the card.
 */
internal fun renderReplayCard(text: DesktopReplayCardText, wide: Font? = null): BufferedImage {
    val image = BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val fill = com.cosmotune.ui.orbit.STAND_INS[Math.floorMod(text.title.hashCode(), com.cosmotune.ui.orbit.STAND_INS.size)]
        g.color = java.awt.Color(fill.red, fill.green, fill.blue)
        g.fillRect(0, 0, image.width, image.height)
        val ink = java.awt.Color(0xFF, 0xF3, 0xEC)
        fun ink(alpha: Int) = java.awt.Color(ink.red, ink.green, ink.blue, alpha)
        // The disc in its orbit (a quiet disc, the faint ring and the moon at the top), as the story cards open.
        val cx = 860
        val cy = 300
        g.color = ink(36)
        g.fillOval(cx - 110, cy - 110, 220, 220)
        g.color = ink(70)
        g.stroke = java.awt.BasicStroke(4f)
        g.drawOval(cx - 140, cy - 140, 280, 280)
        g.color = ink(245)
        g.fillOval(cx - 12, cy - 152, 24, 24)
        var y = 200
        fun line(value: String, size: Int, heading: Boolean, alpha: Int = 255, gap: Int = 24, width: Int = 920) {
            g.font = if (heading && wide != null) wide.deriveFont(Font.BOLD, size.toFloat()) else Font(Font.SANS_SERIF, if (heading) Font.BOLD else Font.PLAIN, size)
            g.color = ink(alpha)
            var shown = value
            // Long titles are shortened to the card's width rather than drawn past its edge.
            while (shown.length > 4 && g.fontMetrics.stringWidth(shown) > width) shown = shown.dropLast(2).trimEnd() + "…"
            y += g.fontMetrics.ascent
            g.drawString(shown, 80, y)
            y += g.fontMetrics.descent + gap
        }
        fun label(value: String, gap: Int = 20) {
            // The mono label, upper case and tracked, as the cards' labels are.
            g.font = Font(Font.MONOSPACED, Font.BOLD, 30)
            g.color = ink(180)
            val tracked = value.uppercase().toCharArray().joinToString("\u200A")
            y += g.fontMetrics.ascent
            g.drawString(tracked, 80, y)
            y += g.fontMetrics.descent + gap
        }
        label("Cosmotune Replay", gap = 18)
        line(text.title, 64, heading = true, width = 640)
        line(text.period, 40, heading = false, alpha = 190, gap = 90, width = 640)
        line(text.headline, 120, heading = true, gap = 16)
        line(text.stats.joinToString("  ·  "), 38, heading = false, alpha = 200, gap = 90)
        if (text.songs.isNotEmpty()) {
            label("Top songs")
            text.songs.forEach { line(it, 36, heading = false, alpha = 235, gap = 14) }
            y += 60
        }
        if (text.artists.isNotEmpty()) {
            label("Top artists")
            text.artists.forEach { line(it, 36, heading = false, alpha = 235, gap = 14) }
        }
        g.font = if (wide != null) wide.deriveFont(Font.BOLD, 32f) else Font(Font.SANS_SERIF, Font.BOLD, 32)
        g.color = ink(170)
        g.drawString("Cosmotune", 80, 1840)
    } finally { g.dispose() }
    return image
}

/** Archivo, the Orbit headings' face, from the shared resources; null when it cannot be read (the card is then set in the system sans). */
internal suspend fun replayCardFace(): Font? = runCatching {
    val bytes = com.cosmotune.music.sharedui.resources.Res.readBytes("font/archivo_variable.ttf")
    Font.createFont(Font.TRUETYPE_FONT, bytes.inputStream())
}.getOrNull()

/** The desktop's recap share card: a preview and a save through the system file picker, as the lyrics card does. */
@Composable
internal fun DesktopReplayShareDialog(summary: DesktopReplaySummary, holder: String, onDismiss: () -> Unit) {
    val text = remember(summary, holder) { summary.cardText(holder) }
    val image by produceState<BufferedImage?>(null, text) { val face = replayCardFace(); value = withContext(Dispatchers.Default) { renderReplayCard(text, face) } }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    com.cosmotune.ui.sheets.CosmoDialog(onDismiss = onDismiss, maxWidth = 480.dp) {
        run {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.cosmotune.ui.sheets.CosmoDialogHeading(null, "Share your Replay", null)
                image?.let { card ->
                    Image(remember(card) { card.toComposeImageBitmap() }, "Replay card preview",
                        Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 540.dp))
                } ?: Text("Preparing your card…")
                message?.let { Text(it) }
                com.cosmotune.ui.sheets.CosmoDialogActions(
                    confirmLabel = if (saving) "Saving…" else "Save image",
                    confirmEnabled = image != null && !saving,
                    dismissLabel = "Close",
                    onDismiss = onDismiss,
                    dismissEnabled = !saving,
                    onConfirm = {
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
                    },
                )
            }
        }
    }
}
