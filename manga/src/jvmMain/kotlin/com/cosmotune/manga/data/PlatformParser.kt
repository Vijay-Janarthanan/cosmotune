package com.cosmotune.manga.data

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
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
private val scriptDeadline = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Manga JS deadline").apply { isDaemon = true } }
internal actual suspend fun evaluateMangaScript(script: String, timeoutMs: Long): String? = withContext(Dispatchers.Default) {
    require(script.length <= 512 * 1024) { "Source script exceeds the execution limit" }
    val context = Context.newBuilder("js").allowHostAccess(HostAccess.NONE).allowHostClassLookup { false }
        .allowCreateThread(false).resourceLimits(ResourceLimits.newBuilder().statementLimit(2_000_000, null).build())
        .option("engine.WarnInterpreterOnly", "false").build()
    val deadline = scriptDeadline.schedule({ context.close(true) }, timeoutMs, TimeUnit.MILLISECONDS)
    try { context.eval("js", script).let { if (it.isNull) null else it.toString() } }
    finally { deadline.cancel(false); context.close(true) }
}
