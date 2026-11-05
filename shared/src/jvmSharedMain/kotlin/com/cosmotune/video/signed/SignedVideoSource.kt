package com.cosmotune.video.signed

import com.cosmotune.music.data.TrackLog
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.video.account.AccountFailure
import com.cosmotune.video.account.InnerTubeComments
import com.cosmotune.video.account.InnertubeWeb
import com.cosmotune.video.account.YoutubeFeedParser
import com.cosmotune.video.account.YoutubeWeb
import com.cosmotune.video.model.CommentPage
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.PlaylistPage
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import com.cosmotune.youtube.account.YouTubeAuthState
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * Video's source with the signed path in front of [anonymous] (the NewPipe adapter, which stays exactly what it was: no account, no cookie).
 *
 * Signed in, the watch page and its streams come from [SignedPlayerResolver] and the site's `next` answer, so age-restricted and members'
 * videos and the account's own private and unlisted ones play; any failure there falls back to [anonymous], and what that cannot read
 * either is tried once more with the app clients that need no credential ([SignedPlayerClient.RESCUE]). Signed out, or with a session
 * that has expired, every playback call is simply [anonymous]'s.
 *
 * A stream set is asked again soon after it was given only because the player's fetch was refused (a set lasts for hours), so the client
 * that minted it sits out for [REFUSED_MS] and the next one answers. When they have all sat out, [anonymous] answers.
 *
 * Comments are read from the site's own endpoint for everyone ([InnerTubeComments]): it has both orders, and signed in it carries what
 * the rating and posting calls need. NewPipe stays the fallback; each page's token says which of the two it belongs to. An account's
 * playlist is read with the session too, because a private one has no public page; a public one reads the same.
 *
 * What is remembered here belongs to one account: it is dropped when [identity] changes, and a rotation of the cookies does not change it.
 */
class SignedVideoSource internal constructor(
    private val anonymous: VideoSource,
    private val signedIn: () -> Boolean,
    private val identity: () -> Any?,
    private val resolver: SignedPlayerResolver,
    private val web: YoutubeWeb,
    private val clock: () -> Long,
) : VideoSource by anonymous {

    /** The process-wide session, over [anonymous]. */
    constructor(anonymous: VideoSource, clock: () -> Long = System::currentTimeMillis) : this(
        anonymous = anonymous,
        signedIn = { YouTubeSession.shared.state.value is YouTubeAuthState.SignedIn },
        identity = { YouTubeSession.shared.generation to Innertube.chosenChannelKey },
        resolver = SignedPlayerResolver.shared,
        web = InnertubeWeb,
        clock = clock,
    )

    private val comments = InnerTubeComments(web)
    private val lock = Any()
    private var heldIdentity: Any? = null

    /** Signed details by video id, most recently used last; guarded by [lock]. */
    private val cache = object : LinkedHashMap<String, VideoDetails>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoDetails>): Boolean = size > CACHE_SIZE
    }

    /** Which client gave a video's last set and when; guarded by [lock]. */
    private val lastGiven = HashMap<String, Pair<SignedPlayerClient, Long>>()

    /** The clients sitting out for a video, and until when; guarded by [lock]. */
    private val refused = HashMap<String, MutableMap<SignedPlayerClient, Long>>()

    /** Forgets what the last account's requests learned; called before anything here is read. */
    private fun followIdentity() {
        val now = identity()
        synchronized(lock) {
            if (now == heldIdentity) return
            heldIdentity = now
            cache.clear()
            lastGiven.clear()
            refused.clear()
        }
    }

    override fun setRegion(country: String, language: String) {
        anonymous.setRegion(country, language)
        synchronized(lock) { cache.clear() } // titles and descriptions come back in the new language
    }

    // ---- Watching --------------------------------------------------------------------------------------------------------

    override suspend fun details(videoId: String): VideoDetails {
        if (!signedIn()) return anonymous.details(videoId)
        followIdentity()
        synchronized(lock) { cache[videoId]?.takeIf { clock() - it.fetchedAtMs in 0 until DETAILS_TTL_MS } }?.let { return it }
        val playback = attemptSigned(videoId) ?: return anonymousOrRescue(videoId, { anonymous.details(videoId) }) { rescue ->
            SignedPlayerParser.details(videoId, rescue, nextAnswer(videoId), clock())
        }
        val details = SignedPlayerParser.details(videoId, playback, nextAnswer(videoId), clock())
        synchronized(lock) { cache[videoId] = details }
        return details
    }

    override suspend fun streams(videoId: String): StreamSet {
        if (!signedIn()) return anonymous.streams(videoId)
        followIdentity()
        noteRefusal(videoId)
        val playback = attemptSigned(videoId) ?: return anonymousOrRescue(videoId, { anonymous.streams(videoId) }) { it.streams }
        synchronized(lock) { cache[videoId]?.let { cache[videoId] = it.copy(streams = playback.streams) } }
        return playback.streams
    }

    override suspend fun related(videoId: String): List<ListItem> {
        if (!signedIn()) return anonymous.related(videoId)
        val signed = runCatchingSource { details(videoId) }?.related.orEmpty()
        return if (signed.any { it is ListItem.Video }) signed else anonymous.related(videoId)
    }

    private fun noteRefusal(videoId: String) {
        synchronized(lock) {
            val (client, at) = lastGiven.remove(videoId) ?: return
            val now = clock()
            if (now - at < REFUSED_MS) refused.getOrPut(videoId) { HashMap() }[client] = now + REFUSED_MS
        }
    }

    private fun sittingOut(videoId: String): Set<SignedPlayerClient> = synchronized(lock) {
        val entries = refused[videoId] ?: return@synchronized emptySet()
        val now = clock()
        entries.values.removeAll { it <= now }
        entries.keys.toSet()
    }

    /** The signed answer, or null when none of the clients gave one. */
    private suspend fun attemptSigned(videoId: String): SignedPlayback? = try {
        resolver.resolve(videoId, SignedPlayerClient.SIGNED, sittingOut(videoId)).also { playback ->
            synchronized(lock) { lastGiven[videoId] = playback.client to clock() }
            TrackLog.d(TAG, "signed player: ${playback.client.name} answered", about = videoId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "signed player: falling back to the anonymous source (${e.message})", about = videoId)
        null
    }

    /** [ask]s the anonymous source; what it throws is answered by the rescue clients when they can, and is rethrown when they cannot. */
    private suspend fun <T> anonymousOrRescue(videoId: String, ask: suspend () -> T, rescued: suspend (SignedPlayback) -> T): T {
        try {
            return ask()
        } catch (e: CancellationException) {
            throw e
        } catch (e: VideoSourceError) {
            val playback = try {
                resolver.resolve(videoId, SignedPlayerClient.RESCUE, sittingOut(videoId))
            } catch (rescue: CancellationException) {
                throw rescue
            } catch (rescue: Exception) {
                throw e
            }
            synchronized(lock) { lastGiven[videoId] = playback.client to clock() }
            return rescued(playback)
        }
    }

    /** The watch page's `next` answer. Without it the page still plays: it only has no related videos, chapters or like count. */
    private suspend fun nextAnswer(videoId: String): JsonObject? = runCatchingSource {
        web.post("next") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.also { response -> YoutubeFeedParser.errorOf(response)?.let { throw it } }
    }

    // ---- Comments --------------------------------------------------------------------------------------------------------

    override suspend fun comments(videoId: String, sort: CommentSort, page: PageToken?): CommentPage {
        if (page != null && !page.value.startsWith(InnerTubeComments.TOKEN_PREFIX)) return anonymous.comments(videoId, sort, page)
        if (page != null) return commentCall { comments.more(page) }
        return try {
            comments.first(videoId, sort)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "comments: falling back to the anonymous source (${e.javaClass.simpleName})", about = videoId)
            anonymous.comments(videoId, sort, null)
        }
    }

    override suspend fun replies(token: PageToken): CommentPage =
        if (token.value.startsWith(InnerTubeComments.TOKEN_PREFIX)) commentCall { comments.more(token) } else anonymous.replies(token)

    /** A later page has no fallback (its token means nothing to the other source): what it throws leaves as a source error. */
    private suspend fun commentCall(block: suspend () -> CommentPage): CommentPage = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw sourceError(e)
    }

    private fun sourceError(e: Exception): VideoSourceError = when (val failure = com.cosmotune.video.account.failureOf(e)) {
        is AccountFailure.Network -> VideoSourceError.Network(failure.message.orEmpty(), failure)
        else -> VideoSourceError.Parsing(failure.message.orEmpty(), failure)
    }

    // ---- Playlists -------------------------------------------------------------------------------------------------------

    override suspend fun playlist(playlistId: String, page: PageToken?): PlaylistPage {
        val mine = page?.value?.startsWith(InnerTubeComments.TOKEN_PREFIX) == true
        if (page != null && !mine) return anonymous.playlist(playlistId, page)
        if (!signedIn() && !mine) return anonymous.playlist(playlistId, page)
        val bare = playlistId.removePrefix("VL")
        return try {
            signedPlaylist(bare, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine) throw sourceError(e)
            TrackLog.w(TAG, "playlist: falling back to the anonymous source (${e.javaClass.simpleName})")
            anonymous.playlist(playlistId, null)
        }
    }

    private suspend fun signedPlaylist(id: String, page: PageToken?): PlaylistPage {
        val response = web.post("browse") {
            if (page != null) put("continuation", page.value.removePrefix(InnerTubeComments.TOKEN_PREFIX)) else put("browseId", "VL$id")
        }
        YoutubeFeedParser.errorOf(response)?.let { throw it }
        val feed = YoutubeFeedParser.parse(response)
        if (feed.looksChanged) throw AccountFailure.Changed("The playlist could not be read")
        if (page == null && feed.isEmpty && feed.loggedOut) throw AccountFailure.SessionExpired()
        val next = feed.continuation?.let { PageToken(InnerTubeComments.TOKEN_PREFIX + it) }
        val videos = Page(feed.videos, next)
        if (page != null) {
            return PlaylistPage(PlaylistItem(id = id, title = "", thumbnailUrl = null, ownerName = null, ownerId = null, videoCount = null), null, videos)
        }
        val header = YoutubeFeedParser.parsePlaylistHeader(response, id) ?: throw AccountFailure.Changed("The playlist had no header")
        return PlaylistPage(header.playlist, header.description, videos)
    }

    private inline fun <T> runCatchingSource(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "signed watch page: ${e.javaClass.simpleName}")
        null
    }

    internal companion object {
        private const val TAG = "CosmoVideo"
        private const val CACHE_SIZE = 24
        const val DETAILS_TTL_MS = 10 * 60_000L

        /** A set asked for again this soon was refused; its client sits out this long. */
        const val REFUSED_MS = 10 * 60_000L
    }
}
