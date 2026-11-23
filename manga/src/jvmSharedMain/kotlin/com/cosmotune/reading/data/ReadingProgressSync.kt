package com.cosmotune.reading.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The reader's KOReader sync account: the server, the user name and the key KOReader derives from the password (never the password). */
@Serializable data class ReadingKoSync(val server: String = ReadingKoSyncClient.DEFAULT_SERVER, val username: String, val key: String,
    val device: String, val lastResult: String = "")

/** Where another device (KOReader, a Kobo, another Cosmotune) left a book. */
data class ReadingRemotePosition(val locator: ReadingLocator, val percentage: Double, val device: String, val atMs: Long)

/**
 * KOReader's progress sync (kosync): the same small HTTP API KOReader, Readest and KOReader-compatible apps use, on the public server
 * or the reader's own. A book is known by KOReader's partial MD5 of its file, so the same file matches on every device.
 */
internal class ReadingKoSyncClient(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val DEFAULT_SERVER = "https://sync.koreader.rocks/"
        private const val ACCEPT = "application/vnd.koreader.v1+json"

        /** KOReader's document identity: an MD5 of 1 KB samples at 0, 1 KB, 4 KB, 16 KB… (each step four times the last). */
        fun documentHash(file: File): String {
            val md5 = MessageDigest.getInstance("MD5")
            RandomAccessFile(file, "r").use { input ->
                val buffer = ByteArray(1024)
                for (i in -1..10) {
                    val position = if (i < 0) 0L else 1024L shl (2 * i)
                    if (position >= input.length()) break
                    input.seek(position)
                    val count = input.read(buffer)
                    if (count <= 0) break
                    md5.update(buffer, 0, count)
                }
            }
            return md5.digest().joinToString("") { "%02x".format(it) }
        }
        fun key(password: String): String = MessageDigest.getInstance("MD5").digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
        fun server(address: String): HttpUrl {
            val url = runCatching { address.trim().ifBlank { DEFAULT_SERVER }.let { if (it.endsWith("/")) it else "$it/" }.toHttpUrl() }.getOrNull()
                ?: throw IllegalArgumentException("Enter the sync server's address, starting with https://")
            require(url.isHttps || url.host == "localhost" || url.host.startsWith("192.168.") || url.host.startsWith("10.") || url.host == "127.0.0.1") {
                "Use an https:// address (plain http only works for a server on this network)."
            }
            return url
        }

        /** KOReader's way of saying where in a book: "/body/DocFragment[3]/body" for the third chapter, the page number for a PDF. */
        fun progressOf(document: ReadingFormat, locator: ReadingLocator, spine: List<String>): String = when (document) {
            ReadingFormat.PDF -> (locator.page + 1).toString()
            ReadingFormat.EPUB -> "/body/DocFragment[${spine.indexOf(locator.resourceHref?.substringBefore('#')).coerceAtLeast(0) + 1}]/body"
        }
        fun locatorOf(document: ReadingFormat, progress: String, spine: List<String>): ReadingLocator? = when (document) {
            ReadingFormat.PDF -> progress.trim().toIntOrNull()?.takeIf { it >= 1 }?.let { ReadingLocator(page = it - 1) }
            ReadingFormat.EPUB -> Regex("DocFragment\\[(\\d+)]").find(progress)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { spine.getOrNull(it - 1) }?.let { ReadingLocator(resourceHref = it) }
        }
    }

    private fun Request.Builder.auth(account: ReadingKoSync) = header("x-auth-user", account.username).header("x-auth-key", account.key).header("Accept", ACCEPT)

    /** Checks the account; with [create], registers it first. */
    suspend fun signIn(account: ReadingKoSync, create: Boolean): Unit = withContext(Dispatchers.IO) {
        val base = server(account.server)
        if (create) {
            val body = buildJsonObject { put("username", account.username); put("password", account.key) }.toString().toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url(base.resolve("users/create")!!).header("Accept", ACCEPT).post(body).build()).awaitReading { response ->
                check(response.code == 201 || response.code == 200) { if (response.code == 402) "That user name is taken." else "The account could not be made (${response.code})." }
            }
        }
        http.newCall(Request.Builder().url(base.resolve("users/auth")!!).auth(account).build()).awaitReading { response ->
            check(response.isSuccessful) { if (response.code == 401) "The user name or password is not right." else "The sync server answered ${response.code}." }
        }
    }

    suspend fun push(account: ReadingKoSync, document: String, progress: String, percentage: Double): Unit = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("document", document); put("progress", progress); put("percentage", percentage.coerceIn(0.0, 1.0))
            put("device", "Cosmotune"); put("device_id", account.device)
        }.toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(server(account.server).resolve("syncs/progress")!!).auth(account).put(body).build()).awaitReading { response ->
            check(response.isSuccessful) { "The sync server answered ${response.code}." }
        }
    }

    /** The position the server has for [document], with the device that left it; null when there is none. */
    suspend fun pull(account: ReadingKoSync, document: String): Triple<String, Double, Pair<String, Long>>? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(server(account.server).newBuilder().addPathSegments("syncs/progress").addPathSegment(document).build()).auth(account).build())
            .awaitReading { response ->
                if (!response.isSuccessful) return@awaitReading null
                val root = json.parseToJsonElement(response.catalogueText(64 * 1024)).jsonObject
                val progress = root["progress"]?.jsonPrimitive?.contentOrNull ?: return@awaitReading null
                val device = root["device"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val deviceId = root["device_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (deviceId == account.device) return@awaitReading null
                Triple(progress, root["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0, device to ((root["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000))
            }
    }
}

/**
 * Sends the reading position back to the library server a book came from, so its web reader agrees: Komga (read progress for
 * PDFs, the EPUB position for books) and Kavita (the page). The book's download address says which server and which book it is.
 */
internal class ReadingServerProgress(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val kavitaTokens = HashMap<String, String>()

    sealed interface Target
    data class Komga(val base: HttpUrl, val book: String) : Target
    data class Kavita(val base: HttpUrl, val apiKey: String, val series: Int, val volume: Int, val chapter: Int) : Target

    companion object {
        /** Which server and book [download] (an OPDS acquisition address) belongs to, or null for any other library. */
        fun target(download: String): Target? {
            val url = runCatching { download.toHttpUrl() }.getOrNull() ?: return null
            val path = url.encodedPath
            Regex("^(.*?)/(?:opds/v[0-9.]+|api/v1)/books/([A-Za-z0-9]+)/file").find(path)?.let { match ->
                return Komga(url.newBuilder().encodedPath(match.groupValues[1] + "/").query(null).build(), match.groupValues[2])
            }
            Regex("^(.*?)/api/opds/([^/]+)/series/(\\d+)/volume/(\\d+)/chapter/(\\d+)/download").find(path)?.let { m ->
                return Kavita(url.newBuilder().encodedPath(m.groupValues[1] + "/").query(null).build(), m.groupValues[2],
                    m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt())
            }
            return null
        }
    }

    suspend fun send(target: Target, source: ReadingOpdsSource?, book: ReadingBook, locator: ReadingLocator, section: Int, finished: Boolean): Unit = withContext(Dispatchers.IO) {
        fun Request.Builder.basic() = apply { if (source != null && source.username.isNotEmpty()) header("Authorization", Credentials.basic(source.username, source.password, Charsets.UTF_8)) }
        when (target) {
            is Komga -> if (book.format == ReadingFormat.PDF || finished) {
                val body = buildJsonObject { if (!finished) put("page", locator.page + 1); put("completed", finished) }.toString().toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder().url(target.base.resolve("api/v1/books/${target.book}/read-progress")!!).basic().patch(body).build())
                    .awaitReading { check(it.isSuccessful) { "Komga answered ${it.code}." } }
            } else {
                val body = buildJsonObject {
                    putJsonObject("device") { put("id", "cosmotune"); put("name", "Cosmotune") }
                    putJsonObject("locator") {
                        put("href", locator.resourceHref?.substringBefore('#').orEmpty()); put("type", "application/xhtml+xml")
                        putJsonObject("locations") { put("progression", locator.progression) }
                    }
                    put("modified", java.time.Instant.now().toString())
                }.toString().toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder().url(target.base.resolve("api/v1/books/${target.book}/progression")!!).basic().put(body).build())
                    .awaitReading { check(it.isSuccessful || it.code == 409) { "Komga answered ${it.code}." } }
            }
            is Kavita -> {
                val token = kavitaTokens[target.apiKey] ?: http.newCall(Request.Builder().url(target.base.newBuilder().addPathSegments("api/Plugin/authenticate")
                    .addQueryParameter("apiKey", target.apiKey).addQueryParameter("pluginName", "Cosmotune").build())
                    .post(ByteArray(0).toRequestBody(null)).build()).awaitReading { response ->
                        check(response.isSuccessful) { "Kavita answered ${response.code}." }
                        json.parseToJsonElement(response.catalogueText(64 * 1024)).jsonObject["token"]?.jsonPrimitive?.content ?: error("Kavita gave no session.")
                    }.also { kavitaTokens[target.apiKey] = it }
                val library = http.newCall(Request.Builder().url(target.base.resolve("api/Series/${target.series}")!!).header("Authorization", "Bearer $token").build())
                    .awaitReading { response -> json.parseToJsonElement(response.catalogueText(512 * 1024)).jsonObject["libraryId"]?.jsonPrimitive?.intOrNull ?: 0 }
                val body = buildJsonObject {
                    put("volumeId", target.volume); put("chapterId", target.chapter); put("seriesId", target.series); put("libraryId", library)
                    put("pageNum", if (book.format == ReadingFormat.PDF) locator.page else section)
                }.toString().toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder().url(target.base.resolve("api/Reader/progress")!!).header("Authorization", "Bearer $token").post(body).build())
                    .awaitReading { check(it.isSuccessful) { "Kavita answered ${it.code}." } }
            }
        }
    }
}
