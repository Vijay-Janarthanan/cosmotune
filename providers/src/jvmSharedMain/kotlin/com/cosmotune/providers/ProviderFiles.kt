package com.cosmotune.providers

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.*

/** File permission grants a private provider workspace, never the user's library or credential stores. */
internal class ProviderFiles(directory: File, private val network: ProviderNetwork,
    private val quota: Long = 1024L * 1024 * 1024, private val progress: (Long, Long?) -> Unit = { _, _ -> },
    private val segmentProgress: (Long, Long?, Int, Int) -> Unit = { bytes, total, _, _ -> progress(bytes, total) }) {
    private val root = directory.apply {
        require(!Files.isSymbolicLink(toPath())) { "Provider workspace cannot be a link" }
        check(isDirectory || mkdirs()); ownerOnly()
    }.canonicalFile

    fun path(raw: String): File {
        require(raw.isNotEmpty() && raw.length <= 4_096 && '\u0000' !in raw)
        val candidate = File(raw).let { if (it.isAbsolute) it else File(root, raw) }.absoluteFile.toPath().normalize()
        require(candidate.startsWith(root.toPath()) && candidate != root.toPath()) { "Provider file is outside its workspace" }
        val relative = root.toPath().relativize(candidate)
        require(relative.none { it.toString() in setOf(".", "..") || ':' in it.toString() })
        var current = root.toPath()
        for (part in relative) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current) && (!Files.exists(current) || Files.isDirectory(current) || Files.isRegularFile(current))) { "Provider file links and special files are not supported" }
        }
        return candidate.toFile()
    }

    fun call(action: String, args: JsonArray): JsonElement {
        fun text(index: Int) = (args.getOrNull(index) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (action == "downloadSegments") {
            val segments = args.getOrNull(0) as? JsonArray ?: error("Segments are required")
            val target = path(text(1)); val options = args.getOrNull(2) as? JsonObject ?: JsonObject(emptyMap())
            val attempts = options["maxAttempts"]?.let { requireNotNull((it as? JsonPrimitive)?.intOrNull).also { count -> require(count in 1..3) } } ?: 1
            val concurrency = options["concurrency"]?.let { requireNotNull((it as? JsonPrimitive)?.intOrNull) } ?: 1
            require(concurrency in 1..4)
            val available = remaining(); require(available > 0)
            prepare(target)
            return ProviderSegmentTransfer(network, minOf(available, MAX_FILE_BYTES)).download(segments, target,
                options["headers"] as? JsonObject ?: JsonObject(emptyMap()), attempts, segmentProgress)
        }
        if (action == "transformPatternedBlocks") {
            val source = path(text(0)); val target = path(text(1))
            val options = args.getOrNull(2) as? JsonObject ?: error("Transform options are required")
            val operation = (options["operation"] as? JsonPrimitive)?.contentOrNull ?: "decrypt"
            require(operation in setOf("encrypt", "decrypt"))
            require((options["padding"] as? JsonPrimitive)?.contentOrNull.orEmpty() in setOf("", "none"))
            val cipher = ProviderCrypto.cipher(options, operation == "decrypt")
            val available = remaining()
            prepare(target)
            return ProviderPatternedTransform.run(source, target, options, cipher, available,
                { check(!network.isCancelled) { "Provider transform cancelled" } }, progress)
        }
        if (action == "download") {
            val target = path(text(1))
            val options = args.getOrNull(2) as? JsonObject ?: JsonObject(emptyMap())
            require(options.keys.all { it in setOf("headers", "onProgress", "resume", "maxAttempts", "persistentCheckpoint", "directMedia") }) { "Unsupported provider transfer option" }
            val available = remaining() + File(target.parentFile, target.name + ".part").takeIf { it.isFile }?.length().orZero()
            require(available > 0) { "Provider workspace is full" }
            ProviderMediaTransfer(network, minOf(available, MAX_FILE_BYTES)).download(text(0), target,
                options["headers"] as? JsonObject ?: JsonObject(emptyMap()), progress)
            return success { put("path", target.absolutePath); put("file_path", target.absolutePath); put("size", target.length()); put("file_size", target.length()) }
        }
        val file = path(text(0))
        return when (action) {
            "exists" -> JsonPrimitive(file.isFile)
            "getSize" -> { require(file.isFile); success { put("size", file.length()) } }
            "delete" -> { require(!file.exists() || file.isFile); check(!file.exists() || file.delete()); success() }
            "copy", "move" -> {
                require(file.isFile && file.length() <= MAX_FILE_BYTES)
                val target = path(text(1))
                if (file == target) return success()
                require(file.length() <= remaining() + target.takeIf { it.isFile }?.length().orZero()) { "Provider workspace is full" }
                prepare(target)
                val temporary = File.createTempFile("copy-", ".part", target.parentFile)
                try {
                    temporary.ownerOnly()
                    file.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                    ProviderStore.move(temporary, target)
                    if (action == "move") check(file.delete())
                } finally { temporary.delete() }
                success()
            }
            "read", "readBytes" -> {
                require(file.isFile)
                val options = args.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
                val offset = options.number("offset", 0)
                val length = options.number("length", MAX_READ_BYTES.toLong())
                require(offset >= 0 && length in 0..MAX_READ_BYTES.toLong())
                if (action == "read") require(file.length() <= MAX_READ_BYTES)
                val data = RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    ByteArray(minOf(length, (input.length() - offset).coerceAtLeast(0)).toInt()).also { input.readFully(it) }
                }
                success {
                    put("data", if (action == "read") JsonPrimitive(ProviderPackages.utf8(data)) else encode(data, options))
                    put("offset", offset); put("size", file.length()); put("eof", offset + data.size >= file.length()); put("bytes_read", data.size)
                }
            }
            "write", "writeBytes" -> {
                val options = args.getOrNull(2) as? JsonObject ?: JsonObject(emptyMap())
                val value = if (action == "write") text(1).toByteArray(Charsets.UTF_8) else decode(args.getOrNull(1), options)
                require(value.size <= MAX_READ_BYTES)
                val append = action == "writeBytes" && options["append"]?.jsonPrimitive?.booleanOrNull == true
                val truncate = action == "write" || options["truncate"]?.jsonPrimitive?.booleanOrNull == true
                val offset = if (append) file.takeIf { it.isFile }?.length() ?: 0 else if (action == "write") 0 else options.number("offset", 0)
                require(offset >= 0 && offset <= MAX_FILE_BYTES - value.size)
                val oldSize = file.takeIf { it.isFile }?.length() ?: 0
                val newSize = if (truncate) offset + value.size else maxOf(oldSize, offset + value.size)
                require(newSize - oldSize <= remaining()) { "Provider workspace is full" }
                prepare(file)
                RandomAccessFile(file, "rw").use { output ->
                    file.ownerOnly()
                    if (truncate) output.setLength(0)
                    output.seek(offset); output.write(value); output.fd.sync()
                }
                success { put("bytes_written", value.size) }
            }
            else -> error("Unsupported provider file API")
        }
    }

    private fun prepare(file: File) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()); parent.ownerOnly(); path(file.absolutePath)
    }
    private fun remaining(): Long {
        var bytes = 0L
        var count = 0
        Files.walk(root.toPath()).use { paths -> paths.forEach { item ->
            require(++count <= 2_000 && !Files.isSymbolicLink(item)) { "Provider workspace entry limit reached" }
            if (Files.isRegularFile(item)) { bytes += Files.size(item); require(bytes <= quota) { "Provider workspace is full" } }
        } }
        return quota - bytes
    }
    private fun JsonObject.number(key: String, default: Long): Long = get(key)?.let { requireNotNull((it as? JsonPrimitive)?.longOrNull) } ?: default
    private fun Long?.orZero() = this ?: 0L
    private fun encode(bytes: ByteArray, options: JsonObject): JsonElement = when (options["encoding"]?.jsonPrimitive?.contentOrNull ?: "base64") {
        "base64" -> JsonPrimitive(Base64.getEncoder().encodeToString(bytes))
        "hex" -> JsonPrimitive(bytes.joinToString("") { "%02x".format(it) })
        "bytes", "array" -> buildJsonArray { bytes.forEach { add(it.toInt() and 255) } }
        else -> error("Unsupported provider file encoding")
    }
    private fun decode(value: JsonElement?, options: JsonObject): ByteArray {
        if (value is JsonObject && value["__bytes"] is JsonArray) return decode(value["__bytes"], options)
        if (value is JsonArray) {
            require(value.size <= MAX_READ_BYTES)
            return value.map { requireNotNull((it as? JsonPrimitive)?.intOrNull).also { byte -> require(byte in 0..255) }.toByte() }.toByteArray()
        }
        val text = (value as? JsonPrimitive)?.contentOrNull ?: error("Invalid provider bytes")
        require(text.length <= MAX_READ_BYTES * 2)
        return when (options["encoding"]?.jsonPrimitive?.contentOrNull ?: "base64") {
            "base64" -> Base64.getDecoder().decode(text)
            "hex" -> { require(text.length % 2 == 0); text.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
            else -> error("Unsupported provider file encoding")
        }
    }
    private fun success(block: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("success", true); block() }
    private companion object { const val MAX_READ_BYTES = 256 * 1024; const val MAX_FILE_BYTES = 512L * 1024 * 1024 }
}
