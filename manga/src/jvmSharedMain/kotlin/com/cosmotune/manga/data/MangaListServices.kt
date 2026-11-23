package com.cosmotune.manga.data

import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The public client ids AniList and MyAnimeList give an app registered with them (no secret). They come from the build's config
 * (`manga/tracker-clients.properties` or the `cosmotune.anilistClientId` / `cosmotune.malClientId` Gradle properties); none is set
 * until the owner registers the app, and without one the sign-in is not offered.
 */
data class MangaListClients(val anilist: String = "", val myanimelist: String = "") {
    fun id(service: MangaListService): String? = (if (service == MangaListService.ANILIST) anilist else myanimelist).trim().takeIf { it.matches(VALID) }

    companion object {
        private val VALID = Regex("[A-Za-z0-9_-]{1,128}")
        val bundled: MangaListClients get() = MangaListClients(BundledTrackerClients.ANILIST, BundledTrackerClients.MYANIMELIST)
    }
}

/**
 * AniList and MyAnimeList reading lists. The sign-in returns to the same addresses as Watch's anime tracking, so one registered
 * app serves both modes; [MangaListSignIn] takes only the returns of sign-ins Manga started.
 */
enum class MangaListService(val key: String, val label: String, val redirect: String) {
    ANILIST(MangaTrackers.ANILIST, "AniList", "cosmotune://watch/tracker/anilist"),
    MYANIMELIST(MangaTrackers.MYANIMELIST, "MyAnimeList", "cosmotune://watch/tracker/myanimelist");

    /** Why the sign-in cannot be offered, in the listener's words, or null when it can. */
    fun unavailable(clients: MangaListClients): String? = if (clients.id(this) == null) "Sign-in needs a client id" else null

    companion object { fun of(key: String): MangaListService? = entries.find { it.key == key } }
}

/** What the service sent back after the listener signed in: a token (AniList), a code to exchange (MyAnimeList), or an error. */
data class MangaListReturn(val service: MangaListService, val token: String?, val code: String?, val expiresInS: Long?, val error: String?, val verifier: String)

/** Starts a sign-in in the browser and recognises its return: AniList's secret-free implicit sign-in, MyAnimeList's PKCE. */
class MangaListSignIn(private val clients: MangaListClients = MangaListClients.bundled) {
    private data class Pending(val service: MangaListService, val state: String, val verifier: String)
    @Volatile private var pending: Pending? = null
    private val random = SecureRandom()

    /** The sign-in page to open, or null without a client id. Each call starts a new sign-in. */
    fun start(service: MangaListService): String? {
        val id = clients.id(service) ?: return null
        val started = Pending(service, token(24), token(64))
        pending = started
        return when (service) {
            MangaListService.ANILIST -> "https://anilist.co/api/v2/oauth/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", id).addQueryParameter("response_type", "token").addQueryParameter("state", started.state).build().toString()
            MangaListService.MYANIMELIST -> "https://myanimelist.net/v1/oauth2/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("response_type", "code").addQueryParameter("client_id", id).addQueryParameter("state", started.state)
                .addQueryParameter("redirect_uri", service.redirect).addQueryParameter("code_challenge", started.verifier)
                .addQueryParameter("code_challenge_method", "plain").build().toString()
        }
    }

    /** The return of the sign-in started here, taken once; null when [link] is not it (another app's, or Watch's own). */
    @Synchronized fun claim(link: String): MangaListReturn? {
        val started = pending ?: return null
        val trimmed = link.trim().takeIf { it.length <= 8_192 } ?: return null
        val service = MangaListService.entries.firstOrNull { trimmed.startsWith(it.redirect) } ?: return null
        if (service != started.service) return null
        val rest = trimmed.removePrefix(service.redirect)
        val values = (rest.substringAfter('?', "").substringBefore('#') + "&" + rest.substringAfter('#', "")).split('&').filter { '=' in it }.associate { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('='))
        }
        if (values["state"] != started.state) return null
        pending = null
        return MangaListReturn(service, values["access_token"], values["code"], values["expires_in"]?.toLongOrNull(), values["error"], started.verifier)
    }

    fun clientId(service: MangaListService): String? = clients.id(service)

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault("")
    private fun token(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
}

/** AniList (GraphQL) and MyAnimeList (REST v2) for manga: who signed in, finding a series, and moving its chapter count on. */
class MangaListApi(client: OkHttpClient, private val anilist: String = "https://graphql.anilist.co", private val mal: String = "https://api.myanimelist.net/v2",
    private val malAuth: String = "https://myanimelist.net/v1/oauth2") {
    private val http = client.newBuilder().followRedirects(false).build()
    private val type = "application/json".toMediaType()

    /** Turns a sign-in's return into a kept account. */
    suspend fun finish(back: MangaListReturn, clientId: String, now: Long = System.currentTimeMillis()): MangaTrackerAccount {
        back.error?.let { error("${back.service.label} sign-in was not allowed") }
        return when (back.service) {
            MangaListService.ANILIST -> {
                val token = back.token ?: error("AniList sent no sign-in")
                val name = graphql(token, "query{Viewer{name}}", buildJsonObject { })["data"]?.jsonObject?.get("Viewer")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                MangaTrackerAccount(back.service.key, name ?: "AniList", token, expiresAtMs = now + (back.expiresInS ?: 31_536_000L) * 1000)
            }
            MangaListService.MYANIMELIST -> {
                val code = back.code ?: error("MyAnimeList sent no sign-in code")
                val account = malToken(FormBody.Builder().add("client_id", clientId).add("grant_type", "authorization_code").add("code", code)
                    .add("code_verifier", back.verifier).add("redirect_uri", back.service.redirect).build(), now)
                val name = call(Request.Builder().url("$mal/users/@me").header("Authorization", "Bearer ${account.token}").build())["name"]?.jsonPrimitive?.contentOrNull
                account.copy(user = name ?: "MyAnimeList")
            }
        }
    }

    /** A fresh session for [account]: MyAnimeList's expired one is renewed with its refresh token; null when it cannot be. */
    suspend fun fresh(account: MangaTrackerAccount, clientId: String?, now: Long = System.currentTimeMillis()): MangaTrackerAccount? {
        if (account.expiresAtMs == 0L || now < account.expiresAtMs - 60_000) return account
        if (account.service != MangaTrackers.MYANIMELIST || account.refresh.isBlank() || clientId == null) return null
        return malToken(FormBody.Builder().add("client_id", clientId).add("grant_type", "refresh_token").add("refresh_token", account.refresh).build(), now)
            .copy(user = account.user)
    }

    suspend fun search(account: MangaTrackerAccount, title: String): List<MangaTrackerMatch> = when (account.service) {
        MangaTrackers.ANILIST -> aniListMatches(graphql(account.token, "query(\$q:String){Page(perPage:10){media(search:\$q,type:MANGA){id title{romaji english} format startDate{year} coverImage{medium} siteUrl}}}",
            buildJsonObject { put("q", title.take(200)) }))
        else -> malMatches(call(Request.Builder().url("$mal/manga".toHttpUrl().newBuilder().addQueryParameter("q", title.take(64)).addQueryParameter("limit", "10")
            .addQueryParameter("fields", "media_type,start_date").build()).header("Authorization", "Bearer ${account.token}").build()))
    }

    /** The chapter count the listener's list holds for [remoteId] (0 when it is on none). */
    suspend fun listed(account: MangaTrackerAccount, remoteId: String): Int = when (account.service) {
        MangaTrackers.ANILIST -> graphql(account.token, "query(\$id:Int){Media(id:\$id){mediaListEntry{progress}}}", buildJsonObject { put("id", remoteId.toInt()) })["data"]
            ?.jsonObject?.get("Media")?.jsonObject?.get("mediaListEntry")?.let { (it as? JsonObject)?.get("progress")?.jsonPrimitive?.intOrNull } ?: 0
        else -> (call(Request.Builder().url("$mal/manga/${remoteId.toInt()}?fields=my_list_status").header("Authorization", "Bearer ${account.token}").build())["my_list_status"]
            as? JsonObject)?.get("num_chapters_read")?.jsonPrimitive?.intOrNull ?: 0
    }

    suspend fun setChapter(account: MangaTrackerAccount, remoteId: String, chapter: Int) {
        when (account.service) {
            MangaTrackers.ANILIST -> graphql(account.token, "mutation(\$id:Int,\$p:Int){SaveMediaListEntry(mediaId:\$id,progress:\$p,status:CURRENT){progress}}",
                buildJsonObject { put("id", remoteId.toInt()); put("p", chapter) })
            else -> call(Request.Builder().url("$mal/manga/${remoteId.toInt()}/my_list_status").header("Authorization", "Bearer ${account.token}")
                .patch(FormBody.Builder().add("num_chapters_read", chapter.toString()).add("status", "reading").build()).build())
        }
    }

    private suspend fun malToken(form: RequestBody, now: Long): MangaTrackerAccount {
        val token = call(Request.Builder().url("$malAuth/token").post(form).build())
        val access = token["access_token"]?.jsonPrimitive?.contentOrNull ?: error("MyAnimeList did not sign you in")
        return MangaTrackerAccount(MangaTrackers.MYANIMELIST, "MyAnimeList", access, token["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            now + (token["expires_in"]?.jsonPrimitive?.longOrNull ?: 3_600L) * 1000)
    }

    private suspend fun graphql(token: String, query: String, variables: JsonObject): JsonObject =
        call(Request.Builder().url(anilist).header("Authorization", "Bearer $token")
            .post(buildJsonObject { put("query", query); put("variables", variables) }.toString().toRequestBody(type)).build())
            .also { answer -> (answer["errors"] as? JsonArray)?.firstOrNull()?.let { error("AniList: " + ((it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "request failed").take(160)) } }

    private suspend fun call(request: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(request.newBuilder().header("Accept", "application/json").build()).execute().use { response ->
            val service = if (request.url.host.endsWith("anilist.co")) "AniList" else "MyAnimeList"
            if (response.code == 401) throw MangaSourceFailure("$service signed you out. Sign in again in Settings › Manga.")
            check(response.isSuccessful) { "$service returned HTTP ${response.code}" }
            val bytes = response.body.byteStream().use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024) { "$service answered with too much data" }
            if (bytes.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    companion object {
        private fun words(value: String?) = value?.takeIf { it.isNotBlank() }?.lowercase()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase)

        internal fun aniListMatches(answer: JsonObject): List<MangaTrackerMatch> =
            (answer["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media") as? JsonArray).orEmpty().mapNotNull { item ->
                val media = item as? JsonObject ?: return@mapNotNull null
                val id = media["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val titles = media["title"] as? JsonObject
                val title = listOf("english", "romaji").firstNotNullOfOrNull { (titles?.get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) } ?: return@mapNotNull null
                val year = ((media["startDate"] as? JsonObject)?.get("year") as? JsonPrimitive)?.intOrNull
                MangaTrackerMatch(id.toString(), title, listOfNotNull(words((media["format"] as? JsonPrimitive)?.contentOrNull), year?.toString()).joinToString(" · "),
                    ((media["coverImage"] as? JsonObject)?.get("medium") as? JsonPrimitive)?.contentOrNull, (media["siteUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }

        internal fun malMatches(answer: JsonObject): List<MangaTrackerMatch> = (answer["data"] as? JsonArray).orEmpty().mapNotNull { item ->
            val node = (item as? JsonObject)?.get("node") as? JsonObject ?: return@mapNotNull null
            val id = node["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = node["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val year = (node["start_date"] as? JsonPrimitive)?.contentOrNull?.take(4)?.takeIf { it.length == 4 }
            MangaTrackerMatch(id.toString(), title, listOfNotNull(words((node["media_type"] as? JsonPrimitive)?.contentOrNull), year).joinToString(" · "),
                ((node["main_picture"] as? JsonObject)?.get("medium") as? JsonPrimitive)?.contentOrNull, "https://myanimelist.net/manga/$id")
        }

        /** A list only ever moves on. */
        fun shouldSend(read: Int, listed: Int) = read > listed
    }
}

/**
 * Where a sign-in's return reaches Manga: the app hands every `cosmotune://watch/tracker/...` link here first, and Manga keeps it
 * only when it is the return of a sign-in Manga started (otherwise it goes on to Watch).
 */
object MangaTrackerLinks {
    val signIn = MangaListSignIn()
    private val returns = kotlinx.coroutines.flow.MutableSharedFlow<MangaListReturn>(extraBufferCapacity = 4)
    val received: kotlinx.coroutines.flow.SharedFlow<MangaListReturn> = returns

    fun offer(link: String?): Boolean {
        val back = link?.let(signIn::claim) ?: return false
        returns.tryEmit(back)
        return true
    }
}
