package com.cosmotune.music.download

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Publish a new generation only after every segment and the local playlist have been written. */
internal object OfflineMusicPackage {
    fun build(context: Context, id: String, write: (File) -> Unit): Uri {
        val parent = File(context.filesDir, "offline-hls").canonicalFile
        require(parent.parentFile == context.filesDir.canonicalFile)
        return Uri.fromFile(buildFiles(parent, id, write))
    }

    internal fun buildFiles(directory: File, id: String, write: (File) -> Unit): File {
        val parent = directory.canonicalFile
        check(parent.isDirectory || parent.mkdirs()) { "Could not create offline package storage" }
        val generation = UUID.randomUUID().toString()
        val identity = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
        val staging = File(parent, ".building-$generation")
        val target = File(parent, "$identity-$generation")
        require(staging.canonicalFile.parentFile == parent && target.canonicalFile.parentFile == parent)
        check(staging.mkdir()) { "Could not create offline package" }
        try {
            write(staging)
            val playlist = File(staging, "playlist.m3u8")
            check(playlist.isFile && playlist.length() > 0) { "Offline package is incomplete" }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return File(target, "playlist.m3u8")
        } finally {
            // Only this unreferenced generation is removed. A previously published package is never touched.
            require(staging.canonicalFile.parentFile == parent && staging.name == ".building-$generation")
            staging.deleteRecursively()
        }
    }

    fun write(file: File, bytes: ByteArray) { FileOutputStream(file).use { it.write(bytes); it.fd.sync() } }
    fun extras(root: File, lyrics: LyricsTag.Embeddable?, artwork: MediaTagger.Artwork?) {
        lyrics?.let {
            val text = (it.enhanced ?: it.plain.orEmpty()).toByteArray(Charsets.UTF_8)
            require(text.size <= 2 * 1024 * 1024); write(File(root, "lyrics.lrc"), text)
        }
        artwork?.let { require(it.bytes.size <= 8 * 1024 * 1024); write(File(root, "cover.${if (it.mime.contains("png")) "png" else "jpg"}"), it.bytes) }
    }
}
