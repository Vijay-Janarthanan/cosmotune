package com.cosmotune.watch.tracking

import com.cosmotune.watch.AnimeTracker
import com.cosmotune.watch.AnimeTracking
import com.cosmotune.watch.AnimeTrackingStatus
import com.cosmotune.watch.store.WatchStore
import com.cosmotune.watch.store.allProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * AniList (GraphQL, implicit sign-in) and MyAnimeList (REST v2, PKCE) against their public APIs, with the client id the
 * viewer saved. Tokens stay in Watch's sealed store, per viewer profile; the client id is per device.
 */
class AnimeTrackingService(
    client: OkHttpClient,
    private val store: WatchStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val anilistApi: String = "https://graphql.anilist.co",
    private val malApi: String = "https://api.myanimelist.net/v2",
    private val malAuth: String = "https://myanimelist.net/v1/oauth2",
) : AnimeTracking {
    @Serializable private data class Session(val token: String, val refresh: String = "", val expiresAtMs: Long = 0, val user: String? = null)
    private data class Pending(val service: AnimeTracker, val state: String, val verifier: String, val profile: String, val clientId: String)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val network = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    @Volatile private var pending: Pending? = null
    private val mutable = MutableStateFlow(AnimeTracker.entries.map { AnimeTrackingStatus(it) })
    override val statuses: StateFlow<List<AnimeTrackingStatus>> = mutable

    private fun clientKey(service: AnimeTracker) = "anime.client.${service.name}"
    private fun sessionKey(service: AnimeTracker, profile: String) = "anime.$profile.${service.name}"
    private fun clientId(service: AnimeTracker) = store.state.value.settings[clientKey(service)]
    private fun session(service: AnimeTracker, profile: String = store.state.value.activeProfile): Session? =
        store.state.value.settings[sessionKey(service, profile)]?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }

    private suspend fun save(service: AnimeTracker, profile: String, value: Session?) = store.update { state ->
        state.copy(settings = if (value == null) state.settings - sessionKey(service, profile)
            else state.settings + (sessionKey(service, profile) to json.encodeToString(Session.serializer(), value)))
    }

    private fun publish() {
        val profile = store.state.value.activeProfile
        mutable.update { list -> list.map { s -> val saved = session(s.service, profile)
            s.copy(clientId = clientId(s.service), connected = saved != null, user = saved?.user) } }
    }

    private fun say(service: AnimeTracker, message: String?, busy: Boolean = false) =
        mutable.update { list -> list.map { if (it.service == service) it.copy(message = message, busy = busy) else it } }

    init {
        scope.launch { store.state.map { it.activeProfile }.distinctUntilChanged().collect { publish() } }
    }

    override suspend fun setClientId(service: AnimeTracker, clientId: String) {
        val id = clientId.trim()
        require(id.isEmpty() || Auth.validClientId(id)) { "Enter the client ID from your ${service.label} app" }
        store.update { s -> s.copy(settings = if (id.isEmpty()) s.settings - clientKey(service) else s.settings + (clientKey(service) to id)) }
        publish()
    }

    override fun signInUrl(service: AnimeTracker): String? {
        val id = clientId(service) ?: return null
        val started = Pending(service, Auth.randomToken(32), Auth.randomToken(64), store.state.value.activeProfile, id)
        pending = started
        return Auth.authorizeUrl(service, id, started.state, started.verifier, malAuth)
    }

    override suspend fun finish(link: String): String = lock.withLock {
        val back = Auth.parseReturn(link) ?: return@withLock "That is not a sign-in return address."
        val started = pending?.takeIf { it.service == back.service } ?: return@withLock "Start the ${back.service.label} sign-in again from Watch settings."
        if (back.error != null) { pending = null; return@withLock "${back.service.label} sign-in was not allowed." }
        if (back.state != null && back.state != started.state || back.state == null && back.service == AnimeTracker.MYANIMELIST)
            return@withLock "This sign-in return is from another attempt. Start again."
        say(back.service, null, busy = true)
        try {
            val session = when (back.service) {
                AnimeTracker.ANILIST -> {
                    val token = back.accessToken ?: error("AniList sent no sign-in")
                    Session(token, expiresAtMs = now() + (back.expiresInS ?: 31_536_000L) * 1000)
                }
                AnimeTracker.MYANIMELIST -> {
                    val code = back.code ?: error("MyAnimeList sent no sign-in code")
                    malToken(FormBody.Builder().add("client_id", started.clientId).add("grant_type", "authorization_code").add("code", code)
                        .add("code_verifier", started.verifier).add("redirect_uri", AnimeTracker.MYANIMELIST.redirect).build())
                }
            }
            val named = session.copy(user = runCatching { userName(back.service, session.token) }.getOrNull())
            save(back.service, started.profile, named)
            pending = null
            publish()
            "Connected to ${back.service.label}${named.user?.let { " as $it" } ?: ""}. Finished anime episodes now move your list on."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { "${back.service.label} sign-in did not finish. Try again." }
        finally { say(back.service, null, busy = false) }
    }

    override suspend fun disconnect(service: AnimeTracker) {
        save(service, store.state.value.activeProfile, null)
        publish()
    }

    /**
     * The viewer finished episode [episode] (counted as MyAnimeList counts it) of the MyAnimeList entry [malId]: every
     * connected list that has fewer watched moves to it, and to completed when it is the last.
     */
    suspend fun push(malId: Int, episode: Int) {
        if (malId <= 0 || episode <= 0) return
        val profile = store.state.value.activeProfile
        for (service in AnimeTracker.entries) {
            val saved = session(service, profile) ?: continue
            try {
                val fresh = if (service == AnimeTracker.MYANIMELIST) refreshed(saved, profile) else saved
                when (service) {
                    AnimeTracker.ANILIST -> pushAniList(fresh.token, malId, episode)
                    AnimeTracker.MYANIMELIST -> pushMal(fresh.token, malId, episode)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { say(service, "${service.label} could not be updated just now.") }
        }
    }

    private suspend fun pushAniList(token: String, malId: Int, episode: Int) {
        val media = graphql(token, "query(\$m:Int){Media(idMal:\$m,type:ANIME){id episodes mediaListEntry{progress status}}}",
            buildJsonObject { put("m", malId) }).obj("data")?.obj("Media") ?: return
        val id = media.int("id") ?: return
        val entry = media.obj("mediaListEntry")
        val update = Auth.update(entry?.int("progress"), entry?.text("status") == "COMPLETED", episode, media.int("episodes")) ?: return
        // A profile that keeps its list private makes the entries it writes private; otherwise an entry's own setting is left alone.
        val hidden = store.state.value.let { it.allProfiles()[it.activeProfile]?.preferences?.animePrivate } == true
        graphql(token, "mutation(\$id:Int,\$p:Int,\$s:MediaListStatus${if (hidden) ",\$x:Boolean" else ""}){SaveMediaListEntry(mediaId:\$id,progress:\$p,status:\$s${if (hidden) ",private:\$x" else ""}){id}}",
            buildJsonObject { put("id", id); put("p", update.episodes); put("s", if (update.completed) "COMPLETED" else "CURRENT"); if (hidden) put("x", true) })
    }

    private suspend fun pushMal(token: String, malId: Int, episode: Int) {
        val anime = call(Request.Builder().url("$malApi/anime/$malId?fields=num_episodes,my_list_status").header("Authorization", "Bearer $token").build())
        val status = anime.obj("my_list_status")
        val update = Auth.update(status?.int("num_watched_episodes"), status?.text("status") == "completed", episode, anime.int("num_episodes")?.takeIf { it > 0 }) ?: return
        call(Request.Builder().url("$malApi/anime/$malId/my_list_status").header("Authorization", "Bearer $token")
            .patch(FormBody.Builder().add("status", if (update.completed) "completed" else "watching").add("num_watched_episodes", update.episodes.toString()).build()).build())
    }

    private suspend fun refreshed(saved: Session, profile: String): Session {
        if (saved.expiresAtMs == 0L || saved.expiresAtMs - now() > 86_400_000L || saved.refresh.isBlank()) return saved
        val id = clientId(AnimeTracker.MYANIMELIST) ?: return saved
        val next = malToken(FormBody.Builder().add("client_id", id).add("grant_type", "refresh_token").add("refresh_token", saved.refresh).build())
            .copy(user = saved.user)
        save(AnimeTracker.MYANIMELIST, profile, next)
        return next
    }

    private suspend fun malToken(form: FormBody): Session {
        val token = call(Request.Builder().url("$malAuth/token").post(form).build())
        return Session(token.text("access_token") ?: error("no token"), token.text("refresh_token").orEmpty(),
            now() + (token.long("expires_in") ?: 2_678_400L) * 1000)
    }

    private suspend fun userName(service: AnimeTracker, token: String): String? = when (service) {
        AnimeTracker.ANILIST -> graphql(token, "query{Viewer{name}}", JsonObject(emptyMap())).obj("data")?.obj("Viewer")?.text("name")
        AnimeTracker.MYANIMELIST -> call(Request.Builder().url("$malApi/users/@me").header("Authorization", "Bearer $token").build()).text("name")
    }

    private suspend fun graphql(token: String, query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject { put("query", query); put("variables", variables) }.toString().toRequestBody("application/json".toMediaType())
        return call(Request.Builder().url(anilistApi).header("Authorization", "Bearer $token").header("Accept", "application/json").post(body).build())
    }

    private suspend fun call(request: Request): JsonObject = withContext(Dispatchers.IO) {
        network.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject
        }
    }

    private fun JsonElement?.obj(name: String) = (this as? JsonObject)?.get(name) as? JsonObject
    private fun JsonObject.text(name: String) = runCatching { get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(name: String) = runCatching { get(name)?.jsonPrimitive?.intOrNull }.getOrNull()
    private fun JsonObject.long(name: String) = runCatching { get(name)?.jsonPrimitive?.longOrNull }.getOrNull()

    /** The parts of signing in and updating that need no network, kept apart so they can be checked. */
    object Auth {
        private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        private val random = SecureRandom()

        fun validClientId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,200}"))

        /** A random string of PKCE's unreserved characters ([length] 43 to 128 for a verifier). */
        fun randomToken(length: Int) = buildString(length) { repeat(length) { append(TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)]) } }

        /** MyAnimeList takes only the "plain" PKCE method, so the challenge is the verifier itself. */
        fun authorizeUrl(service: AnimeTracker, clientId: String, state: String, verifier: String, malAuth: String = "https://myanimelist.net/v1/oauth2"): String {
            fun e(text: String) = URLEncoder.encode(text, "UTF-8")
            return when (service) {
                AnimeTracker.ANILIST -> "https://anilist.co/api/v2/oauth/authorize?client_id=${e(clientId)}&response_type=token&state=${e(state)}"
                AnimeTracker.MYANIMELIST -> "$malAuth/authorize?response_type=code&client_id=${e(clientId)}&state=${e(state)}" +
                    "&redirect_uri=${e(service.redirect)}&code_challenge=${e(verifier)}&code_challenge_method=plain"
            }
        }

        data class Return(val service: AnimeTracker, val state: String?, val code: String?, val accessToken: String?, val expiresInS: Long?, val error: String?)

        /** The service's return to [AnimeTracker.redirect]: its query and (AniList's token) fragment read. */
        fun parseReturn(link: String): Return? {
            val text = link.trim()
            val service = AnimeTracker.entries.firstOrNull { text.startsWith(it.redirect) } ?: return null
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            fun params(raw: String?) = raw.orEmpty().split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val all = params(uri.rawQuery) + params(uri.rawFragment)
            return Return(service, all["state"], all["code"], all["access_token"], all["expires_in"]?.toLongOrNull(), all["error"])
        }

        data class Update(val episodes: Int, val completed: Boolean)

        /**
         * What a list that has [current] watched (and is [done] already) becomes after episode [watched] of [total]: null when it
         * is already there or past it, or when the episode is beyond the entry's end.
         */
        fun update(current: Int?, done: Boolean, watched: Int, total: Int?): Update? {
            if (done || (current ?: 0) >= watched) return null
            if (total != null && watched > total) return null
            return Update(watched, total != null && watched == total)
        }
    }
}
