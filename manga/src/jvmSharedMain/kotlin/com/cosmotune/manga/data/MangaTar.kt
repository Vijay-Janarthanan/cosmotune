package com.cosmotune.manga.data

import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Streaming TAR/CBT conversion; archive paths never become filesystem paths. No native decoder bundle. */
internal object MangaTar {
    suspend fun convert(source: File, output: File, gzip: Boolean) {
        val stream = source.inputStream().let { if (gzip) GZIPInputStream(it) else it }
        stream.use { input -> ZipOutputStream(output.outputStream()).use { zip ->
            var expanded = 0L; var entries = 0
            val names = HashSet<String>()
            fun readExact(count: Int): ByteArray {
                val bytes = input.readNBytes(count)
                require(bytes.size == count) { "Truncated TAR archive" }
                expanded += count
                require(expanded <= MangaArchive.MAX_ARCHIVE_BYTES) { "Expanded TAR exceeds 512 MB" }
                return bytes
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val header = readExact(512)
                if (header.all { it == 0.toByte() }) break
                require(++entries <= 10_000) { "Archive contains too many entries" }
                fun text(offset: Int, size: Int) = header.copyOfRange(offset, offset + size).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()
                fun octal(offset: Int, size: Int): Long = text(offset, size).trim().ifEmpty { "0" }.also { require(it.all { value -> value in '0'..'7' }) { "Unsupported TAR numeric encoding" } }.toLong(8)
                val checksum = octal(148, 8)
                val measured = header.withIndex().sumOf { (position, byte) -> if (position in 148..155) 32L else (byte.toInt() and 255).toLong() }
                require(checksum == measured) { "TAR header checksum failed" }
                val prefix = text(345, 155)
                val rawName = (if (prefix.isEmpty()) "" else "$prefix/") + text(0, 100)
                val type = header[156].toInt().toChar()
                val size = octal(124, 12)
                // A leading './' and a root directory entry are ordinary output from tar -cf archive.tar .
                var name = rawName
                while (name.startsWith("./")) name = name.removePrefix("./")
                if (type == '5' && size == 0L && name.trimEnd('/') in listOf("", ".")) continue
                require(name.length in 1..1_024 && !name.startsWith('/') && !name.startsWith('\\') && ':' !in name && '\\' !in name && name.none(Char::isISOControl) &&
                    name.trimEnd('/').split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe TAR entry name" }
                require(type in setOf('\u0000', '0', '5')) { "TAR links, special files and extended headers are not supported. Save a standard TAR or CBZ instead." }
                require(size in 0..MangaArchive.MAX_ARCHIVE_BYTES && expanded + size <= MangaArchive.MAX_ARCHIVE_BYTES) { "TAR entry exceeds import limits" }
                require(names.add(name)) { "Archive contains duplicate entries" }
                val extension = name.substringAfterLast('.', "").lowercase()
                val keep = type != '5' && (extension in setOf("jpg", "jpeg", "png", "webp", "avif", "gif") || name.equals("ComicInfo.xml", true))
                if (keep) {
                    require(size <= if (name.equals("ComicInfo.xml", true)) 256L * 1024 else MangaArchive.MAX_PAGE_BYTES) { "This page exceeds the 32 MB limit" }
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                }
                var left = size
                while (left > 0) {
                    currentCoroutineContext().ensureActive()
                    val bytes = readExact(minOf(left, 32 * 1024L).toInt())
                    if (keep) zip.write(bytes)
                    left -= bytes.size
                }
                if (keep) zip.closeEntry()
                val padding = ((512 - size % 512) % 512).toInt()
                if (padding > 0) readExact(padding)
                require(output.length() <= MangaArchive.MAX_ARCHIVE_BYTES) { "Converted archive exceeds 512 MB" }
            }
        } }
    }
}
