package com.cosmotune.manga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** A WebDAV folder on the listener's own server (Nextcloud, a NAS): where Manga keeps one sync file. */
@Serializable
data class MangaWebDav(
    val url: String,
    val user: String = "",
    val password: String = "",
    val lastSyncMs: Long = 0L,
    val lastError: String? = null,
)

/**
 * Komikku's WebDAV sync: the items account sync carries ([SyncMangaLibrary.snapshot]) in one JSON file on the listener's server,
 * merged both ways against what the last sync left there. A side that changed an item wins over one that did not, removals travel,
 * and when both changed it, this device wins.
 */
object MangaWebDavSync {
    const val FILE = "cosmotune-manga.json"
    private val json = Json { ignoreUnknownKeys = true }

    data class Merge(val result: Map<String, JsonObject>, val upserts: Map<String, JsonObject>, val deletes: Set<String>)

    fun merge(local: Map<String, JsonObject>, remote: Map<String, JsonObject>, base: Map<String, JsonObject>): Merge {
        val result = LinkedHashMap<String, JsonObject>()
        for (key in local.keys + remote.keys + base.keys) {
            val mine = local[key]
            val theirs = remote[key]
            val before = base[key]
            val chosen = when {
                mine == theirs -> mine
                mine == before -> theirs // only the server's copy changed, or it was removed there
                theirs == before -> mine
                else -> mine ?: theirs
            }
            if (chosen != null) result[key] = chosen
        }
        val upserts = result.filter { (key, value) -> local[key] != value }
        return Merge(result, upserts, local.keys - result.keys)
    }

    fun encode(items: Map<String, JsonObject>, nowMs: Long): String = buildJsonObject {
        put("version", 1); put("updatedAtMs", nowMs); put("items", JsonObject(items))
    }.toString()

    fun decode(text: String): Map<String, JsonObject> {
        if (text.isBlank()) return emptyMap()
        val root = json.parseToJsonElement(text).jsonObject
        require((root["version"]?.jsonPrimitive?.longOrNull ?: 1L) == 1L) { "The sync file on your server is from a newer Cosmotune" }
        return root["items"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject }
    }

    /** The sync file's address: [folder] itself when it names a .json file, otherwise [FILE] inside it. */
    fun fileUrl(folder: String): String {
        val trimmed = folder.trim()
        if (trimmed.endsWith(".json", ignoreCase = true)) return trimmed
        return trimmed.trimEnd('/') + "/" + FILE
    }

    /** One sync: reads the server's file, merges it into the library, writes the merged file back and keeps it as the next base. */
    suspend fun sync(store: MangaStore, client: OkHttpClient, account: MangaWebDav, nowMs: Long = System.currentTimeMillis()): Merge = withContext(Dispatchers.IO) {
        val address = fileUrl(account.url)
        require(address.startsWith("https://") || address.startsWith("http://")) { "Enter your WebDAV folder's address, starting with https://" }
        fun Request.Builder.signed() = apply { if (account.user.isNotBlank()) header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8)) }
        val refused = "Your server did not accept this user name and password"
        val remote = client.newCall(Request.Builder().url(address).signed().get().build()).execute().use { response ->
            when {
                response.code == 404 -> emptyMap()
                response.code == 401 || response.code == 403 -> throw MangaSourceFailure(refused)
                !response.isSuccessful -> throw MangaSourceFailure("Your server answered ${response.code}")
                else -> decode(response.body.string().also { require(it.length <= 32_000_000) { "The sync file is too large" } })
            }
        }
        val baseFile = File(store.root, "webdav-base.json")
        val base = runCatching { decode(baseFile.readText()) }.getOrDefault(emptyMap())
        val merge = merge(SyncMangaLibrary.snapshot(store.state.value), remote, base)
        if (merge.upserts.isNotEmpty() || merge.deletes.isNotEmpty()) store.update { SyncMangaLibrary.apply(it, merge.upserts, merge.deletes) }
        val body = encode(merge.result, nowMs)
        if (merge.result != remote) client.newCall(Request.Builder().url(address).signed().put(body.toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw MangaSourceFailure(refused)
            if (!response.isSuccessful) throw MangaSourceFailure("Your server did not take the sync file (${response.code})")
        }
        baseFile.writeText(body)
        merge
    }
}
