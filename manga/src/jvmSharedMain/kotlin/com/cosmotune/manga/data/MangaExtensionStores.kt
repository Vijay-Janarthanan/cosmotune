package com.cosmotune.manga.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class MangaExtensionListing(val name: String, val packageName: String, val apkUrl: String, val versionCode: Long,
    val version: String, val api: String, val languages: Set<String>, val contentWarning: Int)
data class MangaExtensionStoreResult(val store: MangaExtensionStore, val extensions: List<MangaExtensionListing>)

/** Modern tachiyomix protobuf/JSON stores, including legacy repo.json index_v2 migrations. */
@OptIn(ExperimentalSerializationApi::class)
class MangaExtensionStores(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
    .followSslRedirects(false).build()) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun fetch(address: String, expectedKey: String? = null): MangaExtensionStoreResult = withContext(Dispatchers.IO) {
        val visited = HashSet<String>()
        suspend fun load(url: HttpUrl, depth: Int): MangaExtensionStoreResult {
            require(depth <= 4 && visited.add(url.toString())) { "Extension store redirects form a loop" }
            val bytes = fetchBytes(url)
            val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (text.startsWith("[")) {
                require(url.pathSegments.last() == "index.min.json") { "Use the repository's repo.json or index.pb address" }
                return load(requireNotNull(url.resolve("repo.json")), depth + 1)
            }
            if (text.startsWith("{")) {
                val root = json.parseToJsonElement(text).jsonObject
                root["index_v2"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { next ->
                    return load(requireNotNull(url.resolve(next)), depth + 1)
                }
                root["meta"]?.jsonObject?.let { meta ->
                    val key = key(meta.getValue("signingKeyFingerprint").jsonPrimitive.content, expectedKey)
                    val index = requireNotNull(url.resolve("index.min.json"))
                    val entries = json.parseToJsonElement(fetchBytes(index).toString(Charsets.UTF_8)).jsonArray
                    val listings = entries.map { value ->
                        val item = value.jsonObject
                        val apk = item.getValue("apk").jsonPrimitive.content
                        require(apk.matches(Regex("[A-Za-z0-9_.-]+\\.apk"))) { "Invalid extension APK filename" }
                        MangaExtensionListing(item.getValue("name").jsonPrimitive.content.substringAfter("Tachiyomi: "),
                            item.getValue("pkg").jsonPrimitive.content, requireNotNull(url.resolve("apk/$apk")).toString(),
                            item.getValue("code").jsonPrimitive.long, item.getValue("version").jsonPrimitive.content,
                            item.getValue("version").jsonPrimitive.content.substringBeforeLast('.'),
                            setOf(item["lang"]?.jsonPrimitive?.content.orEmpty()), if (item["nsfw"]?.jsonPrimitive?.intOrNull == 1) 3 else 1)
                    }
                    return MangaExtensionStoreResult(MangaExtensionStore(url.toString(), meta.getValue("name").jsonPrimitive.content, key,
                        meta["website"]?.jsonPrimitive?.content.orEmpty()), validated(listings))
                }
            }
            val wire = if (text.startsWith("{")) json.decodeFromString<WireStore>(text) else ProtoBuf.decodeFromByteArray(WireStore.serializer(), bytes)
            val signingKey = key(wire.signingKey, expectedKey)
            val list = wire.extensionList ?: wire.extensionListUrl?.let { next ->
                val payload = fetchBytes(requireNotNull(url.resolve(next)))
                if (payload.firstOrNull()?.toInt() == '{'.code) json.decodeFromString<WireList>(payload.toString(Charsets.UTF_8))
                else ProtoBuf.decodeFromByteArray(WireList.serializer(), payload)
            } ?: error("The store contains no extension list")
            val listings = list.extensions.map { entry -> MangaExtensionListing(entry.name, entry.packageName,
                entry.resources.apkUrl, entry.versionCode, entry.versionName, entry.extensionLib,
                entry.sources.map { it.language }.toSet(), entry.contentWarning) }
            return MangaExtensionStoreResult(MangaExtensionStore(url.toString(), wire.name, signingKey, wire.contact.website), validated(listings))
        }
        load(https(address), 0)
    }

    private fun key(value: String, expected: String?): String {
        val key = value.lowercase()
        require(key.matches(Regex("[0-9a-f]{64}"))) { "Store signer fingerprint is missing or invalid" }
        require(expected == null || expected.lowercase() == key) { "The store signing key changed. Review the publisher before adding it again." }
        return key
    }

    private fun validated(list: List<MangaExtensionListing>): List<MangaExtensionListing> {
        require(list.size <= 10_000) { "Extension store contains too many entries" }
        list.forEach { entry ->
            require(entry.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid extension package identity" }
            https(entry.apkUrl)
        }
        return list.groupBy { it.packageName }.values.map { versions -> versions.maxBy { it.versionCode } }.sortedBy { it.name.lowercase() }
    }

    private suspend fun fetchBytes(url: HttpUrl): ByteArray {
        https(url.toString())
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            currentCoroutineContext().ensureActive()
            check(response.isSuccessful) { "Extension store returned HTTP ${response.code}" }
            require(response.body.contentLength() <= MAX_BYTES) { "Extension store exceeds the 8 MB limit" }
            val data = bounded(response.body.byteStream())
            if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) bounded(GZIPInputStream(ByteArrayInputStream(data))) else data
        }
    }
    private suspend fun bounded(input: InputStream): ByteArray = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "Extension store exceeds the 8 MB limit" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    companion object {
        private const val MAX_BYTES = 8L * 1024 * 1024
        private fun https(address: String): HttpUrl = address.toHttpUrl().also {
            require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) { "Use an HTTPS extension-store address without embedded credentials" }
        }
    }
}

// Wire numbers follow Mihon d001e6f210e6a077a30dd9a1392573a3225bba2f's
// NetworkExtensionStore (Apache-2.0); see third-party/LICENSE.mihon.
@OptIn(ExperimentalSerializationApi::class)
@Serializable internal data class WireStore(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: WireContact = WireContact(),
    @ProtoNumber(101) val extensionList: WireList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
)
@OptIn(ExperimentalSerializationApi::class)
@Serializable internal data class WireContact(@ProtoNumber(1) val website: String = "", @ProtoNumber(2) val discord: String? = null)
@OptIn(ExperimentalSerializationApi::class)
@Serializable internal data class WireList(@ProtoNumber(1) val extensions: List<WireExtension> = emptyList())
@OptIn(ExperimentalSerializationApi::class)
@Serializable internal data class WireExtension(
    @ProtoNumber(1) val name: String = "", @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: WireResources = WireResources(), @ProtoNumber(4) val extensionLib: String = "",
    @ProtoNumber(5) val versionCode: Long = 0, @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(7) val contentWarning: Int = 0, @ProtoNumber(8) val sources: List<WireSource> = emptyList(),
)
@OptIn(ExperimentalSerializationApi::class)
@Serializable internal data class WireResources(@ProtoNumber(1) val apkUrl: String = "", @ProtoNumber(2) val iconUrl: String = "")
@OptIn(ExperimentalSerializationApi::class)
@Serializable internal data class WireSource(@ProtoNumber(1) val id: Long = 0, @ProtoNumber(2) val name: String = "", @ProtoNumber(3) val language: String = "")
