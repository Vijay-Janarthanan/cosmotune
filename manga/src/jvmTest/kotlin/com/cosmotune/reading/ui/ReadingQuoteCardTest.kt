package com.cosmotune.reading.ui

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingQuoteCardTest {
    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    @Test fun aQuoteBecomesAPortraitPictureInItsColours() {
        val png = drawQuoteCard(measurer, "It was the best of times, it was the worst of times.", "A Tale of Two Cities", "Charles Dickens", QuoteCardLook.NIGHT)
        val image = ImageIO.read(png.inputStream())
        assertEquals(1080, image.width); assertEquals(1350, image.height)
        val corner = java.awt.Color(image.getRGB(4, 4))
        assertEquals(Triple(0x16, 0x18, 0x1D), Triple(corner.red, corner.green, corner.blue), "the paper colour")
        var inked = 0
        for (y in 0 until image.height step 4) for (x in 0 until image.width step 4) if (java.awt.Color(image.getRGB(x, y)).red > 0xC0) inked++
        assertTrue(inked > 200, "the words are drawn: $inked")
    }

    @Test fun aVeryLongQuoteStillFits() {
        val png = drawQuoteCard(measurer, "word ".repeat(400), "Book", "", QuoteCardLook.PAPER)
        assertEquals(1350, ImageIO.read(png.inputStream()).height)
    }
}
