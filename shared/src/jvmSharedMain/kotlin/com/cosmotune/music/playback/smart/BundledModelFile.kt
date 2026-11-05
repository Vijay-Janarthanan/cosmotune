package com.cosmotune.music.playback.smart

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Refresh an extracted model when bundled bytes change, including same-sized replacements. */
object BundledModelFile {
    @Synchronized
    fun install(target: File, source: () -> InputStream): File {
        val expected = source().use(::digest)
        if (target.isFile && target.inputStream().use(::digest).contentEquals(expected)) return target
        val parent = requireNotNull(target.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create model directory" }
        val partial = Files.createTempFile(parent.toPath(), target.name, ".partial")
        try {
            source().use { input -> Files.newOutputStream(partial).use { input.copyTo(it) } }
            check(Files.newInputStream(partial).use(::digest).contentEquals(expected)) { "Model copy changed during extraction" }
            try {
                Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun digest(input: InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        return hash.digest()
    }
}
