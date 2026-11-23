package com.cosmotune.manga.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File

actual object MangaPageImages {
    actual fun treat(source: File, target: File, treatment: MangaPageTreatment): Boolean {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return false
        // Very large pages are decoded at half size or less, so preparing one never runs the device out of memory.
        var sample = 1
        while (probe.outWidth / sample * (probe.outHeight / sample) > 12_000_000) sample *= 2
        val full = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        try {
            var bounds = MangaPageBounds(0, 0, full.width, full.height)
            if (treatment.crop) {
                val scale = maxOf(1, full.width / 400)
                val small = Bitmap.createScaledBitmap(full, full.width / scale, full.height / scale, true)
                val pixels = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
                val found = mangaContentBounds(pixels, small.width, small.height)
                if (small !== full) small.recycle()
                bounds = MangaPageBounds((found.left * scale).coerceAtMost(full.width - 1), (found.top * scale).coerceAtMost(full.height - 1),
                    (found.right * scale).coerceIn(1, full.width), (found.bottom * scale).coerceIn(1, full.height))
            }
            bounds = bounds.half(treatment.half)
            val turn = treatment.rotateWide && treatment.half == 0 && bounds.width > bounds.height
            if (bounds == MangaPageBounds(0, 0, full.width, full.height) && !turn) return false
            val matrix = Matrix().apply { if (turn) postRotate(90f) }
            val out = Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width, bounds.height, matrix, true)
            val temporary = File(target.path + ".part")
            temporary.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (out !== full) out.recycle()
            return temporary.renameTo(target) || run { target.delete(); temporary.renameTo(target) }
        } finally { full.recycle() }
    }

    @Suppress("DEPRECATION")
    actual fun slice(source: File, top: Int, bottom: Int, target: File): Boolean = runCatching {
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(source.path, false) ?: return@runCatching false
        try {
            val piece = decoder.decodeRegion(android.graphics.Rect(0, top, decoder.width, bottom), null) ?: return@runCatching false
            try { target.outputStream().use { piece.compress(Bitmap.CompressFormat.JPEG, 92, it) } } finally { piece.recycle() }
        } finally { decoder.recycle() }
    }.getOrDefault(false)

    actual fun size(file: File): Pair<Int, Int>? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, probe)
        return if (probe.outWidth > 0 && probe.outHeight > 0) probe.outWidth to probe.outHeight else null
    }
}
