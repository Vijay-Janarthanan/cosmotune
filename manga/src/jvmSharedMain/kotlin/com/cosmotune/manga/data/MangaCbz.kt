package com.cosmotune.manga.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Mihon's "save as CBZ": a downloaded chapter also written as one comic archive (`Series/Chapter.cbz`, pages `001.jpg` on) that other
 * readers and servers open. With splitting on, a page more than three times taller than wide goes in as pieces about twice its width.
 */
object MangaCbz {
    /** Where the archives go when the listener has not chosen a folder; the phone and the computer set it. */
    @Volatile var defaultFolder: () -> File? = { null }

    fun folder(state: MangaLibraryState): File? = state.cbzFolder?.takeIf { it.isNotBlank() }?.let(::File) ?: runCatching { defaultFolder() }.getOrNull()

    fun safeName(text: String): String {
        val cleaned = text.map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").trim().trim('.', ' ')
        return cleaned.take(120).ifBlank { "Untitled" }
    }

    fun splitPlan(width: Int, height: Int, maxRatio: Float = 3f): List<IntRange> {
        if (width <= 0 || height <= width * maxRatio) return listOf(0 until height)
        val parts = Math.ceil(height / (width * 2.0)).toInt().coerceIn(2, 100)
        val step = Math.ceil(height / parts.toDouble()).toInt()
        return (0 until parts).map { part -> (part * step) until minOf(height, (part + 1) * step) }.filter { !it.isEmpty() }
    }

    fun extensionOf(file: File): String {
        val head = file.inputStream().use { input -> ByteArray(12).also { var n = 0; while (n < it.size) { val r = input.read(it, n, it.size - n); if (r < 0) break; n += r } } }
        fun at(i: Int) = head[i].toInt() and 0xFF
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
            at(0) == 0x89 && head[1] == 'P'.code.toByte() -> "png"
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() -> "gif"
            head[0] == 'R'.code.toByte() && head[8] == 'W'.code.toByte() -> "webp"
            String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "avif"
            else -> "jpg"
        }
    }

    /** Writes [pages] into [target] (through a temporary file, so a half-written archive never stands under the real name). */
    fun write(target: File, pages: List<File>, split: Boolean, sizeOf: (File) -> Pair<Int, Int>?, slice: (File, Int, Int, File) -> Boolean) {
        target.parentFile?.mkdirs()
        val temporary = File(target.path + ".part")
        val scratch = File(target.parentFile, ".pieces-" + System.nanoTime())
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                pages.forEachIndexed { index, page ->
                    val number = "%03d".format(index + 1)
                    val size = if (split) sizeOf(page) else null
                    val plan = size?.let { (width, height) -> splitPlan(width, height) }.orEmpty()
                    if (plan.size > 1) {
                        scratch.mkdirs()
                        plan.forEachIndexed { part, rows ->
                            val piece = File(scratch, "$number-${part + 1}")
                            if (slice(page, rows.first, rows.last + 1, piece)) {
                                zip.putNextEntry(ZipEntry("$number-${part + 1}.jpg")); piece.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                            }
                            piece.delete()
                        }
                    } else {
                        zip.putNextEntry(ZipEntry("$number.${extensionOf(page)}")); page.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
            }
            if (!temporary.renameTo(target)) { target.delete(); check(temporary.renameTo(target)) { "The archive could not be saved" } }
        } finally {
            temporary.delete()
            scratch.deleteRecursively()
        }
    }
}
