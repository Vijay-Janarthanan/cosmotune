package com.cosmotune.watch.debrid

import com.cosmotune.watch.WatchDebrid
import com.cosmotune.watch.model.Stream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Original clients against the three services' public APIs. Only explicit playback creates a transfer. */
class WatchDebridClient(private val client: OkHttpClient,
    private val bases: Map<WatchDebrid, String> = mapOf(WatchDebrid.REAL_DEBRID to "https://api.real-debrid.com/rest/1.0",
        WatchDebrid.ALL_DEBRID to "https://api.alldebrid.com", WatchDebrid.TORBOX to "https://api.torbox.app/v1/api"),
) {
    data class FileOffer(val id: String, val name: String, val link: String? = null)

    suspend fun resolve(service: WatchDebrid, token: String, stream: Stream): String = withTimeout(75_000) {
        require(stream.isTorrent && token.isNotBlank())
        val result = when (service) {
            WatchDebrid.REAL_DEBRID -> realDebrid(token, stream)
            WatchDebrid.ALL_DEBRID -> allDebrid(token, stream)
            WatchDebrid.TORBOX -> torbox(token, stream)
        }
        val url = result.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "The service did not return a secure video address" }
        result
    }

    private suspend fun realDebrid(token: String, stream: Stream): String {
        val service = WatchDebrid.REAL_DEBRID
        val added = request(service, token, "/torrents/addMagnet", form = mapOf("magnet" to stream.url)).jsonObject
        val id = added.text("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: error("The service did not accept this torrent")
        var chosen: FileOffer? = null
        repeat(25) {
            val info = request(service, token, "/torrents/info/$id").jsonObject
            val files = (info["files"] as? JsonArray).orEmpty().mapNotNull { item ->
                val value = item.jsonObject
                FileOffer(value.text("id") ?: return@mapNotNull null, value.text("path") ?: return@mapNotNull null)
            }
            if (files.isNotEmpty() && chosen == null) {
                chosen = choose(files, stream)
                request(service, token, "/torrents/selectFiles/$id", form = mapOf("files" to chosen!!.id))
            }
            if (info.text("status") == "downloaded") {
                val selected = (info["files"] as? JsonArray).orEmpty().filter { it.jsonObject["selected"]?.jsonPrimitive?.intOrNull == 1 }
                val index = selected.indexOfFirst { it.jsonObject.text("id") == chosen?.id }
                val link = (info["links"] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.contentOrNull
                if (link != null) return request(service, token, "/unrestrict/link", form = mapOf("link" to link)).jsonObject.text("download")
                    ?: error("The service did not return a stream")
            }
            check(info.text("status") !in setOf("magnet_error", "error", "virus", "dead")) { "The service could not prepare this torrent" }
            delay(2000)
        }
        error("The torrent is still preparing in your service account. Try a cached copy or check its dashboard.")
    }

    private suspend fun allDebrid(token: String, stream: Stream): String {
        val service = WatchDebrid.ALL_DEBRID
        val added = data(request(service, token, "/v4/magnet/upload", form = mapOf("magnets[]" to stream.url)))
        val id = (added["magnets"] as? JsonArray)?.firstOrNull()?.jsonObject?.text("id")
            ?.takeIf { it.matches(Regex("[0-9]+")) } ?: error("The service did not accept this torrent")
        repeat(25) {
            val result = data(request(service, token, "/v4.1/magnet/files", form = mapOf("id[]" to id)))
            val magnet = (result["magnets"] as? JsonArray)?.firstOrNull()?.jsonObject
            val files = flatten((magnet?.get("files") as? JsonArray).orEmpty())
            if (files.isNotEmpty()) {
                val chosen = choose(files, stream)
                return data(request(service, token, "/v4/link/unlock", query = mapOf("link" to requireNotNull(chosen.link)))).text("link")
                    ?: error("The service did not return a stream")
            }
            delay(2000)
        }
        error("The torrent is still preparing in your service account")
    }

    private suspend fun torbox(token: String, stream: Stream): String {
        val service = WatchDebrid.TORBOX
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("magnet", stream.url).addFormDataPart("seed", "1").build()
        val added = data(request(service, token, "/torrents/createtorrent", body = form))
        val id = added.text("torrent_id")?.takeIf { it.matches(Regex("[0-9]+")) } ?: error("The service did not accept this torrent")
        repeat(25) {
            val response = request(service, token, "/torrents/mylist", query = mapOf("id" to id, "bypass_cache" to "true")).jsonObject
            check(response["success"]?.jsonPrimitive?.booleanOrNull != false) { "The service could not read this torrent" }
            val raw = response["data"]
            val info = (raw as? JsonObject) ?: (raw as? JsonArray)?.firstOrNull()?.jsonObject
            if (info?.get("download_ready")?.jsonPrimitive?.booleanOrNull == true) {
                val files = (info["files"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val file = entry.jsonObject
                    FileOffer(file.text("id") ?: return@mapNotNull null, file.text("name") ?: file.text("short_name") ?: return@mapNotNull null)
                }
                val chosen = choose(files, stream)
                val download = request(service, token, "/torrents/requestdl", query = mapOf("token" to token, "torrent_id" to id, "file_id" to chosen.id, "zip_link" to "false")).jsonObject
                check(download["success"]?.jsonPrimitive?.booleanOrNull != false) { "The service did not return a stream" }
                return download["data"]?.jsonPrimitive?.contentOrNull ?: error("The service did not return a stream")
            }
            delay(2000)
        }
        error("The torrent is still preparing in your service account")
    }

    /** Never substitute another episode or a pack's largest video when the requested filename is known. */
    fun choose(files: List<FileOffer>, stream: Stream): FileOffer {
        fun basename(name: String) = name.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        val videos = files.filter { basename(it.name).substringAfterLast('.') in setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts") }
        val wanted = stream.filename?.let(::basename)
        if (wanted != null) return requireNotNull(videos.singleOrNull { basename(it.name) == wanted }) { "The requested video file was not found in this torrent" }
        return requireNotNull(videos.singleOrNull()) { "This torrent contains several videos. Choose an offer that names the exact file." }
    }
    private fun flatten(items: List<JsonElement>): List<FileOffer> {
        val queue = java.util.ArrayDeque<JsonElement>(items)
        val files = mutableListOf<FileOffer>()
        var seen = 0
        while (queue.isNotEmpty()) {
            require(++seen <= 10_000) { "Torrent has too many files" }
            val item = queue.removeFirst() as? JsonObject ?: continue
            (item["e"] as? JsonArray)?.let { queue.addAll(it) }
            val link = item.text("l") ?: continue
            files += FileOffer(files.size.toString(), item.text("n").orEmpty(), link)
        }
        return files
    }
    private fun data(value: JsonElement): JsonObject {
        val root = value.jsonObject
        check(root.text("status") != "error" && root["success"]?.jsonPrimitive?.booleanOrNull != false) { "The service rejected the request. Check your account and token." }
        return root["data"] as? JsonObject ?: error("The service returned an invalid response")
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private suspend fun request(service: WatchDebrid, token: String, path: String, form: Map<String, String>? = null,
        query: Map<String, String> = emptyMap(), body: RequestBody? = null): JsonElement = suspendCancellableCoroutine { continuation ->
        val address = (bases.getValue(service).trimEnd('/') + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(address).header("Authorization", "Bearer $token").header("User-Agent", "Cosmotune").header("Accept", "application/json")
        val payload = body ?: form?.let { FormBody.Builder().apply { it.forEach { (key, value) -> add(key, value) } }.build() }
        if (payload != null) builder.post(payload)
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Service connection failed")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        check(it.isSuccessful) { "Service request failed (${it.code})" }
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        val input = it.body.byteStream()
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 2 * 1024 * 1024) { "Service response is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8").takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement) ?: JsonObject(emptyMap())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(IOException("The service could not prepare this copy. Check your account or try another source.")) }
            }
        })
    }
}
