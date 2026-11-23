package com.cosmotune.reading.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** A picture as PNG bytes, for saving or sharing. */
internal expect fun encodeReadingPng(image: ImageBitmap): ByteArray

/** The colours of a quote card: its paper, its words and the quiet line under them. */
internal enum class QuoteCardLook(val label: String, val paper: Color, val ink: Color, val dim: Color) {
    PAPER("Paper", Color(0xFFF6F1E7), Color(0xFF2B2622), Color(0xFF7A7068)),
    NIGHT("Night", Color(0xFF16181D), Color(0xFFECE7DF), Color(0xFF9C978F)),
    PEACH("Peach", Color(0xFFF3B79B), Color(0xFF2A1A14), Color(0xFF6B4A3C)),
}

/**
 * Draws a quote card, 1080 by 1350 (a portrait post): the quote large in a serif, shrunk to fit when long, then the book and author.
 * Gives the PNG bytes.
 */
internal fun drawQuoteCard(measurer: TextMeasurer, quote: String, book: String, author: String, look: QuoteCardLook): ByteArray {
    val width = 1080; val height = 1350; val margin = 96f
    val image = ImageBitmap(width, height)
    val density = Density(1f)
    val text = "“" + quote.replace(Regex("""\s+"""), " ").trim().take(900) + "”"
    val maxQuote = Constraints(maxWidth = (width - margin * 2).toInt(), maxHeight = (height - margin * 2 - 220).toInt())
    // The largest size (from 64 down) at which the whole quote fits.
    var size = 64f
    var quoteLayout = measurer.measure(text, TextStyle(color = look.ink, fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontFamily = FontFamily.Serif), constraints = maxQuote, density = density)
    while ((quoteLayout.hasVisualOverflow || quoteLayout.didOverflowHeight) && size > 26f) {
        size -= 4f
        quoteLayout = measurer.measure(text, TextStyle(color = look.ink, fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontFamily = FontFamily.Serif), constraints = maxQuote,
            overflow = TextOverflow.Ellipsis, density = density)
    }
    val title = measurer.measure(book, TextStyle(color = look.ink, fontSize = 36.sp, fontWeight = FontWeight.SemiBold), constraints = Constraints(maxWidth = (width - margin * 2).toInt()),
        maxLines = 2, overflow = TextOverflow.Ellipsis, density = density)
    val by = measurer.measure(author, TextStyle(color = look.dim, fontSize = 30.sp, fontStyle = FontStyle.Italic), constraints = Constraints(maxWidth = (width - margin * 2).toInt()),
        maxLines = 1, overflow = TextOverflow.Ellipsis, density = density)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        drawRect(look.paper)
        val quoteTop = (height - 220 - quoteLayout.size.height) / 2f
        drawText(quoteLayout, topLeft = Offset(margin, quoteTop.coerceAtLeast(margin)))
        val footTop = height - margin - title.size.height - (if (author.isBlank()) 0 else by.size.height + 12)
        drawRoundRect(look.dim, Offset(margin, footTop - 40f), Size(72f, 6f), CornerRadius(3f, 3f))
        drawText(title, topLeft = Offset(margin, footTop))
        if (author.isNotBlank()) drawText(by, topLeft = Offset(margin, footTop + title.size.height + 12))
    }
    return encodeReadingPng(image)
}

/** [drawQuoteCard] with this screen's text measurer. */
@Composable
internal fun rememberQuoteCard(): (String, String, String, QuoteCardLook) -> ByteArray {
    val measurer = rememberTextMeasurer()
    @Suppress("UNUSED_VARIABLE") val density = LocalDensity.current
    return remember(measurer) { { quote, book, author, look -> drawQuoteCard(measurer, quote, book, author, look) } }
}
