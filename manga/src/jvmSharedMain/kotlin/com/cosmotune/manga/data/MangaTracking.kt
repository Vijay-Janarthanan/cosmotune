package com.cosmotune.manga.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A reading list the listener signed in to: the service, the name shown, and the session it handed back (never the password). */
@Serializable data class MangaTrackerAccount(val service: String, val user: String, val token: String, val refresh: String = "", val expiresAtMs: Long = 0)

/** A series linked to its entry on a reading list: the entry's id and title there, and the last chapter count sent to it. */
@Serializable data class MangaTrack(val service: String, val remoteId: String, val title: String, val chapter: Int = 0, val url: String = "")

/** One search hit on a reading list, to link a series to. */
data class MangaTrackerMatch(val remoteId: String, val title: String, val detail: String, val cover: String?, val url: String)

/** The reading lists Cosmotune can keep up to date. */
object MangaTrackers {
    const val MANGAUPDATES = "mangaupdates"
    const val KOMGA = "komga"
    const val ANILIST = "anilist"
    const val MYANIMELIST = "myanimelist"
    /** The lists a series can be linked to, in the order they are offered. */
    val lists = listOf(MANGAUPDATES, ANILIST, MYANIMELIST)
    fun name(service: String): String = when (service) { MANGAUPDATES -> "MangaUpdates"; KOMGA -> "Komga"; ANILIST -> "AniList"; MYANIMELIST -> "MyAnimeList"; else -> service }
}

/**
 * The chapter count a reading list should show for [book]: the highest whole chapter number among its read chapters (a source
 * that numbers nothing counts the read chapters instead). Half chapters do not count past the whole one before them.
 */
fun mangaTrackedChapter(book: MangaBook, read: Set<String>): Int {
    val done = book.chapters.filter { it.id in read }
    if (done.isEmpty()) return 0
    val numbered = done.filter { it.number > 0f }
    return if (numbered.isEmpty()) done.size else numbered.maxOf { it.number }.toInt()
}

/** How well a reading-list title matches a series title, 0 to 1: equal ignoring case and punctuation is 1. */
fun mangaTitleScore(a: String, b: String): Double {
    fun words(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotBlank() }.toSet()
    val left = words(a); val right = words(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    return left.intersect(right).size.toDouble() / left.union(right).size
}

/**
 * MangaUpdates (Baka-Updates Manga) reading lists, through its public API: the listener signs in with their own account, the
 * session it returns is kept (the password is not), and finished chapters move the series' chapter count on their Reading list.
 */
class MangaUpdatesTracker(private val client: OkHttpClient, private val base: String = "https://api.mangaupdates.com/v1") {
    private val json = Json { ignoreUnknownKeys = true }
    private val type = "application/json".toMediaType()

    suspend fun signIn(user: String, password: String): MangaTrackerAccount {
        require(user.isNotBlank() && password.isNotBlank()) { "Enter your MangaUpdates user name and password" }
        val body = buildJsonObject { put("username", user.trim()); put("password", password) }
        val answer = call("PUT", "account/login", body, token = null, signIn = true).jsonObject
        val token = answer["context"]?.jsonObject?.get("session_token")?.jsonPrimitive?.contentOrNull
            ?: error(answer["reason"]?.jsonPrimitive?.contentOrNull ?: "MangaUpdates did not accept this sign-in")
        return MangaTrackerAccount(MangaTrackers.MANGAUPDATES, user.trim(), token)
    }

    suspend fun search(account: MangaTrackerAccount, title: String): List<MangaTrackerMatch> {
        val body = buildJsonObject { put("search", title.take(200)); put("perpage", 10) }
        return call("POST", "series/search", body, account.token).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { hit ->
            val record = hit.jsonObject["record"]?.jsonObject ?: return@mapNotNull null
            val id = record["series_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = record["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val detail = listOfNotNull(record["type"]?.jsonPrimitive?.contentOrNull, record["year"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }).joinToString(" · ")
            val cover = record["image"]?.jsonObject?.get("url")?.jsonObject?.get("thumb")?.jsonPrimitive?.contentOrNull
            MangaTrackerMatch(id, org.jsoup.Jsoup.parse(name).text(), detail, cover, record["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    /** Puts the series on the Reading list (when it is on none) and returns the chapter count the list holds. */
    suspend fun link(account: MangaTrackerAccount, remoteId: String): Int {
        val current = entry(account, remoteId)
        if (current != null) return current
        call("POST", "lists/series", buildJsonArray { add(buildJsonObject { put("series", buildJsonObject { put("id", remoteId.toLong()) }); put("list_id", 0) }) }, account.token)
        return 0
    }

    suspend fun setChapter(account: MangaTrackerAccount, remoteId: String, chapter: Int) {
        val list = listId(account, remoteId) ?: run { link(account, remoteId); 0 }
        call("POST", "lists/series/update", buildJsonArray { add(buildJsonObject {
            put("series", buildJsonObject { put("id", remoteId.toLong()) }); put("list_id", list)
            put("status", buildJsonObject { put("chapter", chapter) })
        }) }, account.token)
    }

    private suspend fun entry(account: MangaTrackerAccount, remoteId: String): Int? =
        listItem(account, remoteId)?.let { item -> (item["status"] as? JsonObject)?.get("chapter")?.jsonPrimitive?.intOrNull ?: 0 }

    private suspend fun listId(account: MangaTrackerAccount, remoteId: String): Int? = listItem(account, remoteId)?.get("list_id")?.jsonPrimitive?.intOrNull

    private suspend fun listItem(account: MangaTrackerAccount, remoteId: String): JsonObject? = try {
        call("GET", "lists/series/${remoteId.toLong()}", null, account.token).jsonObject
    } catch (missing: NotOnList) { null }

    private class NotOnList : IllegalStateException("Not on a list")

    private suspend fun call(method: String, path: String, body: JsonElement?, token: String?, signIn: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        val url = (base.trimEnd('/') + "/" + path).toHttpUrl()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .method(method, body?.toString()?.toRequestBody(type)).build()
        client.newBuilder().followRedirects(false).build().newCall(request).execute().use { response ->
            if (response.code == 404 && method == "GET") throw NotOnList()
            if (response.code == 401 || response.code == 403) throw MangaSourceFailure(if (signIn) "MangaUpdates did not accept this user name and password" else "MangaUpdates signed you out. Sign in again in Settings › Manga.")
            check(response.isSuccessful) { "MangaUpdates returned HTTP ${response.code}" }
            val bytes = response.body.byteStream().use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024) { "MangaUpdates answered with too much data" }
            if (bytes.isEmpty()) JsonObject(emptyMap()) else json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        }
    }
}

/**
 * Keeps the reading lists up to date: after a chapter is finished, each linked list gets the series' chapter count, and a Komga
 * series marks the finished book read on the listener's server. Failures stay quiet here (the next finished chapter sends the
 * count again); [MangaLibraryState.trackFailures] says which series could not be sent.
 */
class MangaTracking(private val store: MangaStore, client: OkHttpClient, internal val mangaUpdates: MangaUpdatesTracker = MangaUpdatesTracker(client),
    internal val lists: MangaListApi = MangaListApi(client), private val signIns: MangaListSignIn = MangaTrackerLinks.signIn,
    private val server: suspend (MangaSourceConfig, String) -> Unit = { config, chapter ->
        when (config.kind) { "komga" -> KomgaSource(config, client).markRead(chapter); "suwayomi" -> SuwayomiSource(config, client).markRead(chapter) }
    }) {

    suspend fun signIn(user: String, password: String) {
        val account = mangaUpdates.signIn(user, password)
        store.update { it.copy(trackerAccounts = it.trackerAccounts + (account.service to account)) }
    }

    private val signInMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** How the last AniList or MyAnimeList sign-in went, in the listener's words, while it is news. */
    val signInNews: kotlinx.coroutines.flow.StateFlow<String?> = signInMessage

    /** The AniList or MyAnimeList sign-in page to open in the browser; null without a client id. */
    fun signInPage(service: MangaListService): String? { signInMessage.value = null; return signIns.start(service) }

    /** Finishes a sign-in [back] from the browser and keeps the account. */
    suspend fun finish(back: MangaListReturn) {
        signInMessage.value = try {
            val account = lists.finish(back, signIns.clientId(back.service) ?: error("Sign-in needs a client id"))
            store.update { it.copy(trackerAccounts = it.trackerAccounts + (account.service to account)) }
            "Signed in to ${back.service.label} as ${account.user}"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failure.message ?: "${back.service.label} could not be reached" }
    }

    /** The account for [service], renewed when it has expired (and kept); null when it is signed out or cannot be renewed. */
    private suspend fun account(service: String): MangaTrackerAccount? {
        val saved = store.state.value.trackerAccounts[service] ?: return null
        if (service == MangaTrackers.MANGAUPDATES) return saved
        val fresh = lists.fresh(saved, MangaListService.of(service)?.let(signIns::clientId)) ?: return null
        if (fresh != saved) store.update { it.copy(trackerAccounts = it.trackerAccounts + (service to fresh)) }
        return fresh
    }

    private suspend fun listed(account: MangaTrackerAccount, remoteId: String): Int =
        if (account.service == MangaTrackers.MANGAUPDATES) mangaUpdates.link(account, remoteId) else lists.listed(account, remoteId)

    private suspend fun setChapter(account: MangaTrackerAccount, remoteId: String, chapter: Int) =
        if (account.service == MangaTrackers.MANGAUPDATES) mangaUpdates.setChapter(account, remoteId, chapter) else lists.setChapter(account, remoteId, chapter)

    suspend fun signOut(service: String) = store.update { old ->
        old.copy(trackerAccounts = old.trackerAccounts - service, tracks = old.tracks.mapValues { (_, list) -> list.filterNot { it.service == service } }.filterValues { it.isNotEmpty() })
    }

    suspend fun search(title: String, service: String = MangaTrackers.MANGAUPDATES): List<MangaTrackerMatch> {
        val account = account(service) ?: error("Sign in to ${MangaTrackers.name(service)} first")
        val found = if (service == MangaTrackers.MANGAUPDATES) mangaUpdates.search(account, title) else lists.search(account, title)
        return found.sortedByDescending { mangaTitleScore(it.title, title) }
    }

    /** Links [book] to [match] on [service] and sends its chapter count when Cosmotune has read further than the list says. */
    suspend fun link(book: MangaBook, match: MangaTrackerMatch, service: String = MangaTrackers.MANGAUPDATES) {
        val account = account(service) ?: error("Sign in to ${MangaTrackers.name(service)} first")
        store.save(book)
        val listed = listed(account, match.remoteId)
        val read = mangaTrackedChapter(book, store.state.value.readChapters)
        if (MangaListApi.shouldSend(read, listed)) setChapter(account, match.remoteId, read)
        val track = MangaTrack(service, match.remoteId, match.title, maxOf(read, listed), match.url)
        store.update { old -> old.copy(tracks = old.tracks + (book.id to (old.tracks[book.id].orEmpty().filterNot { it.service == track.service } + track))) }
    }

    suspend fun unlink(bookId: String, service: String) = store.update { old ->
        val left = old.tracks[bookId].orEmpty().filterNot { it.service == service }
        old.copy(tracks = if (left.isEmpty()) old.tracks - bookId else old.tracks + (bookId to left))
    }

    /**
     * Watches the library: chapters that become read (in the reader, on a series page, from a backup or another device) send
     * their series on. The state when watching starts is the baseline, so opening the app sends nothing.
     */
    fun watch(scope: kotlinx.coroutines.CoroutineScope) = scope.launch {
        launch { MangaTrackerLinks.received.collect { back -> finish(back) } }
        var known = store.state.value.readChapters
        store.state.collect { state ->
            val added = state.readChapters - known
            known = state.readChapters
            if (added.isEmpty()) return@collect
            val chapters = state.books.flatMap { book -> book.chapters.filter { it.id in added } }
            chapters.groupBy { it.bookId }.forEach { (bookId, read) ->
                try { send(bookId, read) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
        }
    }

    /** Sends a series on after [read] of its chapters were finished: the new chapter count to each linked list, read marks to Komga. */
    suspend fun send(bookId: String, read: List<MangaChapter>) {
        val state = store.state.value
        val book = state.books.find { it.id == bookId } ?: return
        val failures = ArrayList<String>()
        if ((book.sourceId.startsWith("komga:") || book.sourceId.startsWith("suwayomi:")) && state.serverWriteBack) {
            state.sources.find { it.id == book.sourceId }?.let { config ->
                read.take(200).forEach { chapter ->
                    try { withContext(Dispatchers.IO) { server(config, chapter.url) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { failures += MangaTrackers.KOMGA }
                }
            }
        }
        val count = mangaTrackedChapter(book, state.readChapters)
        val links = state.tracks[book.id].orEmpty()
        val sent = links.map { track ->
            if (state.trackerAccounts[track.service] == null || count <= track.chapter) track
            else try { setChapter(account(track.service) ?: error("Signed out"), track.remoteId, count); track.copy(chapter = count) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += track.service; track }
        }
        if (links.isEmpty() && failures.isEmpty() && book.id !in state.trackFailures) return
        store.update { old -> old.copy(
            tracks = if (sent.isEmpty() || old.tracks[book.id] == null) old.tracks else old.tracks + (book.id to sent),
            trackFailures = if (failures.isEmpty()) old.trackFailures - book.id else old.trackFailures + book.id,
        ) }
    }
}
