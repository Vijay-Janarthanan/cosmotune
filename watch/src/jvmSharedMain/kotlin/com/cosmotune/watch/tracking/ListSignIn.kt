package com.cosmotune.watch.tracking

import com.cosmotune.watch.AnimeTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The one AniList and MyAnimeList sign-in in the app, used by Watch (anime lists) and Manga (reading lists). Both modes sign in
 * with the same registered app and come back on the same return addresses ([AnimeTracker.redirect]); each return is matched by
 * its state to the sign-in that started it, so it reaches the mode that asked for it. The app's link handler ([com.cosmotune.watch.WatchLinks.offer])
 * hands every return here first: Manga's go out on [returns], Watch's stay with Watch's own Finish signing in.
 *
 * The client id is the app's own (the `cosmotune.anilistClientId` / `cosmotune.malClientId` build properties, or
 * `watch/tracker-clients.properties`), else the one the viewer saved in Watch settings on this device. Without one no sign-in is
 * offered.
 */
object ListSignIn {
    /** The mode that started a sign-in. */
    enum class Asker { WATCH, MANGA }

    data class Started(val service: AnimeTracker, val asker: Asker, val state: String, val verifier: String, val clientId: String, val profile: String)

    /** What the service sent back for the sign-in [started]: a token (AniList), a code to exchange (MyAnimeList), or an error. */
    data class Back(val started: Started, val token: String?, val code: String?, val expiresInS: Long?, val error: String?) {
        val service: AnimeTracker get() = started.service
    }

    /** A signed-in account: its token, MyAnimeList's refresh token, when the token ends (0 for never known), and its name. */
    @Serializable data class Session(val token: String, val refresh: String = "", val expiresAtMs: Long = 0, val user: String? = null)

    private val bundled = mapOf(AnimeTracker.ANILIST to BundledTrackerClients.ANILIST, AnimeTracker.MYANIMELIST to BundledTrackerClients.MYANIMELIST)

    /** The client ids the viewer saved in Watch settings on this device ([useSavedClientIds] sets this at app start). */
    @Volatile var savedClientId: (AnimeTracker) -> String? = { null }

    /** Where Watch settings keep the client id saved on this device for [service]. */
    fun savedClientIdKey(service: AnimeTracker): String = "anime.client.${service.name}"

    /**
     * Reads the client ids saved in Watch settings from Watch's [store] (opened only when an id is first asked for). The phone, TV
     * and desktop call this at start, so a client id typed in Watch serves Manga's sign-in without Watch being opened first.
     */
    fun useSavedClientIds(store: () -> com.cosmotune.watch.store.WatchStore) {
        val opened by lazy(store)
        savedClientId = { service -> opened.state.value.settings[savedClientIdKey(service)] }
    }

    /** The client id a sign-in to [service] uses, or null when there is none. */
    fun clientId(service: AnimeTracker): String? =
        bundled[service]?.trim()?.takeIf(AnimeTrackingService.Auth::validClientId) ?: savedClientId(service)?.trim()?.takeIf(AnimeTrackingService.Auth::validClientId)

    /** True when the app itself carries a client id for [service] (nothing for the viewer to enter). */
    fun bundled(service: AnimeTracker): Boolean = bundled[service]?.trim()?.let(AnimeTrackingService.Auth::validClientId) == true

    private val pending = LinkedHashMap<String, Started>()

    /**
     * Starts a sign-in to [service] for [asker] (and Watch's viewer [profile]) and gives the page to open in the browser, or null
     * without a client id. A mode's new sign-in to a service replaces its earlier one; the other mode's stays open.
     */
    @Synchronized fun start(service: AnimeTracker, asker: Asker, profile: String = "", malAuth: String = "https://myanimelist.net/v1/oauth2"): String? {
        val id = clientId(service) ?: return null
        val started = Started(service, asker, AnimeTrackingService.Auth.randomToken(32), AnimeTrackingService.Auth.randomToken(64), id, profile)
        pending.values.removeAll { it.service == service && it.asker == asker }
        pending[started.state] = started
        while (pending.size > 8) pending.remove(pending.keys.first())
        return AnimeTrackingService.Auth.authorizeUrl(service, id, started.state, started.verifier, malAuth)
    }

    /** The sign-in [link] is the return of, without taking it; null when it is none started here. */
    @Synchronized fun startedFor(link: String): Started? {
        val back = AnimeTrackingService.Auth.parseReturn(link.takeIf { it.length <= 8_192 } ?: return null) ?: return null
        return match(back)
    }

    /** Takes the return [link] once: the sign-in it answers and what came back; null when it answers none started here. */
    @Synchronized fun claim(link: String): Back? {
        val back = AnimeTrackingService.Auth.parseReturn(link.takeIf { it.length <= 8_192 } ?: return null) ?: return null
        val started = match(back) ?: return null
        pending.remove(started.state)
        return Back(started, back.accessToken, back.code, back.expiresInS, back.error)
    }

    // AniList's token comes back on the fragment and may leave the state out: then it answers the latest AniList sign-in.
    private fun match(back: AnimeTrackingService.Auth.Return): Started? = when {
        back.state != null -> pending[back.state]?.takeIf { it.service == back.service }
        back.service == AnimeTracker.ANILIST -> pending.values.lastOrNull { it.service == AnimeTracker.ANILIST }
        else -> null
    }

    private val delivered = MutableSharedFlow<Back>(extraBufferCapacity = 4)
    /** The returns of sign-ins a mode other than Watch started ([Asker.MANGA]); that mode finishes them. */
    val returns: SharedFlow<Back> = delivered

    /** The app's link handler: true when [link] answered a Manga sign-in and went out on [returns]. Watch's own are left. */
    fun deliver(link: String): Boolean {
        if (startedFor(link)?.asker != Asker.MANGA) return false
        val back = claim(link) ?: return false
        delivered.tryEmit(back)
        return true
    }

    /** Turns a sign-in's [back] into a session: AniList's token as it is, MyAnimeList's code exchanged; the account name added. */
    suspend fun finish(back: Back, client: OkHttpClient, now: Long = System.currentTimeMillis(), endpoints: Endpoints = Endpoints()): Session {
        back.error?.let { error("${back.service.label} sign-in was not allowed") }
        val session = when (back.service) {
            AnimeTracker.ANILIST -> Session(back.token ?: error("AniList sent no sign-in"), expiresAtMs = now + (back.expiresInS ?: 31_536_000L) * 1000)
            AnimeTracker.MYANIMELIST -> malToken(client, endpoints, now, FormBody.Builder().add("client_id", back.started.clientId)
                .add("grant_type", "authorization_code").add("code", back.code ?: error("MyAnimeList sent no sign-in code"))
                .add("code_verifier", back.started.verifier).add("redirect_uri", AnimeTracker.MYANIMELIST.redirect).build())
        }
        return session.copy(user = runCatching { userName(client, endpoints, back.service, session.token) }.getOrNull())
    }

    /** A new MyAnimeList session from [session]'s refresh token; the name is kept. */
    suspend fun renew(session: Session, client: OkHttpClient, now: Long = System.currentTimeMillis(), endpoints: Endpoints = Endpoints()): Session {
        val id = clientId(AnimeTracker.MYANIMELIST) ?: error("Sign-in needs a client id")
        require(session.refresh.isNotBlank()) { "MyAnimeList needs you to sign in again" }
        return malToken(client, endpoints, now, FormBody.Builder().add("client_id", id).add("grant_type", "refresh_token").add("refresh_token", session.refresh).build())
            .copy(user = session.user)
    }

    /** Where the services answer (tests point these at a local server). */
    data class Endpoints(val anilist: String = "https://graphql.anilist.co", val mal: String = "https://api.myanimelist.net/v2",
        val malAuth: String = "https://myanimelist.net/v1/oauth2")

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun malToken(client: OkHttpClient, endpoints: Endpoints, now: Long, form: FormBody): Session {
        val token = call(client, Request.Builder().url("${endpoints.malAuth}/token").post(form).build())
        return Session(token["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("MyAnimeList did not sign you in"),
            token["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(), now + (token["expires_in"]?.jsonPrimitive?.longOrNull ?: 2_678_400L) * 1000)
    }

    private suspend fun userName(client: OkHttpClient, endpoints: Endpoints, service: AnimeTracker, token: String): String? = when (service) {
        AnimeTracker.ANILIST -> call(client, Request.Builder().url(endpoints.anilist).header("Authorization", "Bearer $token")
            .post(buildJsonObject { put("query", "query{Viewer{name}}"); put("variables", JsonObject(emptyMap())) }.toString().toRequestBody("application/json".toMediaType())).build())
            .let { (it["data"] as? JsonObject)?.get("Viewer") as? JsonObject }?.get("name")?.jsonPrimitive?.contentOrNull
        AnimeTracker.MYANIMELIST -> call(client, Request.Builder().url("${endpoints.mal}/users/@me").header("Authorization", "Bearer $token").build())["name"]?.jsonPrimitive?.contentOrNull
    }?.takeIf { it.isNotBlank() }

    private suspend fun call(client: OkHttpClient, request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newBuilder().followRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
            .newCall(request.newBuilder().header("Accept", "application/json").build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val bytes = response.body?.byteStream()?.use { it.readNBytes(1024 * 1024 + 1) } ?: ByteArray(0)
                require(bytes.size <= 1024 * 1024) { "too much data" }
                if (bytes.isEmpty()) JsonObject(emptyMap()) else json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            }
    }
}
