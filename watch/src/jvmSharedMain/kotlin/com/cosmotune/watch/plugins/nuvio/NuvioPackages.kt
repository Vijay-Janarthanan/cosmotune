package com.cosmotune.watch.plugins.nuvio

import com.cosmotune.providers.*
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object NuvioPackages {
    suspend fun repository(url: String): List<com.cosmotune.watch.WatchScriptEntry> {
        val data = Json.parseToJsonElement(fetch(url)).jsonObject
        val origin = URI(url)
        return (data["scrapers"] as? JsonArray).orEmpty().take(300).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull
            if ((item["enabled"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
            val id = text("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) } ?: return@mapNotNull null
            val address = origin.resolve(text("filename") ?: return@mapNotNull null)
            if (address.scheme != "https" || address.host != origin.host || address.userInfo != null || address.query != null || address.fragment != null || !address.path.endsWith(".js")) return@mapNotNull null
            com.cosmotune.watch.WatchScriptEntry(id, text("name")?.take(160) ?: id, address.toString(), text("description")?.take(500).orEmpty())
        }.distinctBy { it.url }
    }
    fun sha256(code: String) = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Use a public HTTPS script address" }
        val network = ProviderNetwork(ProviderPermissions(network = listOf(uri.host)))
        try {
            val result = network.request(url, JsonObject(emptyMap()))
            require(result["ok"]?.jsonPrimitive?.booleanOrNull == true) { "The Watch source could not be downloaded" }
            result["body"]?.jsonPrimitive?.content.orEmpty().also {
                require(it.toByteArray().size in 1..512_000) { "Watch source exceeds the size limit" }
            }
        } finally { network.cancel() }
    }
    suspend fun load(file: File, url: String, expected: String): String = withContext(Dispatchers.IO) {
        require(expected.matches(Regex("[a-f0-9]{64}"))) { "Reinstall this Watch script to verify its source" }
        val cached = file.takeIf { it.isFile && it.length() <= 512_000 }?.readText()
        if (cached != null && sha256(cached) == expected) return@withContext cached
        val code = fetch(url)
        require(sha256(code) == expected) { "This Watch script changed upstream. Review and reinstall the update." }
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        val part = File.createTempFile("watch-js-", ".part", file.parentFile)
        try {
            part.writeText(code)
            java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { part.delete() }
        code
    }
}
