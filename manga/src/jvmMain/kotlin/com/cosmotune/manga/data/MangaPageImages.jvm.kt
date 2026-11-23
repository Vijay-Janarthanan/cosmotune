package com.cosmotune.manga.data

import java.io.File
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

actual object MangaPageImages {
    actual fun treat(source: File, target: File, treatment: MangaPageTreatment): Boolean {
        val image = Image.makeFromEncoded(source.readBytes())
        try {
            var bounds = MangaPageBounds(0, 0, image.width, image.height)
            if (treatment.crop) {
                val scale = maxOf(1, image.width / 400)
                val sw = maxOf(1, image.width / scale); val sh = maxOf(1, image.height / scale)
                val small = Bitmap().apply { allocPixels(ImageInfo(sw, sh, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
                Canvas(small).drawImageRect(image, Rect.makeWH(sw.toFloat(), sh.toFloat()))
                val bytes = small.readPixels() ?: return false
                small.close()
                val pixels = IntArray(sw * sh) { i ->
                    val o = i * 4
                    ((bytes[o + 3].toInt() and 0xff) shl 24) or ((bytes[o + 2].toInt() and 0xff) shl 16) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o].toInt() and 0xff)
                }
                val found = mangaContentBounds(pixels, sw, sh)
                bounds = MangaPageBounds((found.left * scale).coerceAtMost(image.width - 1), (found.top * scale).coerceAtMost(image.height - 1),
                    (found.right * scale).coerceIn(1, image.width), (found.bottom * scale).coerceIn(1, image.height))
            }
            val turn = treatment.rotateWide && bounds.width > bounds.height
            if (bounds == MangaPageBounds(0, 0, image.width, image.height) && !turn) return false
            val outW = if (turn) bounds.height else bounds.width
            val outH = if (turn) bounds.width else bounds.height
            val surface = Surface.makeRasterN32Premul(outW, outH)
            try {
                surface.canvas.apply {
                    if (turn) { translate(outW.toFloat(), 0f); rotate(90f) }
                    drawImageRect(image, Rect.makeLTRB(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
                        Rect.makeWH(bounds.width.toFloat(), bounds.height.toFloat()))
                }
                val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 92) ?: return false
                val temporary = File(target.path + ".part")
                temporary.writeBytes(data.bytes)
                return temporary.renameTo(target) || run { target.delete(); temporary.renameTo(target) }
            } finally { surface.close() }
        } finally { image.close() }
    }
}
