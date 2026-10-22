package com.cosmotune.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.ui.player.LyricsShareRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.text.AttributedString
import javax.imageio.ImageIO

/** The same selected-lyrics flow as Android, exported through each desktop OS's file picker. */
@Composable
fun DesktopLyricsShare(request: LyricsShareRequest, onDismiss: () -> Unit) {
    val background = MaterialTheme.colorScheme.surface.toArgb()
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val image by produceState<BufferedImage?>(null, request, background, ink, accent) {
        value = withContext(Dispatchers.Default) { renderLyricsCard(request, background, ink, accent) }
    }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.widthIn(max = 480.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Share lyrics", style = MaterialTheme.typography.titleLarge)
                image?.let { card ->
                    Image(remember(card) { card.toComposeImageBitmap() }, "Selected lyrics card preview",
                        Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 540.dp))
                } ?: Text("Preparing your card…")
                message?.let { Text(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Close") }
                    Button(enabled = image != null && !saving, onClick = {
                        scope.launch {
                            saving = true
                            try {
                                val target = chooseCosmotuneFile("cosmotune-lyrics.png", saving = true)
                                if (target != null) {
                                    withContext(Dispatchers.IO) { check(ImageIO.write(image, "png", target)) { "PNG export is unavailable" } }
                                    message = "Lyrics image saved"
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "The image could not be saved. Try another location." }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) "Saving…" else "Save image") }
                }
            }
        }
    }
}

/** TextLayout handles complex scripts and right-to-left passages using the platform's logical font fallback. */
internal fun renderLyricsCard(request: LyricsShareRequest, background: Int, ink: Int, accent: Int): BufferedImage {
    val image = BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = java.awt.Color(background); g.fillRect(0, 0, image.width, image.height)
        val width = 888f
        fun layouts(text: String, size: Int, weight: Int): List<java.awt.font.TextLayout> {
            if (text.isBlank()) return emptyList()
            val attributed = AttributedString(text).apply { addAttribute(TextAttribute.FONT, Font(Font.SANS_SERIF, weight, size)) }
            val iterator = attributed.iterator
            val measurer = LineBreakMeasurer(iterator, g.fontRenderContext)
            return buildList { while (measurer.position < iterator.endIndex) add(measurer.nextLayout(width)) }
        }
        val passages = request.lines.flatMap { line ->
            if (line.isGap) listOf("…" to false) else listOf(line.text to false) + listOfNotNull(line.subText?.let { it to true })
        }
        val fontSize = (76 downTo 28).firstOrNull { size ->
            passages.sumOf { (text, sub) -> layouts(text, if (sub) size * 2 / 3 else size, Font.PLAIN).sumOf { (it.ascent + it.descent + it.leading + 12).toDouble() } + 18 } <= 1230
        } ?: 28
        var y = 130f
        g.color = java.awt.Color(accent); g.font = Font(Font.SANS_SERIF, Font.BOLD, 34)
        g.drawString("COSMOTUNE", 96, y.toInt())
        y = 300f
        for ((text, sub) in passages) {
            g.color = java.awt.Color(if (sub) accent else ink)
            for (layout in layouts(text, if (sub) fontSize * 2 / 3 else fontSize, Font.PLAIN)) {
                y += layout.ascent
                layout.draw(g, 96f, y)
                y += layout.descent + layout.leading + 12
            }
            y += 18
        }
        y = 1640f
        g.color = java.awt.Color(accent)
        g.fillRect(96, 1590, 888, 2)
        for (layout in layouts(request.song.title, 42, Font.BOLD).take(2)) {
            y += layout.ascent; layout.draw(g, 96f, y); y += layout.descent + 8
        }
        g.color = java.awt.Color(ink)
        for (layout in layouts(request.song.artist, 30, Font.PLAIN).take(2)) {
            y += layout.ascent; layout.draw(g, 96f, y); y += layout.descent + 8
        }
    } finally { g.dispose() }
    return image
}
