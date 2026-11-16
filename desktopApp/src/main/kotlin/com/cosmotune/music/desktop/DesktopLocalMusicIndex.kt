package com.cosmotune.music.desktop

import com.cosmotune.listening.ListeningTrack
import com.cosmotune.music.data.model.Song
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reuses unchanged header metadata. Attribute probes and reads happen before the atomic snapshot write. */
internal class DesktopLocalMusicIndex(private val file: File) {
    @Serializable private data class Entry(val size: Long, val modified: Long, val fileKey: String?, val track: ListeningTrack)
    private val json = Json { ignoreUnknownKeys = true }
    private var changed = false
    private val entries = runCatching {
        if (file.isFile && file.length() <= 16L * 1024 * 1024) json.decodeFromString<Map<String, Entry>>(file.readText()).toMutableMap()
        else mutableMapOf()
    }.getOrDefault(mutableMapOf())

    fun song(path: Path, read: (Path) -> Song): Song {
        val absolute = path.toAbsolutePath().normalize()
        val identity = absolute.toString()
        val attributes = Files.readAttributes(absolute, BasicFileAttributes::class.java)
        val existing = entries[identity]
        if (existing != null && existing.size == attributes.size() && existing.modified == attributes.lastModifiedTime().toMillis() &&
            existing.fileKey == attributes.fileKey()?.toString() && existing.track.path == identity && existing.track.id == "local:$identity") return existing.track.song()
        val track = read(absolute)
        entries[identity] = Entry(attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey()?.toString(), ListeningTrack.of(track))
        changed = true
        return track
    }

    fun finish(retainedPaths: Set<String>) {
        if (entries.keys.retainAll(retainedPaths)) changed = true
        if (!changed) return
        runCatching {
            val raw = json.encodeToString(entries).toByteArray(Charsets.UTF_8)
            require(raw.size <= 16 * 1024 * 1024)
            val parent = file.absoluteFile.parentFile
            check(parent.isDirectory || parent.mkdirs())
            val temporary = File.createTempFile("local-index-", ".tmp", parent)
            try {
                temporary.outputStream().use { it.write(raw); it.fd.sync() }
                try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { temporary.delete() }
        }
    }
}
