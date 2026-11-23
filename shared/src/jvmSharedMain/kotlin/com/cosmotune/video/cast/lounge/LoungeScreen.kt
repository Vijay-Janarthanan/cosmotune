package com.cosmotune.video.cast.lounge

import com.cosmotune.video.downloads.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

/** The lounge answered something this screen cannot go on with (a session the server forgot, a refused pairing). */
class LoungeError(message: String) : IOException(message)

/**
 * This device as a screen the YouTube app can cast to with "Link with TV code" (the lounge SmartTube answers): a screen id
 * (kept, so a linked phone finds it again), a lounge token, a twelve-digit code registered for the phone to type, and the
 * Browser Channel session whose long poll brings the phone's commands ([LoungeProtocol]). [base] is the lounge's address
 * (a local server in tests). Nothing here signs in: the lounge is public and the phone does the asking.
 */
class LoungeScreen(
    private val name: String,
    private val client: OkHttpClient = defaultClient(),
    private val base: String = "https://www.youtube.com/api/lounge",
    private val device: String = UUID.randomUUID().toString(),
) : LoungeLink {
    private val json = Json { ignoreUnknownKeys = true }
    private val sending = Mutex()
    private var rid = 1
    private var ofs = 0
    private var sid: String? = null
    private var gsession: String? = null
    private var lastId = 0
    private var token: String? = null

    /** The screen's id: [saved] when there is one, else a new one from the lounge. */
    override suspend fun screenId(saved: String?): String = saved?.takeIf { it.isNotBlank() } ?: post("$base/pairing/generate_screen_id", emptyMap()).trim().also {
        if (it.isEmpty() || it.length > 64 || it.any { c -> !c.isLetterOrDigit() }) throw LoungeError("no screen id")
    }

    /** Fetches the lounge token of [screenId] (it lasts about two weeks; this asks on each start). */
    override suspend fun connect(screenId: String): String {
        val answer = post("$base/pairing/get_lounge_token_batch", mapOf("screen_ids" to screenId))
        val value = runCatching {
            json.parseToJsonElement(answer).jsonObject["screens"]!!.jsonArray.first().jsonObject["loungeToken"]!!.jsonPrimitive.contentOrNull
        }.getOrNull()
        return value?.takeIf { it.isNotBlank() }?.also { token = it } ?: throw LoungeError("no lounge token")
    }

    /** Registers [code] for [screenId], so the phone can type it (a code lasts a few minutes; register a new one before). */
    override suspend fun register(screenId: String, code: String) {
        post(
            "$base/pairing/register_pairing_code",
            mapOf(
                "access_type" to "permanent", "app" to APP, "lounge_token" to (token ?: throw LoungeError("not connected")),
                "pairing_code" to code, "screen_id" to screenId, "screen_name" to name, "device_id" to device,
            ),
        )
    }

    /**
     * Runs the channel until cancelled: opens a session, long-polls it and hands every command to [onCommand]; a session
     * the server ends is opened again, a network failure waits and tries again.
     */
    override suspend fun run(onCommand: suspend (LoungeCommand) -> Unit) {
        var failures = 0
        while (coroutineContext.isActive) {
            try {
                open(onCommand)
                failures = 0
                while (coroutineContext.isActive) {
                    if (!poll(onCommand)) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                failures++
                delay((2_000L * failures).coerceAtMost(30_000))
            }
        }
    }

    /** Sends [events] (name and fields) to the phones. */
    override suspend fun send(events: List<Pair<String, Map<String, String>>>) {
        if (events.isEmpty()) return
        sending.withLock {
            val s = sid ?: return
            val g = gsession ?: return
            val url = bind().addQueryParameter("RID", (rid++).toString()).addQueryParameter("SID", s)
                .addQueryParameter("gsessionid", g).addQueryParameter("AID", lastId.toString()).build()
            val form = LoungeProtocol.form(ofs, events)
            ofs += events.size
            post(url.toString(), form)
        }
    }

    private suspend fun open(onCommand: suspend (LoungeCommand) -> Unit) {
        sending.withLock { rid = 1; ofs = 0; sid = null; gsession = null }
        val url = bind().addQueryParameter("RID", (rid++).toString()).addQueryParameter("AID", lastId.toString())
            .addQueryParameter("CVER", "1").build()
        val answer = post(url.toString(), mapOf("count" to "0"))
        deliver(LoungeProtocol.split(answer).first, onCommand)
        if (sid == null || gsession == null) throw LoungeError("no session")
    }

    /** One long poll; false when the server ended the session (a new one is needed). */
    private suspend fun poll(onCommand: suspend (LoungeCommand) -> Unit): Boolean {
        val url = bind().addQueryParameter("RID", "rpc").addQueryParameter("SID", sid!!).addQueryParameter("gsessionid", gsession!!)
            .addQueryParameter("CI", "0").addQueryParameter("TYPE", "xmlhttp").addQueryParameter("AID", lastId.toString()).build()
        val call = longClient.newCall(Request.Builder().url(url).get().build())
        // a long poll blocks a thread: leaving (the listener turned it off, Video closed) cancels the call
        val stop = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        // the commands are handled where the caller runs (the screen's main thread), not on the reading thread
        val caller = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
        return try { withContext(Dispatchers.IO) {
            call.execute().use { response ->
                if (response.code == 400 || response.code == 404 || response.code == 410) return@withContext false
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val source = response.body.source()
                var buffer = ""
                while (coroutineContext.isActive) {
                    val chunk = source.buffer
                    if (!source.request(1)) break
                    buffer += chunk.readUtf8()
                    val (arrays, rest) = LoungeProtocol.split(buffer)
                    buffer = rest
                    if (arrays.isNotEmpty()) withContext(caller) { deliver(arrays, onCommand) }
                }
                true
            }
        } } finally {
            stop?.dispose()
        }
    }

    private suspend fun deliver(arrays: List<String>, onCommand: suspend (LoungeCommand) -> Unit) {
        for (array in arrays) {
            val messages = runCatching { LoungeProtocol.messages(array) }.getOrDefault(emptyList())
            for (m in messages) {
                lastId = maxOf(lastId, m.id)
                when (val command = LoungeProtocol.command(m)) {
                    is LoungeCommand.Session -> sid = command.sid
                    is LoungeCommand.GSession -> gsession = command.id
                    else -> onCommand(command)
                }
            }
        }
    }

    private fun bind(): HttpUrl.Builder = "$base/bc/bind".toHttpUrl().newBuilder()
        .addQueryParameter("device", "LOUNGE_SCREEN").addQueryParameter("theme", "cl").addQueryParameter("capabilities", "dsp,mic,dpa")
        .addQueryParameter("mdxVersion", "2").addQueryParameter("VER", "8").addQueryParameter("v", "2").addQueryParameter("t", "1")
        .addQueryParameter("app", APP).addQueryParameter("name", name).addQueryParameter("id", device)
        .addQueryParameter("loungeIdToken", token ?: throw LoungeError("not connected"))
        .addQueryParameter("zx", UUID.randomUUID().toString().replace("-", "").take(12))

    private suspend fun post(url: String, fields: Map<String, String>): String {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return client.newCall(Request.Builder().url(url).post(body).build()).await { response ->
            if (!response.isSuccessful) throw LoungeError("HTTP ${response.code}")
            response.body.string()
        }
    }

    private val longClient: OkHttpClient by lazy { client.newBuilder().readTimeout(5, TimeUnit.MINUTES).build() }

    companion object {
        private const val APP = "cosmotune"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
