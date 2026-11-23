package com.cosmotune.desktop

import com.cosmotune.music.desktop.DesktopCanvasDecoder
import com.cosmotune.watch.WatchFrameGrabber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Seek thumbnails for files on the computer: FFmpeg decodes each frame small, and they are laid out in one JPEG. */
internal object DesktopFrameGrabber : WatchFrameGrabber {
    override suspend fun sheet(path: String, timesMs: List<Long>, columns: Int, frameWidth: Int, frameHeight: Int, outPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val decoder = DesktopCanvasDecoder(decorative = true)
            try {
                decoder.open(path).getOrNull() ?: return@withContext false
                decoder.showAt(frameWidth, frameHeight)
                val rows = (timesMs.size + columns - 1) / columns
                val sheet = BufferedImage(columns * frameWidth, rows * frameHeight, BufferedImage.TYPE_INT_RGB)
                val g = sheet.createGraphics()
                g.color = Color.BLACK
                g.fillRect(0, 0, sheet.width, sheet.height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                var drawn = 0
                timesMs.forEachIndexed { index, at ->
                    ensureActive()
                    val w = decoder.width
                    val h = decoder.height
                    if (w <= 0 || h <= 0 || !decoder.seekToMs(at)) return@forEachIndexed
                    val bytes = ByteArray(w * h * 4)
                    if (!decoder.nextFrame(bytes, loop = false)) return@forEachIndexed
                    val pixels = IntArray(w * h) { i ->
                        val b = bytes[i * 4].toInt() and 0xFF
                        val gr = bytes[i * 4 + 1].toInt() and 0xFF
                        val r = bytes[i * 4 + 2].toInt() and 0xFF
                        (r shl 16) or (gr shl 8) or b
                    }
                    val frame = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, pixels, 0, w) }
                    val scale = minOf(frameWidth.toFloat() / w, frameHeight.toFloat() / h)
                    val dw = (w * scale).toInt()
                    val dh = (h * scale).toInt()
                    val x = (index % columns) * frameWidth + (frameWidth - dw) / 2
                    val y = (index / columns) * frameHeight + (frameHeight - dh) / 2
                    g.drawImage(frame, x, y, dw, dh, null)
                    drawn++
                }
                g.dispose()
                drawn > 0 && ImageIO.write(sheet, "jpg", File(outPath))
            } catch (_: Exception) {
                false
            } finally {
                decoder.close()
            }
        }
}
