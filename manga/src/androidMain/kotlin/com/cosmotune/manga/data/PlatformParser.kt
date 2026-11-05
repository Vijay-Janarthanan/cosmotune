package com.cosmotune.manga.data

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect as AndroidRect
import com.dokar.quickjs.QuickJs
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect

private class AndroidPage(val image: android.graphics.Bitmap) : Bitmap {
    override val width get() = image.width
    override val height get() = image.height
    override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
        Canvas(image).drawBitmap((sourceBitmap as AndroidPage).image,
            AndroidRect(src.left, src.top, src.right, src.bottom), AndroidRect(dst.left, dst.top, dst.right, dst.bottom), null)
    }
}
internal actual fun newMangaBitmap(width: Int, height: Int): Bitmap = AndroidPage(android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888))
internal actual fun decodeMangaBitmap(data: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000)
    return AndroidPage(requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size)))
}
internal actual fun encodeMangaBitmap(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
    (bitmap as AndroidPage).image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    it.toByteArray()
}
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
