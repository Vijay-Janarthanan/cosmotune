package com.cosmotune.providers

import java.io.File
import kotlinx.serialization.json.*

/** Native media operations supplied by the application, never arbitrary extension commands. */
interface ProviderMediaTools {
    fun probe(input: File, cancelled: () -> Boolean): ProviderMediaInfo
    fun remux(input: File, output: File, key: String?, maximumBytes: Long, cancelled: () -> Boolean)

    companion object {
        @Volatile var installed: ProviderMediaTools? = null
    }
}

data class ProviderMediaInfo(val codec: String, val sampleRate: Int, val channels: Int,
    val bitDepth: Int, val durationMs: Long, val bitrate: Long, val format: String = "") {
    fun json(): JsonObject = buildJsonObject {
        put("success", true); put("codec", codec); put("audio_codec", codec); put("format", format); put("sampleRate", sampleRate); put("sample_rate", sampleRate)
        put("channels", channels); put("bitDepth", bitDepth); put("bit_depth", bitDepth)
        put("duration", durationMs / 1000.0); put("duration_ms", durationMs); put("bitrate", bitrate)
        put("totalSamples", durationMs * sampleRate / 1000)
    }
}

/** Checks paths and quotas before native code sees a file; output is published only on success. */
internal class ProviderMediaProcessing(private val files: ProviderFiles, private val tools: ProviderMediaTools?,
    private val cancelled: () -> Boolean) {
    val available: Boolean get() = tools != null
    private fun ready() { check(!cancelled() && !Thread.currentThread().isInterrupted) { "Provider media processing cancelled" } }
    private fun input(path: String) = files.path(path).also { require(it.isFile && it.length() in 1..MAX_BYTES); ready() }
    fun probe(path: String): ProviderMediaInfo = requireNotNull(tools) { "Native audio processing is unavailable" }.probe(input(path), cancelled)

    fun call(action: String, args: JsonArray): JsonElement {
        fun text(i: Int) = (args.getOrNull(i) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (action) {
            "getInfo" -> probe(text(0)).json()
            "convert" -> {
                val options = args.getOrNull(2) as? JsonObject ?: JsonObject(emptyMap())
                // Stream copy preserves the delivered audio. Encoding requires a separate codec implementation.
                require(options.keys.all { it == "codec" } && (options["codec"]?.jsonPrimitive?.content ?: "copy") == "copy") {
                    "Unsupported provider conversion options"
                }
                val file = remux(text(0), text(1), null)
                buildJsonObject { put("success", true); put("path", file.absolutePath) }
            }
            else -> error("Unsupported provider media operation")
        }
    }

    fun prepare(raw: JsonObject, path: String, outputBase: String): File {
        val descriptor = raw["decryption"]?.takeUnless { it == JsonNull }
        val legacy = (raw["decryption_key"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        if (descriptor == null && legacy == null) {
            val source = input(path)
            val conversion = (raw["requires_container_conversion"] ?: raw["requiresContainerConversion"]) == JsonPrimitive(true)
            if (!conversion) return source
            if (probe(path).codec != "flac") throw ProviderResolutionException("native_processing_required", null)
            return remux(path, "$outputBase-prepared.flac", null)
        }
        if (tools == null) throw ProviderResolutionException("native_processing_required", null)
        val spec = descriptor as? JsonObject
        val strategy = spec?.get("strategy")?.jsonPrimitive?.content ?: if (legacy != null) "ffmpeg.mov_key" else ""
        if (strategy !in setOf("ffmpeg.mov_key", "ffmpeg_mov_key", "mov_decryption_key", "mp4_decryption_key", "ffmpeg.mp4_decryption_key"))
            throw ProviderResolutionException("native_processing_required", null)
        val key = (spec?.get("key") as? JsonPrimitive)?.contentOrNull ?: legacy.orEmpty()
        require(key.matches(Regex("[a-fA-F0-9]{32}"))) { "Invalid provider audio key" }
        require(spec?.get("iv") == null || spec["iv"] == JsonNull) { "Unsupported provider audio IV" }
        val extension = ((raw["output_extension"] ?: spec?.get("output_extension")) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: ".flac"
        require(extension in setOf(".flac", ".m4a", ".mp4", ".ogg", ".opus")) { "Unsupported provider output container" }
        return remux(path, "$outputBase-prepared$extension", key)
    }

    private fun remux(source: String, destination: String, key: String?): File {
        val backend = tools ?: throw ProviderResolutionException("native_processing_required", null)
        val input = input(source); val output = files.path(destination)
        require(input != output && output.extension.lowercase() in setOf("flac", "m4a", "mp4", "ogg", "opus", "mp3", "aac", "wav"))
        val maximum = minOf(MAX_BYTES, files.remainingBytes())
        require(maximum > 0)
        val parent = requireNotNull(output.parentFile)
        check(parent.isDirectory || parent.mkdirs()); parent.ownerOnly()
        val staging = File.createTempFile("media-", ".${output.extension}", parent).also { it.ownerOnly() }
        try {
            backend.remux(input, staging, key, maximum, cancelled)
            ready(); require(staging.length() in 1..maximum)
            files.path(destination); ProviderStore.move(staging, output)
            return output
        } finally { staging.delete() }
    }
    companion object { private const val MAX_BYTES = 512L * 1024 * 1024 }
}
