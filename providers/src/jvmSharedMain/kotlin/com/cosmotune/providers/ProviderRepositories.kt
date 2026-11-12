package com.cosmotune.providers

import com.cosmotune.music.data.Http
import com.cosmotune.music.data.sources.readAddonJsonBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable data class ProviderRepositoryEntry(val id: String = "", val name: String = "", val version: String,
    @SerialName("display_name") val displayName: String = "", val description: String = "",
    @SerialName("download_url") val downloadUrl: String, val sha256: String,
    @SerialName("min_app_version") val minAppVersion: String = "0.0.0",
    @SerialName("icon_url") val iconUrl: String? = null, val runtime: String = "") {
    val identity: String get() = id.ifBlank { name }
    fun validate(): ProviderRepositoryEntry = apply {
        require(supportsProviderRuntime(runtime)) { "This provider package requires another runtime" }
        require(identity.matches(Regex("[a-z0-9][a-z0-9_-]{0,99}"))) { "Invalid repository provider identity" }
        require(sha256.matches(Regex("[A-Fa-f0-9]{64}"))) { "Repository package hash is missing" }
        require(version.isNotBlank() && version.length <= 64)
        require(downloadUrl.toHttpUrl().let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }) { "Provider package address must use HTTPS" }
    }
}

private fun supportsProviderRuntime(runtime: String) = runtime.trim().let { it.isEmpty() || it.equals("javascript", true) }

/** How a repository listing relates to what is installed, so Sources only offers a real update. */
enum class ProviderListingStatus { NEW, UPDATE, CURRENT, OLDER }

fun ProviderRepositoryEntry.statusAgainst(installed: ProviderInstall?): ProviderListingStatus {
    if (installed == null) return ProviderListingStatus.NEW
    val order = ProviderManager.compareVersions(version, installed.manifest.version)
    return when {
        order > 0 -> ProviderListingStatus.UPDATE
        order < 0 -> ProviderListingStatus.OLDER
        // The same version republished with different bytes is offered for review, never installed silently.
        sha256.equals(installed.sha256, ignoreCase = true) -> ProviderListingStatus.CURRENT
        else -> ProviderListingStatus.UPDATE
    }
}

@Serializable data class ProviderRepositoryIndex(val version: Int = 1, val name: String = "", val extensions: List<ProviderRepositoryEntry>)

/** Repositories publish package hashes. A refreshed listing cannot execute or silently replace installed code. */
class ProviderRepositories(private val packages: ProviderPackages,
    private val client: OkHttpClient = Http.client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(address: String): ProviderRepositoryIndex = withContext(Dispatchers.IO) {
        response(address) { reply ->
            val document = json.parseToJsonElement(reply.body.readAddonJsonBody()) as? JsonObject
                ?: error("Invalid provider repository")
            val entries = document["extensions"] as? JsonArray ?: error("Missing provider repository entries")
            require(entries.size <= 2_000) { "Oversized provider repository" }
            val compatible = entries.filter { entry ->
                val runtime = (entry as? JsonObject)?.get("runtime") ?: return@filter true
                runtime is JsonPrimitive && runtime.isString && supportsProviderRuntime(runtime.contentOrNull.orEmpty())
            }
            val index = json.decodeFromJsonElement(ProviderRepositoryIndex.serializer(), JsonObject(document + ("extensions" to JsonArray(compatible))))
            require(index.version == 1) { "Unsupported provider repository" }
            index.extensions.forEach { it.validate() }
            require(index.extensions.map { it.identity }.distinct().size == index.extensions.size) { "Duplicate repository provider identity" }
            index
        }
    }

    suspend fun download(entry: ProviderRepositoryEntry): ProviderPackageReview = withContext(Dispatchers.IO) {
        entry.validate()
        val review = response(entry.downloadUrl) { reply ->
            require(reply.body.contentLength() <= ProviderPackages.MAX_PACKAGE_BYTES) { "Provider package exceeds 32 MB" }
            packages.inspect { reply.body.byteStream() }
        }
        try {
            require(review.sha256.equals(entry.sha256, true)) { "Provider package does not match its repository hash" }
            require(review.manifest.name == entry.identity && review.manifest.version == entry.version) { "Provider identity/version differs from its repository listing" }
            review
        } catch (error: Throwable) { review.file.delete(); throw error }
    }

    private suspend fun <T : Any> response(address: String, read: suspend (okhttp3.Response) -> T): T {
        var url = address.toHttpUrl()
        val visited = HashSet<String>()
        repeat(5) {
            currentCoroutineContext().ensureActive()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "Provider repository requests require HTTPS" }
            require(visited.add(url.toString())) { "Provider repository redirect loop" }
            val result = client.newCall(Request.Builder().url(url).header("User-Agent", "Cosmotune/1.0.23").build()).withReply { reply ->
                currentCoroutineContext().ensureActive()
                if (reply.code in setOf(301, 302, 303, 307, 308)) {
                    null to (url.resolve(reply.header("Location").orEmpty()) ?: error("Invalid provider repository redirect"))
                } else {
                    check(reply.isSuccessful) { "Provider repository returned HTTP ${reply.code}" }
                    read(reply) to null
                }
            }
            result.first?.let { return it }
            url = requireNotNull(result.second)
        }
        error("Provider repository redirected too many times")
    }
}
