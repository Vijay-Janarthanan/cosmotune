package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.video.player.VideoPlayerEngine
import com.cosmotune.watch.SubtitleBackground
import com.cosmotune.watch.SubtitleColour
import com.cosmotune.watch.SubtitleFont
import com.cosmotune.watch.WatchSubtitleStyle

/** The three lines of the editor's preview. */
private const val PREVIEW_LINE = "The harbour lights came on\none by one."
private val HEIGHT_NAMES = listOf("Lowest", "Low", "Middle", "High", "Highest")

/**
 * The subtitles of what [engine] plays, drawn over the picture in [style] (the engines hand their cues to the app and draw none
 * themselves). Fills the box it is put in, which is the picture's own frame.
 */
@Composable
fun BoxScope.WatchEngineSubtitles(engine: VideoPlayerEngine, style: WatchSubtitleStyle) {
    val cues by engine.cues.collectAsState()
    if (cues.isEmpty()) return
    WatchSubtitleText(cues.joinToString("\n") { it.text.trim() }, style)
}

/** [text] as subtitles in [style]: centred, [WatchSubtitleStyle.liftPercent] of the box above its foot, at most 80 % of its width. */
@Composable
fun BoxScope.WatchSubtitleText(text: String, style: WatchSubtitleStyle, fonts: OrbitFonts = orbitFonts()) {
    val s = style.coerced()
    BoxWithConstraints(Modifier.matchParentSize().testTag("watch:subtitles")) {
        val size = s.textSize(maxHeight.value)
        val imported = remember(s.fontPath) { s.fontPath?.let(::subtitleFontFamily) }
        val family = when (s.font) {
            SubtitleFont.APP -> fonts.text; SubtitleFont.SERIF -> FontFamily.Serif; SubtitleFont.MONO -> FontFamily.Monospace
            SubtitleFont.YOURS -> imported ?: fonts.text
        }
        val plainShadow = s.background == SubtitleBackground.NONE && !s.outline
        val base = TextStyle(fontFamily = family, fontWeight = if (s.bold) FontWeight.Bold else FontWeight(500), fontSize = size.sp,
            lineHeight = (size * 1.28f).sp, textAlign = TextAlign.Center,
            shadow = if (plainShadow) Shadow(Color.Black, Offset(0f, 1f), 6f) else null)
        val back = Color.Black.copy(alpha = s.backgroundPercent / 100f)
        val words: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                if (s.outline) Text(text, style = base.copy(drawStyle = Stroke(width = size / 6f), shadow = null), color = Color.Black, maxLines = 3)
                Text(text, style = base, color = Color(s.colour.argb), maxLines = 3)
            }
        }
        val lift = maxHeight * (s.liftPercent / 100f)
        val wideAs = maxWidth
        when (s.background) {
            SubtitleBackground.BAND -> Box(Modifier.align(Alignment.BottomCenter).padding(bottom = lift).fillMaxWidth().background(back).padding(vertical = 4.dp),
                contentAlignment = Alignment.Center) { words(Modifier.widthIn(max = wideAs * 0.86f)) }
            SubtitleBackground.BOX -> words(Modifier.align(Alignment.BottomCenter).padding(bottom = lift).widthIn(max = maxWidth * 0.8f)
                .background(back, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            SubtitleBackground.NONE -> words(Modifier.align(Alignment.BottomCenter).padding(bottom = lift).widthIn(max = maxWidth * 0.8f))
        }
    }
}

/**
 * The subtitle look editor: a live preview on a dark still, then size, colour, what is behind the words and how strong, the face and
 * the height, each a row of chips under its label, then Outline and Bold. Every change shows at once and is kept by [onChange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchSubtitleEditor(style: WatchSubtitleStyle, onChange: (WatchSubtitleStyle) -> Unit, fonts: OrbitFonts, look: OrbitLook,
    /** Picks a font file and keeps it; null where fonts cannot be imported. */
    onImportFont: (() -> Unit)? = null, fontMessage: String? = null) {
    val s = style.coerced()
    val group: @Composable (String, @Composable () -> Unit) -> Unit = { label, content ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitLabel(label, fonts, look.dim, Modifier.padding(start = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The preview: a dusk still drawn by the app, so it never depends on what plays.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3B4A6B), Color(0xFF9A6A5A), Color(0xFF2A2522)))).testTag("watch:subtitles:preview")) {
            WatchSubtitleText(PREVIEW_LINE, s, fonts)
        }
        group("Size") { WatchSubtitleStyle.SIZES.forEach { v -> OrbitSelectChip("$v%", s.sizePercent == v, fonts, look, onClick = { onChange(s.copy(sizePercent = v)) }, haptic = false) } }
        group("Colour") { SubtitleColour.entries.forEach { c -> OrbitSelectChip(c.label, s.colour == c, fonts, look, onClick = { onChange(s.copy(colour = c)) }, haptic = false) } }
        group("Behind the words") { SubtitleBackground.entries.forEach { b -> OrbitSelectChip(b.label, s.background == b, fonts, look, onClick = { onChange(s.copy(background = b)) }, haptic = false) } }
        if (s.background != SubtitleBackground.NONE) group("Background strength") {
            WatchSubtitleStyle.BACKGROUNDS.forEach { v -> OrbitSelectChip("$v%", s.backgroundPercent == v, fonts, look, onClick = { onChange(s.copy(backgroundPercent = v)) }, haptic = false) }
        }
        group("Face") {
            SubtitleFont.entries.filter { it != SubtitleFont.YOURS }.forEach { f -> OrbitSelectChip(f.label, s.font == f, fonts, look, onClick = { onChange(s.copy(font = f)) }, haptic = false) }
            // An imported font is chosen again by its name; "Import a font" picks another file.
            if (style.fontPath != null) OrbitSelectChip(style.fontName ?: "Your font", s.font == SubtitleFont.YOURS, fonts, look,
                onClick = { onChange(s.copy(font = SubtitleFont.YOURS)) }, haptic = false)
            if (onImportFont != null) OrbitSelectChip("Import a font", false, fonts, look, onClick = onImportFont, haptic = false)
        }
        fontMessage?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp)) }
        group("Height above the bottom") {
            WatchSubtitleStyle.LIFTS.forEachIndexed { i, v ->
                OrbitSelectChip(HEIGHT_NAMES[i], s.liftPercent == v, fonts, look, onClick = { onChange(s.copy(liftPercent = v)) }, haptic = false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Outline", style = fonts.title, color = look.ink, modifier = Modifier.weight(1f).padding(start = 6.dp))
            OrbitSwitch(s.outline, { onChange(s.copy(outline = it)) }, look, "Outline")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bold", style = fonts.title, color = look.ink, modifier = Modifier.weight(1f).padding(start = 6.dp))
            OrbitSwitch(s.bold, { onChange(s.copy(bold = it)) }, look, "Bold")
        }
        if (s != WatchSubtitleStyle()) com.cosmotune.ui.orbit.WatchCardChip("Back to the usual look", look.ink, fonts, onClick = { onChange(WatchSubtitleStyle()) })
    }
}
