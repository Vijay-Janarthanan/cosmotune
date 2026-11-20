package com.cosmotune.manga.data

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.dokar.quickjs.QuickJs
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect

private class DesktopPage(val image: BufferedImage) : Bitmap {
    override val width get() = image.width
    override val height get() = image.height
    override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
        val graphics = image.createGraphics()
        try { graphics.drawImage((sourceBitmap as DesktopPage).image,
            dst.left, dst.top, dst.right, dst.bottom, src.left, src.top, src.right, src.bottom, null) }
        finally { graphics.dispose() }
    }
}
internal actual fun newMangaBitmap(width: Int, height: Int): Bitmap = DesktopPage(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))
internal actual fun decodeMangaBitmap(data: ByteArray): Bitmap {
    ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Unsupported page image")
        try {
            reader.input = input
            require(reader.getWidth(0).toLong() * reader.getHeight(0) <= 40_000_000) { "Page dimensions exceed the decoding limit" }
            return DesktopPage(reader.read(0))
        } finally { reader.dispose() }
    }
}
internal actual fun encodeMangaBitmap(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
    check(ImageIO.write((bitmap as DesktopPage).image, "png", it))
    it.toByteArray()
}
/** The phone's QuickJS limits: no host objects at all, 64 MiB, a 512 KiB stack and the caller's deadline. */
internal actual suspend fun evaluateMangaScript(script: String, timeoutMs: Long): String? = withTimeout(timeoutMs) {
    require(script.length <= 512 * 1024) { "Source script exceeds the execution limit" }
    withContext(Dispatchers.Default) {
        val engine = QuickJs.create(Dispatchers.Default)
        engine.maxStackSize = 512 * 1024L
        engine.memoryLimit = 64L * 1024 * 1024
        engine.evaluationTimeoutMillis = timeoutMs
        try { engine.evaluate<Any?>(script)?.toString() } finally { engine.close() }
    }
}
