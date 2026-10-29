package com.cosmotune.video.account

import com.cosmotune.music.data.DebugLog as Log
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [VideoAccount] over the YouTube site's youtubei API (WEB client), signed with the Google session Music already holds
 * (`Innertube.cookie`: SAPISIDHASH over https://www.youtube.com plus the account scope), so one sign-in serves both modes.
 *
 * Reads: what-to-watch (`FEwhat_to_watch`), subscriptions (`FEsubscriptions`), channels (`FEchannels`, then the guide), playlists
 * (`FEplaylist_aggregation`, then `FElibrary`), a playlist (`VL<id>`, Watch Later `VLWL`, Liked `VLLL`), history (`FEhistory`) and
 * their continuations. Writes: `like/like|dislike|removelike`, `subscription/subscribe|unsubscribe`, `browse/edit_playlist`.
 *
 * Honesty about verification: the item shapes were checked against real anonymous answers; the signed-in endpoints, their answers
 * and the write calls could not be exercised without an account and are NOT live-verified. A shape that cannot be read is
 * reported as [AccountFailure.Changed] and logged by shape name, never as a crash. This borrows the web session the way the
 * site's own pages use it; Google can change or refuse that at any time.
 *
 * Every first page is cached in memory for [CACHE_MS]; at most [CONCURRENCY] requests are in flight; a continuation is only
 * followed when the caller asks for it, except that an empty page with a token (a page of ads) is followed up to
 * [EMPTY_HOPS] times and a whole-list call (channels, playlists) reads at most [LIST_PAGES] pages.
 */
class InnerTubeVideoAccount internal constructor(
    private val web: YoutubeWeb,
    private val cookie: StateFlow<String?>,
    private val hosts: StateFlow<AccountHost?>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : VideoAccount {
    /** The process-wide account: the Innertube session and the installed host. */
    constructor() : this(
        InnertubeWeb,
        Innertube.cookieState,
        AccountHostRegistry.host,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        System::currentTimeMillis,
    )

    private val gate = Semaphore(CONCURRENCY)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val subscribed = MutableStateFlow<Set<String>>(emptySet())

    private class Cached(val atMs: Long, val value: Any)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<AccountState> = combine(
        cookie,
        hosts.flatMapLatest { it?.identity ?: flowOf(null) },
    ) { c, identity -> stateOf(c, identity) }
        .stateIn(scope, SharingStarted.Eagerly, stateOf(cookie.value, hosts.value?.identity?.value))

    override val subscribedIds: StateFlow<Set<String>> = subscribed.asStateFlow()

    init {
        // Whatever was learned belongs to the account that was active: drop it when the account changes or goes.
        scope.launch {
            state.map { (it as? AccountState.SignedIn)?.active?.id to (it is AccountState.SignedIn) }
                .distinctUntilChanged()
                .drop(1)
                .collect { invalidate(); subscribed.value = emptySet() }
        }
    }

    private fun stateOf(cookie: String?, identity: HostIdentity?): AccountState {
        if (cookie == null || Innertube.webAuthorization(cookie) == null) return AccountState.SignedOut
        val active = identity?.accounts?.firstOrNull { it.id == identity.activeId }
        return AccountState.SignedIn(
            displayName = identity?.displayName.orEmpty(),
            handle = identity?.handle,
            avatarUrl = identity?.avatarUrl,
            accounts = identity?.accounts.orEmpty(),
            active = active,
        )
    }

    private val signedIn: Boolean get() = state.value is AccountState.SignedIn

    override suspend fun signInRequest() {
        hosts.value?.openSignIn()
    }

    override suspend fun switchAccount(id: String): Result<Unit> = attempt {
        val host = hosts.value ?: throw AccountFailure.Changed("No host to switch accounts")
        if (!host.switchAccount(id)) throw AccountFailure.Changed("Unknown account")
        invalidate()
    }

    override suspend fun signOut() {
        hosts.value?.signOut()
        invalidate()
    }

    override fun invalidate() {
        cache.clear()
    }

    // ---- Reading ------------------------------------------------------------------------------------------------------

    override suspend fun home(continuation: PageToken?): Result<Page<VideoItem>> =
        videoPage("home", "FEwhat_to_watch", continuation)

    override suspend fun subscriptionsFeed(continuation: PageToken?): Result<Page<VideoItem>> =
        videoPage("subs", "FEsubscriptions", continuation)

    override suspend fun history(continuation: PageToken?): Result<Page<VideoItem>> =
        videoPage("history", "FEhistory", continuation)

    override suspend fun playlist(id: String, continuation: PageToken?): Result<Page<VideoItem>> {
        val bare = id.removePrefix("VL")
        if (bare.isBlank()) return Result.failure(AccountFailure.Changed("Empty playlist id"))
        return videoPage("pl:$bare", "VL$bare", continuation)
    }

    override suspend fun subscribedChannels(): Result<List<ChannelItem>> = attempt {
        requireSession()
        val list = cached<List<ChannelItem>>("channels") ?: run {
            var channels = readAll("FEchannels") { it.channels }
            if (channels.isEmpty()) {
                // The channels page can come back as something this parser does not know; the guide lists the same channels.
                val guide = call("guide") {}
                channels = YoutubeFeedParser.parseGuideChannels(guide)
            }
            channels.also { store("channels", it) }
        }
        subscribed.value = list.mapTo(HashSet()) { it.id }
        list
    }

    override suspend fun playlists(): Result<List<PlaylistItem>> = attempt {
        requireSession()
        cached<List<PlaylistItem>>("playlists") ?: run {
            var found = readAll("FEplaylist_aggregation") { it.playlists }
            if (found.isEmpty()) found = readAll("FElibrary") { it.playlists }
            found.filter { it.id != VideoAccount.WATCH_LATER && it.id != VideoAccount.LIKED }.also { store("playlists", it) }
        }
    }

    // ---- Writing ------------------------------------------------------------------------------------------------------

    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> = attempt {
        requireSession()
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        write(endpoint) {
            putJsonArray("channelIds") { add(channelId) }
            // The two `params` the site's own buttons send for these calls.
            put("params", if (subscribed) "EgIIAhgA" else "CgIIAhgA")
        }
        this.subscribed.update { if (subscribed) it + channelId else it - channelId }
        cache.remove("channels")
        cache.remove("subs")
        cache.remove("home")
    }

    override suspend fun rate(videoId: String, rating: Rating): Result<Unit> = attempt {
        requireSession()
        val endpoint = when (rating) {
            Rating.LIKE -> "like/like"
            Rating.DISLIKE -> "like/dislike"
            Rating.NONE -> "like/removelike"
        }
        write(endpoint) { putJsonObject("target") { put("videoId", videoId) } }
        cache.remove("pl:${VideoAccount.LIKED}")
    }

    override suspend fun addToPlaylist(playlistId: String, videoId: String): Result<Unit> = attempt {
        requireSession()
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_ADD_VIDEO")
                put("addedVideoId", videoId)
            }
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, videoId: String): Result<Unit> = attempt {
        requireSession()
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                put("removedVideoId", videoId)
            }
        }
    }

    private suspend fun editPlaylist(playlistId: String, actions: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit) {
        val bare = playlistId.removePrefix("VL")
        val response = write("browse/edit_playlist") {
            put("playlistId", bare)
            putJsonArray("actions", actions)
        }
        val status = (response["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (status != null && status != "STATUS_SUCCEEDED") throw AccountFailure.Changed("Playlist edit refused: $status")
        cache.remove("pl:$bare")
        cache.remove("playlists")
    }

    // ---- Plumbing -----------------------------------------------------------------------------------------------------

    private fun requireSession() {
        if (!signedIn) throw AccountFailure.NotSignedIn()
    }

    /** One call, at most [CONCURRENCY] at a time, an `error` body turned into its failure. */
    private suspend fun call(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
        val response = gate.withPermit { web.post(endpoint, body) }
        YoutubeFeedParser.errorOf(response)?.let { throw it }
        return response
    }

    private suspend fun write(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
        val response = call(endpoint, body)
        if (YoutubeFeedParser.isLoggedOut(response)) throw AccountFailure.SessionExpired()
        return response
    }

    private suspend fun browse(browseId: String?, continuation: String?): YoutubeFeedParser.ParsedFeed {
        val response = call("browse") {
            if (continuation != null) put("continuation", continuation) else put("browseId", browseId.orEmpty())
        }
        val parsed = YoutubeFeedParser.parse(response)
        if (parsed.isEmpty && parsed.loggedOut) throw AccountFailure.SessionExpired()
        if (parsed.looksChanged) throw AccountFailure.Changed("The ${browseId ?: "continued"} list could not be read")
        return parsed
    }

    private suspend fun videoPage(key: String, browseId: String, continuation: PageToken?): Result<Page<VideoItem>> = attempt {
        requireSession()
        if (continuation == null) cached<Page<VideoItem>>(key)?.let { return@attempt it }
        var token = continuation?.value
        var parsed = browse(browseId, token)
        var hops = 0
        // A page of nothing but ads or prompts still has a token; follow it a couple of times, never more.
        while (parsed.videos.isEmpty() && parsed.continuation != null && parsed.continuation != token && hops < EMPTY_HOPS) {
            token = parsed.continuation
            parsed = browse(browseId, token)
            hops += 1
        }
        val next = parsed.continuation?.takeIf { it != token && it != continuation?.value }?.let(::PageToken)
        Page(parsed.videos, next).also { if (continuation == null) store(key, it) }
    }

    /** Every page of a short list (channels, playlists), at most [LIST_PAGES] of them. */
    private suspend fun <T> readAll(browseId: String, pick: (YoutubeFeedParser.ParsedFeed) -> List<T>): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        for (page in 0 until LIST_PAGES) {
            val parsed = try {
                browse(browseId, token)
            } catch (e: AccountFailure.Changed) {
                // The first page failing is the answer; a later page failing keeps what was read.
                if (page == 0) throw e
                break
            }
            out += pick(parsed)
            val next = parsed.continuation
            if (next == null || next == token) break
            token = next
        }
        return out.distinct()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String): T? {
        val hit = cache[key] ?: return null
        if (clock() - hit.atMs > CACHE_MS) {
            cache.remove(key)
            return null
        }
        return hit.value as T
    }

    private fun store(key: String, value: Any) {
        cache[key] = Cached(clock(), value)
    }

    companion object {
        /** How long a first page is served from memory. */
        const val CACHE_MS = 60_000L

        /** Requests in flight at once. */
        const val CONCURRENCY = 2

        /** Extra requests spent on an empty page that has a continuation. */
        const val EMPTY_HOPS = 2

        /** Pages read for a whole list (channels, playlists). */
        const val LIST_PAGES = 5

        /** The process-wide account on the Innertube session and the installed [AccountHost]; one per process. */
        val shared: VideoAccount by lazy { InnerTubeVideoAccount() }
    }
}

/** The account of an app with no sign-in: signed out, every call [AccountFailure.NotSignedIn]. Previews and tests that do not care use it. */
object SignedOutVideoAccount : VideoAccount {
    override val state: StateFlow<AccountState> = MutableStateFlow<AccountState>(AccountState.SignedOut).asStateFlow()
    override val subscribedIds: StateFlow<Set<String>> = MutableStateFlow<Set<String>>(emptySet()).asStateFlow()

    override suspend fun signInRequest() {
        AccountHostRegistry.host.value?.openSignIn()
    }

    override suspend fun switchAccount(id: String): Result<Unit> = no()
    override suspend fun signOut() = Unit
    override fun invalidate() = Unit
    override suspend fun home(continuation: PageToken?): Result<Page<VideoItem>> = no()
    override suspend fun subscriptionsFeed(continuation: PageToken?): Result<Page<VideoItem>> = no()
    override suspend fun subscribedChannels(): Result<List<ChannelItem>> = no()
    override suspend fun playlists(): Result<List<PlaylistItem>> = no()
    override suspend fun playlist(id: String, continuation: PageToken?): Result<Page<VideoItem>> = no()
    override suspend fun history(continuation: PageToken?): Result<Page<VideoItem>> = no()
    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> = no()
    override suspend fun rate(videoId: String, rating: Rating): Result<Unit> = no()
    override suspend fun addToPlaylist(playlistId: String, videoId: String): Result<Unit> = no()
    override suspend fun removeFromPlaylist(playlistId: String, videoId: String): Result<Unit> = no()

    private fun <T> no(): Result<T> = Result.failure(AccountFailure.NotSignedIn())
}
