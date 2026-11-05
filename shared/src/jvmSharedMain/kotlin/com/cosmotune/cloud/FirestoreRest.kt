package com.cosmotune.cloud

import com.opencanvas.core.stream.fetch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/** One stored document: plain JSON [fields], and the two times the sync needs. [syncedAtMs] is stamped by the server, not by a device clock. */
data class CloudDoc(val path: String, val fields: JsonObject, val updateTime: String, val syncedAtMs: Long)

/**
 * One write in a commit. [fields] null deletes the document. [expectUpdateTime] makes the write fail with [CloudConflict] if the
 * document changed since it was read; [mustNotExist] does the same if it appeared. A written document gets `syncedAt` set by the server.
 */
data class DocWrite(val path: String, val fields: JsonObject?, val expectUpdateTime: String? = null, val mustNotExist: Boolean = false)

/** The part of Firestore the sync needs, so the sync can be tested without a network. */
interface CloudDocs {
    suspend fun get(path: String): CloudDoc?

    /** All of them or none. */
    suspend fun commit(writes: List<DocWrite>)

    /** The documents of `{parentPath}/{collectionId}` whose server stamp is after [sinceMs] (all of them for 0), oldest first. */
    suspend fun changedSince(parentPath: String, collectionId: String, sinceMs: Long): List<CloudDoc>

    /** The ids of every document in a collection, for deleting an account's data. */
    suspend fun listIds(collectionPath: String): List<String>
}

/** Firestore wraps every value in its type (`{"stringValue": "x"}`); the app works with plain JSON, so this converts both ways. */
internal object FirestoreValues {
    fun toFields(plain: JsonObject): JsonObject = JsonObject(plain.mapValues { toValue(it.value) })

    fun fromFields(fields: JsonObject?): JsonObject = JsonObject((fields ?: JsonObject(emptyMap())).mapValues { fromValue(it.value.jsonObject) })

    fun toValue(plain: JsonElement): JsonElement = when (plain) {
        is JsonNull -> buildJsonObject { put("nullValue", JsonNull) }
        is JsonObject -> buildJsonObject { putJsonObject("mapValue") { put("fields", toFields(plain)) } }
        is JsonArray -> buildJsonObject { putJsonObject("arrayValue") { put("values", JsonArray(plain.map(::toValue))) } }
        is JsonPrimitive -> when {
            plain.isString -> buildJsonObject { put("stringValue", plain.content) }
            plain.booleanOrNull != null -> buildJsonObject { put("booleanValue", plain.booleanOrNull) }
            plain.longOrNull != null -> buildJsonObject { put("integerValue", plain.content) } // Firestore sends and wants integers as text
            else -> buildJsonObject { put("doubleValue", plain.doubleOrNull ?: 0.0) }
        }
    }

    fun fromValue(value: JsonObject): JsonElement {
        val (type, inner) = value.entries.firstOrNull() ?: return JsonNull
        return when (type) {
            "nullValue" -> JsonNull
            "stringValue", "timestampValue", "referenceValue", "bytesValue" -> JsonPrimitive(inner.jsonPrimitive.content)
            "booleanValue" -> JsonPrimitive(inner.jsonPrimitive.booleanOrNull ?: false)
            "integerValue" -> JsonPrimitive(inner.jsonPrimitive.content.toLong())
            "doubleValue" -> JsonPrimitive(inner.jsonPrimitive.doubleOrNull ?: 0.0)
            "mapValue" -> fromFields(inner.jsonObject["fields"] as? JsonObject)
            "arrayValue" -> JsonArray((inner.jsonObject["values"] as? JsonArray)?.map { fromValue(it.jsonObject) } ?: emptyList())
            else -> JsonNull
        }
    }
}

/** Firestore over REST, signed with the person's ID token. REST has no live listening, so the sync pulls on a schedule. */
class FirestoreRest(
    private val config: FirebaseConfig,
    private val client: OkHttpClient,
    private val idToken: suspend () -> String,
) : CloudDocs {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    override suspend fun get(path: String): CloudDoc? {
        val answer = call(Request.Builder().url("${config.documentsBase}/$path").get())
        if (answer.code == 404) return null
        return document(parse(answer))
    }

    override suspend fun commit(writes: List<DocWrite>) {
        if (writes.isEmpty()) return
        val body = buildJsonObject {
            putJsonArray("writes") {
                for (write in writes) add(buildJsonObject {
                    val name = "${config.databaseName}/documents/${write.path}"
                    if (write.fields == null) {
                        put("delete", name)
                    } else {
                        putJsonObject("update") {
                            put("name", name)
                            put("fields", FirestoreValues.toFields(write.fields))
                        }
                        putJsonArray("updateTransforms") {
                            add(buildJsonObject { put("fieldPath", "syncedAt"); put("setToServerValue", "REQUEST_TIME") })
                        }
                    }
                    when {
                        write.expectUpdateTime != null -> putJsonObject("currentDocument") { put("updateTime", write.expectUpdateTime) }
                        write.mustNotExist -> putJsonObject("currentDocument") { put("exists", false) }
                    }
                })
            }
        }
        val answer = call(Request.Builder().url("${config.documentsBase}:commit").post(body.toString().toRequestBody(jsonType)))
        if (answer.isSuccessful) return
        val failure = failure(answer)
        // a changed or already existing document comes back as ABORTED, FAILED_PRECONDITION or ALREADY_EXISTS
        if (failure.code in setOf("ABORTED", "FAILED_PRECONDITION", "ALREADY_EXISTS")) throw CloudConflict(failure.message ?: failure.code)
        throw failure
    }

    override suspend fun changedSince(parentPath: String, collectionId: String, sinceMs: Long): List<CloudDoc> {
        val query = buildJsonObject {
            putJsonObject("structuredQuery") {
                putJsonArray("from") { add(buildJsonObject { put("collectionId", collectionId) }) }
                if (sinceMs > 0) putJsonObject("where") {
                    putJsonObject("fieldFilter") {
                        putJsonObject("field") { put("fieldPath", "syncedAt") }
                        put("op", "GREATER_THAN")
                        putJsonObject("value") { put("timestampValue", Instant.ofEpochMilli(sinceMs).toString()) }
                    }
                }
                putJsonArray("orderBy") {
                    add(buildJsonObject { putJsonObject("field") { put("fieldPath", "syncedAt") }; put("direction", "ASCENDING") })
                }
            }
        }
        val answer = call(Request.Builder().url("${config.documentsBase}/$parentPath:runQuery").post(query.toString().toRequestBody(jsonType)))
        return parseArray(answer).mapNotNull { (it as? JsonObject)?.get("document") as? JsonObject }.map(::document)
    }

    override suspend fun listIds(collectionPath: String): List<String> {
        val ids = mutableListOf<String>()
        var page: String? = null
        do {
            val url = "${config.documentsBase}/$collectionPath?pageSize=300&mask.fieldPaths=syncedAt" + (page?.let { "&pageToken=$it" } ?: "")
            val body = parse(call(Request.Builder().url(url).get()))
            (body["documents"] as? JsonArray)?.forEach { ids += it.jsonObject["name"]!!.jsonPrimitive.content.substringAfterLast('/') }
            page = body["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null)
        return ids
    }

    private suspend fun call(builder: Request.Builder) =
        client.newCall(builder.header("Authorization", "Bearer ${idToken()}").build()).fetch()

    private fun parse(answer: com.opencanvas.core.stream.Fetched): JsonObject {
        if (!answer.isSuccessful) throw failure(answer)
        return json.parseToJsonElement(answer.text()).jsonObject
    }

    private fun parseArray(answer: com.opencanvas.core.stream.Fetched): JsonArray {
        if (!answer.isSuccessful) throw failure(answer)
        return json.parseToJsonElement(answer.text()).jsonArray
    }

    private fun failure(answer: com.opencanvas.core.stream.Fetched): CloudException {
        val error = runCatching {
            val element = json.parseToJsonElement(answer.text())
            (if (element is JsonArray) element.first() else element).jsonObject["error"]!!.jsonObject
        }.getOrNull()
        val status = error?.get("status")?.jsonPrimitive?.contentOrNull
        return CloudException(status ?: "HTTP_${answer.code}", error?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP ${answer.code}", answer.code)
    }

    private fun document(raw: JsonObject): CloudDoc {
        val fields = FirestoreValues.fromFields(raw["fields"] as? JsonObject)
        val syncedAt = fields["syncedAt"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        val name = raw["name"]!!.jsonPrimitive.content
        return CloudDoc(name.substringAfter("/documents/"), fields, raw["updateTime"]?.jsonPrimitive?.contentOrNull.orEmpty(), syncedAt)
    }
}
