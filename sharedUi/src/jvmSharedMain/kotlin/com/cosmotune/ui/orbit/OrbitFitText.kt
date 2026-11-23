package com.cosmotune.ui.orbit

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * A big title that never breaks inside a word: when its longest word is wider than the room, the type comes down just enough for
 * that word to fit (to [minScale] of [style] at most; past that the line ends in an ellipsis as before). "Bollywood Fire" in a
 * narrow card reads "Bollywood / Fire", not "Bollywoo / d Fire".
 */
@Composable
fun OrbitFitTitle(text: String, style: TextStyle, color: Color, maxLines: Int, modifier: Modifier = Modifier, minScale: Float = 0.7f) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val room = constraints.maxWidth
        val scale = remember(text, style, room) {
            if (room == Constraints.Infinity || room <= 0) 1f
            else {
                val longest = text.split(' ', '\n').filter { it.isNotEmpty() }.maxOfOrNull { word ->
                    measurer.measure(word, style, softWrap = false, maxLines = 1).size.width
                } ?: 0
                if (longest <= room) 1f else (room.toFloat() / longest).coerceIn(minScale, 1f)
            }
        }
        Text(text, style = if (scale >= 1f) style else style.scaled(scale), color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

private fun TextStyle.scaled(by: Float): TextStyle = copy(
    fontSize = fontSize.times(by),
    lineHeight = if (lineHeight.isSpecified) lineHeight.times(by) else lineHeight,
    letterSpacing = if (letterSpacing.isSpecified) letterSpacing.times(by) else letterSpacing,
)

private fun TextUnit.times(by: Float): TextUnit = this * by
