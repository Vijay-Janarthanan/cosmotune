package com.cosmotune.music.data.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

/** A blocking byte transfer: validate the rendition and range before appending, then publish atomically. */
class ValidatedMusicTransfer(
    private val exchange: (Map<String, String>, (okhttp3.Response) -> Unit) -> Unit,
    private val checkCancelled: () -> Unit = {},
    private val protect: (File) -> Unit = {},
    private val maxBytes: Long = 512L * 1024 * 1024,
) {
    fun download(identity: String, destination: File, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File {
        require(maxBytes > 0)
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        protect(parent)
        val part = File(parent, destination.name + ".part")
        val checkpoint = File(parent, destination.name + ".checkpoint")
        require(!java.nio.file.Files.isSymbolicLink(part.toPath()) && !java.nio.file.Files.isSymbolicLink(checkpoint.toPath()))
        val urlHash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        var validator: String? = runCatching {
            require(checkpoint.length() in 1..16_384)
            val saved = Json.parseToJsonElement(checkpoint.readText()).jsonObject
            require(saved["urlHash"]?.jsonPrimitive?.content == urlHash)
            saved["validator"]?.jsonPrimitive?.content?.takeIf { it.length <= 8_192 && ('\r' !in it && '\n' !in it) }
        }.getOrNull()
        val offset = if (validator != null && part.isFile && part.length() in 1..maxBytes) part.length() else 0L
        val request = if (offset > 0) mapOf("Range" to "bytes=$offset-", "If-Range" to requireNotNull(validator)) else emptyMap()
        try {
            checkCancelled()
            exchange(request) { response ->
                require(response.code == 200 || response.code == 206) { "Music download request failed" }
                require(response.header("Content-Encoding").let { it == null || it.equals("identity", true) }) { "Download server encoded a byte range" }
                val nextValidator = response.header("ETag")?.takeIf { it.length in 2..8_192 && it.startsWith('"') && it.endsWith('"') && it.all { c -> c.code in 32..126 } }
                    ?: response.header("Last-Modified")?.takeIf { it.isNotBlank() && it.length <= 8_192 && it.all { c -> c.code in 32..126 } }
                val range = response.header("Content-Range")?.let { RANGE.matchEntire(it) }
                require(response.code != 200 || response.header("Content-Range") == null) { "Download server returned an unexpected content range" }
                val append = offset > 0 && response.code == 206
                if (response.code == 206) {
                    require(range != null) { "Download server returned an invalid content range" }
                    val start = requireNotNull(range.groupValues[1].toLongOrNull())
                    val end = requireNotNull(range.groupValues[2].toLongOrNull())
                    val total = requireNotNull(range.groupValues[3].toLongOrNull())
                    require(start == (if (append) offset else 0L) && end >= start && end < total && end == total - 1) { "Download server returned a discontinuous media range" }
                    require(!append || validator == nextValidator) { "Music download changed during resume" }
                    require(total <= maxBytes)
                    val length = response.body.contentLength()
                    require(length < 0 || length == end - start + 1) { "Music download range length disagrees" }
                }
                val expected = range?.groupValues?.get(3)?.toLongOrNull()
                    ?: response.body.contentLength().takeIf { it >= 0 }
                require(expected == null || expected in 1..maxBytes) { "Music download exceeds its size limit" }
                validator = nextValidator
                // A signed URL is never written to a checkpoint. A missing validator forces a clean retry.
                if (!append || validator == null) checkpoint.delete()
                var written = if (append) offset else 0L
                onProgress(written, expected)
                FileOutputStream(part, append).use { output ->
                    protect(part)
                    if (validator != null) atomicCheckpoint(checkpoint, buildJsonObject { put("urlHash", urlHash); put("validator", validator) }.toString())
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(written <= maxBytes - count && (expected == null || written <= expected - count)) { "Music download exceeds its declared length" }
                            output.write(buffer, 0, count); written += count
                            onProgress(written, expected)
                        }
                    }
                    require(written > 0 && (expected == null || written == expected)) { "Music download ended early" }
                    output.fd.sync()
                }
                checkCancelled()
                move(part, destination)
                protect(destination)
                checkpoint.delete()
            }
            return destination
        } catch (error: Exception) {
            // A malformed range or changed validator must never be retried by appending to these bytes.
            if (error !is IOException && error !is CancellationException) { part.delete(); checkpoint.delete() }
            throw error
        }
    }

    private fun atomicCheckpoint(file: File, text: String) {
        val temporary = File.createTempFile("checkpoint-", ".part", file.parentFile)
        try {
            protect(temporary)
            temporary.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
            move(temporary, file)
        } finally { temporary.delete() }
    }

    private fun move(source: File, target: File) {
        try { java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private companion object { val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)") }
}
