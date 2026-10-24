package com.cosmotune.providers

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A disposable output directory stays locked until its media lease and readers are released. */
internal class ProviderPreparedMedia private constructor(private val files: ProviderFiles, val directory: File,
    private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    val outputPath: String get() = File(directory, "audio").absolutePath
    private val closed = AtomicBoolean(false)

    fun accept(raw: String, cancelled: () -> Boolean): File {
        check(!closed.get())
        val address = if (raw.startsWith("file:", true)) {
            val uri = java.net.URI(raw)
            require(uri.rawQuery == null && uri.fragment == null && uri.rawAuthority.isNullOrEmpty())
            File(uri).absolutePath
        } else raw
        val source = files.path(address)
        require(source.isFile && source.length() in 1..512L * 1024 * 1024) { "Provider returned no complete media file" }
        if (source.toPath().startsWith(directory.toPath())) return source
        // Some packages return their own scoped cache entry. Give this lease a disposable copy.
        require(source.length() <= files.remainingBytes()) { "Provider workspace is full" }
        val size = source.length(); val stamp = source.lastModified()
        val suffix = source.extension.lowercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        val copy = File(directory, "audio.$suffix")
        val buffer = ByteArray(64 * 1024)
        try {
            source.inputStream().use { input -> copy.outputStream().use { output ->
                copy.ownerOnly(); var count = 0L
                while (true) {
                    check(!cancelled()) { "Provider media cancelled" }
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(read.toLong() <= size - count) { "Provider media changed" }
                    output.write(buffer, 0, read); count += read
                }
                require(count == size && source.length() == size && source.lastModified() == stamp) { "Provider media changed" }
                check(!cancelled()); output.fd.sync()
            } }
        } finally { buffer.fill(0) }
        return copy
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { lock.release() } finally { channel.close() }
        deleteDirectory(directory)
    }

    companion object {
        private const val ROOT = ".cosmotune-media"
        fun create(files: ProviderFiles): ProviderPreparedMedia {
            val parent = files.path(ROOT)
            check(parent.isDirectory || parent.mkdirs()); parent.ownerOnly()
            cleanupOrphans(parent)
            val directory = files.path("$ROOT/${UUID.randomUUID()}")
            check(directory.mkdir()); directory.ownerOnly()
            val guard = File(directory, ".lease")
            val channel = FileChannel.open(guard.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                guard.ownerOnly()
                return ProviderPreparedMedia(files, directory, channel, requireNotNull(channel.tryLock()))
            } catch (error: Throwable) { channel.close(); deleteDirectory(directory); throw error }
        }
        private fun cleanupOrphans(parent: File) {
            parent.listFiles().orEmpty().take(2_000).forEach { directory ->
                if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) ||
                    !directory.name.matches(Regex("[a-f0-9-]{36}"))) return@forEach
                val guard = File(directory, ".lease")
                var abandoned = false
                if (guard.isFile && !Files.isSymbolicLink(guard.toPath())) {
                    runCatching { FileChannel.open(guard.toPath(), StandardOpenOption.WRITE).use { channel ->
                        channel.tryLock()?.use { abandoned = true }
                    } }
                } else if (System.currentTimeMillis() - directory.lastModified() > 3_600_000) abandoned = true
                if (abandoned) deleteDirectory(directory)
            }
        }
        private fun deleteDirectory(directory: File) {
            if (!directory.exists() || Files.isSymbolicLink(directory.toPath())) return
            // No FOLLOW_LINKS: a damaged provider entry cannot delete the target of a link.
            runCatching { Files.walk(directory.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { item ->
                    require(item.startsWith(directory.toPath()))
                    Files.deleteIfExists(item)
                }
            } }
        }
        fun container(file: File): String {
            val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(64); var count = 0
            while (count < buffer.size) { val read = input.read(buffer, count, buffer.size - count); if (read < 0) break; count += read }
            buffer.copyOf(count)
        }
            fun at(offset: Int, value: String) = offset + value.length <= bytes.size && value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }
            return when {
                at(0, "fLaC") -> "flac"
                at(0, "RIFF") && at(8, "WAVE") -> "wav"
                at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC")) -> "aiff"
                at(4, "ftyp") -> "mp4"
                at(0, "OggS") && at(28, "OpusHead") -> "opus"
                at(0, "OggS") -> "ogg"
                at(0, "ID3") -> "mp3"
                at(0, "DSD ") -> "dsf"
                at(0, "FRM8") && at(12, "DSD ") -> "dff"
                bytes.size >= 4 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 0xf6) == 0xf0 -> "aac"
                bytes.size >= 4 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 6) != 0 -> "mp3"
                else -> error("Provider output has no recognized audio container")
            }
        }
    }
}
