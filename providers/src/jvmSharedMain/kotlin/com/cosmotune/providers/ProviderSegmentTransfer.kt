package com.cosmotune.providers

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.serialization.json.*

/** Ordered segments stream into one bounded staged file; no second assembled copy is buffered. */
internal class ProviderSegmentTransfer(private val network: ProviderNetwork, private val maxBytes: Long) {
    private data class Segment(val url: String, val headers: JsonObject, val range: LongRange?)
    fun download(input: JsonArray, destination: File, headers: JsonObject, attempts: Int,
        progress: (Long, Long?, Int, Int) -> Unit): JsonObject {
        require(input.size in 1..4_096 && attempts in 1..3 && maxBytes in 1..512L * 1024 * 1024)
        fun stopped() { check(!network.isCancelled) { "Provider segments cancelled" } }
        val segments = input.map { item ->
            val objectValue = item as? JsonObject
            val raw = (if (item is JsonPrimitive) item else objectValue?.get("url"))?.jsonPrimitive?.contentOrNull.orEmpty()
            require(raw.length in 1..8_192)
            val address = network.address(raw).toString()
            val supplied = objectValue?.get("headers") as? JsonObject ?: JsonObject(emptyMap())
            val combined = JsonObject(headers + supplied)
            require(combined.size <= 100 && combined.values.all { it is JsonPrimitive && it != JsonNull && it.content.length <= 8_192 })
            val ranges = combined.filterKeys { it.equals("range", true) }.values
            require(ranges.size <= 1)
            val range = ranges.singleOrNull()?.jsonPrimitive?.content?.let { text ->
                val match = requireNotNull(Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(text))
                val start = requireNotNull(match.groupValues[1].toLongOrNull()); val end = requireNotNull(match.groupValues[2].toLongOrNull())
                require(start >= 0 && end >= start && end - start < maxBytes)
                start..end
            }
            Segment(address, combined, range)
        }
        stopped()
        val temporary = File.createTempFile("segments-", ".part", requireNotNull(destination.parentFile))
        val buffer = ByteArray(16 * 1024)
        var written = 0L
        try {
            temporary.ownerOnly()
            RandomAccessFile(temporary, "rw").use { output ->
                progress(0, null, 0, segments.size)
                segments.forEachIndexed { index, segment ->
                    val begin = written
                    var attempt = 0
                    while (true) {
                        stopped(); written = begin; output.setLength(begin); output.seek(begin)
                        try {
                            network.withResponse(segment.url, buildJsonObject { put("headers", segment.headers) }, media = true) { response ->
                                if (response.code in 500..599 || response.code == 429) throw IOException("Segment server unavailable")
                                require(response.code == if (segment.range == null) 200 else 206) { "Unexpected segment response" }
                                require(response.header("Content-Encoding").orEmpty().let { it.isBlank() || it.equals("identity", true) })
                                require(response.header("Content-Type").orEmpty().substringBefore(';').lowercase(java.util.Locale.ROOT) !in
                                    setOf("text/html", "application/json", "application/xhtml+xml")) { "Segment did not return media" }
                                val length = response.body.contentLength()
                                require(length != 0L && length <= maxBytes - begin)
                                val expected = if (segment.range != null) {
                                    val match = requireNotNull(Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty()))
                                    val start = match.groupValues[1].toLongOrNull(); val end = match.groupValues[2].toLongOrNull(); val total = match.groupValues[3].toLongOrNull()
                                    require(start == segment.range.first && end == segment.range.last && total != null && total > segment.range.last)
                                    (segment.range.last - segment.range.first + 1).also { require(length < 0 || length == it) }
                                } else { require(response.header("Content-Range") == null); length }
                                var received = 0L
                                response.body.byteStream().use { stream ->
                                    while (true) {
                                        stopped()
                                        val read = stream.read(buffer)
                                        if (read < 0) break
                                        stopped()
                                        require(read.toLong() <= maxBytes - written && (expected < 0 || read.toLong() <= expected - received)) { "Segment exceeds its byte budget" }
                                        output.write(buffer, 0, read); written += read; received += read
                                        progress(written, null, index, segments.size)
                                    }
                                }
                                require(received > 0 && (expected < 0 || received == expected)) { "Segment was truncated" }
                            }
                            break
                        } catch (error: IOException) {
                            stopped()
                            if (++attempt >= attempts) throw error
                        }
                    }
                    progress(written, null, index + 1, segments.size)
                }
                stopped(); output.fd.sync()
            }
            stopped()
            ProviderStore.move(temporary, destination)
            progress(written, written, segments.size, segments.size)
            return buildJsonObject {
                put("success", true); put("path", destination.absolutePath); put("file_path", destination.absolutePath)
                put("size", written); put("file_size", written); put("segments", segments.size)
            }
        } finally { buffer.fill(0); temporary.delete() }
    }
}
