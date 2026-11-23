package com.cosmotune.manga.data

import com.cosmotune.watch.AnimeTracker
import com.cosmotune.watch.tracking.ListSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AniList and MyAnimeList reading lists. Signing in is the app's one AniList and MyAnimeList sign-in
 * ([com.cosmotune.watch.tracking.ListSignIn]), which Watch's anime lists use too: the same registered app, the same return
 * addresses, the same client id.
 */
enum class MangaListService(val key: String, val label: String, val tracker: AnimeTracker) {
    ANILIST(MangaTrackers.ANILIST, "AniList", AnimeTracker.ANILIST),
    MYANIMELIST(MangaTrackers.MYANIMELIST, "MyAnimeList", AnimeTracker.MYANIMELIST);

    val redirect: String get() = tracker.redirect

    /** Why the sign-in cannot be offered, in the listener's words, or null when it can. */
    fun unavailable(clientId: String? = ListSignIn.clientId(tracker)): String? = if (clientId == null) "Sign-in needs a client id" else null

    companion object {
        fun of(key: String): MangaListService? = entries.find { it.key == key }
        fun of(tracker: AnimeTracker): MangaListService = entries.first { it.tracker == tracker }
    }
}

/** AniList (GraphQL) and MyAnimeList (REST v2) for manga: finding a series, and moving its chapter count on. */
class MangaListApi(client: OkHttpClient, private val anilist: String = "https://graphql.anilist.co", private val mal: String = "https://api.myanimelist.net/v2") {
    private val http = client.newBuilder().followRedirects(false).build()
    private val type = "application/json".toMediaType()

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
