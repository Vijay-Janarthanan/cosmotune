package com.cosmotune.providers

import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class ProviderPackageReview(val file: File, val manifest: ProviderManifest, val sha256: String, val expandedBytes: Long)

/** Package import is data inspection. It never initializes the extension's JavaScript. */
class ProviderPackages(private val staging: File) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun inspect(open: () -> InputStream): ProviderPackageReview = withContext(Dispatchers.IO) {
        check(staging.isDirectory || staging.mkdirs())
        val file = File.createTempFile("review-", ".sflx", staging)
        try {
            open().use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count; require(size <= MAX_PACKAGE_BYTES) { "Provider package exceeds 32 MB" }
                    output.write(buffer, 0, count)
                }
            } }
            inspectFile(file)
        } catch (error: Throwable) { file.delete(); throw error }
    }

    suspend fun inspectFile(file: File): ProviderPackageReview = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() <= MAX_PACKAGE_BYTES)
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 100) { "Provider package has too many files" }
            require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate provider archive path" }
            var expanded = 0L
            for (entry in entries) {
                require(safePath(entry.name)) { "Unsafe provider archive path" }
                if (entry.isDirectory) continue
                val limit = when (entry.name) { "manifest.json" -> MAX_MANIFEST_BYTES; "index.js" -> MAX_SCRIPT_BYTES; else -> MAX_EXPANDED_BYTES }
                require(entry.size <= limit) { "Provider file exceeds its size limit" }
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count; expanded += count
                        require(size <= limit && expanded <= MAX_EXPANDED_BYTES) { "Expanded provider package exceeds 64 MB" }
                    }
                }
            }
            val manifestEntry = zip.getEntry("manifest.json") ?: error("Provider package needs manifest.json at its root")
            require(!manifestEntry.isDirectory && zip.getEntry("index.js")?.isDirectory == false) { "Provider package needs index.js at its root" }
            val manifest = json.decodeFromString<ProviderManifest>(utf8(zip.getInputStream(manifestEntry).use { it.readBytes() })).validate()
            // Validate the script's encoding without evaluating it or trusting its contents.
            utf8(zip.getInputStream(zip.getEntry("index.js")).use { it.readBytes() })
            ProviderPackageReview(file.canonicalFile, manifest, digest(file), expanded)
        }
    }

    /** Re-inspect the reviewed bytes to reject changes before the explicit trust step. */
    suspend fun verify(review: ProviderPackageReview): ProviderPackageReview {
        require(review.file.canonicalFile.parentFile == staging.canonicalFile) { "Package is outside its staging directory" }
        val current = inspectFile(review.file)
        require(current.sha256 == review.sha256) { "The reviewed provider package changed; import it again" }
        return current
    }

    companion object {
        const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256L * 1024
        const val MAX_SCRIPT_BYTES = 8L * 1024 * 1024
        internal fun safePath(name: String): Boolean = name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') &&
            !name.contains('\u0000') && !Regex("^[A-Za-z]:").containsMatchIn(name) &&
            name.removeSuffix("/").split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        internal fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        internal fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
