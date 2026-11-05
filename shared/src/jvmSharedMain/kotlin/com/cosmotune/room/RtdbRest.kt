package com.cosmotune.room

import com.cosmotune.cloud.CloudException
import com.opencanvas.core.stream.fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** The part of the Realtime Database the rooms use, so the room logic can be tested without a network. Paths are like `rooms/ABC234/state`. */
interface RtdbApi {
    /** The value at a path, or [JsonNull] when there is none. */
    suspend fun get(path: String): JsonElement

    /** Writes a value and returns it as the server stored it (with server values such as the timestamp filled in). */
    suspend fun put(path: String, value: JsonElement): JsonElement

    /** Several writes in one: each key of [values] is a path below [path], and all of them succeed or none does. A null value deletes. */
    suspend fun update(path: String, values: JsonObject): JsonElement

    suspend fun delete(path: String)

    /** Adds a child with a new time-ordered key and returns the key. */
    suspend fun push(path: String, value: JsonElement): String

    /** The whole value at [path] as it stands, then again after every change, until the collector stops or the stream fails. */
    fun listen(path: String): Flow<JsonElement>
}

/** The server's stamp for "now": written as a value, it comes back as milliseconds on the server's clock. */
val ServerTimestamp: JsonElement = JsonObject(mapOf(".sv" to JsonPrimitive("timestamp")))

/** The Realtime Database over REST, with the ID token as the credential. Each [listen] is one long-lived connection. */
class RtdbRest(
    private val databaseUrl: String,
    private val client: OkHttpClient,
    private val idToken: suspend () -> String,
) : RtdbApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    // the server sends a keep-alive about every 30 seconds, so a silent minute and a half means the connection is gone
    private val streamClient = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()

    private suspend fun url(path: String, query: Map<String, String> = emptyMap()) =
        "${databaseUrl.trimEnd('/')}/${path.trim('/')}.json".toHttpUrl().newBuilder().apply {
            addQueryParameter("auth", idToken())
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    override suspend fun get(path: String): JsonElement = call(Request.Builder().url(url(path)).get())

    override suspend fun put(path: String, value: JsonElement): JsonElement =
        call(Request.Builder().url(url(path)).put(value.toString().toRequestBody(jsonType)))

    override suspend fun update(path: String, values: JsonObject): JsonElement =
        call(Request.Builder().url(url(path)).patch(values.toString().toRequestBody(jsonType)))

    override suspend fun delete(path: String) {
        call(Request.Builder().url(url(path)).delete())
    }

    override suspend fun push(path: String, value: JsonElement): String {
        val answer = call(Request.Builder().url(url(path)).post(value.toString().toRequestBody(jsonType)))
        return (answer as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: throw CloudException("BAD_RESPONSE", "no key", 200)
    }

    private suspend fun call(builder: Request.Builder): JsonElement {
        val answer = client.newCall(builder.build()).fetch()
        if (!answer.isSuccessful) throw failure(answer.text(), answer.code)
        val text = answer.text()
        return if (text.isBlank()) JsonNull else RtdbTree.normalize(json.parseToJsonElement(text))
    }

    override fun listen(path: String): Flow<JsonElement> = flow {
        val request = Request.Builder().url(url(path)).header("Accept", "text/event-stream").get().build()
        val call = streamClient.newCall(request)
        // a blocking read cannot be interrupted by cancelling the collector, so the call itself is cancelled with it
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val response = withContext(Dispatchers.IO) { call.execute() }
        response.use { answer ->
            if (!answer.isSuccessful) throw failure(answer.body?.string().orEmpty(), answer.code)
            val source = answer.body!!.source()
            val parser = SseParser()
            var tree: JsonElement = JsonNull
            while (true) {
                val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                val event = parser.feed(line) ?: continue
                when (event.type) {
                    "put", "patch" -> {
                        val body = json.parseToJsonElement(event.data).jsonObject
                        val at = RtdbTree.segments(body["path"]?.jsonPrimitive?.contentOrNull ?: "/")
                        val data = RtdbTree.normalize(body["data"] ?: JsonNull)
                        tree = if (event.type == "put") RtdbTree.set(tree, at, data) else RtdbTree.merge(tree, at, data as? JsonObject ?: JsonObject(emptyMap()))
                        emit(tree)
                    }
                    "cancel" -> throw CloudException("PERMISSION_DENIED", "The stream was refused", 403)
                    "auth_revoked" -> throw CloudException("AUTH_REVOKED", "The token lapsed", 401)
                }
            }
        }
    }

    private fun failure(body: String, status: Int): CloudException {
        val message = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: "HTTP $status"
        val code = if (message.contains("Permission denied", ignoreCase = true)) "PERMISSION_DENIED" else "RTDB_$status"
        return CloudException(code, message, status)
    }
}

/** One server-sent event: its type and its data. */
class SseEvent(val type: String, val data: String)

/** Reads the text/event-stream format a line at a time; an event is complete at a blank line. */
class SseParser {
    private var type: String? = null
    private val data = StringBuilder()

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            val finished = type?.let { SseEvent(it, data.toString()) }
            type = null
            data.setLength(0)
            return finished
        }
        when {
            line.startsWith("event:") -> type = line.removePrefix("event:").trim()
            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
        }
        return null
    }
}

/** The database tree as a stream builds it: put replaces a path, patch merges children into it. Arrays are kept as objects keyed by index. */
object RtdbTree {
    fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    /** The database stores lists as numbered children and sometimes returns them as arrays; one shape makes later patches safe. */
    fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonObject(value.withIndex().filter { it.value !is JsonNull }.associate { it.index.toString() to normalize(it.value) })
        is JsonObject -> JsonObject(value.mapValues { normalize(it.value) })
        else -> value
    }

    fun set(root: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val current = (root as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val child = set(current[path[0]] ?: JsonNull, path.drop(1), value)
        if (child is JsonNull || (child is JsonObject && child.isEmpty())) current.remove(path[0]) else current[path[0]] = child
        return if (current.isEmpty()) JsonNull else JsonObject(current)
    }

    fun merge(root: JsonElement, path: List<String>, patch: JsonObject): JsonElement {
        var tree = root
        for ((key, value) in patch) tree = set(tree, path + segments(key), value)
        return tree
    }
}
