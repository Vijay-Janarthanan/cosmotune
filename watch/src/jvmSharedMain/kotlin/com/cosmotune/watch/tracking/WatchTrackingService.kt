package com.cosmotune.watch.tracking

import com.cosmotune.watch.*
import com.cosmotune.watch.model.*
import com.cosmotune.watch.store.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Original implementation against the public Trakt and Simkl APIs. Tokens remain in Watch's sealed store. */
class WatchTrackingService(private val client: OkHttpClient, private val store: WatchStore,
    private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis,
    private val endpoints: Map<WatchTracker, String> = mapOf(WatchTracker.TRAKT to "https://api.trakt.tv", WatchTracker.SIMKL to "https://api.simkl.com"),
    private val traktAuth: String = "https://auth.trakt.tv",
) : WatchTracking {
    @Serializable private data class Session(val clientId: String, val token: String, val refresh: String = "",
        val expiresAtMs: Long = 0, val listBaseline: Set<String> = emptySet(), val activity: String? = null,
        val watchedBaseline: Map<String, Long> = emptyMap(), val remoteListCache: List<MyListEntry>? = null,
        val remoteWatched: Map<String, Long> = emptyMap(), val historyReady: Boolean = false)
    private data class RemoteList(val items: List<MyListEntry>, val activity: String? = null)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = WatchTracker.entries.associateWith { Mutex() }
    private val lastReports = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val mutable = MutableStateFlow(WatchTracker.entries.map { WatchTrackingStatus(it) })
    override val statuses: StateFlow<List<WatchTrackingStatus>> = mutable
    private val network = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private fun key(service: WatchTracker, profile: String) = "tracking.$profile.${service.name}"
    private fun session(service: WatchTracker, profile: String): Session? = store.state.value.settings[key(service, profile)]?.let {
        runCatching { json.decodeFromString<Session>(it) }.getOrNull()
    }
    private suspend fun save(service: WatchTracker, profile: String, value: Session?) {
        store.update { state -> state.copy(settings = if (value == null) state.settings - key(service, profile)
            else state.settings + (key(service, profile) to json.encodeToString(Session.serializer(), value))) }
    }
    private fun status(service: WatchTracker, profile: String, transform: (WatchTrackingStatus) -> WatchTrackingStatus) {
        if (store.state.value.activeProfile != profile) return
        mutable.update { list -> list.map { if (it.service == service) transform(it) else it } }
    }
    init {
        scope.launch { store.state.map { it.activeProfile }.distinctUntilChanged().collect { profile ->
            mutable.value = WatchTracker.entries.map { WatchTrackingStatus(it, connected = session(it, profile) != null) }
        } }
    }

    override suspend fun connect(service: WatchTracker, clientId: String) = locks.getValue(service).withLock {
        require(clientId.trim().matches(Regex("[A-Za-z0-9_-]{8,200}"))) { "Enter your registered public client ID" }
        val profile = store.state.value.activeProfile
        status(service, profile) { it.copy(busy = true, message = null) }
        try {
            val auth = if (service == WatchTracker.TRAKT) traktAuth else endpoints.getValue(service)
            val fields = mapOf("client_id" to clientId.trim()) + if (service == WatchTracker.SIMKL) mapOf("scope" to "media:read media:write") else emptyMap()
            val code = call(service, auth, if (service == WatchTracker.TRAKT) "/oauth/device/code" else "/oauth2/device", fields = fields)
            val device = code.text("device_code") ?: error("The tracker returned no device code")
            val user = code.text("user_code") ?: error("The tracker returned no user code")
            val url = code.text("verification_uri_complete") ?: code.text("verification_uri") ?: code.text("verification_url") ?: error("No verification address")
            val uri = java.net.URI(url)
            require(uri.scheme == "https" && uri.host in if (service == WatchTracker.TRAKT) setOf("trakt.tv", "auth.trakt.tv", "app.trakt.tv") else setOf("simkl.com"))
            status(service, profile) { it.copy(verificationUrl = url, userCode = user, message = "Approve this device in your browser") }
            var interval = code.number("interval").coerceIn(1, 60) * 1000
            val deadline = now() + code.number("expires_in").coerceIn(30, 1800) * 1000
            var connected = false
            while (now() < deadline && store.state.value.activeProfile == profile) {
                delay(interval)
                val token = try {
                    call(service, auth, if (service == WatchTracker.TRAKT) "/oauth/device/token" else "/oauth2/token",
                        fields = mapOf("client_id" to clientId.trim(), (if (service == WatchTracker.TRAKT) "code" else "device_code") to device) +
                            if (service == WatchTracker.SIMKL) mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code") else emptyMap())
                } catch (error: TrackingFailure) {
                    if (error.oauth == "authorization_pending" || service == WatchTracker.TRAKT && error.status == 400) continue
                    if (error.oauth == "slow_down" || error.status == 429) { interval = (interval + 5000).coerceAtMost(60_000); continue }
                    throw error
                }
                val access = token.text("access_token") ?: continue
                save(service, profile, Session(clientId.trim(), access, token.text("refresh_token").orEmpty(), expiry(token)))
                connected = true
                break
            }
            check(connected) { "Sign-in expired or the viewer profile changed. Start again." }
            status(service, profile) { it.copy(connected = true, verificationUrl = null, userCode = null, message = "Connected. Sync to restore your library.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { status(service, profile) { it.copy(message = safeError(error), verificationUrl = null, userCode = null) } }
        finally { status(service, profile) { it.copy(busy = false) } }
    }

    override suspend fun disconnect(service: WatchTracker) = locks.getValue(service).withLock {
        val profile = store.state.value.activeProfile
        val saved = session(service, profile)
        if (saved != null) runCatching {
            call(service, if (service == WatchTracker.TRAKT) traktAuth else endpoints.getValue(service),
                if (service == WatchTracker.TRAKT) "/oauth/revoke" else "/oauth2/revoke",
                fields = mapOf("client_id" to saved.clientId, "token" to saved.token))
        }
        save(service, profile, null)
        status(service, profile) { WatchTrackingStatus(service) }
    }

    override suspend fun sync(service: WatchTracker) = locks.getValue(service).withLock {
        val profile = store.state.value.activeProfile
        status(service, profile) { it.copy(busy = true, message = null) }
        try {
            // Re-run once with a renewed token if the service answers 401 part-way: every step reads
            // the remote state again first, so a second pass does not repeat a write.
            authorized(service, profile) { saved -> syncOnce(service, profile, saved) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { status(service, profile) { it.copy(message = safeError(error)) } }
        finally { status(service, profile) { it.copy(busy = false) } }
    }

    private suspend fun syncOnce(service: WatchTracker, profile: String, saved: Session) {
        val local = store.state.value.allProfiles()[profile] ?: return
        val remoteLibrary = readList(service, saved)
        val remote = remoteLibrary.items
        val localKeys = local.myList.map { it.key.value }.toSet()
        val remoteKeys = remote.map { it.key.value }.toSet()
        val localDeletes = saved.listBaseline - localKeys
        val remoteDeletes = saved.listBaseline - remoteKeys
        val merged = (local.myList + remote).distinctBy { it.key }.filterNot { it.key.value in localDeletes + remoteDeletes }
        val adds = merged.filter { it.key.value !in remoteKeys }
        val removals = remote.filter { it.key.value in localDeletes }
        writeList(service, saved, adds, remove = false)
        writeList(service, saved, removals, remove = true)
        // What the service holds now, as far as this sync knows: Simkl answers from this cache
        // while its activities are unchanged, and must not be sent the same additions again.
        val remoteNow = (remote.filterNot { it in removals } + adds).distinctBy { it.key }
        val history = readHistory(service, saved, remoteLibrary.activity)
        val playbacks = history + readPlayback(service, saved, local)
        val knownWatches = saved.remoteWatched + history.associate { historyKey(it) to it.updatedAtMs }
        val sentHistory = writeHistory(service, saved, local.progress.values.filter { it.completed &&
            it.updatedAtMs > maxOf(saved.watchedBaseline[it.key.value] ?: 0, knownWatches[historyKey(it)] ?: 0) })
        store.update { state ->
            val current = state.allProfiles()[profile] ?: return@update state
            // Preserve edits made while the network was busy.
            val duringDeletes = localKeys - current.myList.map { it.key.value }.toSet()
            val duringAdds = current.myList.filter { it.key.value !in localKeys }
            val list = (duringAdds + merged).distinctBy { it.key }.filterNot { it.key.value in duringDeletes }
            val progress = current.progress.toMutableMap()
            playbacks.forEach { p -> if ((progress[p.key.value]?.updatedAtMs ?: 0) < p.updatedAtMs) progress[p.key.value] = p }
            val updated = current.copy(myList = list, progress = progress)
            state.copy(profiles = state.allProfiles() + (profile to updated),
                myList = if (state.activeProfile == profile) list else state.myList,
                progress = if (state.activeProfile == profile) progress else state.progress)
        }
        save(service, profile, saved.copy(listBaseline = merged.map { it.key.value }.toSet(),
            watchedBaseline = saved.watchedBaseline + history.associate { it.key.value to it.updatedAtMs } + sentHistory,
            remoteListCache = remoteNow, activity = remoteLibrary.activity, remoteWatched = knownWatches, historyReady = true))
        status(service, profile) { it.copy(connected = true, message = "Synced ${merged.size} saved titles and ${playbacks.size} resume points") }
    }

    override suspend fun report(progress: WatchProgress, action: String) {
        if (action !in setOf("start", "pause", "stop") || progress.key.namespace == "trailer") return
        val profile = store.state.value.activeProfile
        for (service in WatchTracker.entries) {
            if (session(service, profile) == null) continue
            locks.getValue(service).withLock {
                val saved = try { freshSession(service, profile) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { status(service, profile) { it.copy(message = safeError(error)) }; return@withLock }
                val reportKey = "${service.name}:$profile"
                val identity = "${progress.key}:$action"
                val last = lastReports[reportKey]
                if (last?.first == identity && now() - last.second < 5 * 60_000) return@withLock
                val summary = progress.summary ?: return@withLock
                if (summary.ids.isEmpty) return@withLock
                val body = buildJsonObject {
                    put("progress", (progress.fraction * 100).toDouble())
                    if (progress.key.kind == WatchKey.Kind.EPISODE) {
                        putJsonObject("show") { put("ids", ids(summary.ids)); put("title", summary.name) }
                        putJsonObject("episode") { put("season", progress.key.season); put("number", progress.key.episode) }
                    } else putJsonObject("movie") { put("ids", ids(summary.ids)); put("title", summary.name) }
                }
                try {
                    authorized(service, profile, saved) { session -> request(service, endpoints.getValue(service), "/scrobble/$action", session, body) }
                    lastReports[reportKey] = identity to now()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { status(service, profile) { it.copy(message = safeError(error)) } }
            }
        }
    }

    private suspend fun readList(service: WatchTracker, saved: Session): RemoteList {
        val base = endpoints.getValue(service)
        if (service == WatchTracker.TRAKT) {
            val result = mutableListOf<MyListEntry>()
            var page = 1
            do {
                val array = request(service, base, "/sync/watchlist?extended=full&page=$page&limit=100", saved).jsonArray
                result += array.mapNotNull { row ->
                    val item = row.jsonObject
                    summary(item["movie"] ?: item["show"], item["show"] != null)?.let { MyListEntry(it.key, timestamp(item.text("listed_at")), it) }
                }
                page++
            } while (array.size == 100 && page <= 100)
            return RemoteList(result)
        }
        // Simkl requires the inexpensive activities check before watchlist reads.
        val activity = request(service, base, "/sync/activities", saved).toString()
        if (activity == saved.activity && saved.remoteListCache != null) return RemoteList(saved.remoteListCache, activity)
        val response = request(service, base, "/sync/all-items?extended=full", saved).jsonObject
        val items = listOf("movies", "shows", "anime").flatMap { type ->
            (response[type] as? JsonArray).orEmpty().mapNotNull { row ->
                val item = row.jsonObject
                if (item.text("status") != "plantowatch") return@mapNotNull null
                summary(item["movie"] ?: item["show"] ?: item["anime"], type != "movies")?.let { MyListEntry(it.key, timestamp(item.text("added_to_watchlist_at")), it) }
            }
        }
        return RemoteList(items, activity)
    }

    /** Called under the provider mutex by sync and scrobbling, so refresh tokens cannot race. */
    private suspend fun freshSession(service: WatchTracker, profile: String): Session {
        val saved = requireNotNull(session(service, profile)) { "Connect an account first" }
        return if (saved.expiresAtMs <= now() + 60_000 && saved.refresh.isNotBlank()) renew(service, profile, saved) else saved
    }

    /**
     * Runs [block] with a fresh session; if the service still answers 401 (a token revoked or
     * replaced elsewhere before its expiry), renews once and runs it again. Under the provider mutex.
     */
    private suspend fun <T> authorized(service: WatchTracker, profile: String, first: Session? = null, block: suspend (Session) -> T): T {
        val saved = first ?: freshSession(service, profile)
        return try { block(saved) } catch (failure: TrackingFailure) {
            if (failure.status != 401) throw failure
            block(renew(service, profile, saved))
        }
    }

    /**
     * Swaps the refresh token for a new access token and saves both. Simkl returns the same refresh
     * token and ends the previous access token at once, so the new pair is saved before anything
     * else is sent. A refusal (400/401: the grant was revoked or expired) signs this viewer out of
     * the service and keeps the local library; a network failure leaves the session for next time.
     */
    private suspend fun renew(service: WatchTracker, profile: String, saved: Session): Session {
        if (saved.refresh.isBlank()) signOut(service, profile)
        val token = try {
            call(service, if (service == WatchTracker.TRAKT) traktAuth else endpoints.getValue(service),
                if (service == WatchTracker.TRAKT) "/oauth/token" else "/oauth2/token",
                fields = mapOf("grant_type" to "refresh_token", "client_id" to saved.clientId, "refresh_token" to saved.refresh))
        } catch (failure: TrackingFailure) {
            if (failure.status == 400 || failure.status == 401) signOut(service, profile)
            throw failure
        }
        val renewed = saved.copy(token = token.text("access_token") ?: signOut(service, profile),
            refresh = token.text("refresh_token") ?: saved.refresh, expiresAtMs = expiry(token))
        save(service, profile, renewed)
        return renewed
    }

    private suspend fun signOut(service: WatchTracker, profile: String): Nothing {
        save(service, profile, null)
        val failure = SignedOut(service)
        status(service, profile) { WatchTrackingStatus(service, message = failure.message) }
        throw failure
    }

    private suspend fun writeList(service: WatchTracker, saved: Session, items: List<MyListEntry>, remove: Boolean) {
        for (group in items.chunked(100)) {
            if (group.isEmpty()) continue
            val body = buildJsonObject {
                group.groupBy { if (it.summary.type == TitleType.FILM) "movies" else "shows" }.forEach { (type, titles) ->
                    putJsonArray(type) { titles.forEach { title -> add(buildJsonObject {
                        put("ids", ids(title.summary.ids)); put("title", title.summary.name); title.summary.year?.let { put("year", it) }
                        if (service == WatchTracker.SIMKL && !remove) put("to", "plantowatch")
                    }) } }
                }
            }
            val path = if (service == WatchTracker.TRAKT) "/sync/watchlist" + if (remove) "/remove" else ""
                else if (remove) "/sync/history/remove" else "/sync/add-to-list"
            request(service, endpoints.getValue(service), path, saved, body)
        }
    }

    private suspend fun readPlayback(service: WatchTracker, saved: Session, local: WatchProfile): List<WatchProgress> {
        val response = request(service, endpoints.getValue(service), "/sync/playback?extended=full", saved)
        val rows = (response as? JsonArray) ?: (response as? JsonObject)?.values?.flatMap { (it as? JsonArray).orEmpty() }?.let(::JsonArray) ?: return emptyList()
        return rows.mapNotNull { element ->
            val row = element.jsonObject
            val series = row["show"] != null
            val summary = summary(row["movie"] ?: row["show"], series) ?: return@mapNotNull null
            val episode = row["episode"] as? JsonObject
            val key = if (episode != null) WatchKey.episode(summary.key.namespace, summary.key.id,
                episode.number("season").toInt(), episode.number("number").toInt()) else summary.key
            val duration = local.progress[key.value]?.durationMs?.takeIf { it > 0 }
                ?: ((episode?.number("runtime") ?: summary.runtimeMin?.toLong() ?: 0) * 60_000).takeIf { it > 0 } ?: return@mapNotNull null
            val fraction = (row["progress"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return@mapNotNull null
            WatchProgress(key, (duration * fraction / 100).toLong(), duration, timestamp(row.text("paused_at")), summary, episode?.text("title"))
        }
    }

    private suspend fun readHistory(service: WatchTracker, saved: Session, activity: String?): List<WatchProgress> {
        if (service == WatchTracker.SIMKL && activity == saved.activity && saved.historyReady) return emptyList()
        val base = endpoints.getValue(service)
        val output = mutableListOf<WatchProgress>()
        if (service == WatchTracker.TRAKT) {
            for ((kind, series) in listOf("movies" to false, "shows" to true)) {
                val rows = request(service, base, "/sync/watched/$kind?extended=full", saved).jsonArray
                for (element in rows) {
                    val row = element.jsonObject
                    val card = summary(row[if (series) "show" else "movie"], series) ?: continue
                    if (!series) output += WatchProgress(card.key, 0, 0, timestamp(row.text("last_watched_at")), card, watched = true)
                    else output += watchedEpisodes(row, card)
                }
            }
        } else {
            // The preceding readList checked activities; unchanged histories are skipped above.
            val data = request(service, base, "/sync/all-items?extended=full&episode_watched_at=yes&include_all_episodes=original", saved).jsonObject
            for (kind in listOf("movies", "shows", "anime")) for (element in (data[kind] as? JsonArray).orEmpty()) {
                val row = element.jsonObject
                val card = summary(row["movie"] ?: row["show"] ?: row["anime"], kind != "movies") ?: continue
                if (kind == "movies" && row.text("status") == "completed")
                    output += WatchProgress(card.key, 0, 0, timestamp(row.text("last_watched_at")), card, watched = true)
                else output += watchedEpisodes(row, card)
            }
        }
        return output
    }

    private fun watchedEpisodes(row: JsonObject, card: TitleSummary): List<WatchProgress> = (row["seasons"] as? JsonArray).orEmpty().flatMap { element ->
        val season = element.jsonObject
        val number = season.number("number").toInt()
        (season["episodes"] as? JsonArray).orEmpty().mapNotNull { episode ->
            val item = episode.jsonObject
            val n = item.number("number").toInt()
            if (n <= 0 || number < 0) return@mapNotNull null
            val key = WatchKey.episode(card.key.namespace, card.key.id, number, n)
            WatchProgress(key, 0, 0, timestamp(item.text("last_watched_at") ?: item.text("watched_at") ?: row.text("last_watched_at")), card, watched = true)
        }
    }

    private suspend fun writeHistory(service: WatchTracker, saved: Session, progress: List<WatchProgress>): Map<String, Long> {
        val sent = mutableMapOf<String, Long>()
        for (group in progress.filter { it.summary?.ids?.isEmpty == false }.chunked(100)) {
            val body = buildJsonObject {
                val movies = group.filter { it.key.kind == WatchKey.Kind.FILM }
                if (movies.isNotEmpty()) putJsonArray("movies") { movies.forEach { p -> add(buildJsonObject {
                    put("ids", ids(p.summary!!.ids)); put("watched_at", Instant.ofEpochMilli(p.updatedAtMs).toString())
                }) } }
                val shows = group.filter { it.key.kind == WatchKey.Kind.EPISODE }.groupBy { it.key.seriesKey }
                if (shows.isNotEmpty()) putJsonArray("shows") { shows.values.forEach { episodes -> add(buildJsonObject {
                    put("ids", ids(episodes.first().summary!!.ids))
                    putJsonArray("seasons") { episodes.groupBy { it.key.season }.forEach { (season, items) -> add(buildJsonObject {
                        put("number", season); putJsonArray("episodes") { items.forEach { p -> add(buildJsonObject {
                            put("number", p.key.episode); put("watched_at", Instant.ofEpochMilli(p.updatedAtMs).toString())
                        }) } }
                    }) } }
                }) } }
            }
            if (body.isEmpty()) continue
            val response = request(service, endpoints.getValue(service), "/sync/history", saved, body).jsonObject
            val missing = (response["not_found"] as? JsonObject)?.values?.flatMap { (it as? JsonArray).orEmpty() }.orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("ids") as? JsonObject }
            group.filterNot { p -> missing.any { id -> id.text("imdb") == p.summary?.ids?.imdb && p.summary?.ids?.imdb != null ||
                id["tmdb"]?.jsonPrimitive?.intOrNull == p.summary?.ids?.tmdb && p.summary?.ids?.tmdb != null } }
                .forEach { sent[it.key.value] = it.updatedAtMs }
        }
        return sent
    }

    private fun summary(element: JsonElement?, series: Boolean): TitleSummary? {
        val value = element as? JsonObject ?: return null
        val id = value["ids"] as? JsonObject ?: return null
        val imdb = id.text("imdb")?.takeIf { it.matches(Regex("tt[0-9]+")) }
        val tmdb = id["tmdb"]?.jsonPrimitive?.intOrNull
        val namespace = if (tmdb != null) "tmdb" else "imdb"
        val identity = tmdb?.toString() ?: imdb ?: return null
        val key = if (series) WatchKey.series(namespace, identity) else WatchKey.film(namespace, identity)
        return TitleSummary(key, if (series) TitleType.SERIES else TitleType.FILM, value.text("title") ?: return null,
            year = value["year"]?.jsonPrimitive?.intOrNull, runtimeMin = value["runtime"]?.jsonPrimitive?.intOrNull,
            ids = ExternalIds(tmdb, imdb, id["tvdb"]?.jsonPrimitive?.intOrNull), origin = "tracker")
    }
    private fun ids(value: ExternalIds) = buildJsonObject {
        value.tmdb?.let { put("tmdb", it) }; value.imdb?.let { put("imdb", it) }; value.tvdb?.let { put("tvdb", it) }
        value.mal?.let { put("mal", it) }
    }
    private fun historyKey(value: WatchProgress): String =
        (value.summary?.ids?.imdb ?: value.summary?.ids?.tmdb?.let { "tmdb:$it" } ?: value.key.id) +
            if (value.key.kind == WatchKey.Kind.EPISODE) ":${value.key.season}:${value.key.episode}" else ":film"
    private fun expiry(token: JsonObject) = now() + token.number("expires_in").takeIf { it > 0 }?.times(1000).let { it ?: 7L * 86_400_000 }
    private fun timestamp(value: String?) = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0
    private fun safeError(error: Exception) = if (error is SignedOut) error.message.orEmpty() else if (error is TrackingFailure) "Tracker request failed (${error.status}${error.oauth?.let { ", $it" }.orEmpty()}). Your local library is kept." else "Could not sync. Check your connection and client ID, then retry."
    private class SignedOut(service: WatchTracker) : IOException("${service.label} sign-in expired. Connect again; your library on this device is kept.")
    private class TrackingFailure(val status: Int, code: String?) : IOException("Tracker request failed ($status)") {
        val oauth = code?.takeIf { it.matches(Regex("[a-z_]{1,80}")) }
    }

    private suspend fun call(service: WatchTracker, base: String, path: String, fields: Map<String, String>): JsonObject =
        request(service, base, path, fields = fields).jsonObject

    private suspend fun request(service: WatchTracker, base: String, path: String, session: Session? = null,
        body: JsonObject? = null, fields: Map<String, String>? = null): JsonElement {
        val builder = Request.Builder().url(base.trimEnd('/') + path).header("User-Agent", "Cosmotune")
        val id = session?.clientId ?: fields?.get("client_id")
        if (id != null) builder.header(if (service == WatchTracker.TRAKT) "trakt-api-key" else "simkl-api-key", id)
        if (service == WatchTracker.TRAKT) builder.header("trakt-api-version", "2")
        session?.let { builder.header("Authorization", "Bearer ${it.token}") }
        when {
            fields != null && service == WatchTracker.SIMKL -> builder.post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
            fields != null -> builder.post(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString().toRequestBody("application/json".toMediaType()))
            body != null -> builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        }
        return suspendCancellableCoroutine { continuation ->
          val call = network.newCall(builder.build())
          continuation.invokeOnCancellation { call.cancel() }
          call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
              try {
               val result = response.use {
                val stream = response.body.byteStream()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4 * 1024 * 1024) { "Tracker response is too large" }
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                val data = bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) } ?: JsonObject(emptyMap())
                if (!response.isSuccessful) throw TrackingFailure(response.code, (data as? JsonObject)?.text("error"))
                data
               }
               if (continuation.isActive) continuation.resume(result)
              } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
          })
        }
    }
}
