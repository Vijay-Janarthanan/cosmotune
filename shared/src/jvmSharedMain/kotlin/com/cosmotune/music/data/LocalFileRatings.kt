package com.cosmotune.music.data

import com.cosmotune.music.data.model.LikeStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-local ratings survive scanner row reindexing and temporary unavailability. */
class LocalFileRatings(private val file: File) {
    private val lock = locks.computeIfAbsent(file.absolutePath) { Any() }
    fun read(): Map<String, LikeStatus> = synchronized(lock) {
        if (!file.exists()) emptyMap() else {
            require(file.length() <= 4 * 1024 * 1024) { "Local ratings are too large" }
            Json.decodeFromString<Map<String, String>>(file.readText()).mapValues { LikeStatus.valueOf(it.value) }
                .also { require(it.size <= 20_000 && it.keys.all(::owns)) }
        }
    }
    fun set(id: String, status: LikeStatus) = synchronized(lock) {
        require(owns(id))
        val next = read() + (id to status)
        require(next.size <= 20_000)
        val raw = Json.encodeToString(next.mapValues { it.value.name }).toByteArray(Charsets.UTF_8)
        val parent = file.absoluteFile.parentFile
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile("local-ratings-", ".tmp", parent)
        try {
            temporary.outputStream().use { it.write(raw); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        fun owns(id: String) = id.startsWith("local:") || id.startsWith("content://") || id.startsWith("file://")
        /** Lexical only: neither a filesystem probe nor a metadata/name alias. */
        fun identity(path: String?, uri: String): String = path?.takeIf { it.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(it) }
            ?.let { "local:" + java.nio.file.Paths.get(it).normalize().toString() } ?: uri
    }
}
