package com.cosmotune.music.ui.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import com.cosmotune.music.data.model.durationMillis
import com.cosmotune.music.data.replay.ReplayReel
import com.cosmotune.music.data.stats.ReplaySummary
import com.cosmotune.music.ui.player.LyricsVideoExport
import java.io.File

/**
 * The Replay as a short story video: every story card the Replay has, in the order the stories show them, each held for a
 * few seconds and growing slightly, then sliding away to the next, with story bars along the top and the top song playing
 * underneath. The timing is the shared [ReplayReel], so the desktop's video moves the same way. Drawn on the device and
 * encoded by [LyricsVideoExport.exportFrames]; nothing is uploaded.
 */
internal object ReplayVideo {

    private const val FILE_NAME = "replay.mp4"

    /** Makes the video in the app's share folder and answers a content URI another app can read, or null when it failed. */
    suspend fun make(
        context: Context,
        summary: ReplaySummary,
        holder: String,
        memberSince: String?,
        onProgress: (Float) -> Unit,
    ): Uri? = runCatching {
        val pages = summary.storyPages()
        val width = LyricsVideoExport.FRAME_WIDTH
        val height = LyricsVideoExport.FRAME_HEIGHT
        // Each card as the share button draws it, brought down to the video's size as soon as it is drawn.
        val cards = pages.mapIndexed { index, page ->
            val full = renderReplayPoster(context, summary, holder, memberSince, page)
            onProgress(0.2f * (index + 1) / pages.size)
            Bitmap.createScaledBitmap(full, width, height, true).also { if (it !== full) full.recycle() }
        }
        try {
            val reel = ReplayReel(cards.size)
            val top = summary.songs.firstOrNull()?.song
            val clipStart = ReplayReel.clipStartMs(top?.durationMillis() ?: 0, reel.durationMs)
            val folder = File(context.cacheDir, "shared").apply { mkdirs() }
            val target = File(folder, FILE_NAME)
            val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val painter = Painter(cards, width.toFloat(), height.toFloat())
            try {
                LyricsVideoExport.exportFrames(context, top, clipStart, reel.durationMs, target, { index, pixels ->
                    painter.draw(canvas, reel.at(index * 1_000L / LyricsVideoExport.FRAMES_PER_SECOND))
                    frame.getPixels(pixels, 0, width, 0, 0, width, height)
                }) { onProgress(0.2f + 0.8f * it) }
            } finally {
                frame.recycle()
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        } finally {
            cards.forEach(Bitmap::recycle)
        }
    }.getOrNull()

    /** Draws one moment: the card growing in place, the next one sliding in from the right, the story bars over the top edge. */
    private class Painter(private val cards: List<Bitmap>, private val width: Float, private val height: Float) {
        private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 243, 236) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 255, 243, 236) }
        private val bar = RectF()

        fun draw(canvas: Canvas, moment: ReplayReel.Moment) {
            canvas.drawColor(Color.BLACK)
            val shift = moment.slide * width
            card(canvas, cards[moment.card], -shift, moment.zoom)
            moment.next?.let { card(canvas, cards[it], width - shift, 1f) }
            // The story bars, as the stories on the phone show them.
            val margin = 24f
            val gap = 8f
            val count = moment.progress.size
            val each = (width - 2 * margin - gap * (count - 1)) / count
            moment.progress.forEachIndexed { i, done ->
                val left = margin + i * (each + gap)
                bar.set(left, 22f, left + each, 27f)
                canvas.drawRoundRect(bar, 3f, 3f, track)
                if (done > 0f) {
                    bar.set(left, 22f, left + each * done, 27f)
                    canvas.drawRoundRect(bar, 3f, 3f, fill)
                }
            }
        }

        private fun card(canvas: Canvas, bitmap: Bitmap, dx: Float, zoom: Float) {
            canvas.save()
            canvas.translate(dx, 0f)
            canvas.scale(zoom, zoom, width / 2, height / 2)
            canvas.drawBitmap(bitmap, 0f, 0f, image)
            canvas.restore()
        }
    }
}
