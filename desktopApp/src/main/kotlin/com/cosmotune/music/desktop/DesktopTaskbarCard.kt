package com.cosmotune.music.desktop

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

/**
 * The picture the taskbar shows for the window while a song is loaded: the cover, large over a soft
 * copy of itself, with the title and the artist under it. Drawn with Java2D so it can be built on
 * any thread and handed to the shell as pixels.
 */
internal object DesktopTaskbarCard {

    fun render(width: Int, height: Int, art: BufferedImage?, title: String, artist: String): BufferedImage {
        val w = max(width, 1)
        val h = max(height, 1)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.paint = GradientPaint(0f, 0f, Color(0x1F1F28), 0f, h.toFloat(), Color(0x0B0B0F))
            g.fillRect(0, 0, w, h)

            if (art != null) {
                // A copy a few pixels wide, stretched back, is a blur that costs nothing.
                val soft = BufferedImage(max(w / 24, 2), max(h / 24, 2), BufferedImage.TYPE_INT_ARGB)
                soft.createGraphics().also { s ->
                    s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    cover(s, art, 0, 0, soft.width, soft.height)
                    s.dispose()
                }
                // Two steps up rather than one, so the blur has no visible blocks.
                val middle = BufferedImage(max(w / 6, 2), max(h / 6, 2), BufferedImage.TYPE_INT_ARGB)
                middle.createGraphics().also { m ->
                    m.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    m.drawImage(soft, 0, 0, middle.width, middle.height, null)
                    m.dispose()
                }
                g.drawImage(middle, 0, 0, w, h, null)
                g.color = Color(0, 0, 0, 140)
                g.fillRect(0, 0, w, h)
            }

            val hasText = title.isNotBlank()
            val side = (if (hasText) min(h * 0.56, w * 0.46) else min(h * 0.7, w * 0.6)).toInt()
            val left = (w - side) / 2
            val top = if (hasText) (h * 0.08).toInt() else (h - side) / 2
            val shape = RoundRectangle2D.Float(left.toFloat(), top.toFloat(), side.toFloat(), side.toFloat(), side * 0.14f, side * 0.14f)
            val clip = g.clip
            g.clip(shape)
            if (art != null) {
                cover(g, art, left, top, side, side)
            } else {
                g.paint = GradientPaint(left.toFloat(), top.toFloat(), Color(0x3A3A4A), left.toFloat(), (top + side).toFloat(), Color(0x23232E))
                g.fill(shape)
                note(g, left, top, side)
            }
            g.clip = clip

            if (hasText) {
                val padding = max((w * 0.06).toInt(), 6)
                val room = w - padding * 2
                var y = top + side + (h * 0.11).toInt()
                g.font = Font(Font.SANS_SERIF, Font.BOLD, max((h * 0.095).toInt(), 9))
                g.color = Color.WHITE
                centred(g, fit(g, title, room), w, y)
                if (artist.isNotBlank()) {
                    y += (h * 0.095).toInt() + 3
                    g.font = Font(Font.SANS_SERIF, Font.PLAIN, max((h * 0.08).toInt(), 8))
                    g.color = Color(255, 255, 255, 170)
                    centred(g, fit(g, artist, room), w, y)
                }
            }
        } finally {
            g.dispose()
        }
        return image
    }

    /** [art] scaled to fill the box and cropped to it. */
    private fun cover(g: java.awt.Graphics2D, art: BufferedImage, x: Int, y: Int, width: Int, height: Int) {
        val scale = max(width.toDouble() / art.width, height.toDouble() / art.height)
        val drawnW = (art.width * scale).toInt()
        val drawnH = (art.height * scale).toInt()
        g.drawImage(art, x + (width - drawnW) / 2, y + (height - drawnH) / 2, drawnW, drawnH, null)
    }

    /** A music note, for a song with no cover. */
    private fun note(g: java.awt.Graphics2D, left: Int, top: Int, side: Int) {
        val s = side / 100f
        g.color = Color(255, 255, 255, 150)
        g.fillOval((left + 24 * s).toInt(), (top + 56 * s).toInt(), (22 * s).toInt(), (17 * s).toInt())
        g.fillOval((left + 52 * s).toInt(), (top + 48 * s).toInt(), (22 * s).toInt(), (17 * s).toInt())
        val beam = Path2D.Float()
        beam.moveTo(left + 43 * s, top + 63 * s); beam.lineTo(left + 43 * s, top + 24 * s)
        beam.lineTo(left + 71 * s, top + 17 * s); beam.lineTo(left + 71 * s, top + 55 * s)
        beam.lineTo(left + 66 * s, top + 55 * s); beam.lineTo(left + 66 * s, top + 29 * s)
        beam.lineTo(left + 48 * s, top + 34 * s); beam.lineTo(left + 48 * s, top + 63 * s)
        beam.closePath()
        g.fill(beam)
    }

    private fun centred(g: java.awt.Graphics2D, text: String, width: Int, baseline: Int) {
        g.drawString(text, (width - g.fontMetrics.stringWidth(text)) / 2, baseline)
    }

    /** [text] cut with an ellipsis to fit [room] pixels. */
    private fun fit(g: java.awt.Graphics2D, text: String, room: Int): String {
        val metrics = g.fontMetrics
        if (metrics.stringWidth(text) <= room) return text
        var end = text.length
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > room) end--
        return text.substring(0, end).trimEnd() + "…"
    }
}

/** The three transport buttons under the taskbar preview, drawn white, the way the shell draws its own. */
internal object DesktopTaskbarGlyphs {
    enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

    fun render(glyph: Glyph, size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            val s = size / 16f
            fun triangle(x0: Float, x1: Float) {
                val path = Path2D.Float()
                path.moveTo(x0 * s, 3 * s); path.lineTo(x1 * s, 8 * s); path.lineTo(x0 * s, 13 * s); path.closePath()
                g.fill(path)
            }
            fun bar(x: Float, width: Float) = g.fill(java.awt.geom.Rectangle2D.Float(x * s, 3 * s, width * s, 10 * s))
            fun back(x0: Float, x1: Float) {
                val path = Path2D.Float()
                path.moveTo(x1 * s, 3 * s); path.lineTo(x0 * s, 8 * s); path.lineTo(x1 * s, 13 * s); path.closePath()
                g.fill(path)
            }
            when (glyph) {
                Glyph.PLAY -> triangle(4f, 13f)
                Glyph.PAUSE -> { bar(4f, 3f); bar(9f, 3f) }
                Glyph.NEXT -> { triangle(3f, 11f); bar(11.5f, 2.2f) }
                Glyph.PREVIOUS -> { back(5f, 13f); bar(2.3f, 2.2f) }
            }
        } finally {
            g.dispose()
        }
        return image
    }
}
