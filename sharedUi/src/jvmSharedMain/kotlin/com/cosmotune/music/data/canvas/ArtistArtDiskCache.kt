package com.cosmotune.music.data.canvas

import com.cosmotune.music.data.Http
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** App-owned, bounded display assets. Only Apple's HTTPS image CDN may supply local image files. */
internal class ArtistArtDiskCache(
    private val directory: File,
    private val fetch: (HttpUrl) -> ByteArray? = ::fetchArtistImage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Record(val art: AppleArtistArt?, val fetchedAt: Long) {
        fun fresh(at: Long) = at - fetchedAt in 0 until if (art == null) 60 * 60_000L else 6 * 60 * 60_000L
    }

    @Synchronized fun read(key: String): Record? = runCatching {
        val file = File(directory, hash(key) + ".json")
        if (!file.isFile || file.length() !in 1..16_384) return null
        val row = JSONObject(file.readText(Charsets.UTF_8))
        if (row.optInt("version") != 1) return null
        val at = row.getLong("at")
        if (now() - at !in 0..30 * 24 * 60 * 60_000L) return null
        val raw = row.optJSONObject("art") ?: return Record(null, at)
        val hero = raw.getString("hero")
        val logo = raw.optString("logo").takeIf(String::isNotBlank)
        if (!validRemote(hero) || logo?.let { !validRemote(it) } == true) return null
        fun local(url: String, field: String) = File(directory,
            (raw.optString(field).takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: hash(url)) + ".img")
            .takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES.toLong() }?.toURI()?.toString() ?: url
        val aspect = raw.optDouble("aspect", 1.0).toFloat().takeIf { it.isFinite() && it in .1f..20f } ?: 1f
        Record(AppleArtistArt(local(hero, "heroFile"), logo?.let { local(it, "logoFile") }, aspect,
            raw.optString("video").takeIf { now() - at < 60 * 60_000L && validRemote(it) },
            raw.getInt("background"), raw.getInt("accent"),
            raw.optInt("key").takeIf { raw.has("key") }), at)
    }.getOrNull()

    /** Files are committed before metadata; a failed refresh leaves the previous record intact. */
    @Synchronized fun save(key: String, art: AppleArtistArt?): Record? = runCatching {
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val row = JSONObject().put("version", 1).put("at", now())
        if (art != null) {
            if (!validRemote(art.heroUrl)) return null
            val raw = JSONObject().put("hero", art.heroUrl).put("aspect", art.logoAspect.toDouble())
                .put("background", art.background).put("accent", art.accent)
            art.logoUrl?.takeIf(::validRemote)?.let { raw.put("logo", it) }
            art.videoUrl?.takeIf(::validRemote)?.let { raw.put("video", it) }
            art.keyColor?.let { raw.put("key", it) }
            val previousFile = File(directory, hash(key) + ".json")
            val previous = runCatching { if (previousFile.length() in 1..16_384)
                JSONObject(previousFile.readText(Charsets.UTF_8)).optJSONObject("art") else null }.getOrNull()
            listOfNotNull(art.heroUrl, art.logoUrl).forEach { url ->
                val parsed = url.toHttpUrlOrNull()?.takeIf(::appleImage) ?: return@forEach
                val target = File(directory, hash(url) + ".img")
                if (!target.isFile) runCatching {
                    fetch(parsed)?.takeIf { it.size in 8..MAX_IMAGE_BYTES && imageBytes(it) }?.let { atomicWrite(target, it) }
                }
            }
            fun keepFile(url: String, field: String, oldUrlField: String) {
                val current = hash(url)
                val older = previous?.optString(field)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    ?: previous?.optString(oldUrlField)?.takeIf(::validRemote)?.let(::hash)
                val stored = listOfNotNull(current, older).firstOrNull {
                    File(directory, "$it.img").let { file -> file.isFile && file.length() in 1..MAX_IMAGE_BYTES.toLong() }
                }
                stored?.let { raw.put(field, it) }
            }
            keepFile(art.heroUrl, "heroFile", "hero")
            art.logoUrl?.let { keepFile(it, "logoFile", "logo") }
            row.put("art", raw)
        }
        atomicWrite(File(directory, hash(key) + ".json"), row.toString().toByteArray(Charsets.UTF_8))
        prune()
        read(key)
    }.getOrNull()

    private fun prune() {
        val files = directory.listFiles().orEmpty().filter { it.extension in setOf("json", "img") }
        var bytes = files.sumOf(File::length)
        var records = files.count { it.extension == "json" }
        for (file in files.sortedBy(File::lastModified)) {
            if (bytes <= MAX_CACHE_BYTES && records <= 128) break
            val length = file.length()
            if (file.delete()) { bytes -= length; if (file.extension == "json") records-- }
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private fun hash(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun validRemote(value: String) = value.toHttpUrlOrNull()?.let {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty()
        } == true
        private fun appleImage(url: HttpUrl) = url.isHttps && (url.host == "mzstatic.com" || url.host.endsWith(".mzstatic.com")) &&
            url.username.isEmpty() && url.password.isEmpty()
        private fun imageBytes(bytes: ByteArray) = bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) ||
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() ||
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        private fun atomicWrite(target: File, bytes: ByteArray) {
            val temporary = File(target.parentFile, target.name + "." + UUID.randomUUID() + ".tmp")
            try {
                temporary.outputStream().use { it.write(bytes) }
                try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { temporary.delete() }
        }
        private val imageClient by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build() }
        private fun fetchArtistImage(address: HttpUrl): ByteArray? {
            var url = address
            repeat(4) {
                if (!appleImage(url)) return null
                imageClient.newCall(Request.Builder().url(url).build()).execute().use { reply ->
                    if (reply.code in setOf(301, 302, 303, 307, 308)) {
                        url = url.resolve(reply.header("Location").orEmpty()) ?: return null
                    } else {
                        if (!reply.isSuccessful || reply.body.contentLength() > MAX_IMAGE_BYTES) return null
                        val output = java.io.ByteArrayOutputStream()
                        reply.body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) { val count = input.read(buffer); if (count < 0) break
                                if (output.size() > MAX_IMAGE_BYTES - count) return null
                                output.write(buffer, 0, count)
                            }
                        }
                        return output.toByteArray()
                    }
                }
            }
            return null
        }
    }
}
