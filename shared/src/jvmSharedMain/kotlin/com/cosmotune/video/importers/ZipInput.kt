package com.cosmotune.video.importers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The files of a ZIP by name (spec 8.2: the Takeout ZIP and the NewPipe backup), through `java.util.zip`.
 *
 * - From a [File] it uses the archive's own directory, so each [read] goes straight to its entry: use this for a large
 *   Takeout archive (the import screen can copy a picked document to the cache folder first).
 * - From a stream ([open] gives a fresh one each time it is asked) or from bytes it reads in passes: [names] makes one pass,
 *   and each [read] a pass that stops at its entry. Nothing is held in memory but the entry returned.
 *
 * Entry names are read as UTF-8; an archive whose names are not (older tools write the system code page without the UTF-8
 * flag) is read again with ISO-8859-1, which never fails, so its names are at worst accented wrongly, and the same name always
 * finds the same entry. Backslashes in names are read as `/`. An entry larger than [MAX_ENTRY_BYTES] reads as null, and so
 * does a name the archive does not have. An input that is not a ZIP, or cannot be opened, has no entries.
 */
class ZipInput private constructor(private val open: (() -> InputStream)?, private val file: File?) : ZipEntries {
    /** A ZIP read from the stream [open] returns each time it is called. */
    constructor(open: () -> InputStream) : this(open, null)

    /** A ZIP held in memory. */
    constructor(bytes: ByteArray) : this({ ByteArrayInputStream(bytes) }, null)

    /** A ZIP in a file. */
    constructor(file: File) : this(null, file)

    private var charset: Charset = Charsets.UTF_8
    private var scanned: List<String>? = null

    override fun names(): List<String> {
        scanned?.let { return it }
        val names = scanOrNull(Charsets.UTF_8) ?: run {
            charset = Charsets.ISO_8859_1
            scanOrNull(Charsets.ISO_8859_1)
        } ?: emptyList()
        scanned = names
        return names
    }

    override fun read(name: String): ByteArray? {
        if (name !in names()) return null
        return try {
            if (file != null) readFromFile(file, name) else readFromStream(name)
        } catch (e: IOException) {
            null
        }
    }

    private fun readFromFile(file: File, name: String): ByteArray? = ZipFile(file, charset).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.replace('\\', '/') == name } ?: return null
        zip.getInputStream(entry).use { readLimited(it) }
    }

    private fun readFromStream(name: String): ByteArray? = ZipInputStream(open!!(), charset).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.replace('\\', '/') == name) return readLimited(zip)
            entry = zip.nextEntry
        }
        null
    }

    /** The file names read with [with], or null when the archive cannot be read with it. */
    private fun scanOrNull(with: Charset): List<String>? = try {
        scan(with)
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun scan(with: Charset): List<String> {
        if (file != null) {
            return ZipFile(file, with).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.map { it.name.replace('\\', '/') }.toList()
            }
        }
        val found = ArrayList<String>()
        ZipInputStream(open!!(), with).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) found.add(entry.name.replace('\\', '/'))
            }
        }
        return found
    }

    private fun readLimited(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    companion object {
        /** The largest entry [read] returns (a NewPipe database or a Takeout history is a few megabytes; this is a bomb guard). */
        const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    }
}
