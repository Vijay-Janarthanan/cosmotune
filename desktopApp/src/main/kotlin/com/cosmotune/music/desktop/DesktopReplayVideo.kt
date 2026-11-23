package com.cosmotune.music.desktop

import com.cosmotune.desktop.DesktopLyricsVideo
import com.cosmotune.music.data.model.durationMillis
import com.cosmotune.music.data.replay.ReplayReel
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File

/**
 * The desktop's Replay story video: the [reelCards] one after another, each held and growing slightly, then sliding away to the
 * next, with story bars along the top and the top song underneath. The timing is the shared [ReplayReel], as on the phone, and
 * the encoding is [DesktopLyricsVideo.exportFrames].
 */
internal object DesktopReplayVideo {

    suspend fun export(summary: DesktopReplaySummary, holder: String, target: File, onProgress: (Float) -> Unit) {
        val face = replayCardFace()
        val width = DesktopLyricsVideo.FRAME_WIDTH
        val height = DesktopLyricsVideo.FRAME_HEIGHT
        val cards = summary.reelCards(holder).map { scaled(renderReplayCard(it, face), width, height) }
        val reel = ReplayReel(cards.size)
        val top = summary.songs.firstOrNull()?.song
        val clipStart = ReplayReel.clipStartMs(top?.durationMillis() ?: 0, reel.durationMs)
        DesktopLyricsVideo.exportFrames(top, clipStart, reel.durationMs, target, { index, image ->
            draw(image, cards, reel.at(index * 1_000L / DesktopLyricsVideo.FRAMES_PER_SECOND))
        }, onProgress)
    }

    private fun scaled(card: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.drawImage(card, 0, 0, width, height, null)
        } finally { g.dispose() }
        return out
    }

    private fun draw(image: BufferedImage, cards: List<BufferedImage>, moment: ReplayReel.Moment) {
        val width = image.width.toDouble()
        val height = image.height.toDouble()
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.BLACK
            g.fillRect(0, 0, image.width, image.height)
            val shift = moment.slide * width
            fun card(card: BufferedImage, dx: Double, zoom: Float) {
                val at = AffineTransform()
                at.translate(dx + width / 2, height / 2)
                at.scale(zoom.toDouble(), zoom.toDouble())
                at.translate(-width / 2, -height / 2)
                g.drawImage(card, at, null)
            }
            card(cards[moment.card], -shift, moment.zoom)
            moment.next?.let { card(cards[it], width - shift, 1f) }
            // The story bars, as the stories on the phone show them.
            val margin = 24.0
            val gap = 8.0
            val count = moment.progress.size
            val each = (width - 2 * margin - gap * (count - 1)) / count
            moment.progress.forEachIndexed { i, done ->
                val left = margin + i * (each + gap)
                g.color = Color(255, 243, 236, 90)
                g.fill(RoundRectangle2D.Double(left, 22.0, each, 5.0, 6.0, 6.0))
                if (done > 0f) {
                    g.color = Color(255, 243, 236, 240)
                    g.fill(RoundRectangle2D.Double(left, 22.0, each * done, 5.0, 6.0, 6.0))
                }
            }
        } finally { g.dispose() }
    }
}
