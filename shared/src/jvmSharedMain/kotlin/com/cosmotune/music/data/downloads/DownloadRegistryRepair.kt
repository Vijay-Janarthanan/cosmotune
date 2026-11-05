package com.cosmotune.music.data.downloads

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Repairs a download registry whose files moved: a record whose file is gone is pointed at the one
 * audio file elsewhere in the library or download folders with the same file name (any case) that no
 * other record already claims. Two candidates, or two records wanting one file, are left alone rather
 * than guessed. What cannot be found is reported so the host can offer it for Retry; nothing is
 * downloaded automatically and no file is moved or deleted.
 */
object DownloadRegistryRepair {
    data class Missing(val id: String, val fileName: String)
    data class Plan(val relocated: Map<String, File>, val unresolved: List<String>, val ambiguous: List<String>)

    private val AUDIO = setOf("flac", "mp3", "m4a", "mp4", "aac", "ogg", "oga", "opus", "webm", "wav", "aiff", "aif", "wv", "ape", "alac")

    fun plan(missing: List<Missing>, candidates: Sequence<File>, claimed: Set<String>): Plan {
        if (missing.isEmpty()) return Plan(emptyMap(), emptyList(), emptyList())
        val wanted = missing.groupBy { it.fileName.lowercase() }
        val found = HashMap<String, MutableList<File>>()
        for (file in candidates) {
            val key = file.name.lowercase()
            if (key !in wanted) continue
            val path = runCatching { file.canonicalPath }.getOrNull() ?: continue
            if (path in claimed || runCatching { file.absolutePath }.getOrNull() in claimed) continue
            if (!file.isFile || file.length() <= 0) continue
            val list = found.getOrPut(key) { mutableListOf() }
            if (list.none { it.canonicalPath == path }) list += file
        }
        val relocated = LinkedHashMap<String, File>(); val unresolved = mutableListOf<String>(); val ambiguous = mutableListOf<String>()
        wanted.forEach { (key, records) ->
            val files = found[key].orEmpty()
            when {
                files.isEmpty() -> records.forEach { unresolved += it.id }
                files.size == 1 && records.size == 1 -> relocated[records.single().id] = files.single()
                else -> records.forEach { ambiguous += it.id }
            }
        }
        return Plan(relocated, unresolved, ambiguous)
    }

    /** Audio files under [roots], breadth-bounded; symbolic links are not followed. */
    fun candidatesUnder(roots: Collection<File>, maxDepth: Int = 5, maxFiles: Int = 50_000): Sequence<File> = sequence {
        var seen = 0
        val visited = HashSet<String>()
        for (root in roots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }) {
            if (!root.isDirectory) continue
            val stream = runCatching { Files.walk(root.toPath(), maxDepth) }.getOrNull() ?: continue
            try {
                for (path in stream.iterator()) {
                    if (seen >= maxFiles) return@sequence
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                    val file = path.toFile()
                    if (file.extension.lowercase() !in AUDIO || !visited.add(file.absolutePath)) continue
                    seen++
                    yield(file)
                }
            } catch (_: java.io.UncheckedIOException) { /* an unreadable folder ends that root's walk */ }
            finally { stream.close() }
        }
    }
}
