package com.cosmotune.providers

import java.io.File
import javax.crypto.Cipher
import kotlinx.serialization.json.*

/** Bounded file-to-file transforms stage their output and preserve both originals on failure. */
internal object ProviderPatternedTransform {
    fun run(source: File, target: File, options: JsonObject, cipher: Cipher, available: Long,
        check: () -> Unit, progress: (Long, Long?) -> Unit): JsonObject {
        fun number(key: String, default: Long) = options[key]?.let {
            requireNotNull((it as? JsonPrimitive)?.longOrNull) { "Invalid transform number" }
        } ?: default
        val segmentSize = number("segmentSize", 0).also { require(it in 1..262_144) }.toInt()
        val every = number("transformEvery", 1).also { require(it > 0) }
        val offset = number("transformOffset", 0).also { require(it in 0 until every) }
        val partial = options["transformPartial"]?.let { requireNotNull((it as? JsonPrimitive)?.booleanOrNull) } ?: false
        val mode = (options["mode"] as? JsonPrimitive)?.contentOrNull?.lowercase(java.util.Locale.ROOT) ?: "cbc"
        val padding = (options["padding"] as? JsonPrimitive)?.contentOrNull ?: "none"
        require(mode in setOf("cbc", "ctr") && padding == "none")
        require(mode != "cbc" || segmentSize % cipher.blockSize == 0)
        require(source.isFile && !java.nio.file.Files.isSymbolicLink(source.toPath()))
        val total = source.length(); val modified = source.lastModified()
        require(total <= 512L * 1024 * 1024 && total <= available) { "Provider workspace is full" }
        check()
        val temporary = File.createTempFile("transform-", ".part", requireNotNull(target.parentFile))
        var processed = 0L; var segments = 0L; var transformed = 0L
        val buffer = ByteArray(segmentSize)
        try {
            temporary.ownerOnly()
            progress(0, total)
            source.inputStream().use { input -> temporary.outputStream().use { output ->
                while (true) {
                    check()
                    var count = 0
                    while (count < buffer.size) {
                        check()
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count == 0) break
                    require(processed + count <= total) { "Provider input changed during transform" }
                    if (segments % every == offset && (count == segmentSize || partial)) {
                        require(mode != "cbc" || count % cipher.blockSize == 0) { "Selected transform segment is not block aligned" }
                        // doFinal resets the cipher to its initial key/IV for the next selected segment.
                        val result = cipher.doFinal(buffer, 0, count)
                        try { require(result.size == count); check(); output.write(result) } finally { result.fill(0) }
                        transformed++
                    } else output.write(buffer, 0, count)
                    processed += count; segments++
                    progress(processed, total)
                }
                output.fd.sync()
            } }
            check()
            require(processed == total && source.length() == total && source.lastModified() == modified) { "Provider input changed during transform" }
            ProviderStore.move(temporary, target)
            return buildJsonObject {
                put("success", true); put("path", target.absolutePath); put("file_path", target.absolutePath)
                put("bytes_processed", processed); put("segments_processed", segments); put("segments_transformed", transformed)
            }
        } finally { buffer.fill(0); temporary.delete() }
    }
}
