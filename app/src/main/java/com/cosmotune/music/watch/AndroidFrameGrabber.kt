package com.cosmotune.music.watch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import com.cosmotune.watch.WatchFrameGrabber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Seek thumbnails for files on the phone or TV: the platform's retriever takes each frame small, laid out in one JPEG. */
internal object AndroidFrameGrabber : WatchFrameGrabber {
    override suspend fun sheet(path: String, timesMs: List<Long>, columns: Int, frameWidth: Int, frameHeight: Int, outPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val rows = (timesMs.size + columns - 1) / columns
                val sheet = Bitmap.createBitmap(columns * frameWidth, rows * frameHeight, Bitmap.Config.RGB_565)
                val canvas = Canvas(sheet)
                canvas.drawColor(Color.BLACK)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                var drawn = 0
                timesMs.forEachIndexed { index, at ->
                    ensureActive()
                    val frame = runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, frameWidth * 2, frameHeight * 2)
                        else retriever.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull() ?: return@forEachIndexed
                    val scale = minOf(frameWidth.toFloat() / frame.width, frameHeight.toFloat() / frame.height)
                    val dw = (frame.width * scale).toInt()
                    val dh = (frame.height * scale).toInt()
                    val x = (index % columns) * frameWidth + (frameWidth - dw) / 2
                    val y = (index / columns) * frameHeight + (frameHeight - dh) / 2
                    canvas.drawBitmap(frame, null, Rect(x, y, x + dw, y + dh), paint)
                    frame.recycle()
                    drawn++
                }
                val ok = drawn > 0 && File(outPath).outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                sheet.recycle()
                ok
            } catch (_: Exception) {
                false
            } finally {
                runCatching { retriever.release() }
            }
        }
}
